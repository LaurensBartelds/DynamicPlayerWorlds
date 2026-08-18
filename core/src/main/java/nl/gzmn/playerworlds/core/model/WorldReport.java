package nl.gzmn.playerworlds.core.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A {@code player_world_report} row (FR-39).
 *
 * @param id serial primary key
 * @param worldId world in which the report was filed
 * @param reporterUuid UUID of the player filing the report
 * @param targetUuid UUID of the player being reported
 * @param reason description of the issue
 * @param chatLogJson group-scoped chat log around the report as JSON
 * @param createdAt database time when filed
 * @param handledAt database time when handled, or null if open
 * @param handledBy UUID of the staff member who handled it, or null if open
 */
public record WorldReport(
        long id,
        WorldId worldId,
        UUID reporterUuid,
        UUID targetUuid,
        String reason,
        String chatLogJson,
        Instant createdAt,
        @Nullable Instant handledAt,
        @Nullable UUID handledBy) {

    public WorldReport {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(reporterUuid, "reporterUuid");
        Objects.requireNonNull(targetUuid, "targetUuid");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(chatLogJson, "chatLogJson");
        Objects.requireNonNull(createdAt, "createdAt");
        if ((handledAt == null) != (handledBy == null)) {
            throw new IllegalArgumentException("handledAt and handledBy must either both be null or both non-null");
        }
    }
}
