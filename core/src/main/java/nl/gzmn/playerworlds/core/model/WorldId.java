package nl.gzmn.playerworlds.core.model;

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
        java.util.Objects.requireNonNull(value, "value");
    }

    public static WorldId random() {
        return new WorldId(UUID.randomUUID());
    }

    public static WorldId parse(String text) {
        return new WorldId(UUID.fromString(text));
    }

    /** The live world folder name for this world's overworld (FR-2a). */
    public String folder() {
        return "pw_" + value.toString().replace("-", "");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
