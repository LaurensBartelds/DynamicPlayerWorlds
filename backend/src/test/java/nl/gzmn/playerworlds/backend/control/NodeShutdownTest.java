package nl.gzmn.playerworlds.backend.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import nl.gzmn.playerworlds.backend.platform.DimensionKind;
import nl.gzmn.playerworlds.backend.platform.Platform;
import nl.gzmn.playerworlds.backend.platform.ServerIdentity;
import nl.gzmn.playerworlds.backend.world.LoadedWorld;
import nl.gzmn.playerworlds.backend.world.MembershipCache;
import nl.gzmn.playerworlds.backend.world.WorldFolders;
import nl.gzmn.playerworlds.backend.world.WorldLifecycleService;
import nl.gzmn.playerworlds.backend.world.WorldRegistry;
import nl.gzmn.playerworlds.core.concurrent.DrainableMainScheduler;
import nl.gzmn.playerworlds.core.concurrent.MainThread;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.db.Database;
import nl.gzmn.playerworlds.core.db.MembershipRepository;
import nl.gzmn.playerworlds.core.db.NodeCommandRepository;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository.LeaseGrant;
import nl.gzmn.playerworlds.core.db.Schema;
import nl.gzmn.playerworlds.core.model.Visibility;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.obs.WorldsMetrics;
import nl.gzmn.playerworlds.testing.TestDatabase;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * FR-28: shutdown gives every world up in FR-25's order — commit, unload,
 * release — through the same handoff the control plane uses.
 */
class NodeShutdownTest {

    private static final String NODE_ID = "node-shutdown";

    @TempDir
    Path tempDir;

    private ServerMock server;
    private Plugin plugin;
    private Database database;
    private PluginExecutors executors;
    private WorldsMetrics metrics;
    private Platform platform;
    private WorldFolders folders;
    private WorldRegistry registry;
    private PlayerWorldRepository worlds;
    private NodeCommandRepository nodeCommands;
    private WorldLifecycleService lifecycle;
    private DrainableMainScheduler mainScheduler;

