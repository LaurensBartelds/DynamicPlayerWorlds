package nl.gzmn.playerworlds.backend;

import static org.assertj.core.api.Assertions.assertThat;

import nl.gzmn.playerworlds.backend.platform.Platform;
import nl.gzmn.playerworlds.backend.platform.ServerIdentity;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * Plugin-surface smoke via MockBukkit (plan section 11).
 *
 * <p>Kept deliberately thin: MockBukkit tracks Minecraft versions and can lag a
 * release, which must not block an upgrade. This test only proves the Paper
 * entry point enables on a mock server, selects a platform and opens the
 * executor topology — not gameplay behaviour.
 *
 * <p>MockBukkit's {@code UnsafeValuesMock} hardcodes {@code getDataVersion()} to
 * {@code 1}, so the smoke loads a tiny subclass that supplies the build's data
 * version through {@link GzmnWorldsPlugin#detectIdentity()}. Production still
 * always reads the live server.
 */
class PluginSmokeTest {

    @BeforeEach
    void mockServer() {
        MockBukkit.mock();
    }

    @AfterEach
    void unmockServer() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("GzmnWorldsPlugin enables on MockBukkit and exposes platform + executors")
    void pluginEnablesOnMockBukkit() {
        GzmnWorldsPlugin plugin = MockBukkit.load(SmokePlugin.class);

        assertThat(plugin.isEnabled()).isTrue();
        assertThat(plugin.platform()).isNotNull();
        assertThat(plugin.executors()).isNotNull();
        assertThat(plugin.metrics()).isNotNull();
        assertThat(Bukkit.getPluginManager().isPluginEnabled(plugin)).isTrue();
        assertThat(plugin.platform().identity().dataVersion()).isEqualTo(Platform.BUILD_DATA_VERSION);
    }

    /**
     * Test-only entry point. Supplies a data version MockBukkit itself does not
     * report, so enable can exercise the real path past the D1 gate.
     */
    public static class SmokePlugin extends GzmnWorldsPlugin {

        @Override
        protected ServerIdentity detectIdentity() {
            return new ServerIdentity("26.2", Platform.BUILD_DATA_VERSION);
        }
    }
}
