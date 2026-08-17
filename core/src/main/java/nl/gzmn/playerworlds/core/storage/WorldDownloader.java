package nl.gzmn.playerworlds.core.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Objects;
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
     * Materializes or updates the local world folder under {@code scratchRoot} to match {@code manifest}.
     *
     * @param manifest target snapshot manifest to materialize
     * @param scratchRoot root directory containing local world folders
     * @return summary of operations performed during materialization
     * @throws StorageException if an IO error occurs during download or cloning
     * @throws IllegalArgumentException if an entry path attempts directory traversal outside scratchRoot
     */
    public Result materialize(Manifest manifest, Path scratchRoot) {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(scratchRoot, "scratchRoot");

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

        boolean wasWarm = (filesDownloaded == 0);
        log.debug(
                "Materialized manifest for world {} at {}: checked={}, restored={}, downloaded={}, bytes={}, warm={}",
                manifest.worldId().value(),
                scratchRoot,
                filesChecked,
                filesRestored,
                filesDownloaded,
                bytesDownloaded,
                wasWarm);
        return new Result(filesChecked, filesRestored, filesDownloaded, bytesDownloaded, wasWarm);
    }

    /**
     * Statistics and outcome of a {@link #materialize(Manifest, Path)} invocation.
     *
     * @param filesChecked total entries inspected from the manifest
     * @param filesRestored files copied or cloned from cache to the scratch directory
     * @param filesDownloaded objects fetched from object storage into the local cache
     * @param bytesDownloaded total bytes fetched from object storage
     * @param wasWarm {@code true} if all required objects were already in the local cache (0 downloads)
     */
    public record Result(
            int filesChecked, int filesRestored, int filesDownloaded, long bytesDownloaded, boolean wasWarm) {

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
        }
    }
}
