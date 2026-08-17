package nl.gzmn.playerworlds.core.config;

/**
 * Configuration is invalid and the plugin must refuse to enable.
 *
 * <p>Unchecked on purpose: startup code is not in a position to recover from a
 * bad config, and forcing every caller to declare {@code throws} would only
 * encourage catching it. The enable path catches this at the top, logs the
 * message and disables — that is the whole handling.
 */
public final class ConfigException extends RuntimeException {

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
