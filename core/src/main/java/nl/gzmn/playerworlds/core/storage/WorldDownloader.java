package nl.gzmn.playerworlds.core.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Materializes and updates local world directories from snapshot manifests using
 * {@link LocalObjectCache} and {@link ObjectStore}.
 */
public final class WorldDownloader {

    private static final Logger log = LoggerFactory.getLogger(WorldDownloader.class);

    private final ObjectStore objectStore;
    private final LocalObjectCache cache;
    private final FileCloner cloner;

    public WorldDownloader(ObjectStore objectStore, LocalObjectCache cache, FileCloner cloner) {
        this.objectStore = Objects.requireNonNull(objectStore, "objectStore");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.cloner = Objects.requireNonNull(cloner, "cloner");
    }

    /**
     * Makes the local world folders under {@code scratchRoot} match {@code manifest}.
     *
     * <p><em>Match</em>, not <em>include</em> (MN-4, D16). Every entry in the
     * manifest is downloaded or cloned into place, and every file under
     * {@code relativeDimensionRoots} that the manifest does not list is removed.
     * Without the second half a materialised world is the union of the manifest
     * and whatever the folder already held — a stale region file left by an
     * earlier generation survives a cold load and is then picked up by the next
     * snapshot as though it were current.
     *
     * <p>The roots bound what may be deleted. They come from the caller for the
     * same reason {@link DirtyScanner}'s do: {@code :core} may not see
     * {@code WorldLayout} (CONTRIBUTING rule 2), and a delete pass that guessed
     * at folder names would be guessing about which files to destroy.
     *
     * @param manifest target snapshot manifest to materialize
     * @param scratchRoot root directory containing local world folders
     * @param relativeDimensionRoots the world's dimension folders, relative to
     *     {@code scratchRoot}; nothing outside them is touched
     * @return summary of operations performed during materialization
     * @throws StorageException if an IO error occurs during download or cloning
     * @throws IllegalArgumentException if an entry path attempts directory traversal outside scratchRoot
     */
    public Result materialize(Manifest manifest, Path scratchRoot, Collection<Path> relativeDimensionRoots) {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(scratchRoot, "scratchRoot");
        Objects.requireNonNull(relativeDimensionRoots, "relativeDimensionRoots");

        Path normalizedScratch = scratchRoot.toAbsolutePath().normalize();
        int filesChecked = 0;
        int filesRestored = 0;
        int filesDownloaded = 0;
        long bytesDownloaded = 0;

        for (ManifestEntry entry : manifest.entries().values()) {
            filesChecked++;
            Path destination = normalizedScratch.resolve(entry.path()).normalize();
            if (!destination.startsWith(normalizedScratch)) {
                throw new IllegalArgumentException("Manifest entry path escapes scratch root: " + entry.path());
            }

            boolean cleanMatch = false;
            if (Files.isRegularFile(destination)) {
                try {
                    BasicFileAttributes attrs = Files.readAttributes(destination, BasicFileAttributes.class);
                    if (attrs.size() == entry.sizeBytes()
                            && attrs.lastModifiedTime().toMillis() == entry.lastModifiedMillis()) {
                        cleanMatch = true;
                    }
                } catch (IOException ignored) {
                    cleanMatch = false;
                }
            }

            if (cleanMatch) {
                continue;
            }

            filesRestored++;
            String sha256Hex = entry.sha256Hex();
            if (!cache.contains(sha256Hex)) {
                String dataKey = "worlds/" + manifest.worldId().value() + "/data/" + sha256Hex;
                Path cachedPath = cache.pathOf(sha256Hex);
                objectStore.getObject(dataKey, cachedPath);
                filesDownloaded++;
                bytesDownloaded += entry.sizeBytes();
            }

            Path parent = destination.getParent();
            if (parent != null) {
                try {
                    Files.createDirectories(parent);
                } catch (IOException e) {
                    throw new StorageException("Failed to create parent directories for: " + destination, e);
                }
            }

            try {
                cloner.copy(cache.pathOf(sha256Hex), destination);
                Files.setLastModifiedTime(destination, FileTime.fromMillis(entry.lastModifiedMillis()));
            } catch (IOException e) {
                throw new StorageException("Failed to materialize entry " + entry.path() + " to " + destination, e);
            }
        }

        int filesRemoved = removeUnlisted(manifest, normalizedScratch, relativeDimensionRoots);

        boolean wasWarm = (filesDownloaded == 0);
        log.debug(
                "Materialized manifest for world {} at {}: checked={}, restored={}, downloaded={}, bytes={}, "
                        + "removed={}, warm={}",
                manifest.worldId().value(),
                scratchRoot,
                filesChecked,
                filesRestored,
                filesDownloaded,
                bytesDownloaded,
                filesRemoved,
                wasWarm);
        return new Result(filesChecked, filesRestored, filesDownloaded, bytesDownloaded, filesRemoved, wasWarm);
    }

