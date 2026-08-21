package nl.gzmn.playerworlds.core.menu;

import java.util.Objects;

/**
 * Result of processing a {@link MenuIntent}, sent from proxy back to backend.
 */
public sealed interface MenuResult permits MenuResult.Ok, MenuResult.Failed {

    long correlationId();

    /**
     * The outcome's already-rendered message, as Adventure's Gson component JSON.
     *
     * <p>Opaque here — {@code :core} has no Adventure dependency (it is shaded into both the
     * Paper and Velocity plugins) and never parses this text, only carries it. The proxy renders
     * an {@link nl.gzmn.playerworlds.core.config.MessageCatalog} template (NFR-5) into a styled
     * {@code Component} and serializes it with Adventure's Gson serializer before it reaches this
     * field; the backend deserializes with the same serializer and displays it as-is, without
     * re-wrapping it in a hardcoded prefix of its own.
     */
    String message();

    record Ok(long correlationId, String message) implements MenuResult {
        public Ok {
            Objects.requireNonNull(message, "message");
        }
    }

    record Failed(long correlationId, FailureCode code, String message) implements MenuResult {
        public Failed {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
        }
    }
}
