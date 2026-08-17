package nl.gzmn.playerworlds.backend.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import nl.gzmn.playerworlds.backend.platform.DimensionKind;
import nl.gzmn.playerworlds.backend.platform.PaperItemCodec;
import nl.gzmn.playerworlds.backend.platform.PaperWorldRuntime;
import nl.gzmn.playerworlds.backend.platform.Platform;
import nl.gzmn.playerworlds.backend.platform.ServerIdentity;
import nl.gzmn.playerworlds.backend.storage.QuiesceWatchdog;
import nl.gzmn.playerworlds.backend.world.WorldFolders;
import nl.gzmn.playerworlds.core.concurrent.MainThread;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.config.StorageClientSettings;
import nl.gzmn.playerworlds.core.db.Database;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository;
import nl.gzmn.playerworlds.core.db.ProfileRepository;
import nl.gzmn.playerworlds.core.db.Schema;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.Visibility;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.profile.ProfileCodec;
import nl.gzmn.playerworlds.core.profile.ProfileEnvelope;
import nl.gzmn.playerworlds.core.storage.LocalObjectCache;
import nl.gzmn.playerworlds.core.storage.Manifest;
import nl.gzmn.playerworlds.core.storage.PlainFileCloner;
import nl.gzmn.playerworlds.core.storage.S3ObjectStore;
import nl.gzmn.playerworlds.core.storage.SnapshotCopier;
import nl.gzmn.playerworlds.core.storage.SnapshotEngine;
import nl.gzmn.playerworlds.testing.TestDatabase;
import nl.gzmn.playerworlds.testing.TestObjectStore;
import nl.gzmn.playerworlds.testing.WorldFixture;
import org.bukkit.Material;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

class WorldCommitServiceTest {

    @TempDir
    Path tempDir;

    private ServerMock server;
    private Database database;
    private PluginExecutors executors;
    private Queue<Runnable> mainTasks;
    private Platform platform;
    private WorldFolders folders;
    private ProfileService profileService;
    private ProfileRepository profileRepo;
    private PlayerWorldRepository worldRepo;
    private S3ObjectStore objectStore;
    private LocalObjectCache objectCache;
    private SnapshotEngine snapshotEngine;
    private WorldCommitService commitService;

