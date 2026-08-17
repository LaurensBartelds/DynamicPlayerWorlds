package nl.gzmn.playerworlds.backend.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import nl.gzmn.playerworlds.backend.platform.DimensionKind;
import nl.gzmn.playerworlds.backend.platform.PaperItemCodec;
import nl.gzmn.playerworlds.backend.platform.Platform;
import nl.gzmn.playerworlds.backend.platform.ServerIdentity;
import nl.gzmn.playerworlds.backend.profile.ProfileService;
import nl.gzmn.playerworlds.backend.profile.WorldCommitService;
import nl.gzmn.playerworlds.backend.world.LoadedWorld;
import nl.gzmn.playerworlds.backend.world.WorldFolders;
import nl.gzmn.playerworlds.backend.world.WorldRegistry;
import nl.gzmn.playerworlds.core.concurrent.MainThread;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.config.StorageClientSettings;
import nl.gzmn.playerworlds.core.db.Database;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository;
import nl.gzmn.playerworlds.core.db.ProfileRepository;
import nl.gzmn.playerworlds.core.db.Schema;
import nl.gzmn.playerworlds.core.model.Visibility;
import nl.gzmn.playerworlds.core.model.WorldId;
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
                "node-test");

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
        for (int i = 0; i < 15; i++) {
            Runnable task;
            while ((task = mainTasks.poll()) != null) {
                task.run();
            }
            executors.io().submit(() -> null).get(5, TimeUnit.SECONDS);
            executors.db().submit(() -> null).get(5, TimeUnit.SECONDS);
            while ((task = mainTasks.poll()) != null) {
                task.run();
            }
            Thread.sleep(30);
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
    @DisplayName("empty registry does nothing")
    void emptyRegistryDoesNothing() throws Exception {
        PeriodicSyncTask task = new PeriodicSyncTask(registry, commitService, NetworkPolicy::defaults);
        task.run();
        flushExecutors();
        assertThat(registry.size()).isZero();
    }
}
