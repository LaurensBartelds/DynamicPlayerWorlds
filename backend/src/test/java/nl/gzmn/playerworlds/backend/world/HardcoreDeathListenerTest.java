package nl.gzmn.playerworlds.backend.world;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import nl.gzmn.playerworlds.backend.platform.DefaultWorldLayout;
import nl.gzmn.playerworlds.backend.platform.DimensionKind;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.control.CommandKind;
import nl.gzmn.playerworlds.core.db.Database;
import nl.gzmn.playerworlds.core.db.MembershipRepository;
import nl.gzmn.playerworlds.core.db.NodeCommandRepository;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository;
import nl.gzmn.playerworlds.core.db.Schema;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.Role;
import nl.gzmn.playerworlds.core.model.Visibility;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.testing.TestDatabase;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerQuitEvent.QuitReason;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerRespawnEvent.RespawnReason;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * FR-5b: who a hardcore death ends the world for, and what it leaves behind.
 *
 * <p>Against a real PostgreSQL, because the whole point of the feature is the
 * row it writes: {@code died_at} is what the proxy refuses the next entry from,
 * and a mock that agrees with the code proves nothing about that.
 */
class HardcoreDeathListenerTest {

    private ServerMock server;
    private Plugin plugin;
    private Database database;
    private PluginExecutors executors;
    private WorldFolders folders;
    private WorldRegistry registry;
    private MembershipCache membershipCache;
    private MembershipRepository membership;
    private PlayerWorldRepository worlds;
    private NodeCommandRepository nodeCommands;
    private HardcoreDeathListener listener;

    private WorldMock lobby;
    private WorldMock overworld;
    private UUID owner;
    private WorldId hardcoreWorld;

    @BeforeEach
    void setUp() throws Exception {
        database = TestDatabase.openFresh();
        Schema.migrate(database);
        executors = PluginExecutors.create(2, 2, Runnable::run);
        worlds = new PlayerWorldRepository(database);
        membership = new MembershipRepository(database);
        nodeCommands = new NodeCommandRepository(database);

        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("gzmn-worlds-test");
        folders = new WorldFolders(DefaultWorldLayout.INSTANCE);
        registry = new WorldRegistry();
        membershipCache = new MembershipCache();

        owner = UUID.randomUUID();
        PlayerWorld row =
                worlds.create(WorldId.random(), owner, "hardworld", 42L, 5000, Visibility.PUBLIC, true, null, null);
        hardcoreWorld = row.id();
        registry.register(LoadedWorld.of(row));

        lobby = server.addSimpleWorld("world");
        overworld = server.addSimpleWorld(folders.bukkitWorldName(hardcoreWorld, DimensionKind.OVERWORLD));

        listener = new HardcoreDeathListener(
                plugin,
                folders,
                registry,
                membershipCache,
                membership,
                executors,
                nodeCommands,
                NetworkPolicy::defaults,
                null);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
        executors.shutdown(Duration.ofSeconds(5));
        database.close();
    }

    @Test
    @DisplayName("an owner who dies in their hardcore world is marked dead and sent home (FR-5b)")
    void ownerDeathIsRecordedAndEjected_FR5b() throws Exception {
        PlayerMock player = addPlayerInWorld(owner, "Alice", Role.OWNER);

        die(player);
        respawn(player);
        server.getScheduler().performOneTick();

        assertThat(awaitDead(hardcoreWorld, owner))
                .as("the row is what the proxy refuses the next entry from")
                .isTrue();
        assertThat(player.getWorld().getName())
                .as("out of the player world before anything else on this node can happen")
                .isEqualTo(lobby.getName());
        assertThat(enqueuedKindsForProxy())
                .as("the node cannot move a player between servers; the proxy does that (CP-2)")
                .contains(CommandKind.EJECT_PLAYER.name());
        assertThat(worlds.findById(hardcoreWorld).orElseThrow().state())
                .as("FR-5b ends the world for the player who died, not for the world")
                .isNotNull();
    }

    @Test
    @DisplayName("a builder is subject to it too (FR-5b)")
    void builderDeathIsRecorded_FR5b() throws Exception {
        UUID builder = UUID.randomUUID();
        membership.invite(hardcoreWorld, builder, owner, Duration.ofMinutes(10));
        membership.acceptInvite(hardcoreWorld, builder);
        PlayerMock player = addPlayerInWorld(builder, "Bob", Role.BUILDER);

        die(player);
        respawn(player);
        server.getScheduler().performOneTick();

        assertThat(awaitDead(hardcoreWorld, builder)).isTrue();
        assertThat(player.getWorld().getName()).isEqualTo(lobby.getName());
    }

