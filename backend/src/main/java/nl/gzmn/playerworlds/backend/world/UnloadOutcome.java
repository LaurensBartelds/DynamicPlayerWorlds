package nl.gzmn.playerworlds.backend.world;

import java.util.List;
import java.util.Objects;
import nl.gzmn.playerworlds.backend.platform.DimensionKind;

/**
 * What one unload attempt did (FR-25a).
 *
 * <p>The distinction between {@link Complete} and {@link Blocked} is the whole
 * of FR-25a: a partially unloaded world has a split visibility group and must
 * not be left in that state, so a blocked attempt abandons the remaining
 * dimensions and the retry re-attempts the world as a unit.
 */
public sealed interface UnloadOutcome {

    /** All three dimensions are down. The world can be deregistered. */
    record Complete(List<DimensionKind> unloaded) implements UnloadOutcome {
        public Complete {
            unloaded = List.copyOf(unloaded);
        }
    }

    /**
     * A dimension refused to unload. The remaining ones were not attempted.
     *
     * @param dimension the one that refused
     * @param blockers what was holding it, where the API could name it
     */
    record Blocked(DimensionKind dimension, List<String> blockers) implements UnloadOutcome {
        public Blocked {
            Objects.requireNonNull(dimension, "dimension");
            blockers = List.copyOf(blockers);
        }
    }
}
