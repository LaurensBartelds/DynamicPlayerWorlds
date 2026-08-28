package nl.gzmn.playerworlds.backend.world;

import java.util.concurrent.CompletableFuture;
import nl.gzmn.playerworlds.core.model.WorldId;

/**
 * The one thing the join path needs from {@link WorldLifecycleService}: ask for a
 * world and be told, asynchronously, what happened to it.
 *
 * <p>FR-11 bounds how long a routed player may wait for that answer, and a bound
 * is only worth having if the case it exists for — a load that never answers —
 * can be reproduced. {@code WorldLifecycleService} is final and its cold path
 * goes through object storage, so the only way to stall it in a test is to stall
 * a real download. This interface is the seam that makes the deadline testable
 * without one.
 *
 * <p>It is deliberately narrow. Nothing should widen it: a caller that needs more
 * of the lifecycle should depend on {@link WorldLifecycleService} directly.
 */
@FunctionalInterface
public interface WorldLoader {

    /**
     * Loads the world onto this node if it is not already loaded (FR-11, MN-8).
     *
     * @param id the world to load
     * @return the outcome, completed off the main thread
     */
    CompletableFuture<LoadOutcome> load(WorldId id);
}
