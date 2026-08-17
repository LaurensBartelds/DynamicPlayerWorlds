package nl.gzmn.playerworlds.backend.world;

import java.util.Objects;
import nl.gzmn.playerworlds.core.model.PlayerWorld;

/**
 * What {@code /world create} did (FR-1, FR-2, FR-4).
 *
 * <p>A result type rather than exceptions, because every refusal here is an
 * expected outcome with a message for the player — the cap is reached, the name
 * is taken, the node is full. Reserving exceptions for genuine failures keeps
 * the ones that reach a log meaningful.
 */
public sealed interface CreateOutcome {

    /** The world exists, its overworld is loaded, and the row is {@code READY}. */
    record Created(PlayerWorld row, LoadedWorld world) implements CreateOutcome {
        public Created {
            Objects.requireNonNull(row, "row");
            Objects.requireNonNull(world, "world");
        }
    }

    /** FR-1: the owner already has {@code worlds.max-per-player} worlds. */
    record CapReached(int owned, int cap) implements CreateOutcome {}

    /** The owner already has a world of this name. */
    record NameTaken(String name) implements CreateOutcome {
        public NameTaken {
            Objects.requireNonNull(name, "name");
        }
    }

    /** FR-26: this node is already holding {@code nodes.max-worlds}. */
    record NodeFull(int loaded, int cap) implements CreateOutcome {}

    /**
     * Generation failed and the row was rolled back, so the owner's cap is intact.
     *
     * @param reason short, player-safe text; the detail is in the log
     */
    record Failed(String reason) implements CreateOutcome {
        public Failed {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
