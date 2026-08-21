package nl.gzmn.playerworlds.backend.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import nl.gzmn.playerworlds.backend.platform.DimensionKind;
import nl.gzmn.playerworlds.backend.platform.Platform;
import nl.gzmn.playerworlds.backend.platform.ServerIdentity;
import nl.gzmn.playerworlds.backend.world.LoadOutcome;
import nl.gzmn.playerworlds.backend.world.LoadedWorld;
import nl.gzmn.playerworlds.backend.world.MembershipCache;
import nl.gzmn.playerworlds.backend.world.WorldFolders;
import nl.gzmn.playerworlds.backend.world.WorldLifecycleService;
import nl.gzmn.playerworlds.backend.world.WorldRegistry;
import nl.gzmn.playerworlds.core.concurrent.MainThread;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.config.NodeConfig;
import nl.gzmn.playerworlds.core.control.CommandKind;
import nl.gzmn.playerworlds.core.control.EjectPayload;
import nl.gzmn.playerworlds.core.control.NodeCommand;
import nl.gzmn.playerworlds.core.db.Database;
import nl.gzmn.playerworlds.core.db.MembershipRepository;
import nl.gzmn.playerworlds.core.db.NodeCommandRepository;
import nl.gzmn.playerworlds.core.db.PendingTransferRepository;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository;
import nl.gzmn.playerworlds.core.db.Schema;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.Visibility;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.model.WorldState;
import nl.gzmn.playerworlds.core.obs.WorldsMetrics;
import nl.gzmn.playerworlds.testing.TestDatabase;
import org.bukkit.World;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

class TransferJoinListenerTest {

    @TempDir
    Path tempDir;

    private ServerMock server;
    private Database database;
    private PluginExecutors executors;
    private WorldsMetrics metrics;
    private Platform platform;
    private WorldFolders folders;
    private WorldRegistry registry;
    private MembershipCache membershipCache;
    private PlayerWorldRepository worldRepo;
    private MembershipRepository membershipRepo;
    private PendingTransferRepository transferRepo;
    private NodeCommandRepository nodeCommands;
    private WorldLifecycleService lifecycle;
    private NodeConfig nodeConfig;
    private TransferJoinListener listener;
    private Queue<Runnable> mainTasks;

    @BeforeEach
    void setUp() throws Exception {
        database = TestDatabase.openFresh();
        Schema.migrate(database);
        mainTasks = new ConcurrentLinkedQueue<>();
        executors = PluginExecutors.create(2, 2, mainTasks::add);
        metrics = WorldsMetrics.create();
        platform = Platform.create(new ServerIdentity("26.2", Platform.BUILD_DATA_VERSION));
        folders = new WorldFolders(platform.worldLayout());
        registry = new WorldRegistry();
        membershipCache = new MembershipCache();
        worldRepo = new PlayerWorldRepository(database);
        membershipRepo = new MembershipRepository(database);
        transferRepo = new PendingTransferRepository(database);
        nodeCommands = new NodeCommandRepository(database);

        nodeConfig = new NodeConfig(
                "node-1",
                "127.0.0.1:25565",
                Duration.ofSeconds(30),
                TestDatabase.settings(),
                null,
                tempDir,
                tempDir.resolve("cache"),
                tempDir.resolve("quarantine"),
                0L);

        lifecycle = new WorldLifecycleService(
                worldRepo,
                membershipRepo,
                membershipCache,
                executors,
                platform,
                folders,
                registry,
                metrics,
                NetworkPolicy::defaults,
                tempDir);

        listener = new TransferJoinListener(
                nodeConfig,
                transferRepo,
                lifecycle,
                folders,
                executors,
                nodeCommands,
                NetworkPolicy::defaults,
                metrics);

        MainThread.enter(Thread.currentThread());
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
        MainThread.clear();
        metrics.close();
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

    private List<Long> awaitNodeCommands() throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        List<Long> ids = onDb(() -> nodeCommands.findClaimableIds("proxy", Duration.ofMinutes(1), 10));
        while (ids.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
            ids = onDb(() -> nodeCommands.findClaimableIds("proxy", Duration.ofMinutes(1), 10));
        }
        return ids;
    }

