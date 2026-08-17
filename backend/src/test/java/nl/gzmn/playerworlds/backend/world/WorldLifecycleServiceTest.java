package nl.gzmn.playerworlds.backend.world;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import nl.gzmn.playerworlds.backend.platform.PaperItemCodec;
import nl.gzmn.playerworlds.backend.platform.Platform;
import nl.gzmn.playerworlds.backend.platform.ServerIdentity;
import nl.gzmn.playerworlds.backend.profile.ProfileService;
import nl.gzmn.playerworlds.backend.profile.WorldCommitService;
import nl.gzmn.playerworlds.core.concurrent.MainThread;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.config.StorageClientSettings;
import nl.gzmn.playerworlds.core.db.Database;
import nl.gzmn.playerworlds.core.db.MembershipRepository;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository;
import nl.gzmn.playerworlds.core.db.ProfileRepository;
import nl.gzmn.playerworlds.core.db.Schema;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.Visibility;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.model.WorldState;
import nl.gzmn.playerworlds.core.obs.WorldsMetrics;
import nl.gzmn.playerworlds.core.storage.DirtyScanner;
import nl.gzmn.playerworlds.core.storage.LocalObjectCache;
import nl.gzmn.playerworlds.core.storage.Manifest;
import nl.gzmn.playerworlds.core.storage.PlainFileCloner;
import nl.gzmn.playerworlds.core.storage.S3ObjectStore;
import nl.gzmn.playerworlds.core.storage.SnapshotCopier;
import nl.gzmn.playerworlds.core.storage.SnapshotEngine;
import nl.gzmn.playerworlds.core.storage.WorldDownloader;
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

class WorldLifecycleServiceTest {

    @TempDir
    Path tempDir;

    private ServerMock server;
    private Database database;
    private PluginExecutors executors;
    private Queue<Runnable> mainTasks;
    private Platform platform;
    private WorldFolders folders;
    private WorldRegistry registry;
    private MembershipCache membershipCache;
    private PlayerWorldRepository worldRepo;
    private MembershipRepository membershipRepo;
    private ProfileRepository profileRepo;
    private ProfileService profileService;
    private WorldsMetrics metrics;
    private S3ObjectStore objectStore;
    private LocalObjectCache objectCache;
    private SnapshotEngine snapshotEngine;
    private WorldDownloader worldDownloader;
    private WorldCommitService commitService;
    private WorldLifecycleService lifecycleService;
    private Path scratchDir;

    @BeforeEach
    void setUp() throws Exception {
        database = TestDatabase.openFresh();
        Schema.migrate(database);
        mainTasks = new ConcurrentLinkedQueue<>();
        executors = PluginExecutors.create(2, 2, mainTasks::add);

        platform = Platform.create(new ServerIdentity("26.2", Platform.BUILD_DATA_VERSION));
        folders = new WorldFolders(platform.worldLayout());
        registry = new WorldRegistry();
        membershipCache = new MembershipCache();
        worldRepo = new PlayerWorldRepository(database);
        membershipRepo = new MembershipRepository(database);
        profileRepo = new ProfileRepository(database);
        profileService = new ProfileService(PaperItemCodec.INSTANCE);
        metrics = WorldsMetrics.create();

        scratchDir = tempDir.resolve("scratch");
        Files.createDirectories(scratchDir);

        StorageClientSettings s3Settings = TestObjectStore.settingsForNewBucket();
        objectStore = S3ObjectStore.open(s3Settings);
        objectCache = new LocalObjectCache(tempDir.resolve("cache"), PlainFileCloner.INSTANCE);
        SnapshotCopier copier = new SnapshotCopier(PlainFileCloner.INSTANCE);
        snapshotEngine = new SnapshotEngine(objectStore, objectCache, copier);
        worldDownloader = new WorldDownloader(objectStore, objectCache, PlainFileCloner.INSTANCE);

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

        lifecycleService = new WorldLifecycleService(
                worldRepo,
                membershipRepo,
                membershipCache,
                executors,
                platform,
                folders,
                registry,
                metrics,
                NetworkPolicy::defaults,
                scratchDir,
                worldDownloader,
                objectStore,
                commitService,
                objectCache);

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
        metrics.close();
        executors.shutdown(Duration.ofSeconds(5));
        database.close();
    }

