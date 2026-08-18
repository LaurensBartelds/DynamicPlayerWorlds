package nl.gzmn.playerworlds.backend.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import nl.gzmn.playerworlds.backend.control.WorldHandoff;
import nl.gzmn.playerworlds.backend.platform.DimensionKind;
import nl.gzmn.playerworlds.backend.platform.Platform;
import nl.gzmn.playerworlds.backend.platform.ServerIdentity;
import nl.gzmn.playerworlds.backend.world.LoadedWorld;
import nl.gzmn.playerworlds.backend.world.MembershipCache;
import nl.gzmn.playerworlds.backend.world.WorldFolders;
import nl.gzmn.playerworlds.backend.world.WorldLifecycleService;
import nl.gzmn.playerworlds.backend.world.WorldRegistry;
import nl.gzmn.playerworlds.core.concurrent.MainThread;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.config.StorageClientSettings;
import nl.gzmn.playerworlds.core.db.ArchiveRepository;
import nl.gzmn.playerworlds.core.db.Database;
import nl.gzmn.playerworlds.core.db.MembershipRepository;
import nl.gzmn.playerworlds.core.db.NodeCommandRepository;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository;
import nl.gzmn.playerworlds.core.db.Schema;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.Visibility;
import nl.gzmn.playerworlds.core.model.WorldArchive;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.model.WorldState;
import nl.gzmn.playerworlds.core.obs.WorldsMetrics;
import nl.gzmn.playerworlds.core.storage.S3ObjectStore;
import nl.gzmn.playerworlds.testing.TestDatabase;
import nl.gzmn.playerworlds.testing.TestObjectStore;
import nl.gzmn.playerworlds.testing.WorldFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class WorldArchiverTest {

    @TempDir
    Path tempDir;

    private final Queue<Runnable> mainTasks = new ConcurrentLinkedQueue<>();

    private ServerMock server;
    private Database database;
    private PluginExecutors executors;
    private PlayerWorldRepository worldRepo;
    private ArchiveRepository archiveRepo;
    private Path scratchRoot;
    private Path archiveDir;
    private ArchiveStorage archiveStorage;
    private WorldRegistry registry;
    private Platform platform;
    private WorldFolders folders;

    @BeforeEach
    void setUp() throws Exception {
        database = TestDatabase.openFresh();
        Schema.migrate(database);
        executors = PluginExecutors.create(2, 2, mainTasks::add);
        worldRepo = new PlayerWorldRepository(database);
        archiveRepo = new ArchiveRepository(database);

        scratchRoot = tempDir.resolve("scratch");
        archiveDir = tempDir.resolve("archives");
        Files.createDirectories(scratchRoot);
        Files.createDirectories(archiveDir);
        archiveStorage = ArchiveStorage.filesystem(archiveDir);

        registry = new WorldRegistry();
        platform = Platform.create(new ServerIdentity("26.2", Platform.BUILD_DATA_VERSION));
        folders = new WorldFolders(platform.worldLayout());

        MainThread.enter(Thread.currentThread());
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
        MainThread.clear();
        executors.shutdown(Duration.ofSeconds(5));
        database.close();
    }

    /**
     * Runs work off the main thread (NFR-2) while draining queued main-thread tasks on this
     * thread, which is the one {@link MainThread} is bound to. The archiver blocks on a
     * {@link WorldHandoff} unload that only completes once those tasks have run.
     */
    private <T> T onDb(Callable<T> task) throws Exception {
        Future<T> future = executors.db().submit(task);
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (!future.isDone()) {
            Runnable pending = mainTasks.poll();
            if (pending != null) {
                pending.run();
            } else if (System.nanoTime() > deadlineNanos) {
                var _ = future.cancel(true);
                throw new AssertionError("timed out waiting for off-main work");
            } else {
                Thread.sleep(1);
            }
        }
        Runnable trailing;
        while ((trailing = mainTasks.poll()) != null) {
            trailing.run();
        }
        return future.get();
    }

    @Test
    @DisplayName("Successfully archives a ready world to local archive storage and cleans up scratch")
    void archiveWorldSuccess() throws Exception {
        WorldId worldId = WorldFixture.materialize(scratchRoot);
        UUID owner = UUID.randomUUID();

        onDb(() -> {
            worldRepo.create(worldId, owner, "test-world", 12345L, 5000, Visibility.PRIVATE);
            worldRepo.markReadyAndPlayed(worldId);
            return null;
        });

        WorldArchiver archiver = new WorldArchiver(
                worldRepo,
                database,
                archiveStorage,
                scratchRoot,
                null,
                registry,
                null,
                NetworkPolicy::defaults,
                "node-1",
                Platform.BUILD_DATA_VERSION);

        WorldArchiver.ArchiveResult result = onDb(() -> archiver.archiveWorld(worldId, owner));

        assertThat(result.success()).isTrue();
        assertThat(result.archiveKey()).isNotNull().contains("worlds/" + worldId.value() + "/archive/");
        assertThat(result.sizeBytes()).isGreaterThan(0L);
        assertThat(result.checksum()).hasSize(64);

        // Check DB row updated
        PlayerWorld archived = onDb(() -> worldRepo.findById(worldId).orElseThrow());
        assertThat(archived.state()).isEqualTo(WorldState.ARCHIVED);
        assertThat(onDb(() -> worldRepo.totalStorageUsedBy(owner))).isEqualTo(result.sizeBytes());
        assertThat(archived.manifestKey()).isNull();
        assertThat(archived.assignedNode()).isNull();
        assertThat(archived.leaseExpires()).isNull();

        // Check ArchiveRepository recorded entry
        Optional<WorldArchive> archiveRow = onDb(() -> archiveRepo.findLatestByWorld(worldId));
        assertThat(archiveRow).isPresent();
        assertThat(archiveRow.get().objectKey()).isEqualTo(result.archiveKey());
        assertThat(archiveRow.get().sizeBytes()).isEqualTo(result.sizeBytes());
        assertThat(archiveRow.get().checksum()).isEqualTo(result.checksum());
        assertThat(archiveRow.get().dataVersion()).isEqualTo(Platform.BUILD_DATA_VERSION);

        // Check archive exists on storage
        assertThat(archiveStorage.exists(result.archiveKey())).isTrue();

        // Check scratch directory deleted
        assertThat(Files.exists(scratchRoot.resolve(worldId.folder()))).isFalse();
    }

    @Test
    @DisplayName("Archives world to S3 and purges live S3 objects (data/ and manifest/)")
    void archiveWorldToS3AndPurgesLivePrefix() throws Exception {
        StorageClientSettings s3Settings = TestObjectStore.settingsForNewBucket();
        try (S3ObjectStore store = S3ObjectStore.open(s3Settings)) {
            ArchiveStorage s3ArchiveStorage = ArchiveStorage.s3(store);

            WorldId worldId = WorldFixture.materialize(scratchRoot);
            UUID owner = UUID.randomUUID();

            onDb(() -> {
                worldRepo.create(worldId, owner, "s3-world", 12345L, 5000, Visibility.PRIVATE);
                worldRepo.markReadyAndPlayed(worldId);
                return null;
            });

            // Put dummy live objects to verify they get purged
            String dataKey = "worlds/" + worldId.value() + "/data/blob123";
            String manifestKey = "worlds/" + worldId.value() + "/manifest/0-1.json";
            store.putBytes(dataKey, "dummy blob content".getBytes(StandardCharsets.UTF_8), null);
            store.putBytes(manifestKey, "dummy manifest content".getBytes(StandardCharsets.UTF_8), null);
            assertThat(store.exists(dataKey)).isTrue();
            assertThat(store.exists(manifestKey)).isTrue();

            WorldArchiver archiver = new WorldArchiver(
                    worldRepo,
                    database,
                    s3ArchiveStorage,
                    scratchRoot,
                    store,
                    registry,
                    null,
                    NetworkPolicy::defaults,
                    "node-1",
                    Platform.BUILD_DATA_VERSION);

            WorldArchiver.ArchiveResult result = onDb(() -> archiver.archiveWorld(worldId, owner));

            assertThat(result.success()).isTrue();
            assertThat(s3ArchiveStorage.exists(result.archiveKey())).isTrue();

            // Live prefix objects must be purged
            assertThat(store.exists(dataKey)).isFalse();
            assertThat(store.exists(manifestKey)).isFalse();
        }
    }

    @Test
    @DisplayName("Unloads world through handoff if currently loaded locally")
    void archiveWorldUnloadsLoadedWorld() throws Exception {
        WorldId worldId = WorldFixture.materialize(scratchRoot);
        UUID owner = UUID.randomUUID();

        onDb(() -> {
            worldRepo.create(worldId, owner, "loaded-world", 12345L, 5000, Visibility.PRIVATE);
            worldRepo.markReadyAndPlayed(worldId);
            return null;
        });

        LoadedWorld loaded = new LoadedWorld(worldId, owner, "loaded-world", 12345L, 5000);
        loaded.markMaterialised(DimensionKind.OVERWORLD);
        registry.register(loaded);
        assertThat(registry.isLoaded(worldId)).isTrue();

        server.addSimpleWorld(folders.bukkitWorldName(worldId, DimensionKind.OVERWORLD));

        WorldLifecycleService lifecycle = new WorldLifecycleService(
                worldRepo,
                new MembershipRepository(database),
                new MembershipCache(),
                executors,
                platform,
                folders,
                registry,
                WorldsMetrics.create(),
                NetworkPolicy::defaults,
                scratchRoot);

        WorldHandoff handoff = new WorldHandoff(
                registry,
                lifecycle,
                folders,
                executors,
                null,
                new NodeCommandRepository(database),
                NetworkPolicy::defaults);

        WorldArchiver archiver = new WorldArchiver(
                worldRepo,
                database,
                archiveStorage,
                scratchRoot,
                null,
                registry,
                handoff,
                NetworkPolicy::defaults,
                "node-1",
                Platform.BUILD_DATA_VERSION);

        WorldArchiver.ArchiveResult result = onDb(() -> archiver.archiveWorld(worldId, owner));

        assertThat(result.success()).isTrue();
        assertThat(registry.isLoaded(worldId)).isFalse();
    }

    @Test
    @DisplayName("Refuses archival if active lease is held by another node")
    void archiveWorldFailsWhenLeaseHeldByOtherNode() throws Exception {
        WorldId worldId = WorldFixture.materialize(scratchRoot);
        UUID owner = UUID.randomUUID();

        onDb(() -> {
            worldRepo.create(worldId, owner, "leased-world", 12345L, 5000, Visibility.PRIVATE);
            worldRepo.markReadyAndPlayed(worldId);
            // Node-2 acquires lease
            return worldRepo.acquireLease(worldId, "node-2", Platform.BUILD_DATA_VERSION, Duration.ofMinutes(5));
        });

        WorldArchiver archiver = new WorldArchiver(
                worldRepo,
                database,
                archiveStorage,
                scratchRoot,
                null,
                registry,
                null,
                NetworkPolicy::defaults,
                "node-1",
                Platform.BUILD_DATA_VERSION);

        WorldArchiver.ArchiveResult result = onDb(() -> archiver.archiveWorld(worldId, owner));

        assertThat(result.success()).isFalse();
        assertThat(result.message()).containsIgnoringCase("lease");

        // State must remain READY
        PlayerWorld current = onDb(() -> worldRepo.findById(worldId).orElseThrow());
        assertThat(current.state()).isEqualTo(WorldState.READY);
    }

    @Test
    @DisplayName("Refuses archival if owner UUID does not match")
    void archiveWorldFailsWhenOwnerMismatch() throws Exception {
        WorldId worldId = WorldFixture.materialize(scratchRoot);
        UUID owner = UUID.randomUUID();

        onDb(() -> {
            worldRepo.create(worldId, owner, "owned-world", 12345L, 5000, Visibility.PRIVATE);
            worldRepo.markReadyAndPlayed(worldId);
            return null;
        });

        WorldArchiver archiver = new WorldArchiver(
                worldRepo,
                database,
                archiveStorage,
                scratchRoot,
                null,
                registry,
                null,
                NetworkPolicy::defaults,
                "node-1",
                Platform.BUILD_DATA_VERSION);

        WorldArchiver.ArchiveResult result = onDb(() -> archiver.archiveWorld(worldId, UUID.randomUUID()));

        assertThat(result.success()).isFalse();
        assertThat(result.message()).containsIgnoringCase("owner");
    }
}