    @Test
    @DisplayName("node mismatch refuses join, sends message, and enqueues EJECT_PLAYER to proxy")
    void nodeMismatchRefusesAndEnqueuesEject() throws Exception {
        PlayerMock player = server.addPlayer();
        UUID playerUuid = player.getUniqueId();
        WorldId worldId = WorldId.random();

        onDb(() -> {
            worldRepo.create(worldId, playerUuid, "MismatchWorld", 12345L, 5000, Visibility.PRIVATE);
            // Route transfer for player to node-2 while this node is node-1
            transferRepo.route(playerUuid, worldId, "node-2", 0L);
            return null;
        });

        listener.onJoin(new PlayerJoinEvent(player, Component.text("joined")));

        Component msg = awaitPlayerMessage(player);
        assertThat(msg).isNotNull();
        String message = PlainTextComponentSerializer.plainText().serialize(msg);
        assertThat(message).contains("that world moved to another server while you were connecting");

        List<Long> ids = awaitNodeCommands();
        assertThat(ids).hasSize(1);
        NodeCommand command = onDb(() -> nodeCommands.findById(ids.getFirst())).orElseThrow();
        assertEquals(CommandKind.EJECT_PLAYER.name(), command.command());
        assertThat(command.payloadJson()).contains(playerUuid.toString());
        var eject = EjectPayload.parse(command.payloadJson());
        assertThat(eject).isPresent();
        assertEquals(playerUuid, eject.get().playerUuid());
    }

    @Test
    @DisplayName("generation mismatch refuses join, sends message, and enqueues EJECT_PLAYER to proxy")
    void generationMismatchRefusesAndEnqueuesEject() throws Exception {
        PlayerMock player = server.addPlayer();
        UUID playerUuid = player.getUniqueId();
        WorldId worldId = WorldId.random();

        LoadedWorld loadedWorld = new LoadedWorld(worldId, playerUuid, "TestGenWorld", 12345L, 5000, 5L);
        registry.register(loadedWorld);

        onDb(() -> {
            worldRepo.create(worldId, playerUuid, "TestGenWorld", 12345L, 5000, Visibility.PRIVATE);
            // Route transfer with generation 0 (mismatch against loaded generation 5)
            transferRepo.route(playerUuid, worldId, "node-1", 0L);
            return null;
        });

        listener.onJoin(new PlayerJoinEvent(player, Component.text("joined")));

        Component msg = awaitPlayerMessage(player);
        assertThat(msg).isNotNull();
        String message = PlainTextComponentSerializer.plainText().serialize(msg);
        assertThat(message).contains("that world moved while you were connecting");

        List<Long> ids = awaitNodeCommands();
        assertThat(ids).hasSize(1);
        NodeCommand command = onDb(() -> nodeCommands.findById(ids.getFirst())).orElseThrow();
        assertEquals(CommandKind.EJECT_PLAYER.name(), command.command());
    }

    @Test
    @DisplayName("load outcome NotFound refuses join, sends message, and enqueues EJECT_PLAYER to proxy")
    void notFoundRefusesAndEnqueuesEject() throws Exception {
        PlayerMock player = server.addPlayer();
        UUID playerUuid = player.getUniqueId();
        WorldId missingWorldId = WorldId.random();

        // Temporarily disable FK on pending_transfer to simulate orphaned transfer
        onDb(() -> {
            database.inTransaction(conn -> {
                try (var st = conn.prepareStatement(
                        "ALTER TABLE pending_transfer DROP CONSTRAINT pending_transfer_world_id_fkey")) {
                    st.executeUpdate();
                }
                return null;
            });
            transferRepo.route(playerUuid, missingWorldId, "node-1", 0L);
            return null;
        });

        listener.onJoin(new PlayerJoinEvent(player, Component.text("joined")));

        Component msg = awaitPlayerMessage(player);
        assertThat(msg).isNotNull();
        String message = PlainTextComponentSerializer.plainText().serialize(msg);
        assertThat(message).contains("that world no longer exists");

        List<Long> ids = awaitNodeCommands();
        assertThat(ids).hasSize(1);
        NodeCommand command = onDb(() -> nodeCommands.findById(ids.getFirst())).orElseThrow();
        assertEquals(CommandKind.EJECT_PLAYER.name(), command.command());
        assertThat(command.worldId()).isNull();
    }

