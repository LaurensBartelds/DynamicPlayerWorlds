package nl.gzmn.playerworlds.core.concurrent;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The executor topology from plan section 9.
 *
 * <ul>
 *   <li>{@code main} — platform main thread, via {@link MainScheduler}
 *   <li>{@code db} — fixed platform-thread pool sized to the Hikari pool
 *   <li>{@code io} — bounded to {@code storage.parallel-transfers} (NFR-7)
 *   <li>{@code sched} — single thread for lease heartbeat and commit orchestration
 * </ul>
 *
 * <p>Created once at enable and closed on disable. Callers must not create their
 * own pools for database or object-storage work (CONTRIBUTING.md rule 3).
 */
public final class PluginExecutors implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PluginExecutors.class);

    /** Default wait when a caller does not pass a shutdown budget. */
    public static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(30);

    private final Executor main;
    private final ExecutorService db;
    private final ExecutorService io;
    private final ScheduledExecutorService sched;
    private final int dbThreads;
    private final int ioThreads;

    private PluginExecutors(
            Executor main,
            ExecutorService db,
            ExecutorService io,
            ScheduledExecutorService sched,
            int dbThreads,
            int ioThreads) {
        this.main = main;
        this.db = db;
        this.io = io;
        this.sched = sched;
        this.dbThreads = dbThreads;
        this.ioThreads = ioThreads;
    }

    /**
     * Opens the pools.
     *
     * @param dbPoolSize Hikari maximum pool size; the db executor matches it so
     *     bursts queue in the executor (measured) rather than inside Hikari
     * @param ioParallelism {@code storage.parallel-transfers}
     * @param mainScheduler platform bridge onto the server main thread
     */
    public static PluginExecutors create(int dbPoolSize, int ioParallelism, MainScheduler mainScheduler) {
        Objects.requireNonNull(mainScheduler, "mainScheduler");
        if (dbPoolSize < 1) {
            throw new IllegalArgumentException("dbPoolSize must be at least 1, was: " + dbPoolSize);
        }
        if (ioParallelism < 1) {
            throw new IllegalArgumentException("ioParallelism must be at least 1, was: " + ioParallelism);
        }

        Executor main = task -> {
            Objects.requireNonNull(task, "task");
            mainScheduler.execute(task);
        };

        // Platform threads, not virtual: pgjdbc and the AWS SDK still synchronize,
        // and at this scale a bounded pool is simpler and adequate (plan §9).
        ExecutorService db = Executors.newFixedThreadPool(dbPoolSize, namedFactory("gzmn-db"));
        ExecutorService io = Executors.newFixedThreadPool(ioParallelism, namedFactory("gzmn-io"));
        ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor(namedFactory("gzmn-sched"));

        return new PluginExecutors(main, db, io, sched, dbPoolSize, ioParallelism);
    }

    /** Server main thread. Only for API calls that require it. */
    public Executor main() {
        return main;
    }

    /** Database work. Sized to the Hikari pool. */
    public ExecutorService db() {
        return db;
    }

    /** Object-storage and filesystem work (NFR-7). */
    public ExecutorService io() {
        return io;
    }

    /** Lease heartbeat and commit orchestration. Single-threaded on purpose. */
    public ScheduledExecutorService sched() {
        return sched;
    }

    public int dbThreads() {
        return dbThreads;
    }

    public int ioThreads() {
        return ioThreads;
    }

    /**
     * Ordered, timeout-bounded shutdown (FR-28's executor half).
     *
     * <ol>
     *   <li>Stop {@code sched} first so no new heartbeats or commit orchestrations start
     *   <li>Then {@code db}, so in-flight transactions can finish
     *   <li>Then {@code io}, so uploads already handed off can finish
     * </ol>
     *
     * <p>World snapshot commits (FR-28) must run <em>before</em> this method: they
     * need {@code sched}, {@code db} and {@code io} still accepting work. The
     * platform disable path owns that ordering.
     */
    public void shutdown(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive, was: " + timeout);
        }

        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        // sched first: stop planning new work before draining workers that execute it.
        shutdownPool("sched", sched, remaining(deadlineNanos));
        shutdownPool("db", db, remaining(deadlineNanos));
        shutdownPool("io", io, remaining(deadlineNanos));
    }

    @Override
    public void close() {
        shutdown(DEFAULT_SHUTDOWN_TIMEOUT);
    }

    private static Duration remaining(long deadlineNanos) {
        long left = deadlineNanos - System.nanoTime();
        if (left <= 0L) {
            return Duration.ofMillis(1);
        }
        return Duration.ofNanos(left);
    }

    private static void shutdownPool(String name, ExecutorService pool, Duration budget) {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(budget.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("executor {} did not terminate within {}; forcing shutdownNow", name, budget);
                List<Runnable> dropped = pool.shutdownNow();
                if (!dropped.isEmpty()) {
                    log.warn("executor {} dropped {} tasks on shutdownNow", name, dropped.size());
                }
                if (!pool.awaitTermination(Math.min(budget.toMillis(), 5_000L), TimeUnit.MILLISECONDS)) {
                    log.error("executor {} still not terminated after shutdownNow", name);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("interrupted while shutting down executor {}", name);
            pool.shutdownNow();
        }
    }

    private static ThreadFactory namedFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName(prefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
