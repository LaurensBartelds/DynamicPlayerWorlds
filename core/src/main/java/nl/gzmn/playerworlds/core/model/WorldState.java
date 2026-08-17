package nl.gzmn.playerworlds.core.model;

import java.util.Objects;

/**
 * Lifecycle state of a world, mirroring the {@code CHECK} constraint on
 * {@code player_world.state}.
 *
 * <p>The two transient states are the interesting ones. {@code CREATING} and
 * {@code ARCHIVING}/{@code RESTORING} are what a crash leaves behind, and both
 * FR-35 and FR-36 are written so that finding one is a resumable condition
 * rather than a corrupt world: nothing destructive happens before the thing
 * that replaces it has been verified.
 */
public enum WorldState {

    /**
     * A row exists but its folders may not. Created by {@code /world create}
     * before generation starts (FR-1a) and replaced by {@link #READY} once the
     * overworld is on disk. A row left here by a crash is reclaimed by the FR-40
     * maintenance sweep.
     */
    CREATING,

    /** Normal. The world can be loaded and played. */
    READY,

    /** FR-35 is part-way through packing the world to object storage. */
    ARCHIVING,

    /** FR-35 finished. Live folders are gone; the archive is authoritative. */
    ARCHIVED,

    /** FR-36 is part-way through unpacking an archive back to live folders. */
    RESTORING;

    /** The value as it is stored in {@code player_world.state}. */
    public String wire() {
        return name();
    }

    /**
     * Parses a stored value.
     *
     * @throws IllegalArgumentException if the database holds a value this build
     *     does not know, which means the schema moved ahead of the code
     */
    public static WorldState fromWire(String value) {
        Objects.requireNonNull(value, "value");
        for (WorldState state : values()) {
            if (state.name().equals(value)) {
                return state;
            }
        }
        throw new IllegalArgumentException("unknown player_world.state: " + value);
    }
}
