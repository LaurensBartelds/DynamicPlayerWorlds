package nl.gzmn.playerworlds.backend.lease;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
import nl.gzmn.playerworlds.core.storage.LocalObjectCache;
import nl.gzmn.playerworlds.core.storage.PlainFileCloner;
import nl.gzmn.playerworlds.core.storage.S3ObjectStore;
import nl.gzmn.playerworlds.core.storage.SnapshotCopier;
import nl.gzmn.playerworlds.core.storage.SnapshotEngine;
import nl.gzmn.playerworlds.core.storage.StorageException;
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
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

class LeaseFencingTest {

    @TempDir
    Path tempDir;

    private ServerMock server;
    private Database database;
    private PluginExecutors executors;
    private Queue<Runnable> mainTasks;
    private Platform platform;
    private WorldFolders folders;
    private WorldRegistry registry;
    private PlayerWorldRepository worldRepo;
    private ProfileRepository profileRepo;
    private NodeCommandRepository nodeCommands;
    private WorldsMetrics metrics;
    private S3ObjectStore objectStore;
    private LocalObjectCache objectCache;
    private SnapshotEngine snapshotEngine;
    private WorldCommitService commitService;
    private SelfFencingHandler fencingHandler;
    private LeaseCoordinator leaseCoordinator;
    private Path scratchDir;
    private Path quarantineDir;

    private NetworkPolicy policy = NetworkPolicy.defaults();

