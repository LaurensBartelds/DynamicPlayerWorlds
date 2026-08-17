package nl.gzmn.playerworlds.core.control;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Payload for {@link CommandKind#EJECT_PLAYER} and {@link CommandKind#KICK_MEMBER}.
 *
 * @param playerUuid target player to eject or route
 * @param reason optional user-facing explanation
 */
public record EjectPayload(UUID playerUuid, @Nullable String reason) {

    public EjectPayload {
        Objects.requireNonNull(playerUuid, "playerUuid");
    }

    /**
     * Formats an eject payload as a compact JSON string.
     *
     * @param playerUuid target player to eject or route
     * @param reason optional user-facing explanation
     * @return JSON string representing the payload
     */
    public static String format(UUID playerUuid, @Nullable String reason) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        if (reason == null || reason.isBlank()) {
            return "{\"playerUuid\":\"" + playerUuid + "\"}";
        }
        String sanitized = reason.replace("\"", "\\\"").replace("\n", " ");
        return "{\"playerUuid\":\"" + playerUuid + "\",\"reason\":\"" + sanitized + "\"}";
    }

    /**
     * Parses an eject payload from JSON text.
     *
     * @param json JSON string to parse
     * @return parsed payload, or empty if malformed or missing required fields
     */
    public static Optional<EjectPayload> parse(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        String trimmed = json.strip();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return Optional.empty();
        }
        int uuidKeyIdx = trimmed.indexOf("\"playerUuid\"");
        if (uuidKeyIdx == -1) {
            return Optional.empty();
        }
        int colonIdx = trimmed.indexOf(':', uuidKeyIdx);
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
        String uuidStr = trimmed.substring(firstQuote + 1, secondQuote).strip();
        UUID playerUuid;
        try {
            playerUuid = UUID.fromString(uuidStr);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }

        String reason = null;
        int reasonKeyIdx = trimmed.indexOf("\"reason\"");
        if (reasonKeyIdx != -1) {
            int reasonColon = trimmed.indexOf(':', reasonKeyIdx);
            if (reasonColon != -1) {
                int rFirstQuote = trimmed.indexOf('"', reasonColon);
                if (rFirstQuote != -1) {
                    int rSecondQuote = -1;
                    boolean escaped = false;
                    for (int i = rFirstQuote + 1; i < trimmed.length(); i++) {
                        char c = trimmed.charAt(i);
                        if (c == '\\' && !escaped) {
                            escaped = true;
                        } else if (c == '"' && !escaped) {
                            rSecondQuote = i;
                            break;
                        } else {
                            escaped = false;
                        }
                    }
                    if (rSecondQuote != -1) {
                        reason = trimmed.substring(rFirstQuote + 1, rSecondQuote)
                                .replace("\\\"", "\"")
                                .replace("\\\\", "\\");
                    }
                }
            }
        }
        return Optional.of(new EjectPayload(playerUuid, reason));
    }
}
