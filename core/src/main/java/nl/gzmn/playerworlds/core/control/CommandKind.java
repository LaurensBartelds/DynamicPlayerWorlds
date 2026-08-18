package nl.gzmn.playerworlds.core.control;

import java.util.Optional;

/**
 * The v1 control-plane command set (CP-6).
 *
 * <p>Stored as text on {@code node_command.command}. A node that does not
 * recognise a kind completes the row with an error rather than leaving it to
 * retry forever, so an older build in a mixed pool (spec §12.9) degrades
 * visibly instead of stalling the queue.
 */
public enum CommandKind {
    EJECT_PLAYER,
    KICK_MEMBER,
    APPLY_SETTINGS,
    UNLOAD_WORLD,
    MIGRATE_WORLD,
    DRAIN_NODE,
    INVALIDATE_CACHE,
    ARCHIVE_WORLD,
    RESTORE_WORLD;

    /**
     * Parses a stored command name. Empty when the string is not a known kind —
     * the dispatcher treats that as {@code UNKNOWN_COMMAND}, not as a retry.
     */
    public static Optional<CommandKind> parse(String name) {
        if (name.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(CommandKind.valueOf(name.strip()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