    @Test
    @DisplayName("load outcome WrongState refuses join, sends message, and enqueues EJECT_PLAYER to proxy")
    void wrongStateRefusesAndEnqueuesEject() throws Exception {
        PlayerMock player = server.addPlayer();
        UUID playerUuid = player.getUniqueId();
        WorldId worldId = WorldId.random();

        onDb(() -> {
            PlayerWorld world =
                    worldRepo.create(worldId, playerUuid, "ArchivedWorld", 12345L, 5000, Visibility.PRIVATE);
            worldRepo.transitionState(worldId, WorldState.CREATING, WorldState.ARCHIVED);
            transferRepo.route(playerUuid, worldId, "node-1", world.generation());
            return null;
        });

        listener.onJoin(new PlayerJoinEvent(player, Component.text("joined")));

        Component msg = awaitPlayerMessage(player);
        assertThat(msg).isNotNull();
        String message = PlainTextComponentSerializer.plainText().serialize(msg);
        assertThat(message).contains("and cannot be entered right now");

        List<Long> ids = awaitNodeCommands();
        assertThat(ids).hasSize(1);
        NodeCommand command = onDb(() -> nodeCommands.findById(ids.getFirst())).orElseThrow();
        assertEquals(CommandKind.EJECT_PLAYER.name(), command.command());
    }

    @Test
    @DisplayName("load outcome TooNew refuses join, sends message, and enqueues EJECT_PLAYER to proxy")
    void tooNewRefusesAndEnqueuesEject() throws Exception {
        PlayerMock player = server.addPlayer();
        UUID playerUuid = player.getUniqueId();
        WorldId worldId = WorldId.random();

        onDb(() -> {
            PlayerWorld world = worldRepo.create(worldId, playerUuid, "NewerWorld", 12345L, 5000, Visibility.PRIVATE);
            database.inTransaction(conn -> {
                try (var st = conn.prepareStatement("UPDATE player_world SET data_version = 99999 WHERE id = ?")) {
                    st.setObject(1, worldId.value());
                    st.executeUpdate();
                }
                return null;
            });
            transferRepo.route(playerUuid, worldId, "node-1", world.generation());
            return null;
        });

        listener.onJoin(new PlayerJoinEvent(player, Component.text("joined")));

        Component msg = awaitPlayerMessage(player);
        assertThat(msg).isNotNull();
        String message = PlainTextComponentSerializer.plainText().serialize(msg);
        assertThat(message).contains("that world needs a newer server version");

        List<Long> ids = awaitNodeCommands();
        assertThat(ids).hasSize(1);
        NodeCommand command = onDb(() -> nodeCommands.findById(ids.getFirst())).orElseThrow();
        assertEquals(CommandKind.EJECT_PLAYER.name(), command.command());
    }

