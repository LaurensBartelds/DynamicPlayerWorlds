package nl.gzmn.playerworlds.core.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A {@code player_world_ban} row (FR-9d).
 *
 * @param worldId the world this ban applies to
 * @param uuid the banned player's UUID
 * @param bannedBy the UUID of the player who issued the ban
 * @param reason the optional reason for the ban
 * @param bannedAt database time the ban was created
 */
public record WorldBan(
        WorldId worldId, UUID uuid, UUID bannedBy, @Nullable String reason, Instant bannedAt) {

    public WorldBan {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(bannedBy, "bannedBy");
        Objects.requireNonNull(bannedAt, "bannedAt");
    }
}
