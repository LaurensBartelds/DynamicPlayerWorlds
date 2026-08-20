package nl.gzmn.playerworlds.core.control;

import java.util.Objects;
import java.util.Optional;
import nl.gzmn.playerworlds.core.model.WorldId;

/**
 * Names a world in the payload rather than in {@code node_command.world_id}, for
 * the one case where the column cannot hold it.
 *
 * <p>{@code node_command.world_id} references {@code player_world(id)} with
 * {@code ON DELETE CASCADE}, which is right for every command about a world that
 * still exists: a world that goes takes its queued instructions with it.
 *
 * <p>FR-27's removal of a world stuck in CREATING is the exception. The row is
 * deleted outright — it is the only thing that exists, and leaving it would
 * consume the owner's cap forever — but a node may still have materialised its
 * folders, and something has to tell that node to drop them. Filling
 * {@code world_id} in makes the cascade delete the instruction in the same
 * breath as the world; leaving it null and naming the world here does not.
 *
 * <p>CP-4's generation check reads the column too, and skips when it is null.
 * That is also right here: there is no row left to compare a generation against.
 *
 * @param worldId the world the command is about
 */
public record WorldPayload(WorldId worldId) {

    public WorldPayload {
        Objects.requireNonNull(worldId, "worldId");
    }

    /** Formats as a compact JSON object. */
    public static String format(WorldId worldId) {
        Objects.requireNonNull(worldId, "worldId");
        return "{\"worldId\":\"" + worldId.value() + "\"}";
    }

    /** Parses one, or empty when the text is not one. */
    public static Optional<WorldPayload> parse(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        String trimmed = json.strip();
        int keyIndex = trimmed.indexOf("\"worldId\"");
        if (keyIndex < 0) {
            return Optional.empty();
        }
        int colon = trimmed.indexOf(':', keyIndex);
        if (colon < 0) {
            return Optional.empty();
        }
        int open = trimmed.indexOf('"', colon);
        if (open < 0) {
            return Optional.empty();
        }
        int close = trimmed.indexOf('"', open + 1);
        if (close < 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(new WorldPayload(
                    WorldId.parse(trimmed.substring(open + 1, close).strip())));
        } catch (IllegalArgumentException e) {
            // Refused rather than defaulted: a malformed id would name no world,
            // and CP-6 makes an unreadable payload visible instead of guessed.
            return Optional.empty();
        }
    }
}
