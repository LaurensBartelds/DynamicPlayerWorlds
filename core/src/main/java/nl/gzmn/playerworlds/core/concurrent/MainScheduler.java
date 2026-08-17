package nl.gzmn.playerworlds.core.concurrent;

/**
 * Runs work on the server main thread.
 *
 * <p>Implemented by the platform entry point (Paper scheduler, Velocity
 * executor). {@code :core} only needs "put this back on main"; it must not
 * depend on Bukkit or Velocity to do that.
 */
@FunctionalInterface
public interface MainScheduler {

    /**
     * Schedules {@code task} onto the main thread. May run inline when already
     * on main. Must not block the caller waiting for completion — use
     * {@link java.util.concurrent.CompletableFuture} at the call site when a
     * result is needed.
     */
    void execute(Runnable task);
}
