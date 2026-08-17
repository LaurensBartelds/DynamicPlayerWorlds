package nl.gzmn.playerworlds.core.obs;

import java.util.Objects;
import nl.gzmn.playerworlds.core.config.ConfigException;

/**
 * Bind address for the per-node Prometheus scrape endpoint (plan section 10.2).
 *
 * <p>Defaults to loopback so a misconfigured public bind is opt-in. Port
 * {@code 0} disables the HTTP endpoint while still allowing in-process meters
 * (tests, or a node that scrapes through another path).
 *
 * @param bindAddress interface to listen on
 * @param port TCP port; {@code 0} means "do not open a scrape socket"
 */
public record MetricsSettings(String bindAddress, int port) {

    /** Common Prometheus exporter default; well-known enough for operators. */
    public static final int DEFAULT_PORT = 9464;

    public static final String DEFAULT_BIND = "127.0.0.1";

    public MetricsSettings {
        Objects.requireNonNull(bindAddress, "bindAddress");
        if (bindAddress.isBlank()) {
            throw new ConfigException("metrics.bind-address must not be blank");
        }
        if (port < 0 || port > 65535) {
            throw new ConfigException("metrics.port must be in 0..65535, was: " + port);
        }
    }

    public static MetricsSettings defaults() {
        return new MetricsSettings(DEFAULT_BIND, DEFAULT_PORT);
    }

    /** In-process meters only; no scrape socket. */
    public static MetricsSettings disabled() {
        return new MetricsSettings(DEFAULT_BIND, 0);
    }

    public boolean endpointEnabled() {
        return port > 0;
    }
}
