package nl.gzmn.playerworlds.core.model;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A player world's identity. Wrapping the UUID rather than passing it raw keeps
 * a world id from being confused with a player uuid, which the schema uses
 * side by side in almost every table.
 *
 * <p>The folder name is derived from the id and never from the player-supplied
 * name (FR-2a): sibling dimension folders mean a world whose folder is {@code
 * foo_nether} collides with the nether of a world whose folder is {@code foo},
 * player text would reach a filesystem path, and a case-insensitive filesystem
 * collapses names the database treats as distinct.
 */
public record WorldId(UUID value) {

    public WorldId {
        Objects.requireNonNull(value, "value");
    }

    public static WorldId random() {
        return new WorldId(UUID.randomUUID());
    }

    public static WorldId parse(String text) {
        return new WorldId(UUID.fromString(text));
    }

    /** Prefix that marks a world folder as one of ours (FR-2a). */
    public static final String FOLDER_PREFIX = "pw_";

    /** A UUID with its dashes stripped. */
    private static final int UNDASHED_UUID_LENGTH = 32;

    /** The live world folder name for this world's overworld (FR-2a). */
    public String folder() {
        return FOLDER_PREFIX + value.toString().replace("-", "");
    }

    /**
     * Recovers the id from a folder name produced by {@link #folder()}.
     *
     * <p>Empty rather than throwing, because the caller's question is usually "is
     * this one of ours at all" — a node has a lobby and possibly other worlds, and
     * every one of them reaches this method through the portal and join paths.
     */
    public static Optional<WorldId> fromFolder(String folder) {
        Objects.requireNonNull(folder, "folder");
        if (!folder.startsWith(FOLDER_PREFIX)) {
            return Optional.empty();
        }
        String undashed = folder.substring(FOLDER_PREFIX.length());
        if (undashed.length() != UNDASHED_UUID_LENGTH) {
            return Optional.empty();
        }
        StringBuilder dashed = new StringBuilder(36)
                .append(undashed, 0, 8)
                .append('-')
                .append(undashed, 8, 12)
                .append('-')
                .append(undashed, 12, 16)
                .append('-')
                .append(undashed, 16, 20)
                .append('-')
                .append(undashed, 20, UNDASHED_UUID_LENGTH);
        try {
            UUID parsed = UUID.fromString(dashed.toString());
            // UUID.fromString accepts short groups and pads them, so a folder that
            // is 32 characters of nearly-hex can round-trip to a different id.
            // Comparing back is what makes this a parse rather than a guess.
            WorldId candidate = new WorldId(parsed);
            return candidate.folder().equals(folder) ? Optional.of(candidate) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
