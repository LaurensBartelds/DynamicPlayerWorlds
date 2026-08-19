package nl.gzmn.playerworlds.backend.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import nl.gzmn.playerworlds.backend.platform.Platform;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.config.StorageClientSettings;
import nl.gzmn.playerworlds.core.db.ArchiveRepository;
import nl.gzmn.playerworlds.core.db.Database;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository;
import nl.gzmn.playerworlds.core.db.Schema;
import nl.gzmn.playerworlds.core.model.Visibility;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.model.WorldState;
import nl.gzmn.playerworlds.core.storage.ObjectStore;
import nl.gzmn.playerworlds.core.storage.S3ObjectStore;
import nl.gzmn.playerworlds.core.storage.StorageException;
import nl.gzmn.playerworlds.testing.TestDatabase;
import nl.gzmn.playerworlds.testing.TestObjectStore;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** FR-37: hard deletion destroys the archives it promises to destroy. */
class WorldEraserTest {

    @TempDir
    Path tempDir;

    private Database database;
    private PluginExecutors executors;
    private PlayerWorldRepository worlds;
    private ArchiveRepository archives;

    @BeforeEach
    void setUp() throws Exception {
        database = TestDatabase.openFresh();
        Schema.migrate(database);
        executors = PluginExecutors.create(2, 2, Runnable::run);
        worlds = new PlayerWorldRepository(database);
        archives = new ArchiveRepository(database);
    }

    @AfterEach
    void tearDown() {
        executors.shutdown(Duration.ofSeconds(5));
        database.close();
    }

    private <T> T onDb(Callable<T> task) throws Exception {
        return executors.db().submit(task).get(60, TimeUnit.SECONDS);
    }

    /** An ARCHIVED world with one archive object recorded against it. */
    private WorldId archivedWorld(ArchiveStorage storage, String archiveKey) throws Exception {
        WorldId worldId = WorldId.random();
        UUID owner = UUID.randomUUID();
        Path payload = Files.write(tempDir.resolve("archive-" + worldId.value() + ".bin"), new byte[] {1, 2, 3, 4});
        storage.uploadArchive(archiveKey, payload);
        onDb(() -> {
            worlds.create(worldId, owner, "doomed-" + worldId.value(), 1L, 5000, Visibility.PRIVATE);
            worlds.markReadyAndPlayed(worldId);
            worlds.transitionToArchived(worldId, archiveKey, 4L, "hash", Platform.BUILD_DATA_VERSION);
            return null;
        });
        return worldId;
    }

    @Test
    @DisplayName("hard delete removes the archive objects, not only the rows (FR-37)")
    void hardDeleteRemovesArchiveObjects_FR37() throws Exception {
        StorageClientSettings settings = TestObjectStore.settingsForNewBucket();
        try (S3ObjectStore store = S3ObjectStore.open(settings)) {
            ArchiveStorage storage = ArchiveStorage.s3(store);
            String archiveKey = "worlds/doomed/archive/backup.tar.gz";
            WorldId worldId = archivedWorld(storage, archiveKey);

            // A live snapshot object too: the world had been played before it was
            // archived, so its manifest and data objects are still in the bucket.
            String dataKey = "worlds/" + worldId.value() + "/data/" + "a".repeat(64);
            store.putBytes(dataKey, new byte[] {9, 9}, "application/octet-stream");
            assertThat(store.exists(dataKey)).isTrue();

            WorldEraser eraser = new WorldEraser(worlds, archives, storage, store);
            WorldEraser.Outcome outcome = onDb(() -> eraser.erase(worldId));

            assertThat(outcome).isInstanceOf(WorldEraser.Outcome.Deleted.class);
            assertThat(((WorldEraser.Outcome.Deleted) outcome).archiveObjects()).isEqualTo(1);

            // The whole of R23: the row going away used to be the entire operation,
            // and the objects it named were then unreachable forever, because
            // MN-2b's collection walks per world.
            assertThat(storage.exists(archiveKey)).isFalse();
            assertThat(store.exists(dataKey)).isFalse();
            assertThat(onDb(() -> worlds.findById(worldId))).isEmpty();
            assertThat(onDb(() -> archives.findAllByWorld(worldId))).isEmpty();
        }
    }