    @BeforeEach
    void setUp() throws Exception {
        database = TestDatabase.openFresh();
        Schema.migrate(database);
        MainThread.enter(Thread.currentThread());
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        server.addSimpleWorld("world");

        // The real shape of onDisable: Paper has already marked the plugin
        // disabled, so its scheduler refuses everything. Anything that hops to
        // main has to be drained by the thread running the shutdown, and this
        // delegate fails loudly if the shutdown reaches for the platform instead.
        mainScheduler = new DrainableMainScheduler(MainThread::isMain, task -> {
            throw new IllegalStateException("the platform scheduler is not available during shutdown");
        });
        executors = PluginExecutors.create(2, 2, mainScheduler);
        metrics = WorldsMetrics.create();
        platform = Platform.create(new ServerIdentity("26.2", Platform.BUILD_DATA_VERSION));
        folders = new WorldFolders(platform.worldLayout());
        registry = new WorldRegistry();
        worlds = new PlayerWorldRepository(database);
        nodeCommands = new NodeCommandRepository(database);

        lifecycle = new WorldLifecycleService(
                worlds,
                new MembershipRepository(database),
                new MembershipCache(),
                executors,
                platform,
                folders,
                registry,
                metrics,
                NetworkPolicy::defaults,
                tempDir,
                "world",
                NODE_ID,
                null,
                null,
                null,
                null);
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

    /** Registers a loaded, leased world with a Bukkit overworld behind it. */
    private LoadedWorld leasedWorld(String name) throws Exception {
        UUID owner = UUID.randomUUID();
        WorldId worldId = WorldId.random();
        LeaseGrant grant = onDb(() -> {
            var _ = worlds.create(worldId, owner, name, 42L, 5000, Visibility.PRIVATE);
            return worlds.acquireLease(worldId, NODE_ID, Platform.BUILD_DATA_VERSION, Duration.ofMinutes(3))
                    .orElseThrow();
        });

        LoadedWorld loaded = new LoadedWorld(worldId, owner, name, 42L, 5000, grant.generation());
        loaded.markMaterialised(DimensionKind.OVERWORLD);
        registry.register(loaded);
        var _ = server.addSimpleWorld(folders.bukkitWorldName(worldId, DimensionKind.OVERWORLD));
        return loaded;
    }

    private NodeShutdown shutdown() {
        WorldHandoff handoff =
                new WorldHandoff(registry, lifecycle, folders, executors, null, nodeCommands, NetworkPolicy::defaults);
        mainScheduler.beginShutdown();
        return new NodeShutdown(registry, handoff, mainScheduler);
    }

    @Test
    @DisplayName("the lease is still held while the world is coming down (FR-28, FR-25, MN-12)")
    void shutdownReleasesAfterUnload_FR28() throws Exception {
        LoadedWorld loaded = leasedWorld("ShutdownWorld");
        WorldId worldId = loaded.id();
        String overworldName = folders.bukkitWorldName(worldId, DimensionKind.OVERWORLD);
        PlayerMock inside = server.addPlayer();
        inside.teleport(server.getWorld(overworldName).getSpawnLocation());

        // The whole of R14: read who holds the lease at the instant the world is
        // being unloaded. Release before unload leaves this null, which is the
        // window in which another node can take a world this one still has open.
        AtomicReference<String> holderAtUnload = new AtomicReference<>();
        server.getPluginManager()
                .registerEvents(
                        new Listener() {
                            @EventHandler
                            public void onUnload(WorldUnloadEvent event) {
                                if (!event.getWorld().getName().equals(overworldName)) {
                                    return;
                                }
                                try {
                                    holderAtUnload.set(leaseHolder(worldId));
                                } catch (Exception e) {
                                    throw new IllegalStateException("could not read the lease during unload", e);
                                }
                            }
                        },
                        plugin);

        assertThat(leaseHolder(worldId)).isEqualTo(NODE_ID);

        shutdown().releaseAll(Duration.ofSeconds(20));

        assertThat(holderAtUnload.get()).isEqualTo(NODE_ID);
        assertThat(registry.isLoaded(worldId)).isFalse();
        assertThat(server.getWorld(overworldName)).isNull();
        assertThat(inside.getWorld().getName()).isEqualTo("world");
        assertThat(leaseHolder(worldId)).isNull();
        // afterUnload's other half, which the old shutdown path skipped by never
        // calling it: MN-15a scores a warm copy from last_played, so a node that
        // does not write it is one placement forgets ever played the world.
        assertThat(lastPlayed(worldId)).isNotNull();
    }

    @Test
    @DisplayName("a world that will not unload keeps its lease through shutdown (FR-25a, MN-12)")
    void aWorldThatWillNotUnloadKeepsItsLease_FR28() throws Exception {
        LoadedWorld loaded = leasedWorld("StuckWorld");
        WorldId worldId = loaded.id();

        // No holding world on this node, so the eject has nowhere to move them to
        // and Bukkit refuses to unload a world that still holds a player.
        WorldMock lobby = (WorldMock) server.getWorld("world");
        var _ = server.removeWorld(lobby);
        PlayerMock stuck = server.addPlayer();
        stuck.teleport(server.getWorld(folders.bukkitWorldName(worldId, DimensionKind.OVERWORLD))
                .getSpawnLocation());

        shutdown().releaseAll(Duration.ofSeconds(20));

        assertThat(leaseHolder(worldId)).isEqualTo(NODE_ID);
        assertThat(registry.isLoaded(worldId)).isTrue();
    }

    private @Nullable String leaseHolder(WorldId worldId) throws Exception {
        return onDb(() -> database.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT assigned_node FROM player_world WHERE id = ? AND lease_expires > now()")) {
                statement.setObject(1, worldId.value());
                try (var rows = statement.executeQuery()) {
                    return rows.next() ? rows.getString(1) : null;
                }
            }
        }));
    }

    private @Nullable Object lastPlayed(WorldId worldId) throws Exception {
        return onDb(() -> database.withConnection(connection -> {
            try (var statement = connection.prepareStatement("SELECT last_played FROM player_world WHERE id = ?")) {
                statement.setObject(1, worldId.value());
                try (var rows = statement.executeQuery()) {
                    return rows.next() ? rows.getObject(1) : null;
                }
            }
        }));
    }
}
