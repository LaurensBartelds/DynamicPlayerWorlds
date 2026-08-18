package nl.gzmn.playerworlds.core.control;

import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Payload for {@link CommandKind#ARCHIVE_WORLD} and {@link CommandKind#RESTORE_WORLD} (FR-35, FR-36).
 *
 * <p>The owner is carried so the node can refuse an archival whose expected owner no longer
 * matches the row, and so a restore can hand the world to a different player than the one it
 * was archived under. Both are optional: the maintenance job in FR-40 archives on inactivity
 * with no owner in mind, and an ordinary restore keeps the owner it already has.
 *
 * @param ownerUuid expected owner for archival, or target owner for restore; null when neither applies
 */
public record ArchivePayload(@Nullable UUID ownerUuid) {

    /** Payload carrying no owner, for system-initiated archival and plain restores. */
    public static final ArchivePayload EMPTY = new ArchivePayload(null);

    /**
     * Formats an archive payload as a compact JSON string.
     *
     * @param ownerUuid expected or target owner, or null for none
     * @return JSON string representing the payload
     */
    public static String format(@Nullable UUID ownerUuid) {
        if (ownerUuid == null) {
            return "{}";
        }
        return "{\"ownerUuid\":\"" + ownerUuid + "\"}";
    }

    /**
     * Parses an archive payload from JSON text.
     *
     * <p>An absent, blank or ownerless payload parses to {@link #EMPTY} rather than to empty:
     * "no owner named" is a valid instruction here, distinct from "malformed".
     *
     * @param json JSON string to parse
     * @return parsed payload, or empty if the text is not a JSON object or names an unparseable UUID
     */
    public static Optional<ArchivePayload> parse(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return Optional.of(EMPTY);
        }
        String trimmed = json.strip();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return Optional.empty();
        }
        int keyIdx = trimmed.indexOf("\"ownerUuid\"");
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
            return Optional.of(new ArchivePayload(UUID.fromString(
                    trimmed.substring(firstQuote + 1, secondQuote).strip())));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
