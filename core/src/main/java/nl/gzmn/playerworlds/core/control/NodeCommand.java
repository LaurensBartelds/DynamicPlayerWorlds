package nl.gzmn.playerworlds.core.control;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.jspecify.annotations.Nullable;

/**
 * One row of {@code node_command} (specification section 4 / CP-2).
 *
 * <p>The durable row is the contract. Producers insert it; consumers claim it;
 * {@code NOTIFY} only shortens the wait.
 *
 * @param id primary key
 * @param targetNode addressed node or proxy identity
 * @param worldId optional world the command concerns
 * @param generation lease generation to reject if the world has moved on (CP-4);
 *     null when the command is not world-scoped
 * @param command raw kind string as stored
 * @param payloadJson JSONB contents as text (default {@code {}})
 * @param createdAt database time of insert
 * @param expiresAt after this the row is ineligible to claim
 * @param claimedAt set by the winning claimer; null while pending
 * @param completedAt set when finished; null while open
 * @param attempts how many times a claim has been taken
 * @param result completion wire value, or null while open
 */
public record NodeCommand(
        long id,
        String targetNode,
        @Nullable WorldId worldId,
        @Nullable Long generation,
        String command,
        String payloadJson,
        Instant createdAt,
        Instant expiresAt,
        @Nullable Instant claimedAt,
        @Nullable Instant completedAt,
        int attempts,
        @Nullable String result) {

    public static final String EMPTY_PAYLOAD = "{}";

    public NodeCommand {
        Objects.requireNonNull(targetNode, "targetNode");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(payloadJson, "payloadJson");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (targetNode.isBlank()) {
            throw new IllegalArgumentException("targetNode must not be blank");
        }
        if (command.isBlank()) {
            throw new IllegalArgumentException("command must not be blank");
        }
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must not be negative, was: " + attempts);
        }
    }

    /** Parsed kind when this build knows it. */
    public Optional<CommandKind> kind() {
        return CommandKind.parse(command);
    }

    public Optional<WorldId> world() {
        return Optional.ofNullable(worldId);
    }

    public Optional<Long> generationValue() {
        return Optional.ofNullable(generation);
    }

    public boolean isCompleted() {
        return completedAt != null;
    }

    public boolean isClaimed() {
        return claimedAt != null;
    }

    /** Convenience when the producer has a player uuid rather than a {@link WorldId}. */
    public static @Nullable WorldId worldIdOf(@Nullable UUID raw) {
        return raw == null ? null : new WorldId(raw);
    }
}
