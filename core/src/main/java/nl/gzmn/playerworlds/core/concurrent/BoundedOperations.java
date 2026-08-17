package nl.gzmn.playerworlds.core.concurrent;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs work under a hard deadline.
 *
 * <p>A 24/7 process cannot afford an unbounded wait (plan section 9): cold load,
 * commit and the holding area each have a budget. Cancelling the future on
 * timeout is best-effort — the task must cooperate — but the caller always
 * returns within the budget rather than hanging the join path.
 */
public final class BoundedOperations {

    private BoundedOperations() {}

    /**
     * Submits {@code work} to {@code executor} and waits up to {@code timeout}.
     *
     * @throws TimeoutException if the budget is exhausted; the task is cancelled
     * @throws ExecutionException if the task failed
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public static <T> T call(ExecutorService executor, Duration timeout, Callable<T> work)
            throws TimeoutException, ExecutionException, InterruptedException {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(work, "work");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive, was: " + timeout);
        }

        Future<T> future = executor.submit(work);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        }
    }

    /**
     * Like {@link #call} for a {@link Runnable}.
     *
     * @throws TimeoutException if the budget is exhausted; the task is cancelled
     * @throws ExecutionException if the task failed
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public static void run(ExecutorService executor, Duration timeout, Runnable work)
            throws TimeoutException, ExecutionException, InterruptedException {
        call(executor, timeout, () -> {
            work.run();
            return null;
        });
    }
}
