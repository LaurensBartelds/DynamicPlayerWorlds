package nl.gzmn.playerworlds.core.model;

import java.util.Objects;

/**
 * What a member may do inside a world (FR-9).
 *
 * <p>All three ship in v1 rather than VISITOR arriving with public worlds,
 * because FR-9 is explicit that public worlds depend on VISITOR being a real
 * role rather than a placeholder — and a placeholder role is discovered to be
 * one at the moment strangers first walk in.
 *
 * <p>The capabilities are methods rather than a table in a listener, so the
 * answer to "may this player break a block" has one implementation that both
 * plugins and every test read.
 *
 * <p>Note that this enum is <em>not</em> the authority on who owns a world.
 * {@code player_world.owner_uuid} is, and the {@code OWNER} value here is a
 * denormalised convenience that loses every disagreement (FR-31a).
 */
public enum Role {

    /** Full control. Authoritative ownership is {@code player_world.owner_uuid} (FR-31a). */
    OWNER,

    /** Build and break, and open containers. */
    BUILDER,

    /**
     * Interact only. No block placement or breaking, and no container access
     * unless the owner has enabled it for visitors (FR-9, FR-9e).
     */
    VISITOR;

    /** Whether this role may place and break blocks (FR-9). */
    public boolean canBuild() {
        return this != VISITOR;
    }

    /**
     * Whether this role may open containers.
     *
     * @param visitorsMayOpenContainers the per-world FR-9e setting, which only
     *     affects visitors; builders and owners are never gated on it
     */
    public boolean canOpenContainers(boolean visitorsMayOpenContainers) {
        return this != VISITOR || visitorsMayOpenContainers;
    }

    /**
     * Whether this role may change membership and world settings.
     *
     * <p>Only the owner, and callers should be checking {@code owner_uuid}
     * directly wherever they can (FR-31a). This exists for the paths that have
     * already resolved a role and would otherwise re-read the world.
     */
    public boolean canManage() {
        return this == OWNER;
    }

    /** The value as it is stored in {@code player_world_member.role}. */
    public String wire() {
        return name();
    }

    public static Role fromWire(String value) {
        Objects.requireNonNull(value, "value");
        for (Role role : values()) {
            if (role.name().equals(value)) {
                return role;
            }
        }
        throw new IllegalArgumentException("unknown player_world_member.role: " + value);
    }
}
