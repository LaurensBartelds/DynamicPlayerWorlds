package nl.gzmn.playerworlds.proxy.command;

import java.util.Objects;
import net.kyori.adventure.text.Component;

/**
 * Result of executing a domain action via {@link WorldActions}.
 */
public sealed interface ActionResult {

    Component message();

    record Ok(Component message) implements ActionResult {
        public Ok {
            Objects.requireNonNull(message, "message");
        }
    }

    record Failed(String code, Component message) implements ActionResult {
        public Failed {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
        }
    }

    static ActionResult success(Component message) {
        return new Ok(message);
    }

    static ActionResult failure(String code, Component message) {
        return new Failed(code, message);
    }
}
