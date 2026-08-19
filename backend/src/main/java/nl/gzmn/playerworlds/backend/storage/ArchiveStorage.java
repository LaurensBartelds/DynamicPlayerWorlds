package nl.gzmn.playerworlds.backend.storage;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import nl.gzmn.playerworlds.core.storage.ObjectStore;
import nl.gzmn.playerworlds.core.storage.StorageException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages storage operations for cold world archives across S3 object storage
 * or local filesystem fallback (FR-35, FR-36, plan Task 3).
 */
public final class ArchiveStorage {

    private static final Logger log = LoggerFactory.getLogger(ArchiveStorage.class);

    private final @Nullable ObjectStore objectStore;
    private final @Nullable Path localArchiveDirectory;

    public ArchiveStorage(@Nullable ObjectStore objectStore, @Nullable Path localArchiveDirectory) {
        if (objectStore == null && localArchiveDirectory == null) {
            throw new IllegalArgumentException("Either objectStore or localArchiveDirectory must be configured");
        }
        this.objectStore = objectStore;
        this.localArchiveDirectory = localArchiveDirectory != null
                ? localArchiveDirectory.toAbsolutePath().normalize()
                : null;
    }

    /**
     * Creates an {@link ArchiveStorage} backed by S3 {@link ObjectStore}.
     */
    public static ArchiveStorage s3(ObjectStore objectStore) {
        Objects.requireNonNull(objectStore, "objectStore");
        return new ArchiveStorage(objectStore, null);
    }

    /**
     * Creates an {@link ArchiveStorage} backed by a local filesystem directory.
     */
    public static ArchiveStorage filesystem(Path localArchiveDirectory) {
        Objects.requireNonNull(localArchiveDirectory, "localArchiveDirectory");
        return new ArchiveStorage(null, localArchiveDirectory);
    }

    /**
     * Returns whether this storage client is using S3 object storage.
     */
    public boolean isS3() {
        return objectStore != null;
    }

    /**
     * Uploads or stores a compressed archive artifact under the given storage key.
     *
     * @param key storage key (e.g. {@code worlds/<world_id>/archive/<filename>.tar.zst})
     * @param file local archive file to store
     * @throws StorageException if the upload fails
     */
    public void uploadArchive(String key, Path file) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(file, "file");

        if (objectStore != null) {
            objectStore.putObject(key, file);
            return;
        }

