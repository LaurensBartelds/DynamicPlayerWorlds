package nl.gzmn.playerworlds.core.menu;

import java.util.Objects;

/**
 * Result of processing a {@link MenuIntent}, sent from proxy back to backend.
 */
public sealed interface MenuResult permits MenuResult.Ok, MenuResult.Failed {

    long correlationId();

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
