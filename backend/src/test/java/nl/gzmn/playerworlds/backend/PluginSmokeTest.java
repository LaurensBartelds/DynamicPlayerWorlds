package nl.gzmn.playerworlds.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import nl.gzmn.playerworlds.backend.platform.Platform;
import nl.gzmn.playerworlds.backend.platform.ServerIdentity;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.db.DatabaseSettings;
import nl.gzmn.playerworlds.testing.TestDatabase;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * Plugin-surface smoke via MockBukkit (plan 00 section 11).
 *
 * <p>Kept deliberately thin: MockBukkit tracks Minecraft versions and can lag a
 * release, which must not block an upgrade. What it proves is that the enable
 * bootstrap runs end to end in the real order — config, platform, schema
 * migration, policy, validation, executors, probe, world lifecycle — and that a
 * node which cannot reach its database refuses to enable instead of coming up
 * half-built.
 *
 * <p>Two things MockBukkit does not implement are supplied by the test subclass
 * below, which is why {@link GzmnWorldsPlugin#detectIdentity()} and
 * {@link GzmnWorldsPlugin#worldContainer()} are {@code protected}:
 * {@code UnsafeValuesMock#getDataVersion()} is hardcoded to {@code 1}, below the
 * D1 floor, and {@code ServerMock#getWorldContainer()} throws outright. Before
 * the second was overridable this test <em>skipped</em> rather than failed,
 * which is the worst of the three outcomes.
 */
class PluginSmokeTest {

    @TempDir
    static Path serverRoot;

    @BeforeEach
    void mockServer() {
        MockBukkit.mock();
    }

    @AfterEach
    void unmockServer() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("enable migrates the schema, validates config and starts the world lifecycle")
    void pluginEnablesAgainstARealDatabase() throws Exception {
        // Start from an empty database so the enable path performs the baseline
        // migration itself rather than finding one already applied.
        // Closed immediately: the plugin opens its own pool, and what this
        // establishes is that the schema starts empty so enable migrates it.
        TestDatabase.openFresh().close();

        GzmnWorldsPlugin plugin = MockBukkit.loadWithConfig(SmokePlugin.class, config(TestDatabase.settings()));

        assertThat(plugin.isEnabled()).isTrue();
        assertThat(Bukkit.getPluginManager().isPluginEnabled(plugin)).isTrue();
        assertThat(plugin.platform()).isNotNull();
        assertThat(plugin.platform().identity().dataVersion()).isEqualTo(Platform.BUILD_DATA_VERSION);
        assertThat(plugin.executors()).isNotNull();
        assertThat(plugin.metrics()).isNotNull();
        assertThat(plugin.controlPlane()).isNotNull();
        assertThat(plugin.heartbeat()).isNotNull();

        // Milestone 1: the world registry exists and starts empty, and network
        // policy came from the database rather than from the in-code defaults
        // being silently assumed.
        assertThat(plugin.registry()).isNotNull();
        assertThat(plugin.registry().size()).isZero();
        assertThat(plugin.policy()).isNotNull();
        assertThat(plugin.policy().maxWorldsPerPlayer()).isEqualTo(NetworkPolicy.DEFAULT_MAX_WORLDS_PER_PLAYER);
        assertThat(plugin.menuService()).isNotNull();
        assertThat(plugin.menuChannel()).isNotNull();
    }

    @Test
    @DisplayName("gui-only mode suppresses heartbeat and world lifecycle")
    void guiOnlyModeSuppressesHeartbeatAndLifecycle() throws Exception {
        TestDatabase.openFresh().close();

        FileConfiguration config = config(TestDatabase.settings());
        config.set("node.mode", "gui-only");

        GzmnWorldsPlugin plugin = MockBukkit.loadWithConfig(SmokePlugin.class, config);

        assertThat(plugin.isEnabled()).isTrue();
        assertThat(Bukkit.getPluginManager().isPluginEnabled(plugin)).isTrue();
        assertThat(plugin.platform()).isNotNull();
        assertThat(plugin.executors()).isNotNull();
        assertThat(plugin.policy()).isNotNull();
        assertThat(plugin.menuService()).isNotNull();
        assertThat(plugin.menuChannel()).isNotNull();
        assertThat(plugin.heartbeat()).isNull();
        assertThat(plugin.registry()).isNull();
        assertThat(plugin.controlPlane()).isNull();
        assertThat(plugin.commits()).isNull();
        assertThat(plugin.archiver()).isNull();
        assertThat(plugin.restorer()).isNull();
        assertThat(plugin.leaseCoordinator()).isNull();
        assertThat(plugin.fencingHandler()).isNull();
    }

    @Test
    @DisplayName("the descriptor declares /pworld, gated, and leaves /world to the proxy (plan 01, D8)")
    void descriptorDeclaresTheOperatorCommand() throws Exception {
        // Read the built descriptor rather than asking the mock server. MockBukkit
        // matches plugin.yml to a plugin by comparing its `main` to the loaded
        // class's qualified name, so the SmokePlugin subclass below always gets a
        // synthesised description with no commands at all — the mock can never
        // answer this question, and a test that asked it would only ever be
        // asserting the synthetic default.
        YamlConfiguration descriptor;
        try (var in = GzmnWorldsPlugin.class.getResourceAsStream("/plugin.yml")) {
            assertThat(in).as("plugin.yml on the classpath").isNotNull();
            descriptor = YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
        }

        assertThat(descriptor.getString("main")).isEqualTo(GzmnWorldsPlugin.class.getName());
        assertThat(descriptor.getConfigurationSection("commands.pworld")).isNotNull();
        assertThat(descriptor.getString("commands.pworld.permission")).isEqualTo("gzmn.worlds.dev");
        assertThat(descriptor.getConfigurationSection("permissions.gzmn.worlds.dev"))
                .as("the permission the command is gated on must itself be declared")
                .isNotNull();

        // The player-facing root belongs to the proxy (specification section 6,
        // OQ-15). Claiming it here would have to be torn back in milestone 5.
        assertThat(descriptor.getConfigurationSection("commands.world")).isNull();
    }

    @Test
    @DisplayName("a node that cannot reach its database refuses to enable rather than half-starting")
    void unreachableDatabaseRefusesEnable() throws Exception {
        FileConfiguration config = config(TestDatabase.settings());
        // Port 1 is reserved and never listening, so this is a connection failure
        // rather than a slow one.
        config.set("database.url", "jdbc:postgresql://127.0.0.1:1/nothing");
        config.set("database.connection-timeout-seconds", 1);

        GzmnWorldsPlugin plugin = MockBukkit.loadWithConfig(SmokePlugin.class, config);

        assertThat(plugin.isEnabled()).isFalse();
        assertThat(plugin.registry()).isNull();
    }

    @Test
    @DisplayName("config that violates a safety property refuses the enable (plan 00 section 8.2)")
    void invalidConfigRefusesEnable() throws Exception {
        FileConfiguration config = config(TestDatabase.settings());
        // MN-9: three missed heartbeats must still fit inside the lease. At 300s
        // against the default 180s lease, one missed beat already exceeds it.
        config.set("node.heartbeat-seconds", 300);

        GzmnWorldsPlugin plugin = MockBukkit.loadWithConfig(SmokePlugin.class, config);

        assertThat(plugin.isEnabled()).isFalse();
    }

    /** A config.yml equivalent pointed at the test database and a temp world container. */
    private static FileConfiguration config(DatabaseSettings database) throws Exception {
        Path cache = Files.createDirectories(serverRoot.resolve("cache"));
        Path quarantine = Files.createDirectories(serverRoot.resolve("quarantine"));
        Files.createDirectories(worldContainerPath());

        FileConfiguration config = new YamlConfiguration();
        config.set("node.id", "worlds-test");
        config.set("node.address", "127.0.0.1:25566");
        config.set("node.heartbeat-seconds", 30);
        config.set("database.url", database.jdbcUrl());
        config.set("database.user", database.username());
        config.set("database.password", database.password());
        config.set("database.pool-size", DatabaseSettings.MIN_POOL_SIZE);
        config.set("database.connection-timeout-seconds", 10);
        // Blank: follow the server's world container, which is the only directory
        // Bukkit will create a world in (plan 01 section 5.1).
        config.set("storage.local-scratch-path", "");
        config.set("storage.local-cache-path", cache.toString());
        config.set("storage.quarantine-path", quarantine.toString());
        // The NFR-3 floor is a production concern; a CI runner has no 20 GiB to
        // spare and the check itself is covered by ConfigValidatorTest.
        config.set("storage.min-free-space-bytes", 0);
        // No scrape socket: a bound port would make the suite order-dependent.
        config.set("metrics.port", 0);
        return config;
    }

    private static Path worldContainerPath() {
        return serverRoot.resolve("worlds");
    }

    /**
     * Test-only entry point supplying the two facts MockBukkit cannot.
     * Production always reads the live server.
     */
    public static class SmokePlugin extends GzmnWorldsPlugin {

        @Override
        protected ServerIdentity detectIdentity() {
            return new ServerIdentity("26.2", Platform.BUILD_DATA_VERSION);
        }

        @Override
        protected Path worldContainer() {
            return worldContainerPath();
        }
    }
}
