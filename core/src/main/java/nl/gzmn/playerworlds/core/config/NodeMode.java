package nl.gzmn.playerworlds.core.config;

import java.util.Objects;

/**
 * Operating mode of a backend node.
 *
 * <ul>
 *   <li>{@link #WORLDS}: Full worlds node hosting player worlds, running lifecycles, and publishing heartbeats.</li>
 *   <li>{@link #GUI_ONLY}: GUI-only node (e.g. lobby servers) providing database access and menu infrastructure without hosting worlds or publishing heartbeats.</li>
 * </ul>
 */
public enum NodeMode {
    WORLDS("worlds"),
    GUI_ONLY("gui-only");

    private final String configValue;

    NodeMode(String configValue) {
        this.configValue = configValue;
    }

    /** The lowercase configuration identifier (e.g. {@code "worlds"}, {@code "gui-only"}). */
    public String configValue() {
        return configValue;
    }

    /** Stable token matching the configuration value. */
    public String wire() {
        return configValue;
    }

    /**
     * Parses a string configuration value into a {@link NodeMode}.
     *
     * @param value raw configuration string
     * @return the matching {@link NodeMode}
     * @throws ConfigException if {@code value} is unknown
     */
    public static NodeMode fromConfig(String value) {
        Objects.requireNonNull(value, "value");
        for (NodeMode mode : values()) {
            if (mode.configValue.equalsIgnoreCase(value.trim())) {
                return mode;
            }
        }
        throw new ConfigException("unknown node.mode: " + value + " (expected 'worlds' or 'gui-only')");
    }
}
