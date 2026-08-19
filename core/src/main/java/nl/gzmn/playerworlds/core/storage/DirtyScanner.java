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
import nl.gzmn.playerworlds.core.model.WorldId;

/**
 * Scans local world directories for new or modified files against a baseline snapshot manifest (MN-5a step 3).
 *
 * <p>Excludes node-local files (e.g. {@code session.lock}, {@code uid.dat}) configured via {@code excludeGlobs}.
 * Compares file length and last-modified time against {@link ManifestEntry} metadata to identify dirty files.
 *
 * <p>Callers supply the dimension folder paths (relative to {@code scratchRoot}) because on-disk layout
 * is version-sensitive — Paper 26 nests Bukkit worlds under
 * {@code <level-name>/dimensions/minecraft/<name>/}, while cold archives still unpack to flat
 * {@code <name>/} folders. {@link #scanDirty(Path, WorldId, Map, List)} keeps the flat layout for
 * archive extract trees and tests.
 */
public final class DirtyScanner {

    private DirtyScanner() {}

    /**
     * Scans the given dimension folders under {@code scratchRoot} for files that are new or modified
     * compared to {@code baselineEntries}.
     *
     * @param scratchRoot root directory that relative paths are resolved against
     * @param relativeDimensionRoots dimension folder paths relative to {@code scratchRoot}
     * @param baselineEntries map of relative unix path to baseline manifest entry
     * @param excludeGlobs list of file names or glob patterns to omit from the dirty set
     * @return sorted list of relative paths that are new or modified
     * @throws StorageException if scanning the directory hierarchy fails with an IO error
     */
    public static List<Path> scanDirty(
            Path scratchRoot,
            Collection<Path> relativeDimensionRoots,
            Map<String, ManifestEntry> baselineEntries,
            List<String> excludeGlobs) {
        Objects.requireNonNull(scratchRoot, "scratchRoot");
        Objects.requireNonNull(relativeDimensionRoots, "relativeDimensionRoots");
        Objects.requireNonNull(baselineEntries, "baselineEntries");
        Objects.requireNonNull(excludeGlobs, "excludeGlobs");

        List<Path> dirty = new ArrayList<>();

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
        return List.copyOf(dirty);
    }

    /**
     * Scans flat Bukkit world folders ({@code <folder>}, {@code <folder>_nether},
     * {@code <folder>_the_end}) under {@code scratchRoot}.
     *
     * <p>Used for cold-archive extract trees and synthetic fixtures that still use the
     * classic multi-folder layout. Live Paper 26 nodes must pass layout-resolved roots
     * via {@link #scanDirty(Path, Collection, Map, List)} instead.
     */
    public static List<Path> scanDirty(
            Path scratchRoot, WorldId worldId, Map<String, ManifestEntry> baselineEntries, List<String> excludeGlobs) {
        Objects.requireNonNull(worldId, "worldId");
        String base = worldId.folder();
        return scanDirty(
                scratchRoot,
                List.of(Path.of(base), Path.of(base + "_nether"), Path.of(base + "_the_end")),
                baselineEntries,
                excludeGlobs);
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
