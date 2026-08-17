package nl.gzmn.playerworlds.backend.world;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.UUID;
import nl.gzmn.playerworlds.backend.platform.DimensionKind;
import nl.gzmn.playerworlds.backend.platform.Platform;
import nl.gzmn.playerworlds.backend.platform.PortalRouting;
import nl.gzmn.playerworlds.backend.platform.ServerIdentity;
import nl.gzmn.playerworlds.core.concurrent.MainThread;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.db.Database;
import nl.gzmn.playerworlds.core.db.MembershipRepository;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.obs.WorldsMetrics;
import nl.gzmn.playerworlds.testing.TestDatabase;
import org.bukkit.Location;
import org.bukkit.PortalType;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
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
 * The cause-to-portal-type mapping (FR-3a) and player respawn routing.
 *
 * <p>Enums on both sides, so cause mapping needs no server. The destination maths it feeds
 * is covered by {@code DefaultPortalRoutingTest}; what is tested here is that the
 * right kind of transit is recognised at all — a miss means the event falls
 * through to Bukkit's default search, which resolves against the server's primary
 * world and drops the player in the wrong one.
 */
class PortalListenerTest {

    @TempDir
    Path tempDir;

    private ServerMock server;
    private Database database;
    private PluginExecutors executors;
    private WorldsMetrics metrics;
    private PortalListener listener;
    private WorldFolders folders;
    private WorldRegistry registry;
    private WorldMock defaultWorld;
    private WorldMock overworld;
    private WorldMock nether;
    private WorldMock end;
    private LoadedWorld loadedWorld;
    private WorldId worldId;

    @BeforeEach
    void setUp() throws Exception {
        database = TestDatabase.openFresh();
        executors = PluginExecutors.create(2, 2, Runnable::run);
        metrics = WorldsMetrics.create();

        Platform platform = Platform.create(new ServerIdentity("26.2", Platform.BUILD_DATA_VERSION));
        folders = new WorldFolders(platform.worldLayout());
        registry = new WorldRegistry();

        WorldLifecycleService lifecycle = new WorldLifecycleService(
                new PlayerWorldRepository(database),
                new MembershipRepository(database),
                new MembershipCache(),
                executors,
                platform,
                folders,
                registry,
                metrics,
                NetworkPolicy::defaults,
                tempDir);

        listener = new PortalListener(platform, folders, registry, lifecycle, NetworkPolicy::defaults);

        MainThread.enter(Thread.currentThread());
        server = MockBukkit.mock();

        defaultWorld = server.addSimpleWorld("world");

        worldId = WorldId.random();
        loadedWorld = new LoadedWorld(worldId, UUID.randomUUID(), "Test", 12345L, 5000);
        loadedWorld.markMaterialised(DimensionKind.OVERWORLD);
        loadedWorld.markMaterialised(DimensionKind.NETHER);
        loadedWorld.markMaterialised(DimensionKind.END);
        registry.register(loadedWorld);

        overworld = server.addSimpleWorld(folders.bukkitWorldName(worldId, DimensionKind.OVERWORLD));
        nether = server.addSimpleWorld(folders.bukkitWorldName(worldId, DimensionKind.NETHER));
        end = server.addSimpleWorld(folders.bukkitWorldName(worldId, DimensionKind.END));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
        MainThread.clear();
        metrics.close();
        executors.shutdown(java.time.Duration.ofSeconds(5));
        database.close();
    }

    @Test
    @DisplayName("player portal causes map to the routing seam's types")
    void playerCausesMap() {
        assertThat(PortalListener.portalTypeOf(TeleportCause.NETHER_PORTAL)).isEqualTo(PortalRouting.PortalType.NETHER);
        assertThat(PortalListener.portalTypeOf(TeleportCause.END_PORTAL)).isEqualTo(PortalRouting.PortalType.END);
        assertThat(PortalListener.portalTypeOf(TeleportCause.END_GATEWAY))
                .isEqualTo(PortalRouting.PortalType.END_GATEWAY);
    }

    @Test
    @DisplayName("teleports that are not portal transits are left alone")
    void nonPortalCausesAreIgnored() {
        // A plugin teleport or an ender pearl must not be rerouted: only the
        // three portal causes are FR-3a's subject.
        assertThat(PortalListener.portalTypeOf(TeleportCause.PLUGIN)).isNull();
        assertThat(PortalListener.portalTypeOf(TeleportCause.ENDER_PEARL)).isNull();
        assertThat(PortalListener.portalTypeOf(TeleportCause.COMMAND)).isNull();
    }

    @Test
    @DisplayName("entity portal types map to the same routing types")
    void entityPortalTypesMap() {
        assertThat(PortalListener.portalTypeOf(PortalType.NETHER)).isEqualTo(PortalRouting.PortalType.NETHER);
        assertThat(PortalListener.portalTypeOf(PortalType.ENDER)).isEqualTo(PortalRouting.PortalType.END);
        assertThat(PortalListener.portalTypeOf(PortalType.END_GATEWAY)).isEqualTo(PortalRouting.PortalType.END_GATEWAY);
    }

