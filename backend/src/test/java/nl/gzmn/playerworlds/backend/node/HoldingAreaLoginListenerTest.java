package nl.gzmn.playerworlds.backend.node;

import static org.assertj.core.api.Assertions.assertThat;

import nl.gzmn.playerworlds.backend.platform.DefaultWorldLayout;
import nl.gzmn.playerworlds.backend.platform.DimensionKind;
import nl.gzmn.playerworlds.backend.world.HoldingArea;
import nl.gzmn.playerworlds.backend.world.WorldFolders;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.bukkit.Location;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * FR-11: a login lands in the holding area, never inside a player world.
 *
 * <p>The spawn point of a returning player comes out of their {@code playerdata},
 * so somebody who logged out inside a world is put straight back into it if it
 * happens to still be loaded. Three things downstream assume a join is followed
 * by a world change — the profile restore, the position restore and the join
 * announcement — and all three were silently skipped in that case.
 */
class HoldingAreaLoginListenerTest {

    private ServerMock server;
    private WorldFolders folders;
    private HoldingAreaLoginListener listener;
    private WorldMock lobby;
    private WorldMock overworld;
    private WorldMock nether;
    private WorldId worldId;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        folders = new WorldFolders(DefaultWorldLayout.INSTANCE);
        listener = new HoldingAreaLoginListener(folders, new HoldingArea(folders));

        // Bukkit.getWorlds() is ordered by load order, and the primary world is
        // first — which is what HoldingArea picks.
        lobby = server.addSimpleWorld("world");
        worldId = WorldId.random();
        overworld = server.addSimpleWorld(folders.bukkitWorldName(worldId, DimensionKind.OVERWORLD));
        nether = server.addSimpleWorld(folders.bukkitWorldName(worldId, DimensionKind.NETHER));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("logging back in inside a player world is redirected out of it")
    void loginInsideAPlayerWorldIsRedirected() {
        Location redirected = listener.holdingAreaFor(overworld.getSpawnLocation());

        assertThat(redirected).isNotNull();
        assertThat(redirected.getWorld()).isEqualTo(lobby);
    }

    @Test
    @DisplayName("a login into any dimension of a player world is redirected, not just the overworld")
    void everyDimensionCounts() {
        Location redirected = listener.holdingAreaFor(nether.getSpawnLocation());

        assertThat(redirected).isNotNull();
        assertThat(redirected.getWorld()).isEqualTo(lobby);
    }

    @Test
    @DisplayName("a login that is already outside every player world is left alone")
    void loginOutsideAPlayerWorldIsLeftAlone() {
        assertThat(listener.holdingAreaFor(lobby.getSpawnLocation()))
                .as("the lobby is the holding area; moving them would be a teleport for nothing")
                .isNull();
    }
}
