package nl.gzmn.playerworlds.core.model;

import java.util.Objects;

/**
 * Whether a world appears in {@code /world browse} (FR-9a).
 *
 * <p>PRIVATE is the default and the safe direction: FR-9h gates making a world
 * public behind a permission, because a public world admits strangers to a node
 * whose other tenants are private worlds protected only by the isolation logic
 * in specification section 5.5.
 */
public enum Visibility {
    PRIVATE,
    PUBLIC;

    /** The value as it is stored in {@code player_world.visibility}. */
    public String wire() {
        return name();
    }

    public static Visibility fromWire(String value) {
        Objects.requireNonNull(value, "value");
        for (Visibility visibility : values()) {
            if (visibility.name().equals(value)) {
                return visibility;
            }
        }
        throw new IllegalArgumentException("unknown player_world.visibility: " + value);
    }
}
