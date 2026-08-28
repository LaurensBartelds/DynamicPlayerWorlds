package nl.gzmn.playerworlds.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import nl.gzmn.playerworlds.backend.platform.Platform;
import nl.gzmn.playerworlds.backend.platform.ServerIdentity;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.config.StorageClientSettings;
import nl.gzmn.playerworlds.core.db.DatabaseSettings;
import nl.gzmn.playerworlds.testing.TestDatabase;
import nl.gzmn.playerworlds.testing.TestObjectStore;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
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
    @DisplayName("every Listener in the backend is registered at enable (R1)")
    void everyListenerInTheBackendIsRegisteredAtEnable() throws Exception {
        // The guard, not the instance. CommandGuardListener implemented FR-21 and
        // FR-22 completely, passed its own unit suite, and was never constructed —
        // so /list and /tell leaked presence between two worlds on one node for
        // three milestones. An unregistered listener is invisible to every other
        // kind of test by construction, which is what makes this assertion worth
        // more than the one-line registration it protects.
        TestDatabase.openFresh().close();
        GzmnWorldsPlugin plugin = MockBukkit.loadWithConfig(SmokePlugin.class, config(TestDatabase.settings()));
        assertThat(plugin.isEnabled()).isTrue();

        Set<String> registered = HandlerList.getRegisteredListeners(plugin).stream()
                .map(listener -> listener.getListener().getClass().getName())
                .collect(Collectors.toSet());

        JavaClasses backend = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("nl.gzmn.playerworlds.backend");

        Set<String> declared = backend.stream()
                .filter(candidate -> candidate.isAssignableTo(Listener.class))
                .filter(candidate -> !candidate.getModifiers().contains(JavaModifier.ABSTRACT))
                .filter(JavaClass::isTopLevelClass)
                .map(JavaClass::getFullName)
                .collect(Collectors.toSet());

        assertThat(declared)
                .as("sanity: the importer found the listener classes at all")
                .isNotEmpty();
        assertThat(registered)
                .as("every Listener in :backend must be registered by onEnable; "
                        + "an unregistered one is dead code that still passes its own tests")
                .containsAll(declared);
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
    @DisplayName("enable opens object storage early and the capability probe checks it (plan 00 section 10.4)")
    void enableChecksObjectStorageWhenConfigured() throws Exception {
        TestDatabase.openFresh().close();
        StorageClientSettings storage = TestObjectStore.settingsForNewBucket();

        FileConfiguration config = config(TestDatabase.settings());
        withObjectStorage(config, storage);

        GzmnWorldsPlugin plugin = MockBukkit.loadWithConfig(SmokePlugin.class, config);

        assertThat(plugin.isEnabled())
                .as("a reachable, configured bucket must not block enable")
                .isTrue();
        assertThat(plugin.objectStore())
                .as("the store opened ahead of the probe is the one startWorldLifecycle keeps, not a second one")
                .isNotNull();
    }

    @Test
    @DisplayName("a node pointed at a bucket that does not exist refuses to enable (plan 00 section 10.4)")
    void unreachableObjectStorageRefusesEnable() throws Exception {
        TestDatabase.openFresh().close();
        // Never created by TestObjectStore.settingsForNewBucket(), so every call
        // against it fails exactly like a real misconfigured bucket or credential.
        StorageClientSettings storage = TestObjectStore.settings("gzmn-smoke-test-no-such-bucket");

        FileConfiguration config = config(TestDatabase.settings());
        withObjectStorage(config, storage);

        GzmnWorldsPlugin plugin = MockBukkit.loadWithConfig(SmokePlugin.class, config);

        assertThat(plugin.isEnabled())
                .as("object storage configured but unreachable must refuse enable, the same as an unreachable database")
                .isFalse();
        assertThat(plugin.objectStore())
                .as("the early-opened store must not leak past a refused enable")
                .isNull();
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

    /** Points {@code config} at {@code storage}, mirroring what {@code storage.s3.*} means in config.yml. */
    private static void withObjectStorage(FileConfiguration config, StorageClientSettings storage) {
        config.set("storage.s3.enabled", true);
        config.set("storage.s3.endpoint", storage.endpoint().toString());
        config.set("storage.s3.region", storage.region());
        config.set("storage.s3.access-key", storage.accessKey());
        config.set("storage.s3.secret-key", storage.secretKey());
        config.set("storage.s3.bucket", storage.bucket());
        config.set("storage.s3.path-style-access", storage.pathStyleAccess());
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
