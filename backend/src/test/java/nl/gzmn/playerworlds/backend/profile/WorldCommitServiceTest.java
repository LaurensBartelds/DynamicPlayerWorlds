package nl.gzmn.playerworlds.backend.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
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
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import nl.gzmn.playerworlds.backend.lease.SelfFencingHandler;
import nl.gzmn.playerworlds.backend.platform.DimensionKind;
import nl.gzmn.playerworlds.backend.platform.PaperItemCodec;
import nl.gzmn.playerworlds.backend.platform.PaperWorldRuntime;
import nl.gzmn.playerworlds.backend.platform.Platform;
import nl.gzmn.playerworlds.backend.platform.ServerIdentity;
import nl.gzmn.playerworlds.backend.storage.QuiesceWatchdog;
import nl.gzmn.playerworlds.backend.world.LoadedWorld;
import nl.gzmn.playerworlds.backend.world.WorldFolders;
import nl.gzmn.playerworlds.backend.world.WorldRegistry;
import nl.gzmn.playerworlds.core.concurrent.MainThread;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.config.StorageClientSettings;
import nl.gzmn.playerworlds.core.control.CommandKind;
import nl.gzmn.playerworlds.core.control.EjectPayload;
import nl.gzmn.playerworlds.core.control.NodeCommand;
import nl.gzmn.playerworlds.core.db.Database;
import nl.gzmn.playerworlds.core.db.NodeCommandRepository;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository;
import nl.gzmn.playerworlds.core.db.ProfileRepository;
import nl.gzmn.playerworlds.core.db.Schema;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.Visibility;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.obs.WorldsMetrics;
import nl.gzmn.playerworlds.core.profile.ProfileCodec;
import nl.gzmn.playerworlds.core.profile.ProfileEnvelope;
import nl.gzmn.playerworlds.core.storage.LocalObjectCache;
import nl.gzmn.playerworlds.core.storage.Manifest;
import nl.gzmn.playerworlds.core.storage.ObjectStore;
import nl.gzmn.playerworlds.core.storage.PlainFileCloner;
import nl.gzmn.playerworlds.core.storage.S3ObjectStore;
import nl.gzmn.playerworlds.core.storage.SnapshotCopier;
import nl.gzmn.playerworlds.core.storage.SnapshotEngine;
import nl.gzmn.playerworlds.core.storage.StorageException;
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
    private NodeCommandRepository nodeCommands;
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
        nodeCommands = new NodeCommandRepository(database);

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
                "node-test",
                WorldFixture.PRIMARY_LEVEL_NAME);

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
    @DisplayName("departing profile survives a failed commit and is written on the next one (R6, FR-15)")
    void departingProfileSurvivesAFailedCommit_FR15() throws Exception {
        Path scratch = tempDir.resolve("scratch");
        WorldId worldId = WorldFixture.materialize(scratch);
        UUID owner = UUID.randomUUID();
        onDb(() -> {
            worldRepo.create(worldId, owner, "r6-world", 1234L, 5000, Visibility.PRIVATE);
            worldRepo.markReadyAndPlayed(worldId);
            return null;
        });

        String overworldName = folders.bukkitWorldName(worldId, DimensionKind.OVERWORLD);
        WorldMock overworld = server.addSimpleWorld(overworldName);

        PlayerMock departingPlayer = server.addPlayer();
        departingPlayer.teleport(overworld.getSpawnLocation());
        departingPlayer.getInventory().addItem(new ItemStack(Material.EMERALD, 9));
        departingPlayer.setLevel(11);

        // Object store that fails the first commit's uploads (phase 2), then works.
        java.util.concurrent.atomic.AtomicBoolean failUploads = new java.util.concurrent.atomic.AtomicBoolean(true);
        ObjectStore flakyStore = new ObjectStore() {
            @Override
            public void putObject(String key, Path sourceFile) {
                if (failUploads.get()) {
                    throw new StorageException("injected phase-2 failure for R6");
                }
                objectStore.putObject(key, sourceFile);
            }

            @Override
            public void putBytes(String key, byte[] bytes, String contentType) {
                if (failUploads.get()) {
                    throw new StorageException("injected phase-2 failure for R6");
                }
                objectStore.putBytes(key, bytes, contentType);
            }

            @Override
            public void getObject(String key, Path destinationFile) {
                objectStore.getObject(key, destinationFile);
            }

            @Override
            public byte[] getBytes(String key) {
                return objectStore.getBytes(key);
            }

            @Override
            public boolean exists(String key) {
                return objectStore.exists(key);
            }

            @Override
            public void deleteObject(String key) {
                objectStore.deleteObject(key);
            }

            @Override
            public void deletePrefix(String prefix) {
                objectStore.deletePrefix(prefix);
            }

            @Override
            public long getObjectSize(String key) {
                return objectStore.getObjectSize(key);
            }

            @Override
            public void close() {
                // underlying store closed in tearDown
            }
        };

        SnapshotEngine flakyEngine =
                new SnapshotEngine(flakyStore, objectCache, new SnapshotCopier(PlainFileCloner.INSTANCE));
        WorldCommitService flakyService = new WorldCommitService(
                profileRepo,
                worldRepo,
                profileService,
                folders,
                platform,
                executors,
                flakyEngine,
                NetworkPolicy::defaults,
                scratch,
                "node-test",
                WorldFixture.PRIMARY_LEVEL_NAME);

        // Player leaves — payload staged, commit starts and fails in phase 2.
        var failed = flakyService.commitDeparture(worldId, departingPlayer, overworldName);
        // Leave the world so captureWorld cannot re-capture them on the retry.
        departingPlayer.teleport(server.addSimpleWorld("lobby").getSpawnLocation());
        flushExecutors();
        try {
            failed.join();
        } catch (Exception expected) {
            // phase-2 StorageException wrapped in CompletionException
        }
        flushExecutors();

        assertThat(flakyService.hasPendingDeparture(worldId, departingPlayer.getUniqueId()))
                .as("R6: departure must still be staged after the failed commit")
                .isTrue();
        assertThat(onDb(() -> profileRepo.latestSnapshot(worldId))).isEmpty();

        // Next commit succeeds and writes the staged departure.
        failUploads.set(false);
        var ok = flakyService.requestCommit(worldId);
        flushExecutors();
        ok.join();
        flushExecutors();

        assertThat(flakyService.hasPendingDeparture(worldId, departingPlayer.getUniqueId()))
                .isFalse();
        ProfileRepository.Snapshot snap =
                onDb(() -> profileRepo.latestSnapshot(worldId)).orElseThrow();
        var stored = onDb(() -> profileRepo.load(worldId, departingPlayer.getUniqueId(), snap));
        assertThat(stored).isPresent();
        ProfileEnvelope envelope =
                ProfileCodec.decode(stored.get().data(), stored.get().formatVersion());
        assertThat(envelope.xpLevel()).isEqualTo(11);
        ItemStack[] items = PaperItemCodec.INSTANCE.deserializeItems(envelope.inventory());
        assertThat(items).anyMatch(item -> item != null && item.getType() == Material.EMERALD && item.getAmount() == 9);
    }

    /**
     * R8 / MN-3a: a world missing from the registry must abort, not pretend generation is 0.
     *
     * <p>Without the explicit abort, phase 2 falls through with {@code generation = 0},
     * phase 3's {@code commitSnapshot} returns false against a leased row (gen ≥ 1),
     * and the service raises {@code COMMIT_FENCED} — a destructive response to a
     * benign cause. Absence is not a fencing token.
     */
    @Test
    @DisplayName("commit without a registered world aborts rather than fencing (R8 / MN-3a)")
    void commitWithoutARegisteredWorldAbortsRatherThanFencing() throws Exception {
        Path scratch = tempDir.resolve("scratch");
        Path quarantine = tempDir.resolve("quarantine");
        Files.createDirectories(scratch);
        Files.createDirectories(quarantine);

        WorldId worldId = WorldFixture.materialize(scratch);
        UUID owner = UUID.randomUUID();
        // Leased row so generation is 1 — a gen-0 commit would fail the fencing predicate.
        PlayerWorld row = onDb(() -> {
            PlayerWorld created = worldRepo.create(
                    worldId, owner, "r8-world", 1234L, 5000, Visibility.PRIVATE, "node-test", Duration.ofSeconds(60));
            worldRepo.markReadyAndPlayed(worldId);
            return created;
        });
        assertThat(row.generation()).isEqualTo(1L);

        String overworldName = folders.bukkitWorldName(worldId, DimensionKind.OVERWORLD);
        server.addSimpleWorld(overworldName);
        server.addSimpleWorld("lobby");

        AtomicInteger uploads = new AtomicInteger();
        ObjectStore countingStore = new ObjectStore() {
            @Override
            public void putObject(String key, Path sourceFile) {
                uploads.incrementAndGet();
                objectStore.putObject(key, sourceFile);
            }

            @Override
            public void putBytes(String key, byte[] bytes, String contentType) {
                uploads.incrementAndGet();
                objectStore.putBytes(key, bytes, contentType);
            }

            @Override
            public void getObject(String key, Path destinationFile) {
                objectStore.getObject(key, destinationFile);
            }

            @Override
            public byte[] getBytes(String key) {
                return objectStore.getBytes(key);
            }

            @Override
            public boolean exists(String key) {
                return objectStore.exists(key);
            }

            @Override
            public void deleteObject(String key) {
                objectStore.deleteObject(key);
            }

            @Override
            public void deletePrefix(String prefix) {
                objectStore.deletePrefix(prefix);
            }

            @Override
            public long getObjectSize(String key) {
                return objectStore.getObjectSize(key);
            }

            @Override
            public void close() {
                // underlying store closed in tearDown
            }
        };

        SnapshotEngine countingEngine =
                new SnapshotEngine(countingStore, objectCache, new SnapshotCopier(PlainFileCloner.INSTANCE));
        WorldCommitService service = new WorldCommitService(
                profileRepo,
                worldRepo,
                profileService,
                folders,
                platform,
                executors,
                countingEngine,
                NetworkPolicy::defaults,
                scratch,
                "node-test",
                WorldFixture.PRIMARY_LEVEL_NAME);

        WorldRegistry registry = new WorldRegistry();
        // Registry wired but empty: pre-R8 treated that as generation = 0.
        service.setRegistry(registry);
        assertThat(registry.find(worldId)).isEmpty();

        WorldsMetrics metrics = WorldsMetrics.create();
        SelfFencingHandler fencing = new SelfFencingHandler(
                registry,
                folders,
                platform,
                executors,
                service,
                new NodeCommandRepository(database),
                metrics,
                scratch,
                quarantine,
                NetworkPolicy::defaults);
        service.setFencingHandler(fencing);

        // Keep LoadedWorld construction reachable for the "registered" contrast, but
        // do not register — generation 0 on the DB would be legitimate; gen 1 is not.
        assertThat(LoadedWorld.of(row).generation()).isEqualTo(1L);
        assertThat(service.isFenced(worldId)).isFalse();
        assertThat(service.cachedManifest(worldId)).isEmpty();

        var orphan = service.requestCommit(worldId);
        flushExecutors();

        assertThatThrownBy(orphan::join)
                .as("R8: missing registration aborts; it must not look like MN-3a fencing")
                .hasCauseInstanceOf(StorageException.class)
                .cause()
                .hasMessageContaining("not registered")
                .hasMessageContaining("R8");

        assertThat(service.isFenced(worldId))
                .as("R8: abort must not call forget via COMMIT_FENCED")
                .isFalse();
        assertThat(uploads.get())
                .as("R8: no SnapshotEngine work after the abort (old path uploaded with gen 0 first)")
                .isZero();
        assertThat(service.cachedManifest(worldId)).isEmpty();

        // Scratch still present — COMMIT_FENCED quarantine did not run.
        Path worldScratch = WorldFixture.dimensionFolder(scratch, worldId.folder());
        assertThat(Files.exists(worldScratch))
                .as("R8: unregistered commit must not quarantine the scratch dir")
                .isTrue();

        PlayerWorld after = onDb(() -> worldRepo.findById(worldId)).orElseThrow();
        assertThat(after.generation()).isEqualTo(1L);
        assertThat(after.manifestKey())
                .as("R8: DB pointer untouched — commit never reached phase 3")
                .isNull();
    }

    /**
     * R7 / MN-10a: after self-fence, {@code forget} must stop further commits.
     *
     * <p>Without a fenced-world set, the teleport that ejects players raises
     * {@code PlayerChangedWorldEvent} → {@code commitDeparture}, which re-queues a
     * full snapshot of a directory {@code QuarantineManager} is already moving.
     */
    @Test
    @DisplayName("fenced world refuses further commits and performs zero uploads (MN-10a / R7)")
    void fencedWorldRefusesFurtherCommits_MN10a() throws Exception {
        Path scratch = tempDir.resolve("scratch");
        WorldId worldId = WorldFixture.materialize(scratch);
        UUID owner = UUID.randomUUID();
        onDb(() -> {
            worldRepo.create(worldId, owner, "fenced-world", 1234L, 5000, Visibility.PRIVATE);
            worldRepo.markReadyAndPlayed(worldId);
            return null;
        });

        String overworldName = folders.bukkitWorldName(worldId, DimensionKind.OVERWORLD);
        WorldMock overworld = server.addSimpleWorld(overworldName);
        WorldMock lobby = server.addSimpleWorld("lobby");

        PlayerMock player = server.addPlayer();
        player.teleport(overworld.getSpawnLocation());
        player.getInventory().addItem(new ItemStack(Material.DIAMOND, 4));
        player.setLevel(3);

        java.util.concurrent.atomic.AtomicInteger uploads = new java.util.concurrent.atomic.AtomicInteger();
        ObjectStore countingStore = new ObjectStore() {
            @Override
            public void putObject(String key, Path sourceFile) {
                uploads.incrementAndGet();
                objectStore.putObject(key, sourceFile);
            }

            @Override
            public void putBytes(String key, byte[] bytes, String contentType) {
                uploads.incrementAndGet();
                objectStore.putBytes(key, bytes, contentType);
            }

            @Override
            public void getObject(String key, Path destinationFile) {
                objectStore.getObject(key, destinationFile);
            }

            @Override
            public byte[] getBytes(String key) {
                return objectStore.getBytes(key);
            }

            @Override
            public boolean exists(String key) {
                return objectStore.exists(key);
            }

            @Override
            public void deleteObject(String key) {
                objectStore.deleteObject(key);
            }

            @Override
            public void deletePrefix(String prefix) {
                objectStore.deletePrefix(prefix);
            }

            @Override
            public long getObjectSize(String key) {
                return objectStore.getObjectSize(key);
            }

            @Override
            public void close() {
                // underlying store closed in tearDown
            }
        };

        SnapshotEngine countingEngine =
                new SnapshotEngine(countingStore, objectCache, new SnapshotCopier(PlainFileCloner.INSTANCE));
        WorldCommitService service = new WorldCommitService(
                profileRepo,
                worldRepo,
                profileService,
                folders,
                platform,
                executors,
                countingEngine,
                NetworkPolicy::defaults,
                scratch,
                "node-test",
                WorldFixture.PRIMARY_LEVEL_NAME);

        // Seed a cached manifest so a post-fence commit would otherwise try a dirty scan/upload.
        var seed = service.requestCommit(worldId);
        flushExecutors();
        seed.join();
        flushExecutors();
        int uploadsAfterSeed = uploads.get();
        assertThat(uploadsAfterSeed).isPositive();
        assertThat(service.cachedManifest(worldId)).isPresent();

        // selfFence path: unregister is external; forget is what the handler calls.
        service.forget(worldId);
        assertThat(service.cachedManifest(worldId)).isEmpty();
        assertThat(service.isFenced(worldId))
                .as("R7: forget marks the world fenced so departures cannot re-queue work")
                .isTrue();

        // Eject: player leaves the world → ProfileListener would call commitDeparture.
        player.teleport(lobby.getSpawnLocation());
        var departure = service.commitDeparture(worldId, player, overworldName);
        var periodic = service.requestCommit(worldId);
        flushExecutors();

        assertThat(departure)
                .as("R7: commitDeparture after fence must already be failed")
                .isCompletedExceptionally();
        assertThat(periodic)
                .as("R7: requestCommit after fence must already be failed")
                .isCompletedExceptionally();
        assertThat(service.hasPendingDeparture(worldId, player.getUniqueId()))
                .as("R7: departure must not be staged for a fenced world")
                .isFalse();
        assertThat(uploads.get())
                .as("R7 / MN-10a: zero SnapshotEngine uploads after fence")
                .isEqualTo(uploadsAfterSeed);

        // A later successful load clears the fence so commits can run again.
        service.allowCommits(worldId);
        assertThat(service.isFenced(worldId)).isFalse();
        var afterReload = service.requestCommit(worldId);
        flushExecutors();
        afterReload.join();
        flushExecutors();
        assertThat(uploads.get()).isGreaterThan(uploadsAfterSeed);
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

    private List<Long> awaitEjectCommands() throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        List<Long> ids = onDb(() -> nodeCommands.findClaimableIds("proxy", Duration.ofMinutes(1), 10));
        while (ids.isEmpty() && System.currentTimeMillis() < deadline) {
            flushExecutors();
            Thread.sleep(20);
            ids = onDb(() -> nodeCommands.findClaimableIds("proxy", Duration.ofMinutes(1), 10));
        }
        return ids;
    }

    private Component awaitPlayerMessage(PlayerMock player) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        Component msg = player.nextComponentMessage();
        while (msg == null && System.currentTimeMillis() < deadline) {
            flushExecutors();
            Thread.sleep(20);
            msg = player.nextComponentMessage();
        }
        return msg;
    }

    @Test
    @DisplayName("R10: resolveSnapshot uses latestSnapshot only when manifest_key is null")
    void resolveSnapshotScopesLatestFallbackToNoStorage_R10() throws Exception {
        WorldId worldId = WorldId.random();
        UUID owner = UUID.randomUUID();
        onDb(() -> {
            worldRepo.create(worldId, owner, "r10-resolve", 1234L, 5000, Visibility.PRIVATE);
            worldRepo.markReadyAndPlayed(worldId);
            return null;
        });

        ProfileListener listener = new ProfileListener(
                folders,
                profileService,
                profileRepo,
                worldRepo,
                commitService,
                executors,
                nodeCommands,
                NetworkPolicy::defaults);

        // Gen-0 profiles exist (no-storage era).
        onDb(() -> {
            profileRepo.commit(worldId, 0L, ProfileCodec.FORMAT_VERSION, Map.of(owner, new byte[] {1}));
            return null;
        });

        ProfileListener.SnapshotResolution noKey = onDb(() -> listener.resolveSnapshot(worldId));
        assertThat(noKey.fromManifestKey()).isFalse();
        assertThat(noKey.unparseableManifest()).isFalse();
        assertThat(noKey.snapshot()).contains(new ProfileRepository.Snapshot(0L, 0));

        onDb(() -> worldRepo.commitSnapshot(
                worldId,
                0L,
                "node-test",
                "worlds/" + worldId.value() + "/manifest/2-3.json",
                100L,
                4903,
                "26.2",
                new ProfileRepository.Snapshot(2L, 3),
                ProfileCodec.FORMAT_VERSION,
                Map.of(),
                profileRepo));

        ProfileListener.SnapshotResolution named = onDb(() -> listener.resolveSnapshot(worldId));
        assertThat(named.fromManifestKey()).isTrue();
        assertThat(named.snapshot()).contains(new ProfileRepository.Snapshot(2L, 3));

        // Unparseable key must not fall back to latest (that was FR-15a skew).
        onDb(() -> database.inTransaction(connection -> {
            try (var stmt = connection.prepareStatement("UPDATE player_world SET manifest_key = ? WHERE id = ?")) {
                stmt.setString(1, "not-a-valid-manifest-key");
                stmt.setObject(2, worldId.value());
                stmt.executeUpdate();
            }
            return null;
        }));
        ProfileListener.SnapshotResolution bad = onDb(() -> listener.resolveSnapshot(worldId));
        assertThat(bad.unparseableManifest()).isTrue();
        assertThat(bad.snapshot()).isEmpty();
    }

    @Test
    @DisplayName("R10: missing named snapshot with older rows refuses rather than restoring latest")
    void profileListenerRefusesWhenNamedSnapshotOrphaned_R10() throws Exception {
        WorldId worldId = WorldId.random();
        UUID worldOwner = UUID.randomUUID();
        onDb(() -> {
            worldRepo.create(worldId, worldOwner, "r10-orphan", 1234L, 5000, Visibility.PRIVATE);
            worldRepo.markReadyAndPlayed(worldId);
            return null;
        });

        WorldMock defaultWorld = server.addSimpleWorld("world");
        WorldMock targetWorld = server.addSimpleWorld(folders.bukkitWorldName(worldId, DimensionKind.OVERWORLD));
        PlayerMock joiningPlayer = server.addPlayer();
        UUID playerId = joiningPlayer.getUniqueId();

        ProfileRepository.Snapshot gen0 = new ProfileRepository.Snapshot(0L, 0);
        ItemStack[] invContents = new ItemStack[41];
        invContents[0] = new ItemStack(Material.DIAMOND, 3);
        ItemStack[] ecContents = new ItemStack[27];
        ProfileEnvelope env = new ProfileEnvelope(
                PaperItemCodec.INSTANCE.serializeItems(invContents),
                PaperItemCodec.INSTANCE.serializeItems(ecContents),
                11,
                0.5f,
                1000,
                20.0,
                20,
                5.0f,
                List.of(),
                new ProfileEnvelope.StoredLocation(
                        folders.bukkitWorldName(worldId, DimensionKind.OVERWORLD), 0, 64, 0, 0, 0));

        // Seed gen-0 only, then point manifest at a snapshot with no profile row
        // without going through commitSnapshot (so re-key does not run) — the
        // prune / missed-transition failure mode §7 describes.
        onDb(() -> {
            database.inTransaction(connection -> {
                profileRepo.saveAll(
                        connection,
                        worldId,
                        gen0,
                        ProfileCodec.FORMAT_VERSION,
                        Map.of(playerId, ProfileCodec.encode(env)));
                return null;
            });
            database.inTransaction(connection -> {
                try (var stmt = connection.prepareStatement("UPDATE player_world SET manifest_key = ? WHERE id = ?")) {
                    stmt.setString(1, "worlds/" + worldId.value() + "/manifest/5-1.json");
                    stmt.setObject(2, worldId.value());
                    stmt.executeUpdate();
                }
                return null;
            });
            return null;
        });

        ProfileListener listener = new ProfileListener(
                folders,
                profileService,
                profileRepo,
                worldRepo,
                commitService,
                executors,
                nodeCommands,
                NetworkPolicy::defaults);

        joiningPlayer.teleport(defaultWorld.getSpawnLocation());
        joiningPlayer.getInventory().addItem(new ItemStack(Material.STONE, 1));

        joiningPlayer.teleport(targetWorld.getSpawnLocation());
        listener.onChangedWorld(new PlayerChangedWorldEvent(joiningPlayer, defaultWorld));

        List<Long> ejectIds = awaitEjectCommands();

        assertThat(joiningPlayer.getInventory().getContents())
                .as("R10/R11: refuse must not restore gen-0 diamonds via latestSnapshot")
                .noneMatch(item -> item != null && item.getType() == Material.DIAMOND);
        assertThat(joiningPlayer.getInventory().getContents())
                .as("R11: FR-16 refuse must not clear the inventory the player arrived with")
                .anyMatch(item -> item != null && item.getType() == Material.STONE);
        assertThat(ejectIds)
                .as("R11: orphaned named snapshot refuses with EJECT_PLAYER")
                .isNotEmpty();
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

        ProfileListener listener = new ProfileListener(
                folders,
                profileService,
                profileRepo,
                worldRepo,
                commitService,
                executors,
                nodeCommands,
                NetworkPolicy::defaults);

        WorldMock defaultWorld = server.addSimpleWorld("world");
        WorldMock targetWorld = server.addSimpleWorld(folders.bukkitWorldName(worldId, DimensionKind.OVERWORLD));

        PlayerMock joiningPlayer = server.addPlayer();
        joiningPlayer.teleport(defaultWorld.getSpawnLocation());

        boolean committed = onDb(() -> worldRepo.commitSnapshot(
                worldId,
                0L,
                "node-test",
                "worlds/" + worldId.value() + "/manifest/0-2.json",
                1024L,
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

    @Test
    @DisplayName("R11: unreadable profile ejects rather than clearing inventory (FR-16)")
    void unreadableProfileEjectsRatherThanClearing_FR16() throws Exception {
        WorldId worldId = WorldId.random();
        UUID owner = UUID.randomUUID();
        onDb(() -> {
            worldRepo.create(worldId, owner, "r11-corrupt", 1234L, 5000, Visibility.PRIVATE);
            worldRepo.markReadyAndPlayed(worldId);
            return null;
        });

        WorldMock defaultWorld = server.addSimpleWorld("world");
        WorldMock targetWorld = server.addSimpleWorld(folders.bukkitWorldName(worldId, DimensionKind.OVERWORLD));
        PlayerMock player = server.addPlayer();
        UUID playerId = player.getUniqueId();

        // Corrupt BYTEA at a real snapshot — decode must throw ProfileFormatException.
        ProfileRepository.Snapshot snap = new ProfileRepository.Snapshot(1L, 1);
        onDb(() -> {
            database.inTransaction(connection -> {
                profileRepo.saveAll(
                        connection, worldId, snap, ProfileCodec.FORMAT_VERSION, Map.of(playerId, new byte[] {
                            1, 2, 3, 4, 5
                        }));
                return null;
            });
            database.inTransaction(connection -> {
                try (var stmt = connection.prepareStatement("UPDATE player_world SET manifest_key = ? WHERE id = ?")) {
                    stmt.setString(1, "worlds/" + worldId.value() + "/manifest/1-1.json");
                    stmt.setObject(2, worldId.value());
                    stmt.executeUpdate();
                }
                return null;
            });
            return null;
        });

        ProfileListener listener = new ProfileListener(
                folders,
                profileService,
                profileRepo,
                worldRepo,
                commitService,
                executors,
                nodeCommands,
                NetworkPolicy::defaults);

        player.teleport(defaultWorld.getSpawnLocation());
        player.getInventory().addItem(new ItemStack(Material.DIAMOND, 7));
        player.setLevel(3);

        player.teleport(targetWorld.getSpawnLocation());
        listener.onChangedWorld(new PlayerChangedWorldEvent(player, defaultWorld));

        Component msg = awaitPlayerMessage(player);
        assertThat(msg).isNotNull();
        String text = PlainTextComponentSerializer.plainText().serialize(msg);
        assertThat(text).contains("could not be read");
        assertThat(text).contains("Returning you to the lobby");
        assertThat(text).doesNotContain("Nothing has been overwritten");

        List<Long> ids = awaitEjectCommands();
        assertThat(ids).hasSize(1);
        NodeCommand command = onDb(() -> nodeCommands.findById(ids.getFirst())).orElseThrow();
        assertEquals(CommandKind.EJECT_PLAYER.name(), command.command());
        var eject = EjectPayload.parse(command.payloadJson()).orElseThrow();
        assertEquals(playerId, eject.playerUuid());
        assertThat(eject.reason()).contains("deserialised");

        // R11 acceptance: undecodable profile → eject and no inventory mutation.
        assertThat(player.getLevel()).isEqualTo(3);
        assertThat(player.getInventory().getContents())
                .as("FR-16 must not clear or replace inventory on refuse")
                .anyMatch(item -> item != null && item.getType() == Material.DIAMOND && item.getAmount() == 7);
    }
}