    @Test
    @DisplayName("a world that is not archived is refused, and nothing is deleted (FR-37)")
    void aWorldThatIsNotArchivedIsRefused_FR37() throws Exception {
        StorageClientSettings settings = TestObjectStore.settingsForNewBucket();
        try (S3ObjectStore store = S3ObjectStore.open(settings)) {
            ArchiveStorage storage = ArchiveStorage.s3(store);
            String archiveKey = "worlds/restored/archive/backup.tar.gz";
            WorldId worldId = archivedWorld(storage, archiveKey);

            // Restored between the owner confirming and the node claiming the
            // command. CP-4's generation check does not see this: a restore that
            // completes leaves the generation where it put it.
            onDb(() -> worlds.transitionState(worldId, WorldState.ARCHIVED, WorldState.READY));

            WorldEraser eraser = new WorldEraser(worlds, archives, storage, store);
            WorldEraser.Outcome outcome = onDb(() -> eraser.erase(worldId));

            assertThat(outcome).isInstanceOf(WorldEraser.Outcome.WrongState.class);
            assertThat(storage.exists(archiveKey)).isTrue();
            assertThat(onDb(() -> worlds.findById(worldId))).isPresent();
        }
    }

    @Test
    @DisplayName("deleting a world that is already gone is success, not an error (CP-5)")
    void deletingAWorldThatIsAlreadyGoneIsSuccess() throws Exception {
        StorageClientSettings settings = TestObjectStore.settingsForNewBucket();
        try (S3ObjectStore store = S3ObjectStore.open(settings)) {
            WorldEraser eraser = new WorldEraser(worlds, archives, ArchiveStorage.s3(store), store);

            WorldEraser.Outcome outcome = onDb(() -> eraser.erase(WorldId.random()));

            assertThat(outcome).isInstanceOf(WorldEraser.Outcome.NotFound.class);
        }
    }

    @Test
    @DisplayName("the row survives an object that will not delete (CONTRIBUTING rule 8)")
    void theRowSurvivesAFailedObjectDelete() throws Exception {
        StorageClientSettings settings = TestObjectStore.settingsForNewBucket();
        try (S3ObjectStore store = S3ObjectStore.open(settings)) {
            String archiveKey = "worlds/stubborn/archive/backup.tar.gz";
            WorldId worldId = archivedWorld(ArchiveStorage.s3(store), archiveKey);

            WorldEraser eraser =
                    new WorldEraser(worlds, archives, ArchiveStorage.s3(store), new PrefixDeleteFails(store));
            WorldEraser.Outcome outcome = onDb(() -> eraser.erase(worldId));

            assertThat(outcome).isInstanceOf(WorldEraser.Outcome.Failed.class);
            // Verify before destroy (CONTRIBUTING rule 8): the row is the only
            // thing that still names whatever objects are left, so it stays until
            // every one of them is gone and the operator can retry.
            assertThat(onDb(() -> worlds.findById(worldId))).isPresent();
        }
    }

    /** An object store whose snapshot-prefix deletion fails, and nothing else. */
    private record PrefixDeleteFails(ObjectStore delegate) implements ObjectStore {

        @Override
        public void deletePrefix(String prefix) {
            throw new StorageException("bucket said no");
        }

        @Override
        public void putObject(String key, Path sourceFile) {
            delegate.putObject(key, sourceFile);
        }

        @Override
        public void putBytes(String key, byte[] bytes, @Nullable String contentType) {
            delegate.putBytes(key, bytes, contentType);
        }

        @Override
        public void getObject(String key, Path destinationFile) {
            delegate.getObject(key, destinationFile);
        }

        @Override
        public byte[] getBytes(String key) {
            return delegate.getBytes(key);
        }

        @Override
        public boolean exists(String key) {
            return delegate.exists(key);
        }

        @Override
        public void deleteObject(String key) {
            delegate.deleteObject(key);
        }

        @Override
        public long getObjectSize(String key) {
            return delegate.getObjectSize(key);
        }

        @Override
        public void close() {
            // The test closes the delegate itself.
        }
    }
}
