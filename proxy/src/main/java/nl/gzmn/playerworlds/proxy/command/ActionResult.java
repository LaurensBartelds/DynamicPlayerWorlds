package nl.gzmn.playerworlds.proxy.command;

import java.util.Objects;
import net.kyori.adventure.text.Component;
import nl.gzmn.playerworlds.core.menu.FailureCode;

/**
 * Result of executing a domain action via {@link WorldActions}.
 *
 * <p>The failure category is a {@link FailureCode}, not a string. It used to be
 * one of thirty-three ad-hoc names that {@code MenuChannelListener} translated
 * through a twenty-five-case table, and anything the table had not heard of
 * became {@code GENERIC_ERROR} without a word — which is what happened to every
 * code added after the table was written. Both ends of that round trip are in
 * this repository, so it bought nothing and lost information.
 */
public sealed interface ActionResult {

    Component message();

    record Ok(Component message) implements ActionResult {
        public Ok {
            Objects.requireNonNull(message, "message");
        }
    }

    record Failed(FailureCode code, Component message) implements ActionResult {
        public Failed {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
        }
    }

    static ActionResult success(Component message) {
        return new Ok(message);
    }

    static ActionResult failure(FailureCode code, Component message) {
        return new Failed(code, message);
    }
}
