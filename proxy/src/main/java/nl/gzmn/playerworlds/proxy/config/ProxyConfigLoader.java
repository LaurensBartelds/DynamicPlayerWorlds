package nl.gzmn.playerworlds.proxy.config;

import com.moandjiezana.toml.Toml;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import nl.gzmn.playerworlds.core.config.ConfigException;
import nl.gzmn.playerworlds.core.config.ProxyConfig;
import nl.gzmn.playerworlds.core.db.DatabaseSettings;
import org.jspecify.annotations.Nullable;

/**
 * Reads the proxy's {@code config.toml} (specification section 7).
 *
 * <p>TOML rather than YAML because that is what Velocity itself uses, so an
 * operator configuring a proxy meets one dialect rather than two.
 *
 * <p>Node-local facts only. Caps and expiries — {@code worlds.max-per-player} at
 * {@code /world create}, {@code invites.expiry-minutes} at {@code /world invite}
 * — come from {@code network_setting} through {@code NetworkPolicy}, so the
 * proxy and every node read one value and cannot disagree (ADR 0007). That is
 * why {@code transfers.expiry-seconds}, which section 7 listed here, is absent.
 */
public final class ProxyConfigLoader {

    public static final String FILE_NAME = "config.toml";

    private ProxyConfigLoader() {}

    /**
     * Loads the config, writing the bundled default first if none exists.
     *
     * @param dataDirectory the plugin's Velocity data directory
     * @throws ConfigException if the file cannot be read or a required value is missing
     */
    public static ProxyConfig load(Path dataDirectory) {
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        Path file = dataDirectory.resolve(FILE_NAME);
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(dataDirectory);
                writeDefault(file);
            }
            return parse(new Toml().read(file.toFile()));
        } catch (IOException e) {
            throw new ConfigException("could not read " + file + ": " + e.getMessage(), e);
        } catch (IllegalStateException e) {
            // toml4j reports a malformed document this way. Name the file, because
            // the message alone rarely says which one.
            throw new ConfigException(file + " is not valid TOML: " + e.getMessage(), e);
        }
    }

    /** Parses an already-read document. Separated so tests need no filesystem. */
    public static ProxyConfig parse(Toml toml) {
        Objects.requireNonNull(toml, "toml");
        DatabaseSettings database = new DatabaseSettings(
                require(toml, "database.url"),
                require(toml, "database.user"),
                toml.getString("database.password", ""),
                Math.toIntExact(toml.getLong("database.pool-size", (long) DatabaseSettings.DEFAULT_POOL_SIZE)),
                Duration.ofSeconds(toml.getLong(
                        "database.connection-timeout-seconds",
                        DatabaseSettings.DEFAULT_CONNECTION_TIMEOUT.toSeconds())));
        return new ProxyConfig(database, require(toml, "lobby-server"));
    }

    private static String require(Toml toml, String key) {
        String value = toml.getString(key);
        if (value == null || value.isBlank()) {
            throw new ConfigException(key + " is required in " + FILE_NAME + " and was not set");
        }
        return value;
    }

    private static void writeDefault(Path file) throws IOException {
        try (InputStream bundled = ProxyConfigLoader.class.getResourceAsStream("/" + FILE_NAME)) {
            if (bundled == null) {
                throw new IOException("the plugin jar does not carry a default " + FILE_NAME);
            }
            Files.write(file, bundled.readAllBytes());
        }
    }

    /** The bundled default, for tests that want to assert it parses. */
    public static @Nullable String bundledDefault() throws IOException {
        try (InputStream bundled = ProxyConfigLoader.class.getResourceAsStream("/" + FILE_NAME)) {
            return bundled == null ? null : new String(bundled.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