        Path destination = resolveLocalPath(key);
        Path temp = null;
        try {
            Path parent = destination.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            temp = destination.resolveSibling(destination.getFileName() + ".tmp." + UUID.randomUUID());
            Files.copy(file, temp, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // Suppress secondary cleanup failure
                }
            }
            throw new StorageException("Failed to save local archive: " + key, e);
        }
    }

    /**
     * Downloads an archive artifact from storage to the local destination path atomically.
     *
     * @param key storage key to retrieve
     * @param destination local file path to write to
     * @throws StorageException if the download or local write fails
     */
    public void downloadArchive(String key, Path destination) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(destination, "destination");

        if (objectStore != null) {
            objectStore.getObject(key, destination);
            return;
        }

        Path source = resolveLocalPath(key);
        if (!Files.exists(source)) {
            throw new StorageException("Archive does not exist: " + key);
        }

        Path temp = null;
        try {
            Path parent = destination.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            temp = destination.resolveSibling(destination.getFileName() + ".tmp." + UUID.randomUUID());
            Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // Suppress secondary cleanup failure
                }
            }
            throw new StorageException("Failed to download local archive: " + key, e);
        }
    }

    /**
     * Deletes an archive from storage if present.
     *
     * @param key storage key to delete
     * @throws StorageException if deletion fails
     */
    public void deleteArchive(String key) {
        Objects.requireNonNull(key, "key");

        if (objectStore != null) {
            objectStore.deleteObject(key);
            return;
        }

        Path target = resolveLocalPath(key);
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new StorageException("Failed to delete local archive: " + key, e);
        }
    }

    /**
     * Checks whether an archive artifact exists in storage.
     *
     * @param key storage key to check
     * @return {@code true} if present, {@code false} otherwise
     */
    public boolean exists(String key) {
        Objects.requireNonNull(key, "key");

        if (objectStore != null) {
            return objectStore.exists(key);
        }

        Path target = resolveLocalPath(key);
        return Files.isRegularFile(target);
    }

    /**
     * Returns the size of an archive artifact in bytes.
     *
     * @param key storage key
     * @return size in bytes
     * @throws StorageException if size lookup fails or object does not exist
     */
    public long getArchiveSize(String key) {
        Objects.requireNonNull(key, "key");

        if (objectStore != null) {
            return objectStore.getObjectSize(key);
        }

        Path target = resolveLocalPath(key);
        if (!Files.isRegularFile(target)) {
            throw new StorageException("Archive not found: " + key);
        }
        try {
            return Files.size(target);
        } catch (IOException e) {
            throw new StorageException("Failed to get size of local archive: " + key, e);
        }
    }

    /**
     * Re-reads a stored archive and compares its SHA-256 against {@code expected}.
     *
     * <p>FR-35 and CONTRIBUTING rule 8 both make this the gate on destruction:
     * archival deletes the live folders <em>and</em> the per-world object prefix,
     * so at the moment it runs the archive is about to become the only copy of
     * the world. A length comparison does not establish that the bytes arrived —
     * a truncated-then-padded multipart upload, or a corrupted part, has exactly
     * the right length.
     *
     * <p>The check reads the object back rather than asking the store for a
     * checksum, because {@link ObjectStore} exposes none and the filesystem
     * backend has none to expose. That costs one extra download of an archive
     * that was just uploaded. It is the cheapest honest option here; an S3-only
     * fast path using {@code ChecksumAlgorithm.SHA256} on the upload would avoid
     * the transfer and is worth having once {@code ObjectStore} can carry it.
     *
     * @return true when the stored bytes hash to {@code expected}
     */
    public boolean verifyStoredArchive(String key, String expected) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(expected, "expected");

        if (objectStore == null) {
            Path stored = resolveLocalPath(key);
            if (!Files.isRegularFile(stored)) {
                log.error("archive verification failed: {} is not a regular file", key);
                return false;
            }
            return ArchivePacker.verifyChecksum(stored, expected);
        }

        Path scratch = null;
        try {
            scratch = Files.createTempFile("archive-verify-", ".tmp");
            objectStore.getObject(key, scratch);
            return ArchivePacker.verifyChecksum(scratch, expected);
        } catch (IOException e) {
            throw new StorageException("Failed to read back archive for verification: " + key, e);
        } finally {
            if (scratch != null) {
                try {
                    Files.deleteIfExists(scratch);
                } catch (IOException ignored) {
                    log.debug("Could not delete verification scratch file: {}", scratch);
                }
            }
        }
    }

    /**
     * Deletes all archive objects matching the specified prefix.
     *
     * @param prefix key prefix to purge
     * @throws StorageException if deletion fails
     */
    public void deletePrefix(String prefix) {
        Objects.requireNonNull(prefix, "prefix");

        if (objectStore != null) {
            objectStore.deletePrefix(prefix);
            return;
        }

        Objects.requireNonNull(localArchiveDirectory, "localArchiveDirectory");
        Path prefixDir = localArchiveDirectory.resolve(prefix).normalize();
        if (!prefixDir.startsWith(localArchiveDirectory)) {
            throw new IllegalArgumentException("Prefix escapes local archive directory: " + prefix);
        }

        if (Files.exists(prefixDir)) {
            try (Stream<Path> walk = Files.walk(prefixDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        log.debug("Could not delete archive path: {}", path);
                    }
                });
            } catch (IOException e) {
                throw new StorageException("Failed to delete local prefix: " + prefix, e);
            }
        }
    }

    private Path resolveLocalPath(String key) {
        Objects.requireNonNull(localArchiveDirectory, "localArchiveDirectory");
        Path path = localArchiveDirectory.resolve(key).normalize();
        if (!path.startsWith(localArchiveDirectory)) {
            throw new IllegalArgumentException("Archive key escapes local archive directory: " + key);
        }
        return path;
    }
}
