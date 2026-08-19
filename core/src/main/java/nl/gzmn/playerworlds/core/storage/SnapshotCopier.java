package nl.gzmn.playerworlds.core.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Copies a dirty file set into a per-sync snapshot directory with post-copy
 * re-stat and bounded retry (MN-5a steps 4–5, plan §9.1).
 *
 * <p>For each relative path:
 *
 * <ol>
 *   <li>fingerprint the live source;
 *   <li>clone into the snapshot directory;
 *   <li>re-fingerprint the source — if size or mtime moved, the clone may be
 *       torn, so delete it and retry;
 *   <li>after {@code maxAttempts} unsettled tries, abort with
 *       {@link UnstableFileException}.
 * </ol>
 *
 * <p>Hard links are never offered as a strategy: see {@link FileCloner}.
 */
public final class SnapshotCopier {

    private static final Logger log = LoggerFactory.getLogger(SnapshotCopier.class);

    /** Specification default for {@code storage.snapshot-copy-retries}. */
    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    private final FileCloner cloner;
    private final int maxAttempts;

    public SnapshotCopier(FileCloner cloner) {
        this(cloner, DEFAULT_MAX_ATTEMPTS);
    }

    /**
     * @param cloner copy strategy (reflink-with-fallback or plain)
     * @param maxAttempts upper bound on copy tries per file; must be &gt;= 1
     */
    public SnapshotCopier(FileCloner cloner, int maxAttempts) {
        this.cloner = Objects.requireNonNull(cloner, "cloner");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        this.maxAttempts = maxAttempts;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /**
     * Copies each path under {@code liveRoot} into the same relative location
     * under {@code snapshotRoot}.
     *
     * @param liveRoot directory holding the live (possibly still-written) files
     * @param snapshotRoot empty-or-existing per-sync snapshot directory
     * @param relativePaths paths relative to {@code liveRoot}; empty collection
     *     is a no-op success
     * @return one result per input path that still existed, in input order.
     *     Paths that disappeared between the scan and the copy are omitted.
     * @throws UnstableFileException if a source will not settle
     * @throws StorageException on IO failure
     */
    public List<CopiedFile> copyAll(Path liveRoot, Path snapshotRoot, Collection<Path> relativePaths) {
        Objects.requireNonNull(liveRoot, "liveRoot");
        Objects.requireNonNull(snapshotRoot, "snapshotRoot");
        Objects.requireNonNull(relativePaths, "relativePaths");
        try {
            Files.createDirectories(snapshotRoot);
        } catch (IOException e) {
            throw new StorageException("could not create snapshot root " + snapshotRoot, e);
        }
        List<CopiedFile> results = new ArrayList<>(relativePaths.size());
        for (Path relative : relativePaths) {
            CopiedFile copied = copyOne(liveRoot, snapshotRoot, relative);
            if (copied != null) {
                results.add(copied);
            }
        }
        return List.copyOf(results);
    }

    /**
     * Copies a single relative path. See {@link #copyAll(Path, Path, Collection)}.
     *
     * @return the settled snapshot copy, or {@code null} when the source no
     *     longer exists
     */
    public @Nullable CopiedFile copyOne(Path liveRoot, Path snapshotRoot, Path relative) {
        Objects.requireNonNull(liveRoot, "liveRoot");
        Objects.requireNonNull(snapshotRoot, "snapshotRoot");
        Objects.requireNonNull(relative, "relative");
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException("relative path must not be absolute: " + relative);
        }
        Path source = liveRoot.resolve(relative).normalize();
        if (!source.startsWith(liveRoot.normalize())) {
            throw new IllegalArgumentException("relative path escapes live root: " + relative);
        }
        Path target = snapshotRoot.resolve(relative).normalize();
        if (!target.startsWith(snapshotRoot.normalize())) {
            throw new IllegalArgumentException("relative path escapes snapshot root: " + relative);
        }

        IOException lastIo = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                if (!Files.exists(source)) {
                    // Gone between the dirty scan and now. That is not a fault and
                    // must not abort the sync: the server writes and removes
                    // transient files under data/ (chunk_tickets.dat is the one
                    // that showed up first) while the scan result is in flight, so
                    // treating a vanished file as fatal means no snapshot ever
                    // completes and object storage stays empty.
                    //
                    // MN-5a's "abort this sync" rule is about a file that will not
                    // settle, which is a torn-read risk. A file that no longer
                    // exists carries no such risk — it is simply not part of the
                    // world state this snapshot describes.
                    log.debug("source vanished between scan and copy, skipping: {}", source);
                    return null;
                }
                if (!Files.isRegularFile(source)) {
                    throw new StorageException("source is not a regular file: " + source);
                }
                FileFingerprint before = FileFingerprint.of(source);
                cloner.copy(source, target);
                FileFingerprint after = FileFingerprint.of(source);
                if (!before.sameAs(after)) {
                    log.debug(
                            "source changed during copy (attempt {}/{}): {} size {}→{} mtime {}→{}",
                            attempt,
                            maxAttempts,
                            source,
                            before.sizeBytes(),
                            after.sizeBytes(),
                            before.lastModifiedTime(),
                            after.lastModifiedTime());
                    deleteQuietly(target);
                    continue;
                }
                FileFingerprint snapshot = FileFingerprint.of(target);
                if (snapshot.sizeBytes() != before.sizeBytes()) {
                    // Clone produced a different length without the source moving —
                    // treat as failure and retry rather than upload a partial file.
                    log.warn(
                            "snapshot length mismatch for {} (attempt {}/{}): source {} snapshot {}",
                            source,
                            attempt,
                            maxAttempts,
                            before.sizeBytes(),
                            snapshot.sizeBytes());
                    deleteQuietly(target);
                    continue;
                }
                return new CopiedFile(relative, target, before, attempt);
            } catch (IOException e) {
                lastIo = e;
                deleteQuietly(target);
                log.debug("copy attempt {}/{} failed for {}: {}", attempt, maxAttempts, source, e.toString());
            }
        }
        if (lastIo != null) {
            throw new StorageException("copy failed after " + maxAttempts + " attempt(s): " + source, lastIo);
        }
        throw new UnstableFileException(source, maxAttempts);
    }

    private static void deleteQuietly(Path target) {
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            log.debug("could not delete partial snapshot {}: {}", target, e.toString());
        }
    }

    /**
     * One successfully settled snapshot file.
     *
     * @param relative path relative to the live / snapshot roots
     * @param snapshotPath absolute (or normalised) path of the snapshot copy
     * @param fingerprint size/mtime observed before the successful copy
     * @param attempts how many tries this file needed (1 = first try settled)
     */
    public record CopiedFile(Path relative, Path snapshotPath, FileFingerprint fingerprint, int attempts) {
        public CopiedFile {
            Objects.requireNonNull(relative, "relative");
            Objects.requireNonNull(snapshotPath, "snapshotPath");
            Objects.requireNonNull(fingerprint, "fingerprint");
            if (attempts < 1) {
                throw new IllegalArgumentException("attempts must be >= 1");
            }
        }
    }
}
