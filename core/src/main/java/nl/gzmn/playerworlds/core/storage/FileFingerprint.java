package nl.gzmn.playerworlds.core.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Objects;

/**
 * Size and mtime snapshot of a file, used by MN-5a steps 3 and 5.
 *
 * <p>Equality is exact on both fields. Filesystems with coarse mtime resolution
 * still catch in-place region rewrites because those also change size or bump
 * mtime on the same write.
 *
 * @param sizeBytes file length in bytes
 * @param lastModifiedTime last-modified instant as reported by the filesystem
 */
public record FileFingerprint(long sizeBytes, FileTime lastModifiedTime) {

    public FileFingerprint {
        Objects.requireNonNull(lastModifiedTime, "lastModifiedTime");
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must be >= 0");
        }
    }

    /** Reads the fingerprint of an existing regular file. */
    public static FileFingerprint of(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
        if (!attrs.isRegularFile()) {
            throw new StorageException("not a regular file: " + path);
        }
        return new FileFingerprint(attrs.size(), attrs.lastModifiedTime());
    }

    /** Whether both size and mtime match {@code other}. */
    public boolean sameAs(FileFingerprint other) {
        Objects.requireNonNull(other, "other");
        return sizeBytes == other.sizeBytes && lastModifiedTime.equals(other.lastModifiedTime);
    }
}
