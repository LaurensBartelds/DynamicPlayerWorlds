package nl.gzmn.playerworlds.core.obs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Behavioural probe for copy-on-write file clones on the scratch filesystem.
 *
 * <p>Uses {@code cp --reflink=always} when a {@code cp} binary is on {@code PATH}.
 * {@code --always} fails hard when the filesystem cannot clone, which is what we
 * want; {@code --auto} would silently full-copy and look like success. When
 * {@code cp} is absent (typical on stock Windows), the verdict is
 * {@link ReflinkVerdict#FULL_COPY} — there is no portable JDK API for FICLONE.
 */
public final class ReflinkProbe {

    private static final Logger log = LoggerFactory.getLogger(ReflinkProbe.class);

    /** Large enough that a naive "same path" stub cannot fake success cheaply. */
    private static final int PROBE_BYTES = 256 * 1024;

    private ReflinkProbe() {}

    /**
     * Probes whether reflink copies work under {@code directory}'s filesystem.
     *
     * @param directory existing directory on the volume under test (usually scratch)
     */
    public static ReflinkVerdict probe(Path directory) {
        Objects.requireNonNull(directory, "directory");
        @Nullable Path work = null;
        try {
            if (!Files.isDirectory(directory)) {
                Files.createDirectories(directory);
            }
            work = Files.createTempDirectory(directory, "gzmn-reflink-");
            Path source = work.resolve("source.bin");
            Path target = work.resolve("target.bin");
            byte[] payload = probePayload();
            Files.write(source, payload);

            ProcessBuilder builder = new ProcessBuilder("cp", "--reflink=always", source.toString(), target.toString());
            builder.redirectErrorStream(true);
            Process process;
            try {
                process = builder.start();
            } catch (IOException startFailed) {
                // No cp on PATH (Windows without GNU coreutils). Not an error —
                // the node will full-copy, which is the honest verdict.
                log.debug("reflink probe: cp not launchable ({})", startFailed.toString());
                return ReflinkVerdict.FULL_COPY;
            }

            boolean finished = process.waitFor(15, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("reflink probe: cp --reflink=always timed out");
                return ReflinkVerdict.UNKNOWN;
            }
            int exit = process.exitValue();
            if (exit != 0) {
                log.debug("reflink probe: cp --reflink=always exited {}", exit);
                return ReflinkVerdict.FULL_COPY;
            }
            if (!Files.isRegularFile(target) || Files.size(target) != payload.length) {
                log.warn("reflink probe: cp reported success but target is missing or wrong size");
                return ReflinkVerdict.UNKNOWN;
            }
            return ReflinkVerdict.REFLINK;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("reflink probe interrupted");
            return ReflinkVerdict.UNKNOWN;
        } catch (IOException e) {
            log.warn("reflink probe failed: {}", e.toString());
            return ReflinkVerdict.UNKNOWN;
        } finally {
            deleteRecursively(work);
        }
    }

    private static byte[] probePayload() {
        byte[] bytes = new byte[PROBE_BYTES];
        // Not random: stable content is fine; uniqueness is per path, not payload.
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i * 31);
        }
        return bytes;
    }

    private static void deleteRecursively(@Nullable Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            log.debug("could not clean reflink probe dir {}: {}", root, e.toString());
        }
    }
}