    @BeforeEach
    void setUp() throws Exception {
        database = TestDatabase.openFresh();
        Schema.migrate(database);
        mainTasks = new ConcurrentLinkedQueue<>();
        executors = PluginExecutors.create(2, 2, mainTasks::add);

        platform = Platform.create(new ServerIdentity("26.2", Platform.BUILD_DATA_VERSION));
        folders = new WorldFolders(platform.worldLayout());
        profileService = new ProfileService(PaperItemCodec.INSTANCE);
        profileRepo = new ProfileRepository(database);
        worldRepo = new PlayerWorldRepository(database);

        StorageClientSettings s3Settings = TestObjectStore.settingsForNewBucket();
        objectStore = S3ObjectStore.open(s3Settings);
        objectCache = new LocalObjectCache(tempDir.resolve("cache"), PlainFileCloner.INSTANCE);
        SnapshotCopier copier = new SnapshotCopier(PlainFileCloner.INSTANCE);
        snapshotEngine = new SnapshotEngine(objectStore, objectCache, copier);

        commitService = new WorldCommitService(
                profileRepo,
                worldRepo,
                profileService,
                folders,
                platform,
                executors,
                snapshotEngine,
                NetworkPolicy::defaults,
                tempDir.resolve("scratch"),
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
    @DisplayName("QuiesceWatchdog restores auto-save when world remains disabled past timeout")
    void quiesceWatchdogRestoresAutoSave() throws Exception {
        ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
        try {
            WorldMock world = server.addSimpleWorld("watchdog-test");
            world.setAutoSave(false);
            assertThat(world.isAutoSave()).isFalse();

            ScheduledFuture<?> future =
                    QuiesceWatchdog.arm(sched, PaperWorldRuntime.INSTANCE, world, Duration.ofMillis(50));
            future.get(); // wait for timeout

            assertThat(world.isAutoSave()).isTrue();
        } finally {
            sched.shutdownNow();
        }
    }

    @Test
    @DisplayName("QuiesceWatchdog does not toggle auto-save if already enabled")
    void quiesceWatchdogLeavesEnabledAutoSaveAlone() throws Exception {
        ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
        try {
            WorldMock world = server.addSimpleWorld("watchdog-enabled-test");
            world.setAutoSave(true);

            ScheduledFuture<?> future =
                    QuiesceWatchdog.arm(sched, PaperWorldRuntime.INSTANCE, world, Duration.ofMillis(50));
            future.get();

            assertThat(world.isAutoSave()).isTrue();
        } finally {
            sched.shutdownNow();
        }
    }

    @Test
    @DisplayName("snapshot commit produces S3 objects, manifest, DB manifestKey, and DB profile updates")
    void snapshotCommitProducesS3ObjectsAndDbUpdates() throws Exception {
        Path scratch = tempDir.resolve("scratch");
        WorldId worldId = WorldFixture.materialize(scratch);
        UUID owner = UUID.randomUUID();
        onDb(() -> {
            worldRepo.create(worldId, owner, "my-world", 1234L, 5000, Visibility.PRIVATE);
            worldRepo.markReadyAndPlayed(worldId);
            return null;
        });

        String overworldName = folders.bukkitWorldName(worldId, DimensionKind.OVERWORLD);
        WorldMock overworld = server.addSimpleWorld(overworldName);

        PlayerMock player = server.addPlayer();
        player.teleport(overworld.getSpawnLocation());
        player.getInventory().addItem(new ItemStack(Material.DIAMOND, 16));
        player.setLevel(15);

        // Execute commit
        var future = commitService.requestCommit(worldId);
        flushExecutors();
        future.join();
        flushExecutors();

        // 1. Verify autoSave is restored to true
        assertThat(overworld.isAutoSave()).isTrue();

        // 2. Verify cached manifest is populated
        assertThat(commitService.cachedManifest(worldId)).isPresent();
        Manifest manifest = commitService.cachedManifest(worldId).orElseThrow();
        assertThat(manifest.worldId()).isEqualTo(worldId);
        assertThat(manifest.sequence()).isEqualTo(1);
        assertThat(manifest.generation()).isZero();

        // 3. Verify S3 manifest exists
        assertThat(objectStore.exists(manifest.manifestKey())).isTrue();

        // 4. Verify DB row updated
        PlayerWorld updatedRow = onDb(() -> worldRepo.findById(worldId)).orElseThrow();
        assertThat(updatedRow.manifestKey()).isEqualTo(manifest.manifestKey());
        assertThat(updatedRow.dataVersion()).isEqualTo(Platform.BUILD_DATA_VERSION);
        assertThat(updatedRow.mcVersion()).isEqualTo("26.2");

        // 5. Verify DB profile saved
        ProfileRepository.Snapshot snap = new ProfileRepository.Snapshot(0L, 1);
        var storedProfile = onDb(() -> profileRepo.load(worldId, player.getUniqueId(), snap));
        assertThat(storedProfile).isPresent();
        ProfileEnvelope envelope = ProfileCodec.decode(
                storedProfile.get().data(), storedProfile.get().formatVersion());
        assertThat(envelope.xpLevel()).isEqualTo(15);
        ItemStack[] deserializedItems = PaperItemCodec.INSTANCE.deserializeItems(envelope.inventory());
        assertThat(deserializedItems)
                .anyMatch(item -> item != null && item.getType() == Material.DIAMOND && item.getAmount() == 16);
    }

    @Test
    @DisplayName("committing departing player captures their profile and commits atomically")
    void commitDeparturePersistsDepartingPlayer() throws Exception {
        Path scratch = tempDir.resolve("scratch");
        WorldId worldId = WorldFixture.materialize(scratch);
        UUID owner = UUID.randomUUID();
        onDb(() -> {
            worldRepo.create(worldId, owner, "depart-world", 1234L, 5000, Visibility.PRIVATE);
            worldRepo.markReadyAndPlayed(worldId);
            return null;
        });

        String overworldName = folders.bukkitWorldName(worldId, DimensionKind.OVERWORLD);
        WorldMock overworld = server.addSimpleWorld(overworldName);

        PlayerMock departingPlayer = server.addPlayer();
        departingPlayer.teleport(overworld.getSpawnLocation());
        departingPlayer.getInventory().addItem(new ItemStack(Material.EMERALD, 5));
        departingPlayer.setLevel(7);

        var future = commitService.commitDeparture(worldId, departingPlayer, overworldName);
        flushExecutors();
        future.join();
        flushExecutors();

        ProfileRepository.Snapshot snap = new ProfileRepository.Snapshot(0L, 1);
        var storedProfile = onDb(() -> profileRepo.load(worldId, departingPlayer.getUniqueId(), snap));
        assertThat(storedProfile).isPresent();
        ProfileEnvelope envelope = ProfileCodec.decode(
                storedProfile.get().data(), storedProfile.get().formatVersion());
        assertThat(envelope.xpLevel()).isEqualTo(7);
        ItemStack[] items = PaperItemCodec.INSTANCE.deserializeItems(envelope.inventory());
        assertThat(items).anyMatch(item -> item != null && item.getType() == Material.EMERALD && item.getAmount() == 5);
    }

    @Test
    @DisplayName("profile-only fallback commits without snapshot engine")
    void fallbackCommitsProfilesDirectly() throws Exception {
        WorldCommitService fallbackService =
                new WorldCommitService(profileRepo, profileService, folders, platform.worldLifecycle(), executors);

        WorldId worldId = WorldId.random();
        UUID owner = UUID.randomUUID();
        onDb(() -> {
            worldRepo.create(worldId, owner, "fallback-world", 1234L, 5000, Visibility.PRIVATE);
            worldRepo.markReadyAndPlayed(worldId);
            return null;
        });

        String overworldName = folders.bukkitWorldName(worldId, DimensionKind.OVERWORLD);
        WorldMock overworld = server.addSimpleWorld(overworldName);

        PlayerMock player = server.addPlayer();
        player.teleport(overworld.getSpawnLocation());
        player.getInventory().addItem(new ItemStack(Material.GOLD_INGOT, 3));

        var future = fallbackService.requestCommit(worldId);
        flushExecutors();
        future.join();
        flushExecutors();

        var latestSnapshot = onDb(() -> profileRepo.latestSnapshot(worldId));
        assertThat(latestSnapshot).isPresent();
        var stored = onDb(() -> profileRepo.load(worldId, player.getUniqueId(), latestSnapshot.get()));
        assertThat(stored).isPresent();
    }

    private void awaitFlush(Callable<Boolean> condition) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            flushExecutors();
            if (Boolean.TRUE.equals(condition.call())) {
                return;
            }
            Thread.sleep(20);
        }
        flushExecutors();
    }

