package nl.gzmn.playerworlds.backend.world;

import static org.assertj.core.api.Assertions.assertThat;

import nl.gzmn.playerworlds.backend.platform.PortalRouting;
import org.bukkit.PortalType;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The cause-to-portal-type mapping (FR-3a).
 *
 * <p>Enums on both sides, so this needs no server. The destination maths it feeds
 * is covered by {@code DefaultPortalRoutingTest}; what is tested here is that the
 * right kind of transit is recognised at all — a miss means the event falls
 * through to Bukkit's default search, which resolves against the server's primary
 * world and drops the player in the wrong one.
 */
class PortalListenerTest {

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
}
