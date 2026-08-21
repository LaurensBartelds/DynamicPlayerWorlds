package nl.gzmn.playerworlds.backend.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import nl.gzmn.playerworlds.backend.platform.Platform;
import nl.gzmn.playerworlds.backend.platform.ServerIdentity;
import nl.gzmn.playerworlds.backend.world.WorldFolders;
import nl.gzmn.playerworlds.core.concurrent.MainThread;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.config.StorageClientSettings;
import nl.gzmn.playerworlds.core.db.ArchiveRepository;
import nl.gzmn.playerworlds.core.db.Database;
import nl.gzmn.playerworlds.core.db.MembershipRepository;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository;
import nl.gzmn.playerworlds.core.db.ProfileRepository;
import nl.gzmn.playerworlds.core.db.Schema;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.Role;
import nl.gzmn.playerworlds.core.model.Visibility;
import nl.gzmn.playerworlds.core.model.WorldArchive;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.model.WorldMember;
import nl.gzmn.playerworlds.core.model.WorldState;
import nl.gzmn.playerworlds.core.storage.LocalObjectCache;
import nl.gzmn.playerworlds.core.storage.PlainFileCloner;
import nl.gzmn.playerworlds.core.storage.S3ObjectStore;
import nl.gzmn.playerworlds.core.storage.SnapshotCopier;
import nl.gzmn.playerworlds.core.storage.SnapshotEngine;
import nl.gzmn.playerworlds.testing.TestDatabase;
import nl.gzmn.playerworlds.testing.TestObjectStore;
import nl.gzmn.playerworlds.testing.WorldFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldRestorerTest {

    /** The archive extract tree is flat; WorldFolders owns that layout (R21). */
    private static final WorldFolders FOLDERS =
            new WorldFolders(Platform.create(new ServerIdentity("26.2", Platform.BUILD_DATA_VERSION))
                    .worldLayout());

    @TempDir
    Path tempDir;

    private Database database;
    private PluginExecutors executors;
    private PlayerWorldRepository worldRepo;
    private ArchiveRepository archiveRepo;
    private MembershipRepository membershipRepo;
    private ProfileRepository profileRepo;
    private Path scratchRoot;
    private Path archiveDir;
    private ArchiveStorage archiveStorage;

    @BeforeEach
    void setUp() throws Exception {
        database = TestDatabase.openFresh();
        Schema.migrate(database);
        executors = PluginExecutors.create(2, 2, Runnable::run);
        worldRepo = new PlayerWorldRepository(database);
        archiveRepo = new ArchiveRepository(database);
        membershipRepo = new MembershipRepository(database);
        profileRepo = new ProfileRepository(database);

        scratchRoot = tempDir.resolve("scratch");
        archiveDir = tempDir.resolve("archives");
        Files.createDirectories(scratchRoot);
        Files.createDirectories(archiveDir);
        archiveStorage = ArchiveStorage.filesystem(archiveDir);

        MainThread.enter(Thread.currentThread());
    }

    @AfterEach
    void tearDown() {
        MainThread.clear();
        executors.shutdown(Duration.ofSeconds(5));
        database.close();
    }

    /** Database work never runs on the main thread (NFR-2), and neither does the restorer. */
    private <T> T onDb(Callable<T> task) throws Exception {
        return executors.db().submit(task).get(60, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("Successfully restores an archived world with S3 object store and increments restore count")
    void restoreWorldSuccessWithS3() throws Exception {
        StorageClientSettings s3Settings = TestObjectStore.settingsForNewBucket();
        try (S3ObjectStore store = S3ObjectStore.open(s3Settings)) {
            LocalObjectCache cache = new LocalObjectCache(tempDir.resolve("cache"), PlainFileCloner.INSTANCE);
            SnapshotEngine snapshotEngine =
                    new SnapshotEngine(store, cache, new SnapshotCopier(PlainFileCloner.INSTANCE, 2));
            ArchiveStorage s3ArchiveStorage = ArchiveStorage.s3(store);

            // Create fixture files and pack them into an archive
            Path sourceFixture = tempDir.resolve("fixture");
            WorldId worldId = WorldFixture.materialize(sourceFixture);
            UUID owner = UUID.randomUUID();

            Path packTarget = tempDir.resolve("sample.tar.zst");
            ArchivePacker.PackResult pack = ArchivePacker.pack(
                    List.of(
                            WorldFixture.dimensionFolder(sourceFixture, worldId.folder()),
                            WorldFixture.dimensionFolder(sourceFixture, worldId.folder() + "_nether"),
                            WorldFixture.dimensionFolder(sourceFixture, worldId.folder() + "_the_end")),
                    packTarget,
                    true);

            String archiveKey = "worlds/" + worldId.value() + "/archive/backup.tar.zst";
            s3ArchiveStorage.uploadArchive(archiveKey, packTarget);

            // Record DB rows: world is ARCHIVED
            onDb(() -> {
                worldRepo.create(worldId, owner, "restored-world", 12345L, 5000, Visibility.PRIVATE);
                worldRepo.markReadyAndPlayed(worldId);
                worldRepo.transitionToArchived(
                        worldId, archiveKey, pack.sizeBytes(), pack.checksum(), Platform.BUILD_DATA_VERSION);
                return null;
            });

            WorldRestorer restorer = new WorldRestorer(
                    worldRepo,
                    profileRepo,
                    archiveRepo,
                    s3ArchiveStorage,
                    snapshotEngine,
                    store,
                    scratchRoot,
                    FOLDERS,
                    NetworkPolicy::defaults,
                    "node-1",
                    Platform.BUILD_DATA_VERSION,
                    "26.2");

            WorldRestorer.RestoreResult result = onDb(() -> restorer.restoreWorld(worldId, owner));

            assertThat(result.success()).isTrue();
            assertThat(result.manifestKey()).isNotNull().contains("worlds/" + worldId.value() + "/manifest/");
            assertThat(result.liveStorageBytes()).isGreaterThan(0L);

            // Check DB state is READY
            PlayerWorld restored = onDb(() -> worldRepo.findById(worldId).orElseThrow());
            assertThat(restored.state()).isEqualTo(WorldState.READY);
            assertThat(restored.manifestKey()).isEqualTo(result.manifestKey());
            assertThat(onDb(() -> worldRepo.totalStorageUsedBy(owner))).isEqualTo(result.liveStorageBytes());
            assertThat(restored.assignedNode()).isNull();
            assertThat(restored.leaseExpires()).isNull();

            // Check restore count incremented
            WorldArchive archiveRecord =
                    onDb(() -> archiveRepo.findLatestByWorld(worldId).orElseThrow());
            assertThat(archiveRecord.restoreCount()).isEqualTo(1);

            // Check manifest exists in S3
            assertThat(store.exists(result.manifestKey())).isTrue();
        }
    }

    @Test
    @DisplayName("Transfers ownership when target owner is specified during restore")
    void restoreWorldTransfersOwnership() throws Exception {
        Path sourceFixture = tempDir.resolve("fixture");
        WorldId worldId = WorldFixture.materialize(sourceFixture);
        UUID originalOwner = UUID.randomUUID();
        UUID newOwner = UUID.randomUUID();

        Path packTarget = tempDir.resolve("sample.tar.zst");
        ArchivePacker.PackResult pack = ArchivePacker.pack(
                List.of(
                        WorldFixture.dimensionFolder(sourceFixture, worldId.folder()),
                        WorldFixture.dimensionFolder(sourceFixture, worldId.folder() + "_nether"),
                        WorldFixture.dimensionFolder(sourceFixture, worldId.folder() + "_the_end")),
                packTarget,
                true);

        String archiveKey = "worlds/" + worldId.value() + "/archive/backup.tar.zst";
        archiveStorage.uploadArchive(archiveKey, packTarget);

        onDb(() -> {
            worldRepo.create(worldId, originalOwner, "transferred-world", 12345L, 5000, Visibility.PRIVATE);
            worldRepo.markReadyAndPlayed(worldId);
            worldRepo.transitionToArchived(
                    worldId, archiveKey, pack.sizeBytes(), pack.checksum(), Platform.BUILD_DATA_VERSION);
            return null;
        });

        WorldRestorer restorer = new WorldRestorer(
                worldRepo,
                profileRepo,
                archiveRepo,
                archiveStorage,
                null,
                null,
                scratchRoot,
                FOLDERS,
                NetworkPolicy::defaults,
                "node-1",
                Platform.BUILD_DATA_VERSION,
                "26.2");

        WorldRestorer.RestoreResult result = onDb(() -> restorer.restoreWorld(worldId, newOwner));

        assertThat(result.success()).isTrue();

        PlayerWorld restored = onDb(() -> worldRepo.findById(worldId).orElseThrow());
        assertThat(restored.ownerUuid()).isEqualTo(newOwner);
        assertThat(restored.state()).isEqualTo(WorldState.READY);

        // Membership role check
        assertThat(onDb(() -> membershipRepo.findMember(worldId, newOwner).map(WorldMember::role)))
                .contains(Role.OWNER);
        assertThat(onDb(() -> membershipRepo.findMember(worldId, originalOwner).map(WorldMember::role)))
                .contains(Role.BUILDER);
    }

    @Test
    @DisplayName("Fails restore and keeps world ARCHIVED if checksum does not match")
    void restoreWorldFailsOnChecksumMismatch() throws Exception {
        Path sourceFixture = tempDir.resolve("fixture");
        WorldId worldId = WorldFixture.materialize(sourceFixture);
        UUID owner = UUID.randomUUID();

        Path packTarget = tempDir.resolve("sample.tar.zst");
        ArchivePacker.PackResult pack = ArchivePacker.pack(
                List.of(WorldFixture.dimensionFolder(sourceFixture, worldId.folder())), packTarget, true);

        String archiveKey = "worlds/" + worldId.value() + "/archive/backup.tar.zst";
        archiveStorage.uploadArchive(archiveKey, packTarget);

        onDb(() -> {
            worldRepo.create(worldId, owner, "corrupt-checksum-world", 12345L, 5000, Visibility.PRIVATE);
            worldRepo.markReadyAndPlayed(worldId);
            // Tamper with checksum recorded in DB
            worldRepo.transitionToArchived(
                    worldId,
                    archiveKey,
                    pack.sizeBytes(),
                    "0000000000000000000000000000000000000000000000000000000000000000",
                    Platform.BUILD_DATA_VERSION);
            return null;
        });

        WorldRestorer restorer = new WorldRestorer(
                worldRepo,
                profileRepo,
                archiveRepo,
                archiveStorage,
                null,
                null,
                scratchRoot,
                FOLDERS,
                NetworkPolicy::defaults,
                "node-1",
                Platform.BUILD_DATA_VERSION,
                "26.2");

        WorldRestorer.RestoreResult result = onDb(() -> restorer.restoreWorld(worldId, owner));

        assertThat(result.success()).isFalse();
        assertThat(result.message()).containsIgnoringCase("checksum");

        PlayerWorld current = onDb(() -> worldRepo.findById(worldId).orElseThrow());
        assertThat(current.state()).isEqualTo(WorldState.ARCHIVED);
    }

    @Test
    @DisplayName("Fails restore if archive data version is newer than node data version")
    void restoreWorldFailsOnNewerDataVersion() throws Exception {
        Path sourceFixture = tempDir.resolve("fixture");
        WorldId worldId = WorldFixture.materialize(sourceFixture);
        UUID owner = UUID.randomUUID();

        Path packTarget = tempDir.resolve("sample.tar.zst");
        ArchivePacker.PackResult pack = ArchivePacker.pack(
                List.of(WorldFixture.dimensionFolder(sourceFixture, worldId.folder())), packTarget, true);

        String archiveKey = "worlds/" + worldId.value() + "/archive/backup.tar.zst";
        archiveStorage.uploadArchive(archiveKey, packTarget);

        int newerDataVersion = Platform.BUILD_DATA_VERSION + 1000;
        onDb(() -> {
            worldRepo.create(worldId, owner, "newer-version-world", 12345L, 5000, Visibility.PRIVATE);
            worldRepo.markReadyAndPlayed(worldId);
            worldRepo.transitionToArchived(worldId, archiveKey, pack.sizeBytes(), pack.checksum(), newerDataVersion);
            return null;
        });

        WorldRestorer restorer = new WorldRestorer(
                worldRepo,
                profileRepo,
                archiveRepo,
                archiveStorage,
                null,
                null,
                scratchRoot,
                FOLDERS,
                NetworkPolicy::defaults,
                "node-1",
                Platform.BUILD_DATA_VERSION,
                "26.2");

        WorldRestorer.RestoreResult result = onDb(() -> restorer.restoreWorld(worldId, owner));

        assertThat(result.success()).isFalse();
        assertThat(result.message()).containsIgnoringCase("dataversion");

        PlayerWorld current = onDb(() -> worldRepo.findById(worldId).orElseThrow());
        assertThat(current.state()).isEqualTo(WorldState.ARCHIVED);
    }

    @Test
    @DisplayName("Fails restore if world is not in ARCHIVED state")
    void restoreWorldFailsWhenNotArchived() throws Exception {
        WorldId worldId = WorldId.random();
        UUID owner = UUID.randomUUID();

        onDb(() -> {
            worldRepo.create(worldId, owner, "ready-world", 12345L, 5000, Visibility.PRIVATE);
            worldRepo.markReadyAndPlayed(worldId);
            return null;
        });

        WorldRestorer restorer = new WorldRestorer(
                worldRepo,
                profileRepo,
                archiveRepo,
                archiveStorage,
                null,
                null,
                scratchRoot,
                FOLDERS,
                NetworkPolicy::defaults,
                "node-1",
                Platform.BUILD_DATA_VERSION,
                "26.2");

        WorldRestorer.RestoreResult result = onDb(() -> restorer.restoreWorld(worldId, owner));

        assertThat(result.success()).isFalse();
        assertThat(result.message()).containsIgnoringCase("state");
    }

    @Test
    @DisplayName("restoring a world returns its players' inventories with it (FR-36, D17)")
    void restoringAWorldPreservesInventories_FR36() throws Exception {
        StorageClientSettings s3Settings = TestObjectStore.settingsForNewBucket();
        try (S3ObjectStore store = S3ObjectStore.open(s3Settings)) {
            LocalObjectCache cache = new LocalObjectCache(tempDir.resolve("cache-inv"), PlainFileCloner.INSTANCE);
            SnapshotEngine snapshotEngine =
                    new SnapshotEngine(store, cache, new SnapshotCopier(PlainFileCloner.INSTANCE, 2));
            ArchiveStorage s3ArchiveStorage = ArchiveStorage.s3(store);

            Path sourceFixture = tempDir.resolve("fixture-inv");
            WorldId worldId = WorldFixture.materialize(sourceFixture);
            UUID owner = UUID.randomUUID();
            UUID member = UUID.randomUUID();

            Path packTarget = tempDir.resolve("inv.tar.zst");
            ArchivePacker.PackResult pack = ArchivePacker.pack(
                    List.of(
                            WorldFixture.dimensionFolder(sourceFixture, worldId.folder()),
                            WorldFixture.dimensionFolder(sourceFixture, worldId.folder() + "_nether"),
                            WorldFixture.dimensionFolder(sourceFixture, worldId.folder() + "_the_end")),
                    packTarget,
                    true);
            String archiveKey = "worlds/" + worldId.value() + "/archive/inv.tar.zst";
            s3ArchiveStorage.uploadArchive(archiveKey, packTarget);

            byte[] ownerPayload = "owner-diamonds".getBytes(StandardCharsets.UTF_8);
            byte[] memberPayload = "member-diamonds".getBytes(StandardCharsets.UTF_8);
            onDb(() -> {
                worldRepo.create(worldId, owner, "inventory-world", 12345L, 5000, Visibility.PRIVATE);
                worldRepo.markReadyAndPlayed(worldId);
                // The profiles the world had when it was last played, keyed to the
                // generation it was played under — which is what a commit does.
                long played = worldRepo.findById(worldId).orElseThrow().generation();
                profileRepo.commit(worldId, played, 1, Map.of(owner, ownerPayload, member, memberPayload));
                worldRepo.transitionToArchived(
                        worldId, archiveKey, pack.sizeBytes(), pack.checksum(), Platform.BUILD_DATA_VERSION);
                return null;
            });

            WorldRestorer restorer = new WorldRestorer(
                    worldRepo,
                    profileRepo,
                    archiveRepo,
                    s3ArchiveStorage,
                    snapshotEngine,
                    store,
                    scratchRoot,
                    FOLDERS,
                    NetworkPolicy::defaults,
                    "node-1",
                    Platform.BUILD_DATA_VERSION,
                    "26.2");

            WorldRestorer.RestoreResult result = onDb(() -> restorer.restoreWorld(worldId, null));
            assertThat(result.success()).isTrue();

            // FR-15b reads the (generation, sequence) out of manifest_key and loads
            // the profiles named by it. Restored at a hardcoded (0, 1), that lookup
            // found nothing and every member was issued a fresh inventory.
            PlayerWorld restored = onDb(() -> worldRepo.findById(worldId).orElseThrow());
            ProfileRepository.Snapshot named = snapshotOf(restored.manifestKey());
            assertThat(named.generation()).isEqualTo(restored.generation());

            assertThat(onDb(() -> profileRepo.load(worldId, owner, named)))
                    .get()
                    .extracting(ProfileRepository.StoredProfile::data)
                    .isEqualTo(ownerPayload);
            assertThat(onDb(() -> profileRepo.load(worldId, member, named)))
                    .get()
                    .extracting(ProfileRepository.StoredProfile::data)
                    .isEqualTo(memberPayload);
        }
    }

    @Test
    @DisplayName("two restores do not write the same manifest key (MN-3)")
    void twoRestoresDoNotWriteTheSameManifestKey_MN3() throws Exception {
        StorageClientSettings s3Settings = TestObjectStore.settingsForNewBucket();
        try (S3ObjectStore store = S3ObjectStore.open(s3Settings)) {
            LocalObjectCache cache = new LocalObjectCache(tempDir.resolve("cache-twice"), PlainFileCloner.INSTANCE);
            SnapshotEngine snapshotEngine =
                    new SnapshotEngine(store, cache, new SnapshotCopier(PlainFileCloner.INSTANCE, 2));
            ArchiveStorage s3ArchiveStorage = ArchiveStorage.s3(store);

            Path sourceFixture = tempDir.resolve("fixture-twice");
            WorldId worldId = WorldFixture.materialize(sourceFixture);
            UUID owner = UUID.randomUUID();

            Path packTarget = tempDir.resolve("twice.tar.zst");
            ArchivePacker.PackResult pack = ArchivePacker.pack(
                    List.of(
                            WorldFixture.dimensionFolder(sourceFixture, worldId.folder()),
                            WorldFixture.dimensionFolder(sourceFixture, worldId.folder() + "_nether"),
                            WorldFixture.dimensionFolder(sourceFixture, worldId.folder() + "_the_end")),
                    packTarget,
                    true);
            String archiveKey = "worlds/" + worldId.value() + "/archive/twice.tar.zst";
            s3ArchiveStorage.uploadArchive(archiveKey, packTarget);

            onDb(() -> {
                worldRepo.create(worldId, owner, "twice-world", 12345L, 5000, Visibility.PRIVATE);
                worldRepo.markReadyAndPlayed(worldId);
                worldRepo.transitionToArchived(
                        worldId, archiveKey, pack.sizeBytes(), pack.checksum(), Platform.BUILD_DATA_VERSION);
                return null;
            });

            WorldRestorer restorer = new WorldRestorer(
                    worldRepo,
                    profileRepo,
                    archiveRepo,
                    s3ArchiveStorage,
                    snapshotEngine,
                    store,
                    scratchRoot,
                    FOLDERS,
                    NetworkPolicy::defaults,
                    "node-1",
                    Platform.BUILD_DATA_VERSION,
                    "26.2");

            String first = onDb(() -> restorer.restoreWorld(worldId, null)).manifestKey();

            // Archive and restore the same world again.
            onDb(() -> {
                worldRepo.transitionToArchived(
                        worldId, archiveKey, pack.sizeBytes(), pack.checksum(), Platform.BUILD_DATA_VERSION);
                return null;
            });
            String second = onDb(() -> restorer.restoreWorld(worldId, null)).manifestKey();

            // MN-3: manifest keys are write-once. Both restores used to write
            // 0-1.json, so the second silently replaced the first with different
            // content while old manifests still referenced the objects it named.
            assertThat(second).isNotNull().isNotEqualTo(first);
            assertThat(store.exists(first)).isTrue();
            assertThat(store.exists(second)).isTrue();
        }
    }

    /** The {@code (generation, sequence)} FR-15b parses out of a manifest key. */
    private static ProfileRepository.Snapshot snapshotOf(String manifestKey) {
        String name = manifestKey.substring(manifestKey.lastIndexOf('/') + 1).replace(".json", "");
        int dash = name.indexOf('-');
        return new ProfileRepository.Snapshot(
                Long.parseLong(name.substring(0, dash)), Integer.parseInt(name.substring(dash + 1)));
    }
}
