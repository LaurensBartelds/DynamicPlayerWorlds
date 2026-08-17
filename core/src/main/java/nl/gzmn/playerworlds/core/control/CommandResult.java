package nl.gzmn.playerworlds.core.control;

import java.util.Objects;

/**
 * Outcome written to {@code node_command.result} when a command is completed.
 *
 * <p>The row is the audit trail operators read at three in the morning (ADR
 * 0002). Keep the strings stable and short; handlers should not dump stack
 * traces here.
 */
public final class CommandResult {

    public static final String OK = "OK";
    public static final String STALE_GENERATION = "STALE_GENERATION";
    public static final String EXPIRED = "EXPIRED";
    public static final String UNKNOWN_COMMAND_PREFIX = "UNKNOWN_COMMAND:";
    public static final String ERROR_PREFIX = "ERROR:";

    private final String wire;

    private CommandResult(String wire) {
        this.wire = Objects.requireNonNull(wire, "wire");
    }

    public static CommandResult ok() {
        return new CommandResult(OK);
    }

    /** World generation moved on between issue and claim (CP-4). */
    public static CommandResult staleGeneration() {
        return new CommandResult(STALE_GENERATION);
    }

    /** {@code expires_at} passed before the command could run. */
    public static CommandResult expired() {
        return new CommandResult(EXPIRED);
    }

    /** Kind not in this build's {@link CommandKind} set (CP-6). */
    public static CommandResult unknownCommand(String rawName) {
        Objects.requireNonNull(rawName, "rawName");
        return new CommandResult(UNKNOWN_COMMAND_PREFIX + rawName);
    }

    /** Handler failed or refused. Detail is operator-facing, not a stack trace. */
    public static CommandResult error(String detail) {
        Objects.requireNonNull(detail, "detail");
        String cleaned = detail.strip();
        if (cleaned.isEmpty()) {
            cleaned = "unspecified";
        }
        return new CommandResult(ERROR_PREFIX + cleaned);
    }

    /** Value stored in {@code node_command.result}. */
    public String wire() {
        return wire;
    }

    public boolean isOk() {
        return OK.equals(wire);
    }

    @Override
    public String toString() {
        return wire;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CommandResult that && wire.equals(that.wire);
    }

    @Override
    public int hashCode() {
        return wire.hashCode();
    }
}
