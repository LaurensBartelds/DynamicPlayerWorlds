package nl.gzmn.playerworlds.backend.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import nl.gzmn.playerworlds.backend.platform.PortalRouting.PortalRequest;
import nl.gzmn.playerworlds.backend.platform.PortalRouting.PortalTarget;
import nl.gzmn.playerworlds.backend.platform.PortalRouting.PortalType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** FR-3a destination math, independent of portal event surfaces. */
class DefaultPortalRoutingTest {

    private final PortalRouting routing = DefaultPortalRouting.INSTANCE;
    private final WorldLayout layout = DefaultWorldLayout.INSTANCE;

    @Test
    @DisplayName("overworld nether portal scales coordinates by 1/8 into this world's nether (FR-3a)")
    void overworldToNether_FR3a() {
        PortalTarget target = routing.resolve(
                new PortalRequest("pw_abc", DimensionKind.OVERWORLD, PortalType.NETHER, 800, 64, 1600, 8), layout);

        assertThat(target.bukkitWorldName()).isEqualTo("pw_abc_nether");
        assertThat(target.dimension()).isEqualTo(DimensionKind.NETHER);
        assertThat(target.x()).isCloseTo(100.0, within(1e-9));
        assertThat(target.y()).isEqualTo(64.0);
        assertThat(target.z()).isCloseTo(200.0, within(1e-9));
    }

    @Test
    @DisplayName("nether portal back scales coordinates by 8 into this world's overworld (FR-3a)")
    void netherToOverworld_FR3a() {
        PortalTarget target = routing.resolve(
                new PortalRequest("pw_abc", DimensionKind.NETHER, PortalType.NETHER, 100, 64, 200, 8), layout);

        assertThat(target.bukkitWorldName()).isEqualTo("pw_abc");
        assertThat(target.dimension()).isEqualTo(DimensionKind.OVERWORLD);
        assertThat(target.x()).isCloseTo(800.0, within(1e-9));
        assertThat(target.z()).isCloseTo(1600.0, within(1e-9));
    }

    @Test
    @DisplayName("end portal from the overworld targets this world's end (FR-3a)")
    void overworldToEnd_FR3a() {
        PortalTarget target = routing.resolve(
                new PortalRequest("pw_abc", DimensionKind.OVERWORLD, PortalType.END, 0, 64, 0, 8), layout);

        assertThat(target.bukkitWorldName()).isEqualTo("pw_abc_the_end");
        assertThat(target.dimension()).isEqualTo(DimensionKind.END);
    }

    @Test
    @DisplayName("return end portal targets this world's overworld (FR-3a)")
    void endToOverworld_FR3a() {
        PortalTarget target = routing.resolve(
                new PortalRequest("pw_abc", DimensionKind.END, PortalType.END, 100, 80, 100, 8), layout);

        assertThat(target.bukkitWorldName()).isEqualTo("pw_abc");
        assertThat(target.dimension()).isEqualTo(DimensionKind.OVERWORLD);
        assertThat(target.x()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("end gateway stays inside this world's end")
    void endGatewayStaysInEnd() {
        PortalTarget target = routing.resolve(
                new PortalRequest("pw_abc", DimensionKind.END, PortalType.END_GATEWAY, 50, 70, 50, 8), layout);

        assertThat(target.bukkitWorldName()).isEqualTo("pw_abc_the_end");
        assertThat(target.dimension()).isEqualTo(DimensionKind.END);
    }

    @Test
    @DisplayName("nether portal is rejected in the end")
    void netherPortalRejectedInEnd() {
        assertThatThrownBy(() -> routing.resolve(
                        new PortalRequest("pw_abc", DimensionKind.END, PortalType.NETHER, 0, 64, 0, 8), layout))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nether portal");
    }
}