    @Test
    @DisplayName("ProfileListener loads exact snapshot indicated by manifest_key and restores player")
    void profileListenerLoadsExactSnapshotFromManifestKey() throws Exception {
        WorldId worldId = WorldId.random();
        UUID owner = UUID.randomUUID();
        onDb(() -> {
            worldRepo.create(worldId, owner, "listener-world", 1234L, 5000, Visibility.PRIVATE);
            worldRepo.markReadyAndPlayed(worldId);
            return null;
        });

        ProfileRepository.Snapshot snap2 = new ProfileRepository.Snapshot(0L, 2);
        ItemStack[] invContents = new ItemStack[41];
        invContents[0] = new ItemStack(Material.NETHERITE_SWORD, 1);
        ItemStack[] ecContents = new ItemStack[27];

        ProfileEnvelope env2 = new ProfileEnvelope(
                PaperItemCodec.INSTANCE.serializeItems(invContents),
                PaperItemCodec.INSTANCE.serializeItems(ecContents),
                42,
                0.5f,
                1000,
                20.0,
                20,
                5.0f,
                List.of(),
                new ProfileEnvelope.StoredLocation(
                        folders.bukkitWorldName(worldId, DimensionKind.OVERWORLD), 0, 64, 0, 0, 0));

        ProfileListener listener =
                new ProfileListener(folders, profileService, profileRepo, worldRepo, commitService, executors);

        WorldMock defaultWorld = server.addSimpleWorld("world");
        WorldMock targetWorld = server.addSimpleWorld(folders.bukkitWorldName(worldId, DimensionKind.OVERWORLD));

        PlayerMock joiningPlayer = server.addPlayer();
        joiningPlayer.teleport(defaultWorld.getSpawnLocation());

        boolean committed = onDb(() -> worldRepo.commitSnapshot(
                worldId,
                0L,
                "node-test",
                "worlds/" + worldId.value() + "/manifest/0-2.json",
                4903,
                "26.2",
                snap2,
                ProfileCodec.FORMAT_VERSION,
                Map.of(joiningPlayer.getUniqueId(), ProfileCodec.encode(env2)),
                profileRepo));
        assertThat(committed).isTrue();

        var inDb = onDb(() -> profileRepo.load(worldId, joiningPlayer.getUniqueId(), snap2));
        assertThat(inDb).isPresent();

        joiningPlayer.teleport(targetWorld.getSpawnLocation());
        PlayerChangedWorldEvent event = new PlayerChangedWorldEvent(joiningPlayer, defaultWorld);
        listener.onChangedWorld(event);

        awaitFlush(() -> joiningPlayer.getLevel() == 42);

        assertThat(joiningPlayer.getLevel()).isEqualTo(42);
        assertThat(joiningPlayer.getInventory().getContents())
                .anyMatch(item -> item != null && item.getType() == Material.NETHERITE_SWORD);
    }
}
