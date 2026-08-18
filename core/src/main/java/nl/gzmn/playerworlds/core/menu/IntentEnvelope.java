package nl.gzmn.playerworlds.core.menu;

import java.util.Objects;

/**
 * Envelope combining a unique correlation id with a {@link MenuIntent}.
 *
 * @param correlationId unique correlation id for request/response tracking and timeouts
 * @param intent the menu intent payload
 */
public record IntentEnvelope(long correlationId, MenuIntent intent) {

    public IntentEnvelope {
        Objects.requireNonNull(intent, "intent");
    }
}