    @Test
    @DisplayName("a visitor dies and carries on; FR-5b exempts them by name")
    void visitorIsExempt_FR5b() throws Exception {
        UUID visitor = UUID.randomUUID();
        membership.addVisitorIfAbsent(hardcoreWorld, visitor);
        PlayerMock player = addPlayerInWorld(visitor, "Carol", Role.VISITOR);

        die(player);
        respawn(player);
        server.getScheduler().performOneTick();

        assertThat(deadAfterSettling(hardcoreWorld, visitor))
                .as("a stranger who walks into a public world must not be able to lock themselves out of it")
                .isFalse();
        assertThat(player.getWorld().getName()).isEqualTo(overworld.getName());
        assertThat(enqueuedKindsForProxy()).isEmpty();
    }

    @Test
    @DisplayName("a death in an ordinary world records nothing (FR-1b)")
    void ordinaryWorldIsUntouched_FR1b() throws Exception {
        PlayerWorld soft =
                worlds.create(WorldId.random(), owner, "softworld", 7L, 5000, Visibility.PRIVATE, false, null, null);
        registry.register(LoadedWorld.of(soft));
        WorldMock softOverworld = server.addSimpleWorld(folders.bukkitWorldName(soft.id(), DimensionKind.OVERWORLD));
        membershipCache.put(soft.id(), owner, Map.of(owner, Role.OWNER));
        PlayerMock player = server.addPlayer("Alice");
        player.teleport(softOverworld.getSpawnLocation());

        die(player);
        respawn(player);
        server.getScheduler().performOneTick();

        assertThat(deadAfterSettling(soft.id(), player.getUniqueId())).isFalse();
        assertThat(player.getWorld().getName()).isEqualTo(softOverworld.getName());
    }

    @Test
    @DisplayName("a player who logs out on the death screen is dead but not ejected twice (FR-5b)")
    void quittingOnTheDeathScreenLeavesNothingPending_FR5b() throws Exception {
        PlayerMock player = addPlayerInWorld(owner, "Alice", Role.OWNER);

        die(player);
        Component noQuitMessage = null;
        listener.onQuit(new PlayerQuitEvent(player, noQuitMessage, QuitReason.DISCONNECTED));
        respawn(player);
        server.getScheduler().performOneTick();

        assertThat(awaitDead(hardcoreWorld, owner))
                .as("the death was recorded when it happened; the proxy refuses their next entry")
                .isTrue();
        assertThat(enqueuedKindsForProxy())
                .as("nothing was pending for the respawn, so nothing was enqueued by it")
                .isEmpty();
    }

    /**
     * Waits for the death write, which the listener dispatches to the database
     * executor rather than performing on the tick thread (NFR-2). A test that
     * asserted straight after the event would be asserting on the race.
     */
    private boolean awaitDead(WorldId worldId, UUID uuid) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (membership.isDead(worldId, uuid)) {
                return true;
            }
            Thread.sleep(25);
        }
        return membership.isDead(worldId, uuid);
    }

    /**
     * The other direction: no write is expected, so give the executor the same
     * chance to produce one before concluding it did not.
     */
    private boolean deadAfterSettling(WorldId worldId, UUID uuid) throws Exception {
        executors.db().submit(() -> null).get();
        return membership.isDead(worldId, uuid);
    }

    private PlayerMock addPlayerInWorld(UUID uuid, String name, Role role) {
        PlayerMock player = new PlayerMock(server, name, uuid);
        server.addPlayer(player);
        player.teleport(overworld.getSpawnLocation());
        membershipCache.put(hardcoreWorld, owner, Map.of(uuid, role));
        return player;
    }

    private void die(PlayerMock player) {
        Component noDeathMessage = null;
        listener.onDeath(new PlayerDeathEvent(
                player,
                DamageSource.builder(DamageType.GENERIC).build(),
                List.of(),
                0,
                0,
                0,
                0,
                noDeathMessage,
                false));
    }

    /** Respawn where the player died, which is what {@code PortalListener} arranges (FR-3a). */
    private void respawn(PlayerMock player) {
        listener.onRespawn(
                new PlayerRespawnEvent(player, overworld.getSpawnLocation(), false, false, false, RespawnReason.DEATH));
    }

    private List<String> enqueuedKindsForProxy() throws Exception {
        List<String> kinds = new java.util.ArrayList<>();
        for (Long id : nodeCommands.findClaimableIds("proxy", Duration.ofMinutes(1), 10)) {
            nodeCommands.findById(id).ifPresent(command -> kinds.add(command.command()));
        }
        return kinds;
    }
}
