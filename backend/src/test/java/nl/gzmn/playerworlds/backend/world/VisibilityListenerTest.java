package nl.gzmn.playerworlds.backend.world;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import nl.gzmn.playerworlds.backend.platform.DefaultWorldLayout;
import nl.gzmn.playerworlds.backend.platform.DimensionKind;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.bukkit.event.player.PlayerChangedWorldEvent;
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
 * FR-19's join and quit announcements, routed to the group rather than to the
 * connection.
 *
 * <p>The reported behaviour, all three symptoms of one mistake: walking into
 * somebody's world announced nothing, leaving it announced a quit, and logging
 * back in announced a join to the world you had left — because the join
 * broadcast was addressed by the world the <em>connection</em> landed in, which
 * is whatever {@code playerdata} said, not by the world the player entered.
 */
class VisibilityListenerTest {

    private ServerMock server;
    private Plugin plugin;
    private WorldFolders folders;
    private VisibilityGroups groups;
    private VisibilityListener listener;

    private WorldMock lobby;
    private WorldMock overworld;
    private WorldMock nether;
    private WorldMock otherWorld;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("gzmn-worlds-test");
        folders = new WorldFolders(DefaultWorldLayout.INSTANCE);
        groups = new VisibilityGroups(folders);
        listener = new VisibilityListener(plugin, groups);

        lobby = server.addSimpleWorld("world");
        WorldId first = WorldId.random();
        WorldId second = WorldId.random();
        overworld = server.addSimpleWorld(folders.bukkitWorldName(first, DimensionKind.OVERWORLD));
        nether = server.addSimpleWorld(folders.bukkitWorldName(first, DimensionKind.NETHER));
        otherWorld = server.addSimpleWorld(folders.bukkitWorldName(second, DimensionKind.OVERWORLD));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("walking into a world announces the arrival to the players already there (FR-19)")
    void enteringAWorldAnnouncesToThatWorld() {
        PlayerMock host = server.addPlayer("Host");
        host.teleport(overworld.getSpawnLocation());
        PlayerMock guest = server.addPlayer("Guest");
        guest.teleport(overworld.getSpawnLocation());

        listener.onChangedWorld(new PlayerChangedWorldEvent(guest, lobby));

        assertThat(translationKeys(host)).containsExactly("multiplayer.player.joined");
        assertThat(translationKeys(guest))
                .as("the player who moved has a loading screen; a line telling them they moved is noise")
                .isEmpty();
    }

    @Test
    @DisplayName("leaving a world tells the players left behind, and nobody else (FR-19)")
    void leavingAWorldAnnouncesToTheWorldLeft() {
        PlayerMock host = server.addPlayer("Host");
        host.teleport(overworld.getSpawnLocation());
        PlayerMock bystander = server.addPlayer("Bystander");
        bystander.teleport(otherWorld.getSpawnLocation());

        PlayerMock guest = server.addPlayer("Guest");
        guest.teleport(lobby.getSpawnLocation());

        listener.onChangedWorld(new PlayerChangedWorldEvent(guest, overworld));

        assertThat(translationKeys(host)).containsExactly("multiplayer.player.left");
        assertThat(translationKeys(bystander))
                .as("§5.5: presence must not cross between two worlds on one node")
                .isEmpty();
    }

    @Test
    @DisplayName("moving between two worlds tells both, in the right direction")
    void movingBetweenWorldsTellsBoth() {
        PlayerMock stayer = server.addPlayer("Stayer");
        stayer.teleport(overworld.getSpawnLocation());
        PlayerMock waiter = server.addPlayer("Waiter");
        waiter.teleport(otherWorld.getSpawnLocation());

        PlayerMock mover = server.addPlayer("Mover");
        mover.teleport(otherWorld.getSpawnLocation());

        listener.onChangedWorld(new PlayerChangedWorldEvent(mover, overworld));

        assertThat(translationKeys(stayer)).containsExactly("multiplayer.player.left");
        assertThat(translationKeys(waiter)).containsExactly("multiplayer.player.joined");
    }

    @Test
    @DisplayName("a nether portal inside one world is not an arrival (FR-2)")
    void dimensionsOfOneWorldAreNotATransition() {
        PlayerMock host = server.addPlayer("Host");
        host.teleport(overworld.getSpawnLocation());
        PlayerMock traveller = server.addPlayer("Traveller");
        traveller.teleport(nether.getSpawnLocation());

        listener.onChangedWorld(new PlayerChangedWorldEvent(traveller, overworld));

        assertThat(translationKeys(host))
                .as("FR-2 treats a world's three dimensions as one unit")
                .isEmpty();
    }

    @Test
    @DisplayName("moving between two worlds that are neither player worlds announces nothing")
    void movementOutsidePlayerWorldsIsSilent() {
        WorldMock secondLobby = server.addSimpleWorld("lobby-two");
        PlayerMock bystander = server.addPlayer("Bystander");
        bystander.teleport(lobby.getSpawnLocation());

        PlayerMock mover = server.addPlayer("Mover");
        mover.teleport(secondLobby.getSpawnLocation());

        listener.onChangedWorld(new PlayerChangedWorldEvent(mover, lobby));

        assertThat(translationKeys(bystander))
                .as("a player outside a player world is a group of one (FR-11)")
                .isEmpty();
    }

    /** The translation keys of every message this player received. */
    private static List<String> translationKeys(PlayerMock player) {
        List<String> keys = new java.util.ArrayList<>();
        Component message;
        while ((message = player.nextComponentMessage()) != null) {
            if (message instanceof TranslatableComponent translatable) {
                keys.add(translatable.key());
            }
        }
        return keys;
    }
}
