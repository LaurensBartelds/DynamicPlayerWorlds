package nl.gzmn.playerworlds.core.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages immutable content-addressed cache files at {@code <cacheRoot>/<sha256Hex>}.
 *
 * <p>Writes use temporary file creation followed by atomic rename to guarantee reader
 * isolation. Materialization uses {@link FileCloner} (reflink or byte copy, never hard links)
 * to ensure scratch modifications never taint cached objects.
 */
public final class LocalObjectCache {

    private static final Logger log = LoggerFactory.getLogger(LocalObjectCache.class);

    private final Path cacheRoot;
    private final FileCloner cloner;
    private final Clock clock;

    public LocalObjectCache(Path cacheRoot, FileCloner cloner) {
        this(cacheRoot, cloner, Clock.systemUTC());
    }

    public LocalObjectCache(Path cacheRoot, FileCloner cloner, Clock clock) {
        this.cacheRoot = Objects.requireNonNull(cacheRoot, "cacheRoot");
        this.cloner = Objects.requireNonNull(cloner, "cloner");
        this.clock = Objects.requireNonNull(clock, "clock");
        try {
            Files.createDirectories(cacheRoot);
        } catch (IOException e) {
            throw new StorageException("Could not initialize cache directory: " + cacheRoot, e);
        }
    }

    /**
     * Returns whether an immutable cache entry exists for the given content hash.
     */
    public boolean contains(String sha256Hex) {
        Objects.requireNonNull(sha256Hex, "sha256Hex");
        return Files.isRegularFile(pathOf(sha256Hex));
    }

    /**
     * Resolves the cache path for the given content hash.
     */
    public Path pathOf(String sha256Hex) {
        Objects.requireNonNull(sha256Hex, "sha256Hex");
        return cacheRoot.resolve(sha256Hex);
    }

    /**
     * Places {@code sourceFile} into the local cache under {@code sha256Hex}.
     *
     * <p>If already present, touches the cached file's modified time without rewriting content.
     * Otherwise, copies to a temporary file in the cache directory first and atomically moves it.
     */
    public void put(String sha256Hex, Path sourceFile) {
        Objects.requireNonNull(sha256Hex, "sha256Hex");
        Objects.requireNonNull(sourceFile, "sourceFile");

        Path target = pathOf(sha256Hex);
        if (Files.isRegularFile(target)) {
            try {
                Files.setLastModifiedTime(target, FileTime.from(clock.instant()));
            } catch (IOException ignored) {
                // Best effort touch
            }
            return;
        }

        if (!Files.exists(sourceFile)) {
            throw new StorageException("Source file does not exist: " + sourceFile);
        }

        Path temp = cacheRoot.resolve(sha256Hex + ".tmp-" + System.nanoTime());
        try {
            cloner.copy(sourceFile, temp);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // Ignore cleanup failure
            }
            throw new StorageException("Failed to put object in local cache: " + sha256Hex, e);
        }
    }

    /**
     * Clones the cached object {@code sha256Hex} onto {@code destinationFile}.
     *
     * <p>Creates missing parent directories and touches the cache file's modified time.
     */
    public void cloneTo(String sha256Hex, Path destinationFile) {
        Objects.requireNonNull(sha256Hex, "sha256Hex");
        Objects.requireNonNull(destinationFile, "destinationFile");

        Path source = pathOf(sha256Hex);
        if (!Files.isRegularFile(source)) {
            throw new StorageException("Object not in cache: " + sha256Hex);
        }

        Path parent = destinationFile.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new StorageException("Failed to create parent directories for: " + destinationFile, e);
            }
        }

        try {
            cloner.copy(source, destinationFile);
            Files.setLastModifiedTime(source, FileTime.from(clock.instant()));
        } catch (IOException e) {
            throw new StorageException("Failed to clone object from cache: " + sha256Hex + " to " + destinationFile, e);
        }
    }

    /**
     * Evicts oldest cached files by last modified time until total cache size is &lt;= {@code maxBytes}.
     *
     * @param maxBytes maximum permitted cache size in bytes (must be &gt;= 0)
     * @return total bytes freed
     */
    public long evictLru(long maxBytes) {
        long limit = Math.max(0, maxBytes);
        try (Stream<Path> stream = Files.list(cacheRoot)) {
            List<Path> files =
                    new ArrayList<>(stream.filter(Files::isRegularFile).toList());
            long total = 0;
            for (Path f : files) {
                try {
                    total += Files.size(f);
                } catch (IOException ignored) {
                    // Skip unreadable files
                }
            }

            if (total <= limit) {
                return 0;
            }

            files.sort(Comparator.comparingLong(p -> {
                try {
                    return Files.getLastModifiedTime(p).toMillis();
                } catch (IOException e) {
                    return 0L;
                }
            }));

            long bytesFreed = 0;
            for (Path f : files) {
                if (total <= limit) {
                    break;
                }
                try {
                    long size = Files.size(f);
                    if (Files.deleteIfExists(f)) {
                        total -= size;
                        bytesFreed += size;
                    }
                } catch (IOException e) {
                    log.warn("Failed to evict cache file: {}", f, e);
                }
            }
            return bytesFreed;
        } catch (IOException e) {
            log.error("Cache eviction scan failed on {}", cacheRoot, e);
            return 0;
        }
    }
}
