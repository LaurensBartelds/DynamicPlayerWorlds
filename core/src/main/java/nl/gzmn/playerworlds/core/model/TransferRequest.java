package nl.gzmn.playerworlds.core.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record TransferRequest(WorldId worldId, UUID toUuid, UUID fromUuid, Instant expiresAt, Instant createdAt) {

    public TransferRequest {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(toUuid, "toUuid");
        Objects.requireNonNull(fromUuid, "fromUuid");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