    @Test
    @DisplayName("a custom portal is not routed")
    void customPortalsAreIgnored() {
        assertThat(PortalListener.portalTypeOf(PortalType.CUSTOM)).isNull();
    }

    @Test
    @DisplayName("respawn in end without bed routes to world overworld spawn")
    void respawnWithoutBedInEndDimensionRoutesToOverworldSpawn() {
        PlayerMock player = server.addPlayer();
        player.teleport(end.getSpawnLocation());

        PlayerRespawnEvent event = new PlayerRespawnEvent(
                player, defaultWorld.getSpawnLocation(), false, false, false, PlayerRespawnEvent.RespawnReason.DEATH);
        listener.onPlayerRespawn(event);

        assertThat(event.getRespawnLocation()).isEqualTo(overworld.getSpawnLocation());
    }

    @Test
    @DisplayName("respawn in nether without bed or anchor routes to world overworld spawn")
    void respawnWithoutBedInNetherDimensionRoutesToOverworldSpawn() {
        PlayerMock player = server.addPlayer();
        player.teleport(nether.getSpawnLocation());

        PlayerRespawnEvent event = new PlayerRespawnEvent(
                player, defaultWorld.getSpawnLocation(), false, false, false, PlayerRespawnEvent.RespawnReason.DEATH);
        listener.onPlayerRespawn(event);

        assertThat(event.getRespawnLocation()).isEqualTo(overworld.getSpawnLocation());
    }

    @Test
    @DisplayName("respawn in overworld without bed routes to world overworld spawn")
    void respawnWithoutBedInOverworldRoutesToOverworldSpawn() {
        PlayerMock player = server.addPlayer();
        player.teleport(overworld.getSpawnLocation());

        PlayerRespawnEvent event = new PlayerRespawnEvent(
                player, defaultWorld.getSpawnLocation(), false, false, false, PlayerRespawnEvent.RespawnReason.DEATH);
        listener.onPlayerRespawn(event);

        assertThat(event.getRespawnLocation()).isEqualTo(overworld.getSpawnLocation());
    }

    @Test
    @DisplayName("respawn with bed in the same player world is preserved")
    void respawnWithBedInSameWorldIsPreserved() {
        PlayerMock player = server.addPlayer();
        player.teleport(end.getSpawnLocation());

        Location bedLocation = new Location(overworld, 10, 64, 10);
        PlayerRespawnEvent event =
                new PlayerRespawnEvent(player, bedLocation, true, false, false, PlayerRespawnEvent.RespawnReason.DEATH);
        listener.onPlayerRespawn(event);

        assertThat(event.getRespawnLocation()).isEqualTo(bedLocation);
    }

    @Test
    @DisplayName("respawn with anchor in the same player world is preserved")
    void respawnWithAnchorInSameWorldIsPreserved() {
        PlayerMock player = server.addPlayer();
        player.teleport(nether.getSpawnLocation());

        Location anchorLocation = new Location(nether, 20, 64, 20);
        PlayerRespawnEvent event = new PlayerRespawnEvent(
                player, anchorLocation, false, true, false, PlayerRespawnEvent.RespawnReason.DEATH);
        listener.onPlayerRespawn(event);

        assertThat(event.getRespawnLocation()).isEqualTo(anchorLocation);
    }

    @Test
    @DisplayName("respawn with bed in a different world is overridden to overworld spawn")
    void respawnWithBedInDifferentWorldIsOverriddenToOverworldSpawn() {
        PlayerMock player = server.addPlayer();
        player.teleport(end.getSpawnLocation());

        Location foreignBed = new Location(defaultWorld, 50, 64, 50);
        PlayerRespawnEvent event =
                new PlayerRespawnEvent(player, foreignBed, true, false, false, PlayerRespawnEvent.RespawnReason.DEATH);
        listener.onPlayerRespawn(event);

        assertThat(event.getRespawnLocation()).isEqualTo(overworld.getSpawnLocation());
    }

    @Test
    @DisplayName("respawn in a foreign non-player world is left alone")
    void respawnInForeignWorldIsIgnored() {
        PlayerMock player = server.addPlayer();
        player.teleport(defaultWorld.getSpawnLocation());

        Location defaultSpawn = defaultWorld.getSpawnLocation();
        PlayerRespawnEvent event = new PlayerRespawnEvent(
                player, defaultSpawn, false, false, false, PlayerRespawnEvent.RespawnReason.DEATH);
        listener.onPlayerRespawn(event);

        assertThat(event.getRespawnLocation()).isEqualTo(defaultSpawn);
    }
}
