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
 */
public record WorldMember(
        WorldId worldId,
        UUID uuid,
        Role role,
        @Nullable UUID invitedBy,
        @Nullable Instant joinedAt) {

    public WorldMember {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(role, "role");
    }
}