    @Test
    @DisplayName("load outcome NodeFull refuses join, sends message, and enqueues EJECT_PLAYER to proxy")
    void nodeFullRefusesAndEnqueuesEject() throws Exception {
        PlayerMock player = server.addPlayer();
        UUID playerUuid = player.getUniqueId();
        WorldId worldId = WorldId.random();

        // Fill registry up to NetworkPolicy default max (5)
        for (int i = 0; i < 5; i++) {
            LoadedWorld dummy = new LoadedWorld(WorldId.random(), UUID.randomUUID(), "Dummy" + i, 1L, 5000);
            registry.register(dummy);
        }

        onDb(() -> {
            PlayerWorld world =
                    worldRepo.create(worldId, playerUuid, "FullNodeWorld", 12345L, 5000, Visibility.PRIVATE);
            transferRepo.route(playerUuid, worldId, "node-1", world.generation());
            return null;
        });

        listener.onJoin(new PlayerJoinEvent(player, Component.text("joined")));

        Component msg = awaitPlayerMessage(player);
        assertThat(msg).isNotNull();
        String message = PlainTextComponentSerializer.plainText().serialize(msg);
        assertThat(message).contains("this server is holding");

        List<Long> ids = awaitNodeCommands();
        assertThat(ids).hasSize(1);
        NodeCommand command = onDb(() -> nodeCommands.findById(ids.getFirst())).orElseThrow();
        assertEquals(CommandKind.EJECT_PLAYER.name(), command.command());
    }

