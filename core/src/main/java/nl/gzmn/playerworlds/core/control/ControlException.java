package nl.gzmn.playerworlds.core.control;

/**
 * A control-plane operation failed.
 *
 * <p>Wraps the underlying cause (typically a SQLException) so {@code core.control}
 * never depends on {@code java.sql} directly — ArchitectureTest confines JDBC to
 * {@code core.db}.
 */
public final class ControlException extends RuntimeException {

    public ControlException(String message) {
        super(message);
    }

    public ControlException(String message, Throwable cause) {
        super(message, cause);
    }
}
