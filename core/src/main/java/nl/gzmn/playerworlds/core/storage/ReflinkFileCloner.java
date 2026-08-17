package nl.gzmn.playerworlds.core.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import nl.gzmn.playerworlds.core.obs.ReflinkVerdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Prefers a copy-on-write clone via {@code cp --reflink=auto}, falling back to a
 * plain copy when the clone is unavailable or fails (MN-5a step 4).
 *
 * <p>The startup {@link nl.gzmn.playerworlds.core.obs.ReflinkProbe} decides
 * whether to attempt clones at all. On {@link ReflinkVerdict#FULL_COPY} and
 * {@link ReflinkVerdict#UNKNOWN} this cloner never shells out — ext4's silent
 * full-copy under {@code --reflink=auto} is exactly the surprise the probe
 * exists to avoid paying for twice.
 *
 * <p>Hard links are never used: a shared inode makes an in-place region write
 * visible through the "copy" and the snapshot is worthless.
 */
public final class ReflinkFileCloner implements FileCloner {

    private static final Logger log = LoggerFactory.getLogger(ReflinkFileCloner.class);

    private final ReflinkVerdict verdict;
    private final FileCloner fallback;

    public ReflinkFileCloner(ReflinkVerdict verdict) {
        this(verdict, PlainFileCloner.INSTANCE);
    }

    public ReflinkFileCloner(ReflinkVerdict verdict, FileCloner fallback) {
        this.verdict = Objects.requireNonNull(verdict, "verdict");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
    }

    /** Verdict this cloner was constructed with (for metrics and logs). */
    public ReflinkVerdict verdict() {
        return verdict;
    }

    @Override
    public void copy(Path source, Path target) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (verdict == ReflinkVerdict.REFLINK && tryReflink(source, target)) {
            return;
        }
        fallback.copy(source, target);
    }

    private static boolean tryReflink(Path source, Path target) {
        ProcessBuilder builder = new ProcessBuilder(
                "cp", "--reflink=auto", "--remove-destination", source.toString(), target.toString());
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            boolean finished = process.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("reflink copy timed out for {}; falling back to plain copy", source);
                return false;
            }
            if (process.exitValue() != 0) {
                log.debug("reflink copy exited {} for {}; falling back to plain copy", process.exitValue(), source);
                return false;
            }
            if (!Files.isRegularFile(target)) {
                log.warn("reflink copy reported success but {} is missing; falling back", target);
                return false;
            }
            return true;
        } catch (IOException startFailed) {
            log.debug("reflink copy not launchable ({}): {}", startFailed.toString(), source);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("reflink copy interrupted for {}; falling back to plain copy", source);
            return false;
        }
    }
}
