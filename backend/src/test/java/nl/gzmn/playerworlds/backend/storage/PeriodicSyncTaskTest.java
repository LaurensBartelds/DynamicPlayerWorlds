package nl.gzmn.playerworlds.backend.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import nl.gzmn.playerworlds.backend.control.WorldHandoff;
import nl.gzmn.playerworlds.backend.platform.DimensionKind;
import nl.gzmn.playerworlds.backend.platform.PaperItemCodec;
import nl.gzmn.playerworlds.backend.platform.Platform;
import nl.gzmn.playerworlds.backend.platform.ServerIdentity;
import nl.gzmn.playerworlds.backend.profile.ProfileService;
import nl.gzmn.playerworlds.backend.profile.WorldCommitService;
import nl.gzmn.playerworlds.backend.world.LoadedWorld;
import nl.gzmn.playerworlds.backend.world.MembershipCache;
import nl.gzmn.playerworlds.backend.world.WorldFolders;
import nl.gzmn.playerworlds.backend.world.WorldLifecycleService;
import nl.gzmn.playerworlds.backend.world.WorldRegistry;
import nl.gzmn.playerworlds.core.concurrent.MainThread;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.config.StorageClientSettings;
import nl.gzmn.playerworlds.core.db.Database;
import nl.gzmn.playerworlds.core.db.MembershipRepository;
import nl.gzmn.playerworlds.core.db.NodeCommandRepository;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository;
import nl.gzmn.playerworlds.core.db.ProfileRepository;
import nl.gzmn.playerworlds.core.db.Schema;
import nl.gzmn.playerworlds.core.model.Visibility;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.obs.StorageHealthCheck;
import nl.gzmn.playerworlds.core.obs.WorldsMetrics;
import nl.gzmn.playerworlds.core.storage.LocalObjectCache;
import nl.gzmn.playerworlds.core.storage.ObjectStoreHealthCheck;
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
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class PeriodicSyncTaskTest {

    private Database database;
    private PluginExecutors executors;
    private Queue<Runnable> mainTasks;
    private ServerMock server;
    private S3ObjectStore objectStore;
    private WorldFolders folders;
    private WorldRegistry registry;
    private WorldCommitService commitService;
    private PlayerWorldRepository worldRepo;
    private ProfileRepository profileRepo;
    private Path scratchDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        database = TestDatabase.openFresh();
        Schema.migrate(database);
        mainTasks = new ConcurrentLinkedQueue<>();
        executors = PluginExecutors.create(2, 2, mainTasks::add);

        Platform platform = Platform.create(new ServerIdentity("26.2", Platform.BUILD_DATA_VERSION));
        folders = new WorldFolders(platform.worldLayout());
        ProfileService profileService = new ProfileService(PaperItemCodec.INSTANCE);
        profileRepo = new ProfileRepository(database);
        worldRepo = new PlayerWorldRepository(database);

        scratchDir = tempDir.resolve("scratch");
        Files.createDirectories(scratchDir);

        StorageClientSettings s3Settings = TestObjectStore.settingsForNewBucket();
        objectStore = S3ObjectStore.open(s3Settings);
        LocalObjectCache objectCache = new LocalObjectCache(tempDir.resolve("cache"), PlainFileCloner.INSTANCE);
        SnapshotCopier copier = new SnapshotCopier(PlainFileCloner.INSTANCE);
        SnapshotEngine snapshotEngine = new SnapshotEngine(objectStore, objectCache, copier);

        registry = new WorldRegistry();
        commitService = new WorldCommitService(
                profileRepo,
                worldRepo,
                profileService,
                folders,
                platform,
                executors,
                snapshotEngine,
                NetworkPolicy::defaults,
                scratchDir,
                "node-test",
                WorldFixture.PRIMARY_LEVEL_NAME);
        // MN-11a's failure window lives on the LoadedWorld, so the service has to be able to
        // find it; GzmnWorldsPlugin wires this the same way.
        commitService.setRegistry(registry);

        MainThread.enter(Thread.currentThread());
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        if (objectStore != null) {
            objectStore.close();
        }
        MockBukkit.unmock();
        MainThread.clear();
        executors.shutdown(Duration.ofSeconds(5));
        database.close();
    }

    private <T> T onDb(Callable<T> task) throws Exception {
        return executors.db().submit(task).get(5, TimeUnit.SECONDS);
    }

    private void flushExecutors() throws Exception {
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline) {
            Runnable task;
            while ((task = mainTasks.poll()) != null) {
                task.run();
            }
            executors.io().submit(() -> null).get(5, TimeUnit.SECONDS);
            executors.db().submit(() -> null).get(5, TimeUnit.SECONDS);
            while ((task = mainTasks.poll()) != null) {
                task.run();
            }
            boolean anyCommitting = false;
            for (LoadedWorld world : registry.loadedWorlds()) {
                if (commitService.isCommitting(world.id())) {
                    anyCommitting = true;
                    break;
                }
            }
            if (!anyCommitting && mainTasks.isEmpty()) {
                break;
            }
            Thread.sleep(50);
        }
    }

    @Test
    @DisplayName("rejects null constructor arguments")
    void rejectsNullConstructorArguments() {
        assertThatNullPointerException()
                .isThrownBy(() -> new PeriodicSyncTask(null, commitService, NetworkPolicy::defaults));
        assertThatNullPointerException()
                .isThrownBy(() -> new PeriodicSyncTask(registry, null, NetworkPolicy::defaults));
        assertThatNullPointerException().isThrownBy(() -> new PeriodicSyncTask(registry, commitService, null));
    }

    @Test
    @DisplayName("getters return configured dependencies")
    void gettersReturnDependencies() {
        PeriodicSyncTask task = new PeriodicSyncTask(registry, commitService, NetworkPolicy::defaults);
        assertThat(task.registry()).isSameAs(registry);
        assertThat(task.commits()).isSameAs(commitService);
        assertThat(task.policySupplier()).isNotNull();
    }

    @Test
    @DisplayName("triggers commit for all loaded worlds in the registry")
    void triggersCommitForAllLoadedWorlds() throws Exception {
        WorldId id1 = WorldId.random();
        WorldId id2 = WorldId.random();
        UUID owner = UUID.randomUUID();

        WorldFixture.materialize(scratchDir, id1, WorldFixture.DimensionSet.ALL_THREE);
        WorldFixture.materialize(scratchDir, id2, WorldFixture.DimensionSet.ALL_THREE);

        onDb(() -> {
            worldRepo.create(id1, owner, "world-one", 111L, 5000, Visibility.PRIVATE);
            worldRepo.markReadyAndPlayed(id1);
            worldRepo.create(id2, owner, "world-two", 222L, 5000, Visibility.PRIVATE);
            worldRepo.markReadyAndPlayed(id2);
            return null;
        });

        server.addSimpleWorld(folders.bukkitWorldName(id1, DimensionKind.OVERWORLD));
        server.addSimpleWorld(folders.bukkitWorldName(id2, DimensionKind.OVERWORLD));

        registry.register(new LoadedWorld(id1, owner, "world-one", 111L, 5000));
        registry.register(new LoadedWorld(id2, owner, "world-two", 222L, 5000));

        PeriodicSyncTask task = new PeriodicSyncTask(registry, commitService, NetworkPolicy::defaults);
        task.run();

        flushExecutors();

        // Verify both worlds were committed
        assertThat(commitService.cachedManifest(id1)).isPresent();
        assertThat(commitService.cachedManifest(id2)).isPresent();
    }

    @Test
    @DisplayName("failure on one world does not stop sync for remaining worlds")
    void failureOnOneWorldDoesNotStopRemainingWorlds() throws Exception {
        WorldId validId1 = WorldId.random();
        WorldId invalidId = WorldId.random(); // Exists in registry and scratch, but not in DB -> DB commit fails
        WorldId validId2 = WorldId.random();
        UUID owner = UUID.randomUUID();

        WorldFixture.materialize(scratchDir, validId1, WorldFixture.DimensionSet.ALL_THREE);
        WorldFixture.materialize(scratchDir, invalidId, WorldFixture.DimensionSet.ALL_THREE);
        WorldFixture.materialize(scratchDir, validId2, WorldFixture.DimensionSet.ALL_THREE);

        onDb(() -> {
            worldRepo.create(validId1, owner, "valid-world-1", 111L, 5000, Visibility.PRIVATE);
            worldRepo.markReadyAndPlayed(validId1);
            worldRepo.create(validId2, owner, "valid-world-2", 333L, 5000, Visibility.PRIVATE);
            worldRepo.markReadyAndPlayed(validId2);
            return null;
        });

        server.addSimpleWorld(folders.bukkitWorldName(validId1, DimensionKind.OVERWORLD));
        server.addSimpleWorld(folders.bukkitWorldName(invalidId, DimensionKind.OVERWORLD));
        server.addSimpleWorld(folders.bukkitWorldName(validId2, DimensionKind.OVERWORLD));

        registry.register(new LoadedWorld(validId1, owner, "valid-world-1", 111L, 5000));
        registry.register(new LoadedWorld(invalidId, owner, "missing-in-db", 222L, 5000));
        registry.register(new LoadedWorld(validId2, owner, "valid-world-2", 333L, 5000));

        PeriodicSyncTask task = new PeriodicSyncTask(registry, commitService, NetworkPolicy::defaults);
        task.run();

        flushExecutors();

        // Valid worlds should still commit successfully despite the invalid world's failure
        assertThat(commitService.cachedManifest(validId1)).isPresent();
        assertThat(commitService.cachedManifest(validId2)).isPresent();
        assertThat(commitService.cachedManifest(invalidId)).isEmpty();
    }

    @Test
    @DisplayName("a commit that fails is counted against MN-11a's window (12.7)")
    void aFailedCommitIsCounted() throws Exception {
        LoadedWorld world = registerWorldWhoseCommitsFail();
        assertThat(world.consecutiveCommitFailures())
                .as("a world that has just loaded is not behind on anything")
                .isZero();
        assertThat(world.isSyncFailingFor(Duration.ZERO)).isFalse();

        new PeriodicSyncTask(registry, commitService, NetworkPolicy::defaults).run();
        flushExecutors();

        assertThat(world.consecutiveCommitFailures()).isEqualTo(1);
        assertThat(world.isSyncFailingFor(Duration.ZERO)).isTrue();
        assertThat(world.isSyncFailingFor(Duration.ofHours(1)))
                .as("one failed commit is not thirty minutes of them")
                .isFalse();
    }

    @Test
    @DisplayName("a world whose commits have failed past the bound is unloaded (MN-11a)")
    void aWorldOverTheSyncFailureBoundIsUnloaded() throws Exception {
        LoadedWorld world = registerWorldWhoseCommitsFail();
        WorldHandoff handoff = handoff();
        // storage.max-sync-failure-minutes = 0: the bound is reached by the first failure, so the
        // test does not have to wait out a real one.
        Supplier<NetworkPolicy> impatient =
                () -> NetworkPolicy.fromRaw(Map.of(NetworkPolicy.KEY_MAX_SYNC_FAILURE_MINUTES, "0"));
        PeriodicSyncTask task = new PeriodicSyncTask(registry, commitService, impatient, () -> handoff, 0);

        // First sweep: the commit fails and is counted. The world is still loaded, because
        // MN-11a bounds the failure window rather than reacting to a single failure.
        task.run();
        flushExecutors();
        assertThat(registry.isLoaded(world.id()))
                .as("one failed sync keeps playing; the world is safe locally (12.7)")
                .isTrue();

        // Second: the window is over and the world stops.
        task.run();
        flushExecutors();

        assertThat(registry.isLoaded(world.id())).isFalse();
        assertThat(Files.isDirectory(
                        WorldFixture.dimensionFolder(scratchDir, world.id().folder())))
                .as("the local folders are the newest copy of the world; MN-11a keeps them")
                .isTrue();
    }

    @Test
    @DisplayName("a world whose commits succeed is never unloaded by MN-11a")
    void aHealthyWorldIsNeverUnloaded() throws Exception {
        WorldId id = WorldId.random();
        UUID owner = UUID.randomUUID();
        WorldFixture.materialize(scratchDir, id, WorldFixture.DimensionSet.ALL_THREE);
        onDb(() -> {
            worldRepo.create(id, owner, "healthy", 111L, 5000, Visibility.PRIVATE);
            worldRepo.markReadyAndPlayed(id);
            return null;
        });
        server.addSimpleWorld(folders.bukkitWorldName(id, DimensionKind.OVERWORLD));
        LoadedWorld world = registry.register(new LoadedWorld(id, owner, "healthy", 111L, 5000));

        WorldHandoff handoff = handoff();
        Supplier<NetworkPolicy> impatient =
                () -> NetworkPolicy.fromRaw(Map.of(NetworkPolicy.KEY_MAX_SYNC_FAILURE_MINUTES, "0"));
        PeriodicSyncTask task = new PeriodicSyncTask(registry, commitService, impatient, () -> handoff, 0);

        task.run();
        flushExecutors();
        task.run();
        flushExecutors();

        // Even at a zero-minute bound: the window opens on a failure, and there has not been one.
        assertThat(world.consecutiveCommitFailures()).isZero();
        assertThat(registry.isLoaded(id)).isTrue();
    }

    @Test
    @DisplayName("a successful object storage ping records object.storage.up, independent of any world (plan 10.4)")
    void pingSucceedsAndRecordsObjectStorageUp() throws Exception {
        StorageClientSettings settings = TestObjectStore.settingsForNewBucket();
        try (var pingStore = S3ObjectStore.open(settings);
                WorldsMetrics metrics = WorldsMetrics.create()) {
            StorageHealthCheck check = new ObjectStoreHealthCheck(pingStore, "_health/test-node.ping");
            PeriodicSyncTask task =
                    new PeriodicSyncTask(registry, commitService, NetworkPolicy::defaults, () -> null, check, metrics);

            task.run();
            flushExecutors();

            assertThat(metrics.objectStorageUp()).isTrue();
        }
    }

    @Test
    @DisplayName("a failed object storage ping records object.storage.up=false without touching world commits")
    void pingFailureRecordsObjectStorageDownAndDoesNotBlockCommits() throws Exception {
        WorldId id = WorldId.random();
        UUID owner = UUID.randomUUID();
        WorldFixture.materialize(scratchDir, id, WorldFixture.DimensionSet.ALL_THREE);
        onDb(() -> {
            worldRepo.create(id, owner, "healthy", 111L, 5000, Visibility.PRIVATE);
            worldRepo.markReadyAndPlayed(id);
            return null;
        });
        server.addSimpleWorld(folders.bukkitWorldName(id, DimensionKind.OVERWORLD));
        registry.register(new LoadedWorld(id, owner, "healthy", 111L, 5000));

        StorageHealthCheck alwaysFails = () -> {
            throw new java.io.IOException("simulated bucket outage");
        };
        try (WorldsMetrics metrics = WorldsMetrics.create()) {
            PeriodicSyncTask task = new PeriodicSyncTask(
                    registry, commitService, NetworkPolicy::defaults, () -> null, alwaysFails, metrics);

            task.run();
            flushExecutors();

            assertThat(metrics.objectStorageUp())
                    .as("a broken ping must flip the gauge even though nothing here is actually committing to S3")
                    .isFalse();
            assertThat(commitService.cachedManifest(id))
                    .as("the ping is independent of the commit path; a fake, unrelated ping failure must not "
                            + "stop the world's own (unrelated, real) commit from proceeding")
                    .isPresent();
        }
    }

    /** A registered world whose snapshot commits fail: its row is not in the database. */
    private LoadedWorld registerWorldWhoseCommitsFail() throws Exception {
        WorldId id = WorldId.random();
        UUID owner = UUID.randomUUID();
        WorldFixture.materialize(scratchDir, id, WorldFixture.DimensionSet.ALL_THREE);
        server.addSimpleWorld(folders.bukkitWorldName(id, DimensionKind.OVERWORLD));
        LoadedWorld world = registry.register(new LoadedWorld(id, owner, "unsaveable", 111L, 5000));
        world.markMaterialised(DimensionKind.OVERWORLD);
        return world;
    }

    private WorldHandoff handoff() {
        Platform platform = Platform.create(new ServerIdentity("26.2", Platform.BUILD_DATA_VERSION));
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
                scratchDir);
        return new WorldHandoff(
                registry,
                lifecycle,
                folders,
                executors,
                commitService,
                new NodeCommandRepository(database),
                NetworkPolicy::defaults);
    }

    @Test
    @DisplayName("empty registry does nothing")
    void emptyRegistryDoesNothing() throws Exception {
        PeriodicSyncTask task = new PeriodicSyncTask(registry, commitService, NetworkPolicy::defaults);
        task.run();
        flushExecutors();
        assertThat(registry.size()).isZero();
    }
}