    private <T> T onDb(Callable<T> task) throws Exception {
        return executors.db().submit(task).get(5, TimeUnit.SECONDS);
    }

    private <T> T awaitFuture(CompletableFuture<T> future) throws Exception {
        long deadline = System.currentTimeMillis() + 10000;
        while (!future.isDone() && System.currentTimeMillis() < deadline) {
            Runnable task;
            while ((task = mainTasks.poll()) != null) {
                task.run();
            }
            Thread.sleep(10);
        }
        Runnable task;
        while ((task = mainTasks.poll()) != null) {
            task.run();
        }
        return future.get(5, TimeUnit.SECONDS);
    }

    private void flushExecutors() throws Exception {
        for (int i = 0; i < 5; i++) {
            executors.db().submit(() -> null).get(5, TimeUnit.SECONDS);
            executors.io().submit(() -> null).get(5, TimeUnit.SECONDS);
            Runnable task;
            while ((task = mainTasks.poll()) != null) {
                task.run();
            }
            executors.db().submit(() -> null).get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("Cold load downloads files from S3 manifest when scratch directory is empty")
    void coldLoadDownloadsFromS3ManifestWhenScratchEmpty() throws Exception {
        Path fixtureDir = tempDir.resolve("fixture");
        WorldId worldId = WorldFixture.materialize(fixtureDir);
        UUID owner = UUID.randomUUID();

        // Perform an initial snapshot of the fixture to populate S3
        List<Path> dirty = DirtyScanner.scanDirty(fixtureDir, worldId, Map.of(), List.of());
        SnapshotEngine.SnapshotResult snapResult = snapshotEngine.executeSnapshot(
                fixtureDir, worldId, 0L, 1, Platform.BUILD_DATA_VERSION, "26.2", Map.of(), dirty, true);
        Manifest manifest = snapResult.manifest();

        // Create world row with the manifest key pointing to S3
        onDb(() -> {
            PlayerWorld row = worldRepo.create(worldId, owner, "cold-world", 12345L, 5000, Visibility.PRIVATE);
            worldRepo.commitSnapshot(
                    worldId,
                    0L,
                    null,
                    manifest.manifestKey(),
                    manifest.dataVersion(),
                    manifest.mcVersion(),
                    new ProfileRepository.Snapshot(0L, 1),
                    1,
                    Map.of(),
                    profileRepo);
            worldRepo.markReadyAndPlayed(worldId);
            return row;
        });

        // Clear objectCache so the node must fetch objects from S3 (cold load)
        try (var stream = Files.walk(tempDir.resolve("cache"))) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }

        // Ensure scratch directory does not contain this world yet (cold)
        Path worldScratchFolder = scratchDir.resolve(worldId.folder());
        assertThat(Files.exists(worldScratchFolder)).isFalse();

        // Load world through lifecycle service
        CompletableFuture<LoadOutcome> loadFuture = lifecycleService.load(worldId);
        LoadOutcome outcome = awaitFuture(loadFuture);

        assertThat(outcome).isInstanceOf(LoadOutcome.Loaded.class);
        LoadOutcome.Loaded loaded = (LoadOutcome.Loaded) outcome;
        assertThat(loaded.world().id()).isEqualTo(worldId);
        assertThat(registry.find(worldId)).isPresent();

        // Verify files materialized in scratch directory
        assertThat(Files.exists(worldScratchFolder.resolve("level.dat"))).isTrue();

        // Verify cold load metric was incremented
        String scrape = metrics.scrape();
        assertThat(scrape).contains("world_load_seconds_count{kind=\"cold\"} 1");

        // Verify commit service has cached manifest
        assertThat(commitService.cachedManifest(worldId)).isPresent();
        assertThat(commitService.cachedManifest(worldId).get().manifestKey()).isEqualTo(manifest.manifestKey());
    }

    @Test
    @DisplayName("Warm load uses matching local scratch files and records warm metric")
    void warmLoadUsesMatchingScratchFiles() throws Exception {
        // Materialize fixture directly in scratch directory
        WorldId worldId = WorldFixture.materialize(scratchDir);
        UUID owner = UUID.randomUUID();

        // Snapshot to S3 and populate object store and cache
        List<Path> dirty = DirtyScanner.scanDirty(scratchDir, worldId, Map.of(), List.of());
        SnapshotEngine.SnapshotResult snapResult = snapshotEngine.executeSnapshot(
                scratchDir, worldId, 0L, 1, Platform.BUILD_DATA_VERSION, "26.2", Map.of(), dirty, true);
        Manifest manifest = snapResult.manifest();

        onDb(() -> {
            worldRepo.create(worldId, owner, "warm-world", 12345L, 5000, Visibility.PRIVATE);
            worldRepo.commitSnapshot(
                    worldId,
                    0L,
                    null,
                    manifest.manifestKey(),
                    manifest.dataVersion(),
                    manifest.mcVersion(),
                    new ProfileRepository.Snapshot(0L, 1),
                    1,
                    Map.of(),
                    profileRepo);
            worldRepo.markReadyAndPlayed(worldId);
            return null;
        });

        // Load world through lifecycle service (scratch already contains all matching files)
        CompletableFuture<LoadOutcome> loadFuture = lifecycleService.load(worldId);
        LoadOutcome outcome = awaitFuture(loadFuture);

        assertThat(outcome).isInstanceOf(LoadOutcome.Loaded.class);
        assertThat(registry.find(worldId)).isPresent();

        // Verify warm load metric was incremented
        String scrape = metrics.scrape();
        assertThat(scrape).contains("world_load_seconds_count{kind=\"warm\"} 1");

        // Verify commit service has cached manifest
        assertThat(commitService.cachedManifest(worldId)).isPresent();
    }

    @Test
    @DisplayName("New world creation triggers initial snapshot commit to S3")
    void newWorldCreationTriggersInitialSnapshotCommit() throws Exception {
        UUID owner = UUID.randomUUID();

        CompletableFuture<CreateOutcome> createFuture = lifecycleService.create(owner, "brand-new-world", 98765L);
        CreateOutcome outcome = awaitFuture(createFuture);

        assertThat(outcome).isInstanceOf(CreateOutcome.Created.class);
        CreateOutcome.Created created = (CreateOutcome.Created) outcome;
        WorldId worldId = created.world().id();

        assertThat(registry.find(worldId)).isPresent();
        assertThat(server.getWorlds()).isNotEmpty();

        // Flush executors and pump main/io/db tasks until commitService finishes the initial snapshot
        long deadline = System.currentTimeMillis() + 10000;
        while (commitService.cachedManifest(worldId).isEmpty() && System.currentTimeMillis() < deadline) {
            Runnable task;
            while ((task = mainTasks.poll()) != null) {
                task.run();
            }
            flushExecutors();
            Thread.sleep(50);
        }

        // Verify in DB that manifestKey was assigned by commitSnapshot
        PlayerWorld dbRow = onDb(() -> worldRepo.findById(worldId).orElseThrow());
        assertThat(dbRow.manifestKey()).isNotNull();
        assertThat(dbRow.state()).isEqualTo(WorldState.READY);

        // Verify manifest exists in objectStore
        assertThat(objectStore.exists(dbRow.manifestKey())).isTrue();

        // Verify commitService cached the manifest
        assertThat(commitService.cachedManifest(worldId)).isPresent();
    }
}