    @BeforeEach
    void setUp() throws Exception {
        database = TestDatabase.openFresh();
        Schema.migrate(database);
        mainTasks = new ConcurrentLinkedQueue<>();
        executors = PluginExecutors.create(1, 1, mainTasks::add);

        MainThread.enter(Thread.currentThread());
        server = MockBukkit.mock();
        platform = Platform.create(new ServerIdentity("26.2", Platform.BUILD_DATA_VERSION));
        folders = new WorldFolders(platform.worldLayout());
        registry = new WorldRegistry();
        worldRepo = new PlayerWorldRepository(database);
        profileRepo = new ProfileRepository(database);
        nodeCommands = new NodeCommandRepository(database);
        metrics = WorldsMetrics.create();

        scratchDir = tempDir.resolve("scratch");
        quarantineDir = tempDir.resolve("quarantine");
        Files.createDirectories(scratchDir);
        Files.createDirectories(quarantineDir);

        StorageClientSettings s3Settings = TestObjectStore.settingsForNewBucket();
        objectStore = S3ObjectStore.open(s3Settings);
        objectCache = new LocalObjectCache(tempDir.resolve("cache"), PlainFileCloner.INSTANCE);
        SnapshotCopier copier = new SnapshotCopier(PlainFileCloner.INSTANCE);
        snapshotEngine = new SnapshotEngine(objectStore, objectCache, copier);

        commitService = new WorldCommitService(
                profileRepo,
                worldRepo,
                new ProfileService(PaperItemCodec.INSTANCE),
                folders,
                platform,
                executors,
                snapshotEngine,
                () -> policy,
                scratchDir,
                "node-A",
                WorldFixture.PRIMARY_LEVEL_NAME);

        fencingHandler = new SelfFencingHandler(
                registry,
                folders,
                platform,
                executors,
                commitService,
                nodeCommands,
                metrics,
                scratchDir,
                WorldFixture.PRIMARY_LEVEL_NAME,
                quarantineDir,
                () -> policy);

        commitService.setRegistry(registry);
        commitService.setFencingHandler(fencingHandler);

        leaseCoordinator = new LeaseCoordinator(
                "node-A", registry, worldRepo, fencingHandler, executors, () -> policy, Duration.ofSeconds(5));
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
    @DisplayName(
            "MN-10b Case 1: Lease stolen/lost triggers immediate self-fencing, dimension unload, player ejection, and scratch quarantine")
    void leaseStolenTriggersImmediateSelfFence() throws Exception {
        WorldId worldId = WorldFixture.materialize(scratchDir);
        UUID owner = UUID.randomUUID();

        // 1. Setup world in DB held by node-A with generation 1
        PlayerWorld row = onDb(() -> {
            PlayerWorld created = worldRepo.create(
                    worldId, owner, "fenced-world", 1234L, 5000, Visibility.PRIVATE, "node-A", Duration.ofSeconds(60));
            worldRepo.markReadyAndPlayed(worldId);
            return created;
        });

        LoadedWorld loaded = LoadedWorld.of(row);
        registry.register(loaded);

        // 2. Add dimensions to MockBukkit and place a player in the overworld
        String overworldName = folders.bukkitWorldName(worldId, DimensionKind.OVERWORLD);
        WorldMock overworld = server.addSimpleWorld(overworldName);
        PlayerMock player = server.addPlayer();
        player.teleport(overworld.getSpawnLocation());

        assertThat(registry.find(worldId)).isPresent();
        assertThat(server.getWorld(overworldName)).isNotNull();
        assertThat(player.getWorld()).isEqualTo(overworld);
        assertThat(overworld.getPlayers()).contains(player);

        // 3. Simulate another node taking the lease (generation 2, assigned_node = "node-B")
        onDb(() -> {
            database.inTransaction(conn -> {
                try (var stmt = conn.prepareStatement(
                        "UPDATE player_world SET assigned_node = 'node-B', generation = 2 WHERE id = ?")) {
                    stmt.setObject(1, worldId.value());
                    int updated = stmt.executeUpdate();
                    assertThat(updated).isEqualTo(1);
                }
                return null;
            });
            return null;
        });

        // 4. Run LeaseCoordinator heartbeat from node-A
        onDb(() -> {
            leaseCoordinator.heartbeatOne(loaded);
            return null;
        });
        flushExecutors();

        // 5. Assert Node A observed lost lease and self-fenced:
        // - Unregistered from registry
        assertThat(registry.find(worldId)).isEmpty();

        // - Ejected command written for proxy control plane
        List<Long> queuedIds = onDb(() -> nodeCommands.findClaimableIds("proxy", Duration.ofSeconds(30), 10));
        assertThat(queuedIds).isNotEmpty();
        long commandId = queuedIds.getFirst();
        NodeCommand queuedCommand = onDb(() -> nodeCommands.findById(commandId).orElseThrow());
        assertThat(queuedCommand.command()).isEqualTo("EJECT_PLAYER");
        assertThat(queuedCommand.payloadJson()).contains(player.getUniqueId().toString());

        // - Scratch directory moved to quarantine
        Path worldScratch = WorldFixture.dimensionFolder(scratchDir, worldId.folder());
        assertThat(Files.exists(worldScratch)).isFalse();

        try (var s = Files.list(quarantineDir)) {
            List<Path> quarantined = s.toList();
            assertThat(quarantined).anyMatch(p -> p.getFileName().toString().startsWith(worldId.folder()));
        }
    }

    @Test
    @DisplayName("MN-10b Case 2: Database unreachable marks lease degraded then triggers safety margin self-fence")
    void databaseUnreachableSafetyMarginSelfFence() throws Exception {
        WorldId worldId = WorldFixture.materialize(scratchDir);
        UUID owner = UUID.randomUUID();

        PlayerWorld row = onDb(() -> {
            PlayerWorld created = worldRepo.create(
                    worldId,
                    owner,
                    "degraded-world",
                    1234L,
                    5000,
                    Visibility.PRIVATE,
                    "node-A",
                    Duration.ofSeconds(60));
            worldRepo.markReadyAndPlayed(worldId);
            return created;
        });

        LoadedWorld loaded = LoadedWorld.of(row);
        registry.register(loaded);

        // 1. Point repo at unreachable database settings to simulate database outage without wiping test DB
        Database brokenDb = Database.open(new nl.gzmn.playerworlds.core.db.DatabaseSettings(
                "jdbc:postgresql://127.0.0.1:1/nonexistent", "user", "pass", 4, Duration.ofMillis(250)));
        try {
            PlayerWorldRepository brokenRepo = new PlayerWorldRepository(brokenDb);

            LeaseCoordinator outageCoordinator = new LeaseCoordinator(
                    "node-A", registry, brokenRepo, fencingHandler, executors, () -> policy, Duration.ofSeconds(5));

            // 2. First heartbeat fails -> lease is marked degraded (MN-10b)
            onDb(() -> {
                outageCoordinator.heartbeatOne(loaded);
                return null;
            });
            flushExecutors();

            assertThat(loaded.isLeaseDegraded()).isTrue();
            assertThat(registry.find(worldId)).isPresent(); // Still loaded before safety margin

            // 3. Configure policy with short lease duration so watchdog detects deadline expired
            this.policy = NetworkPolicy.fromRaw(Map.of(
                    NetworkPolicy.KEY_LEASE_SECONDS, "1",
                    NetworkPolicy.KEY_FENCE_SAFETY_MARGIN_SECONDS, "2"));
            assertThat(loaded.isFencedByDeadlineToDb(Duration.ofSeconds(1), Duration.ofSeconds(2)))
                    .isTrue();

            // 4. Run safety margin watchdog
            onDb(() -> {
                outageCoordinator.checkWatchdog();
                return null;
            });
            flushExecutors();

            // 5. Assert self-fence executed
            assertThat(registry.find(worldId)).isEmpty();
            Path worldScratch = WorldFixture.dimensionFolder(scratchDir, worldId.folder());
            assertThat(Files.exists(worldScratch)).isFalse();
        } finally {
            brokenDb.close();
        }
    }

    @Test
    @DisplayName(
            "Spec §11.7 Split-Brain / SIGSTOP Data Integrity: Superseded node commit fails harmlessly and takeover node data remains pristine")
    void splitBrainSigstopDataIntegrityProtectedByFencingToken() throws Exception {
        WorldId worldId = WorldFixture.materialize(scratchDir);
        UUID owner = UUID.randomUUID();

        // 1. Initial creation with Node A holding lease at Generation 1
        PlayerWorld row = onDb(() -> {
            PlayerWorld created = worldRepo.create(
                    worldId, owner, "sigstop-world", 1234L, 5000, Visibility.PRIVATE, "node-A", Duration.ofSeconds(60));
            worldRepo.markReadyAndPlayed(worldId);
            return created;
        });

        LoadedWorld loadedA = LoadedWorld.of(row);
        registry.register(loadedA);
        assertThat(loadedA.generation()).isEqualTo(1L);

        // Commit initial baseline from Node A
        var initialCommit = commitService.requestCommit(worldId);
        flushExecutors();
        initialCommit.join();
        flushExecutors();

        PlayerWorld nodeAFirstDbRow = onDb(() -> worldRepo.findById(worldId).orElseThrow());
        assertThat(nodeAFirstDbRow.manifestKey()).isNotNull();

        // 2. Simulating Node A SIGSTOP / Network Partition:
        // Node A lease expires in database
        onDb(() -> {
            database.inTransaction(conn -> {
                try (var stmt = conn.prepareStatement(
                        "UPDATE player_world SET lease_expires = now() - interval '1 second' WHERE id = ?")) {
                    stmt.setObject(1, worldId.value());
                    int updated = stmt.executeUpdate();
                    assertThat(updated).isEqualTo(1);
                }
                return null;
            });
            return null;
        });

        // Node B acquires the lease (advancing generation to 2)
        PlayerWorldRepository.LeaseGrant grantB = onDb(() -> worldRepo
                .acquireLease(worldId, "node-B", Platform.BUILD_DATA_VERSION, Duration.ofSeconds(60))
                .orElseThrow());
        assertThat(grantB.generation()).isEqualTo(2L);

        // Node B commits a new snapshot with generation 2
        String nodeBManifestKey = "manifests/" + worldId + "/2-1.json";
        byte[] nodeBManifestBytes = "{\"node\":\"node-B\",\"gen\":2}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        objectStore.putBytes(nodeBManifestKey, nodeBManifestBytes, "application/json");

        UUID playerUuid = UUID.randomUUID();
        ProfileRepository.Snapshot snapB = new ProfileRepository.Snapshot(2L, 1);
        byte[] profileBytesB = "node-B-profile-data".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        onDb(() -> {
            boolean committedB = worldRepo.commitSnapshot(
                    worldId,
                    2L,
                    "node-B",
                    nodeBManifestKey,
                    1024L,
                    Platform.BUILD_DATA_VERSION,
                    "26.2",
                    snapB,
                    ProfileCodec.FORMAT_VERSION,
                    Map.of(playerUuid, profileBytesB),
                    profileRepo);
            assertThat(committedB).isTrue();
            return null;
        });

        PlayerWorld dbAfterNodeB = onDb(() -> worldRepo.findById(worldId).orElseThrow());
        assertThat(dbAfterNodeB.manifestKey()).isEqualTo(nodeBManifestKey);
        assertThat(dbAfterNodeB.generation()).isEqualTo(2L);
        assertThat(dbAfterNodeB.assignedNode()).isEqualTo("node-B");

        // 3. Node A resumes from SIGSTOP and tries to commit its dirty snapshot with Generation 1
        // Touch a non-region world file so DirtyScanner sees a change without failing
        // region-structure verification on the snapshot path.
        Path localDirty =
                WorldFixture.dimensionFolder(scratchDir, worldId.folder()).resolve("paper-world.yml");
        Files.writeString(localDirty, Files.readString(localDirty) + "\n# node-A-stale-modification\n");

        var staleCommit = commitService.requestCommit(worldId);
        flushExecutors();

        // Node A commit must complete exceptionally with StorageException (fenced!)
        assertThatThrownBy(staleCommit::join).hasCauseInstanceOf(StorageException.class);

        flushExecutors();

        // 4. Assert Node A self-fenced
        assertThat(registry.find(worldId)).isEmpty();

        // 5. CRITICAL: Assert Node B's state in DB and S3 remains completely untouched and pristine
        PlayerWorld dbFinal = onDb(() -> worldRepo.findById(worldId).orElseThrow());
        assertThat(dbFinal.manifestKey()).isEqualTo(nodeBManifestKey);
        assertThat(dbFinal.generation()).isEqualTo(2L);
        assertThat(dbFinal.assignedNode()).isEqualTo("node-B");

        byte[] s3Manifest = objectStore.getBytes(nodeBManifestKey);
        assertThat(new String(s3Manifest, java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("{\"node\":\"node-B\",\"gen\":2}");
    }
}
