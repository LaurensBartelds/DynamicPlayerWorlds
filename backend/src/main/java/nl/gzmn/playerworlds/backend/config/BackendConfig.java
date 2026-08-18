package nl.gzmn.playerworlds.backend.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import nl.gzmn.playerworlds.core.config.ConfigException;
import nl.gzmn.playerworlds.core.config.NodeConfig;
import nl.gzmn.playerworlds.core.config.NodeMode;
import nl.gzmn.playerworlds.core.config.StorageClientSettings;
import nl.gzmn.playerworlds.core.db.DatabaseSettings;
import nl.gzmn.playerworlds.core.obs.MetricsSettings;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.jspecify.annotations.Nullable;

/**
 * Reads {@code config.yml} into the typed records {@code :core} works with.
 *
 * <p>Lives in {@code :backend} because {@code :core} must never see Bukkit
 * (CONTRIBUTING.md rule 2), and Bukkit's {@code FileConfiguration} is the only
 * YAML parser on the classpath — adding a second one to a shaded plugin jar to
 * avoid an import would be the wrong trade.
 *
 * <p>Only node-local facts are here. Caps, expiries and retention live in
 * {@code network_setting} and are read through {@code NetworkPolicy}, so two
 * nodes cannot disagree about them (ADR 0007).
 */
public final class BackendConfig {

    private BackendConfig() {}

    /**
     * Builds the node configuration.
     *
     * @param config the parsed {@code config.yml}
     * @param dataFolder the plugin data folder; relative paths resolve against it
     * @param worldContainer the server's world container, which is where Bukkit
     *     materialises worlds and therefore what {@code storage.local-scratch-path}
     *     must name (plan 01 section 5.1)
     * @throws ConfigException if a required value is missing or unusable
     */
    public static NodeConfig node(FileConfiguration config, Path dataFolder, Path worldContainer) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(dataFolder, "dataFolder");
        Objects.requireNonNull(worldContainer, "worldContainer");

        String nodeId = requireString(config, "node.id");
        String address = requireString(config, "node.address");
        Duration heartbeat = Duration.ofSeconds(config.getLong("node.heartbeat-seconds", 30L));
        NodeMode mode = parseMode(config);

        DatabaseSettings database = new DatabaseSettings(
                requireString(config, "database.url"),
                requireString(config, "database.user"),
                config.getString("database.password", ""),
                config.getInt("database.pool-size", DatabaseSettings.DEFAULT_POOL_SIZE),
                Duration.ofSeconds(config.getLong(
                        "database.connection-timeout-seconds",
                        DatabaseSettings.DEFAULT_CONNECTION_TIMEOUT.toSeconds())));

        Path scratch = resolveScratch(config, worldContainer);
        Path cache = resolvePath(config.getString("storage.local-cache-path"), dataFolder, "cache");
        Path quarantine = resolvePath(config.getString("storage.quarantine-path"), dataFolder, "quarantine");
        long minFree = config.getLong("storage.min-free-space-bytes", NodeConfig.DEFAULT_MIN_FREE_SPACE_BYTES);

        return new NodeConfig(
                nodeId, address, heartbeat, database, objectStorage(config), scratch, cache, quarantine, minFree, mode);
    }

    /** Prometheus scrape endpoint, loopback by default. */
    public static MetricsSettings metrics(FileConfiguration config) {
        Objects.requireNonNull(config, "config");
        return new MetricsSettings(
                config.getString("metrics.bind", MetricsSettings.DEFAULT_BIND),
                config.getInt("metrics.port", MetricsSettings.DEFAULT_PORT));
    }

    /**
     * The live-world directory.
     *
     * <p>Blank means "wherever the server puts worlds", which is the only value
     * that is correct without the operator having to know that Bukkit ignores any
     * other. A configured value is accepted only when it resolves to the same
     * directory; anything else is refused rather than silently ignored, because a
     * node that believes its worlds are on a different volume from where they
     * actually are will size free space, quarantine and — from milestone 6 —
     * reflink snapshots against the wrong filesystem.
     */
    private static Path resolveScratch(FileConfiguration config, Path worldContainer) {
        String configured = config.getString("storage.local-scratch-path", "");
        Path container = worldContainer.toAbsolutePath().normalize();
        if (configured == null || configured.isBlank()) {
            return container;
        }
        Path candidate = Path.of(configured).toAbsolutePath().normalize();
        if (!candidate.equals(container)) {
            throw new ConfigException("storage.local-scratch-path is " + candidate
                    + " but this server materialises worlds in " + container
                    + ". Bukkit has no API to create a world outside its world container, so the two must be "
                    + "the same directory. Leave the key blank to follow the server, or point it here.");
        }
        return container;
    }

    /**
     * Object storage, or {@code null} when this node has none.
     *
     * <p>Absent is a legitimate configuration until milestone 6: a node with live
     * folders and a database has nothing to sync to yet.
     */
    private static @Nullable StorageClientSettings objectStorage(FileConfiguration config) {
        ConfigurationSection s3 = config.getConfigurationSection("storage.s3");
        if (s3 == null || !s3.getBoolean("enabled", false)) {
            return null;
        }
        String endpoint = requireString(config, "storage.s3.endpoint");
        try {
            return new StorageClientSettings(
                    new URI(endpoint),
                    s3.getString("region", StorageClientSettings.DEFAULT_REGION),
                    requireString(config, "storage.s3.access-key"),
                    requireString(config, "storage.s3.secret-key"),
                    requireString(config, "storage.s3.bucket"),
                    emptyToNull(s3.getString("archive-bucket")),
                    s3.getBoolean("path-style-access", true));
        } catch (URISyntaxException e) {
            throw new ConfigException("storage.s3.endpoint is not a valid URI: " + endpoint, e);
        }
    }

    private static Path resolvePath(@Nullable String configured, Path dataFolder, String fallback) {
        String value = configured == null || configured.isBlank() ? fallback : configured;
        Path path = Path.of(value);
        return path.isAbsolute() ? path : dataFolder.resolve(path);
    }

    private static String requireString(FileConfiguration config, String key) {
        String value = config.getString(key);
        if (value == null || value.isBlank()) {
            throw new ConfigException(key + " is required in config.yml and was not set");
        }
        return value;
    }

    private static NodeMode parseMode(FileConfiguration config) {
        String raw = config.getString("node.mode");
        if (raw == null || raw.isBlank()) {
            return NodeMode.WORLDS;
        }
        return NodeMode.fromConfig(raw);
    }

    private static @Nullable String emptyToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