    /**
     * Deletes files under the world's folders that the manifest does not list
     * (MN-4).
     *
     * <p>Empty directories are left alone: Minecraft recreates the ones it wants
     * and an empty {@code region/} costs nothing, while a delete pass that also
     * removed directories would race the server recreating them.
     */
    private int removeUnlisted(Manifest manifest, Path normalizedScratch, Collection<Path> relativeDimensionRoots) {
        int removed = 0;
        for (Path relativeRoot : relativeDimensionRoots) {
            Objects.requireNonNull(relativeRoot, "relativeDimensionRoot");
            if (relativeRoot.isAbsolute()) {
                throw new IllegalArgumentException("dimension root must be relative to scratchRoot: " + relativeRoot);
            }
            Path root = normalizedScratch.resolve(relativeRoot).normalize();
            if (!root.startsWith(normalizedScratch)) {
                throw new IllegalArgumentException("dimension root escapes scratch root: " + relativeRoot);
            }
            if (!Files.isDirectory(root)) {
                continue;
            }
            List<Path> unlisted = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile).forEach(path -> {
                    String unixRel =
                            normalizedScratch.relativize(path).toString().replace('\\', '/');
                    if (!manifest.entries().containsKey(unixRel)) {
                        unlisted.add(path);
                    }
                });
            } catch (IOException e) {
                throw new StorageException("Failed to scan " + root + " for files the manifest does not list", e);
            }
            for (Path path : unlisted) {
                try {
                    Files.delete(path);
                    removed++;
                    log.debug("Removed {}, which manifest {} does not list", path, manifest.manifestKey());
                } catch (IOException e) {
                    // Not fatal: the world still matches the manifest for every file
                    // the manifest names. Left in place it will be picked up by the
                    // next snapshot, so say so rather than failing the load.
                    log.warn("Could not remove {}, which manifest {} does not list", path, manifest.manifestKey(), e);
                }
            }
        }
        return removed;
    }

    /**
     * Statistics and outcome of a {@link #materialize(Manifest, Path)} invocation.
     *
     * @param filesChecked total entries inspected from the manifest
     * @param filesRestored files copied or cloned from cache to the scratch directory
     * @param filesDownloaded objects fetched from object storage into the local cache
     * @param bytesDownloaded total bytes fetched from object storage
     * @param filesRemoved local files deleted because the manifest does not list them (MN-4)
     * @param wasWarm {@code true} if all required objects were already in the local cache (0 downloads)
     */
    public record Result(
            int filesChecked,
            int filesRestored,
            int filesDownloaded,
            long bytesDownloaded,
            int filesRemoved,
            boolean wasWarm) {

        public Result {
            if (filesChecked < 0) {
                throw new IllegalArgumentException("filesChecked must be >= 0");
            }
            if (filesRestored < 0) {
                throw new IllegalArgumentException("filesRestored must be >= 0");
            }
            if (filesDownloaded < 0) {
                throw new IllegalArgumentException("filesDownloaded must be >= 0");
            }
            if (bytesDownloaded < 0) {
                throw new IllegalArgumentException("bytesDownloaded must be >= 0");
            }
            if (filesRemoved < 0) {
                throw new IllegalArgumentException("filesRemoved must be >= 0");
            }
        }
    }
}
