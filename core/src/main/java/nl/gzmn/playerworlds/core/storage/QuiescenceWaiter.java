package nl.gzmn.playerworlds.core.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Waits for a dirty set to stop changing before it is copied (MN-5a step 3).
 *
 * <p>{@code World#save()} runs on the main thread and returns as soon as it has
 * queued the write; the chunk IO thread keeps draining that queue afterwards,
 * and there is no stable API to ask it "are you done" (Paper 26's internals are
 * exactly the coupling forbidden-apis exists to prevent). Polling size and
 * mtime across the dirty set is the documented substitute: if a full {@code
 * quiet} interval passes with nothing changed, the copy in step 4 is unlikely
 * to race a write. {@code timeout} bounds the wait — step 5's per-file re-stat
 * and retry is what catches whatever is still moving when it elapses.
 */
public final class QuiescenceWaiter {

    private static final Logger log = LoggerFactory.getLogger(QuiescenceWaiter.class);

    private QuiescenceWaiter() {}

    /**
     * Polls {@code relativePaths} under {@code root} until nothing has changed
     * for a full {@code quiet} interval, bounded by {@code timeout}.
     *
     * @param root directory the relative paths are resolved against
     * @param relativePaths the dirty set to watch; empty is trivially quiescent
     * @param quiet how long nothing must change to count as settled; zero or
     *     negative skips waiting entirely
     * @param timeout upper bound on total time spent polling
     * @return {@code true} if the set settled, {@code false} if {@code timeout}
     *     elapsed first
     * @throws InterruptedException if interrupted while polling
     */
    public static boolean await(Path root, Collection<Path> relativePaths, Duration quiet, Duration timeout)
            throws InterruptedException {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(relativePaths, "relativePaths");
        Objects.requireNonNull(quiet, "quiet");
        Objects.requireNonNull(timeout, "timeout");
        if (relativePaths.isEmpty() || quiet.isZero() || quiet.isNegative()) {
            return true;
        }

        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        Map<Path, FileFingerprint> previous = fingerprintAll(root, relativePaths);
        while (true) {
            Thread.sleep(quiet.toMillis());
            Map<Path, FileFingerprint> current = fingerprintAll(root, relativePaths);
            if (current.equals(previous)) {
                return true;
            }
            if (System.nanoTime() >= deadlineNanos) {
                log.debug("dirty set under {} did not settle within {}; proceeding (MN-5a step 3/5)", root, timeout);
                return false;
            }
            previous = current;
        }
    }

    private static Map<Path, FileFingerprint> fingerprintAll(Path root, Collection<Path> relativePaths) {
        Map<Path, FileFingerprint> result = new HashMap<>();
        for (Path relative : relativePaths) {
            Path absolute = root.resolve(relative);
            try {
                if (Files.isRegularFile(absolute)) {
                    result.put(relative, FileFingerprint.of(absolute));
                }
            } catch (IOException e) {
                // Unreadable counts as "still moving": absent here differs from any
                // fingerprint recorded before or after, so polling keeps going rather
                // than mistaking a transient read failure for stability.
                log.debug("could not fingerprint {} while polling for quiescence: {}", absolute, e.toString());
            }
        }
        return result;
    }
}
