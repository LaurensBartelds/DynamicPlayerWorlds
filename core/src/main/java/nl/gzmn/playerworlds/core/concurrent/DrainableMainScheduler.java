package nl.gzmn.playerworlds.core.concurrent;

import java.time.Duration;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link MainScheduler} the main thread can drain itself once the platform
 * scheduler has stopped accepting work.
 *
 * <p>Paper marks a plugin disabled <em>before</em> it calls {@code onDisable},
 * and its scheduler refuses a task from a plugin it considers disabled. Every
 * asynchronous path in this plugin finishes by hopping back to the tick thread —
 * the snapshot commit's capture phase, the unload, the auto-save restore — so
 * inside {@code onDisable} none of them can complete, and anything waiting on one
 * waits out its whole budget and then reports a timeout that never had a chance
 * of not happening.
 *
 * <p>That is the reason FR-28's shutdown was written a second time in its own
 * order rather than going through the handoff every other give-up path uses: the
 * ordinary path could not run there. After {@link #beginShutdown()}, work
 * submitted from another thread queues here instead of going to the platform,
 * and the main thread runs it while it waits ({@link #awaitDraining}). The
 * ordinary path then runs during shutdown exactly as it does at any other time.
 *
 * <p>Before {@code beginShutdown} this is the plain scheduler it wraps, with the
 * same run-inline-when-already-on-main behaviour {@link MainScheduler} permits.
 */
public final class DrainableMainScheduler implements MainScheduler {

    private static final Logger log = LoggerFactory.getLogger(DrainableMainScheduler.class);

    /** How long a waiting main thread parks between drains. */
    private static final long PARK_NANOS = Duration.ofMillis(1).toNanos();

    private final BooleanSupplier onMainThread;
    private final MainScheduler platform;
    private final Queue<Runnable> queued = new ConcurrentLinkedQueue<>();

    private volatile boolean shuttingDown;

    /**
     * @param onMainThread whether the calling thread is the server main thread
     * @param platform the platform scheduler, used until {@link #beginShutdown()}
     */
    public DrainableMainScheduler(BooleanSupplier onMainThread, MainScheduler platform) {
        this.onMainThread = Objects.requireNonNull(onMainThread, "onMainThread");
        this.platform = Objects.requireNonNull(platform, "platform");
    }

    /**
     * Stops handing work to the platform scheduler; from here the main thread
     * drains it. Idempotent, and safe to call from any thread.
     */
    public void beginShutdown() {
        shuttingDown = true;
    }

    /** Whether {@link #beginShutdown()} has been called. */
    public boolean isShuttingDown() {
        return shuttingDown;
    }

    @Override
    public void execute(Runnable task) {
        Objects.requireNonNull(task, "task");
        if (onMainThread.getAsBoolean()) {
            task.run();
            return;
        }
        if (shuttingDown) {
            queued.add(task);
            return;
        }
        platform.execute(task);
    }

    /**
     * Runs everything queued, on the calling thread.
     *
     * <p>A task that throws is logged and the drain continues: during shutdown
     * the alternative is abandoning the remaining worlds' commits because one of
     * them failed.
     */
    public void drain() {
        Runnable task;
        while ((task = queued.poll()) != null) {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.error("a main-thread task failed while draining during shutdown", e);
            }
        }
    }

    /**
     * Waits for {@code future}, running on this thread any main-thread work it is
     * waiting on.
     *
     * <p>Call only from the main thread, and only after {@link #beginShutdown()} —
     * before it, queued work still goes to the platform and there is nothing here
     * to drain.
     *
     * @return the future's value
     * @throws TimeoutException if the budget runs out first; the future is left
     *     alone rather than cancelled, because the work it represents is still
     *     running and cancelling it would not stop it
     */
    public <T> T awaitDraining(CompletableFuture<T> future, Duration budget)
            throws InterruptedException, ExecutionException, TimeoutException {
        Objects.requireNonNull(future, "future");
        Objects.requireNonNull(budget, "budget");

        long deadline = System.nanoTime() + budget.toNanos();
        while (!future.isDone()) {
            drain();
            if (future.isDone()) {
                break;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                throw new TimeoutException("main-thread work did not complete within " + budget);
            }
            LockSupport.parkNanos(Math.min(remaining, PARK_NANOS));
            if (Thread.interrupted()) {
                throw new InterruptedException("interrupted while draining main-thread work");
            }
        }
        // Trailing work the completion itself scheduled — restoring auto-save
        // after a commit is the one that matters — would otherwise sit in the
        // queue until the process exits.
        drain();
        return future.get(0, TimeUnit.NANOSECONDS);
    }
}
