package nl.gzmn.playerworlds.core.storage;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Walks a world's dimension folders and reports what is there and what has
 * changed since the baseline manifest (MN-5a step 3).
 *
 * <p>Excludes node-local files ({@code session.lock}, {@code uid.dat}, whatever
 * else {@code storage.exclude-globs} names). A file is dirty when its length or
 * last-modified time differs from the baseline {@link ManifestEntry}; unreadable
 * counts as dirty, because copying a file that turns out not to have changed is
 * cheap and skipping one that has is not.
 *
 * <h2>Why the observed set comes back too</h2>
 *
 * <p>The dirty subset alone cannot express a deletion, and MN-3 says a world's
 * state <em>is</em> its manifest. Building the next manifest by adding the dirty
 * files to the previous one means an entry can never leave it: a file deleted
 * from a world folder is resurrected by the next cold load, and MN-2b's garbage
 * collection can never reclaim its object because a retained manifest still
 * points at it. The walk already visits every file, so returning the full set it
 * saw costs nothing and lets {@link SnapshotEngine} build the manifest from what
 * is on disk rather than from what used to be (plan 05, D16).
 *
 * <p>Callers supply the dimension folder paths, relative to {@code scratchRoot},
 * because on-disk layout is version-sensitive: Paper 26 nests Bukkit worlds under
 * {@code <level-name>/dimensions/minecraft/<name>/} while an archive's extract
 * tree is flat. {@code :core} may not see {@code WorldLayout} (CONTRIBUTING rule
 * 2), so it is told rather than deriving folder names from string suffixes.
 */
public final class DirtyScanner {

    private DirtyScanner() {}

    /**
     * What one walk saw.
     *
     * @param dirty relative paths that are new or modified, sorted; what gets
     *     copied, hashed and uploaded
     * @param observed every relative path the walk saw, as unix-separated
     *     manifest keys, sorted and duplicate-free; the complete file set the next
     *     manifest should describe. A list rather than a set so manifest entry
     *     order is stable across runs and two manifests of the same tree diff
     *     cleanly.
     */
    public record Scan(List<Path> dirty, List<String> observed) {

        public Scan {
            Objects.requireNonNull(dirty, "dirty");
            Objects.requireNonNull(observed, "observed");
            dirty = List.copyOf(dirty);
            observed = List.copyOf(observed);
        }

        /** Nothing on disk, which for a world with a baseline means everything was deleted. */
        public boolean isEmpty() {
            return observed.isEmpty();
        }
    }

    /**
     * Walks {@code relativeDimensionRoots} under {@code scratchRoot}.
     *
     * @param scratchRoot root directory that relative paths are resolved against
     * @param relativeDimensionRoots dimension folder paths relative to {@code scratchRoot}
     * @param baselineEntries relative unix path to baseline manifest entry
     * @param excludeGlobs file names or glob patterns to omit
     * @throws StorageException if the walk fails with an IO error — a partial
     *     result would read as "these files were deleted" and take their objects
     *     with it
     */
    public static Scan scan(
            Path scratchRoot,
            Collection<Path> relativeDimensionRoots,
            Map<String, ManifestEntry> baselineEntries,
            List<String> excludeGlobs) {
        Objects.requireNonNull(scratchRoot, "scratchRoot");
        Objects.requireNonNull(relativeDimensionRoots, "relativeDimensionRoots");
        Objects.requireNonNull(baselineEntries, "baselineEntries");
        Objects.requireNonNull(excludeGlobs, "excludeGlobs");

        List<Path> dirty = new ArrayList<>();
        List<String> observed = new ArrayList<>();

        for (Path relativeRoot : relativeDimensionRoots) {
            Objects.requireNonNull(relativeRoot, "relativeDimensionRoot");
            if (relativeRoot.isAbsolute()) {
                throw new IllegalArgumentException("dimension root must be relative to scratchRoot: " + relativeRoot);
            }
            Path root = scratchRoot.resolve(relativeRoot);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                        .filter(path -> {
                            Path fileName = path.getFileName();
                            return fileName != null && !isExcluded(fileName.toString(), excludeGlobs);
                        })
                        .forEach(path -> {
                            Path relative = scratchRoot.relativize(path);
                            String unixRel = relative.toString().replace('\\', '/');
                            observed.add(unixRel);
                            ManifestEntry baseEntry = baselineEntries.get(unixRel);
                            boolean isDirty = true;
                            if (baseEntry != null) {
                                try {
                                    FileFingerprint fp = FileFingerprint.of(path);
                                    if (fp.sizeBytes() == baseEntry.sizeBytes()
                                            && fp.lastModifiedTime().toMillis() == baseEntry.lastModifiedMillis()) {
                                        isDirty = false;
                                    }
                                } catch (IOException ignored) {
                                    // Treat unreadable file as dirty to force copy/stat handling
                                }
                            }
                            if (isDirty) {
                                dirty.add(relative);
                            }
                        });
            } catch (IOException e) {
                throw new StorageException("Failed to scan directory for dirty files: " + root, e);
            }
        }
        dirty.sort(Comparator.naturalOrder());
        observed.sort(Comparator.naturalOrder());
        return new Scan(dirty, observed);
    }

    private static boolean isExcluded(String fileName, List<String> excludeGlobs) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String glob : excludeGlobs) {
            if (glob.equalsIgnoreCase(lower)) {
                return true;
            }
            if (glob.contains("*") || glob.contains("?")) {
                try {
                    if (FileSystems.getDefault().getPathMatcher("glob:" + glob).matches(Path.of(fileName))
                            || FileSystems.getDefault()
                                    .getPathMatcher("glob:" + glob.toLowerCase(Locale.ROOT))
                                    .matches(Path.of(lower))) {
                        return true;
                    }
                } catch (Exception ignored) {
                    // Ignore malformed glob
                }
            }
        }
        return false;
    }
}
