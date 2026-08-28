package nl.gzmn.playerworlds.core.control;

import java.util.Optional;
import nl.gzmn.playerworlds.core.model.WorldState;
import org.jspecify.annotations.Nullable;

/**
 * Payload for {@link CommandKind#DELETE_WORLD} (FR-37).
 *
 * <p>Carries the state the owner's confirmation was given against, because FR-37 now accepts two
 * of them and they mean very different things. ARCHIVED promises to destroy "the world and all
 * backup archives"; READY promises to destroy a world that has no archive behind it at all. A
 * command confirmed against one must not execute against the other — the case that matters is a
 * restore completing between the confirmation and the node claiming the command, which turns an
 * ARCHIVED world into a live READY one and which CP-4's generation check cannot see, since a
 * completed restore leaves the generation where it put it.
 *
 * @param expectedState the state the world was in when the deletion was confirmed, or null for a
 *     caller that did not check one (in which case the node applies FR-37's own state rule alone)
 */
public record DeletePayload(@Nullable WorldState expectedState) {

    /** Payload naming no expectation. */
    public static final DeletePayload EMPTY = new DeletePayload(null);

    /** Formats a delete payload as a compact JSON string. */
    public static String format(@Nullable WorldState expectedState) {
        if (expectedState == null) {
            return "{}";
        }
        return "{\"expectedState\":\"" + expectedState.wire() + "\"}";
    }

    /**
     * Parses a delete payload from JSON text.
     *
     * <p>An absent or blank payload parses to {@link #EMPTY} rather than to empty, so a
     * {@code DELETE_WORLD} enqueued by an older proxy still runs. A payload that names a state
     * this build does not know does not: that is a schema ahead of the code, and guessing which
     * state was meant is exactly the guess this record exists to avoid.
     */
    public static Optional<DeletePayload> parse(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return Optional.of(EMPTY);
        }
        String trimmed = json.strip();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return Optional.empty();
        }
        int keyIdx = trimmed.indexOf("\"expectedState\"");
        if (keyIdx == -1) {
            return Optional.of(EMPTY);
        }
        int colonIdx = trimmed.indexOf(':', keyIdx);
        if (colonIdx == -1) {
            return Optional.empty();
        }
        int firstQuote = trimmed.indexOf('"', colonIdx);
        if (firstQuote == -1) {
            return Optional.empty();
        }
        int secondQuote = trimmed.indexOf('"', firstQuote + 1);
        if (secondQuote == -1) {
            return Optional.empty();
        }
        try {
            return Optional.of(new DeletePayload(WorldState.fromWire(
                    trimmed.substring(firstQuote + 1, secondQuote).strip())));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
