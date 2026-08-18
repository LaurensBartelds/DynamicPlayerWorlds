package nl.gzmn.playerworlds.core.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record OwnershipLogEntry(
        long id, WorldId worldId, UUID fromUuid, UUID toUuid, String reason, Instant transferredAt) {

    public OwnershipLogEntry {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(fromUuid, "fromUuid");
        Objects.requireNonNull(toUuid, "toUuid");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(transferredAt, "transferredAt");
    }
}
