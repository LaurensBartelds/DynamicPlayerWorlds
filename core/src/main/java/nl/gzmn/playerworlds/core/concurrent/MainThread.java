package nl.gzmn.playerworlds.core.concurrent;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Identifies the server main thread and asserts work is on or off it.
 *
 * <p>Paper and Velocity each have one thread that must not block on JDBC or
 * object storage (NFR-2, NFR-7). {@code :core} has no dependency on either
 * platform, so the entry point marks that thread at enable via
 * {@link #enter(Thread)} rather than calling {@code Bukkit.isPrimaryThread()}.
 *
 * <p>Guards are always on. A "production mode that skips the check" is how
 * NFR-2 becomes a code-review note again; the cost of two thread-identity
 * comparisons is nothing next to a stalled tick.
 */
public final class MainThread {

    private static final Object LOCK = new Object();

    private static volatile @Nullable Thread mainThread;

    private MainThread() {}

    /**
     * Records {@code thread} as the server main thread. Called once from the
     * platform entry point while already running on that thread.
     */
    public static void enter(Thread thread) {
        Objects.requireNonNull(thread, "thread");
        synchronized (LOCK) {
            Thread current = mainThread;
            if (current != null && !sameThread(current, thread)) {
                throw new IllegalStateException("main thread already set to " + current.getName()
                        + "; cannot replace with " + thread.getName());
            }
            mainThread = thread;
        }
    }

    /** Drops the main-thread identity. Called from the platform disable path. */
    public static void clear() {
        synchronized (LOCK) {
            mainThread = null;
        }
    }

    /** Whether the calling thread is the marked server main thread. */
    public static boolean isMain() {
        return isCurrent(mainThread);
    }

    /**
     * Requires the calling thread to be the main thread.
     *
     * @throws WrongThreadException if it is not, or if no main thread has been marked
     */
    public static void assertOn() {
        Thread main = mainThread;
        if (main == null) {
            throw new WrongThreadException(
                    "main thread has not been marked; platform enable must call MainThread.enter first");
        }
        if (!isCurrent(main)) {
            throw new WrongThreadException("expected server main thread (" + main.getName() + "), was "
                    + Thread.currentThread().getName());
        }
    }

    /**
     * Requires the calling thread not to be the main thread (NFR-2, NFR-7).
     *
     * <p>If no main thread has been marked yet — unit tests that never touch the
     * platform entry point — this is a no-op so database tests can run on the
     * JUnit thread. Once {@link #enter(Thread)} has run, every call is checked.
     */
    public static void assertOff() {
        Thread main = mainThread;
        if (main != null && isCurrent(main)) {
            throw new WrongThreadException("must not run on the server main thread (" + main.getName() + ") (NFR-2)");
        }
    }

    private static boolean isCurrent(@Nullable Thread thread) {
        return thread != null && sameThread(thread, Thread.currentThread());
    }

    /**
     * Thread identity is intentional: we mean the server's {@link Thread}
     * instance, not a namesake. {@code Thread.equals} is reference equality
     * anyway; Error Prone still wants the comparison named.
     */
    @SuppressWarnings("ReferenceEquality")
    private static boolean sameThread(Thread a, Thread b) {
        return a == b;
    }
}
