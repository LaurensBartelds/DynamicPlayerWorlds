package nl.gzmn.playerworlds.core.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One {@code player_world_member} row (FR-8, FR-9).
 *
 * @param worldId the world this membership is in
 * @param uuid the member
 * @param role FR-9 role; {@code OWNER} here is denormalised and
 *     {@code player_world.owner_uuid} wins any disagreement (FR-31a)
 * @param invitedBy who invited them, {@code null} for the owner's own row and
 *     for a visitor who walked into a public world (FR-9c)
 * @param joinedAt database time of first entry, {@code null} until they arrive
 * @param diedAt database time of a permanent death in a hardcore world (FR-5b),
 *     {@code null} for a member who is alive and for every member of a world
 *     that is not hardcore
 */
public record WorldMember(
        WorldId worldId,
        UUID uuid,
        Role role,
        @Nullable UUID invitedBy,
        @Nullable Instant joinedAt,
        @Nullable Instant diedAt) {

    public WorldMember {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(role, "role");
    }

    public WorldMember(WorldId worldId, UUID uuid, Role role, @Nullable UUID invitedBy, @Nullable Instant joinedAt) {
        this(worldId, uuid, role, invitedBy, joinedAt, null);
    }

    /**
     * Whether this member has died permanently under FR-5b.
     *
     * <p>Only meaningful for a member of a hardcore world (FR-1b): the column is
     * never written for any other kind, and a caller that has not checked
     * {@code PlayerWorld#hardcore()} is asking the wrong question rather than
     * getting a wrong answer.
     */
    public boolean isDead() {
        return diedAt != null;
    }
}