    private void awaitPlayerWorld(PlayerMock player, WorldMock expected) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (!expected.equals(player.getWorld()) && System.currentTimeMillis() < deadline) {
            flushExecutors();
            Thread.sleep(20);
        }
    }

    @Test
    @DisplayName("successful join teleports to overworld and does not enqueue EJECT_PLAYER")
    void successfulJoinTeleportsToOverworld() throws Exception {
        PlayerMock player = server.addPlayer();
        UUID playerUuid = player.getUniqueId();
        WorldId worldId = WorldId.random();

        LoadedWorld loadedWorld = new LoadedWorld(worldId, playerUuid, "ValidWorld", 12345L, 5000, 0L);
        loadedWorld.markMaterialised(DimensionKind.OVERWORLD);
        registry.register(loadedWorld);

        String overworldName = folders.bukkitWorldName(worldId, DimensionKind.OVERWORLD);
        WorldMock worldMock = server.addSimpleWorld(overworldName);

        onDb(() -> {
            PlayerWorld world = worldRepo.create(worldId, playerUuid, "ValidWorld", 12345L, 5000, Visibility.PRIVATE);
            transferRepo.route(playerUuid, worldId, "node-1", world.generation());
            return null;
        });

        listener.onJoin(new PlayerJoinEvent(player, Component.text("joined")));
        awaitPlayerWorld(player, worldMock);

        assertEquals(worldMock, player.getWorld());
        List<Long> ids = onDb(() -> nodeCommands.findClaimableIds("proxy", Duration.ofMinutes(1), 10));
        assertThat(ids).isEmpty();
    }

    /** Two seconds: long enough that the enqueued eject is still claimable when asserted on. */
    private static final Duration SHORT_HOLDING_TIMEOUT = Duration.ofSeconds(2);

    private TransferJoinListener listenerWaitingOn(CompletableFuture<LoadOutcome> load) {
        NetworkPolicy shortHold = NetworkPolicy.fromRaw(Map.of(
                NetworkPolicy.KEY_HOLDING_TIMEOUT_SECONDS,
                Long.toString(SHORT_HOLDING_TIMEOUT.toSeconds()),
                // Both budgets live inside the holding timeout (NFR-1, FR-11), so
                // they have to come down with it or ConfigValidator would refuse
                // this policy on a real node.
                NetworkPolicy.KEY_COLD_LOAD_BUDGET_SECONDS,
                "1",
                NetworkPolicy.KEY_COMMIT_TIMEOUT_SECONDS,
                "1"));
        return new TransferJoinListener(
                nodeConfig, transferRepo, id -> load, folders, executors, nodeCommands, () -> shortHold, metrics);
    }

    private WorldId routedWorldFor(PlayerMock player, String name) throws Exception {
        WorldId worldId = WorldId.random();
        onDb(() -> {
            PlayerWorld world = worldRepo.create(worldId, player.getUniqueId(), name, 12345L, 5000, Visibility.PRIVATE);
            transferRepo.route(player.getUniqueId(), worldId, "node-1", world.generation());
            return null;
        });
        return worldId;
    }

    /**
     * Reads the counter out of the Prometheus exposition rather than the registry:
     * Micrometer is shaded into the plugin jar and is not on the backend test
     * compile classpath, and the scrape is the interface a dashboard sees anyway.
     */
    private double holdingTimeoutCount() {
        String scrape = metrics.scrape();
        String prefix = "holding_timeouts_total ";
        return scrape.lines()
                .map(String::strip)
                .filter(line -> line.startsWith(prefix))
                .map(line -> Double.parseDouble(line.substring(prefix.length()).strip()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("holding_timeouts_total is missing from the scrape: " + scrape));
    }

    @Test
    @DisplayName("a join whose load never completes ejects at the holding timeout (FR-11)")
    void aJoinThatNeverCompletesEjectsAtTheHoldingTimeout_FR11() throws Exception {
        PlayerMock player = server.addPlayer();
        World lobby = player.getWorld();
        WorldId worldId = routedWorldFor(player, "StalledWorld");

        // The load that never answers — spec section 9's "player is transferred but
        // the world fails to load", in its worst shape: it does not fail either.
        CompletableFuture<LoadOutcome> stalled = new CompletableFuture<>();
        TransferJoinListener stalling = listenerWaitingOn(stalled);

        stalling.onJoin(new PlayerJoinEvent(player, Component.text("joined")));

        Component msg = awaitPlayerMessage(player);
        assertThat(msg).isNotNull();
        assertThat(PlainTextComponentSerializer.plainText().serialize(msg))
                .contains("that world took too long to load");

        List<Long> ids = awaitNodeCommands();
        assertThat(ids).hasSize(1);
        NodeCommand command = onDb(() -> nodeCommands.findById(ids.getFirst())).orElseThrow();
        assertEquals(CommandKind.EJECT_PLAYER.name(), command.command());
        EjectPayload eject = EjectPayload.parse(command.payloadJson()).orElseThrow();
        assertEquals(player.getUniqueId(), eject.playerUuid());
        assertThat(eject.reason()).contains("Holding area timeout");
        assertThat(holdingTimeoutCount()).isEqualTo(1.0d);

        // The load arriving late must not teleport a player already on their way to
        // lobby: the world is real and materialised, so an unguarded sendIn would.
        String overworldName = folders.bukkitWorldName(worldId, DimensionKind.OVERWORLD);
        WorldMock lateWorld = server.addSimpleWorld(overworldName);
        LoadedWorld loaded = new LoadedWorld(worldId, player.getUniqueId(), "StalledWorld", 12345L, 5000, 0L);
        loaded.markMaterialised(DimensionKind.OVERWORLD);
        stalled.complete(new LoadOutcome.Loaded(loaded));
        flushExecutors();

        assertThat(player.getWorld()).isEqualTo(lobby).isNotEqualTo(lateWorld);
        assertThat(holdingTimeoutCount()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("disconnecting before the world arrives stands the holding deadline down (FR-11)")
    void disconnectingBeforeTheWorldArrivesStandsTheDeadlineDown_FR11() throws Exception {
        PlayerMock player = server.addPlayer();
        var _ = routedWorldFor(player, "AbandonedWorld");

        CompletableFuture<LoadOutcome> stalled = new CompletableFuture<>();
        TransferJoinListener stalling = listenerWaitingOn(stalled);

        stalling.onJoin(new PlayerJoinEvent(player, Component.text("joined")));
        flushExecutors();
        var _ = player.disconnect();
        stalling.onQuit(new PlayerQuitEvent(player, Component.text("left"), PlayerQuitEvent.QuitReason.DISCONNECTED));

        Thread.sleep(SHORT_HOLDING_TIMEOUT.plusMillis(500).toMillis());
        flushExecutors();

        assertThat(holdingTimeoutCount()).isEqualTo(0.0d);
        List<Long> ids = onDb(() -> nodeCommands.findClaimableIds("proxy", Duration.ofMinutes(1), 10));
        assertThat(ids).isEmpty();
    }
}
