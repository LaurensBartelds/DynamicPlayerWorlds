package nl.gzmn.playerworlds.core.storage;

import java.util.Objects;

/**
 * Metadata record for a single file tracked in a snapshot manifest (MN-2, MN-3).
 *
 * @param path logical path relative to the snapshot root (e.g. {@code pw_<id>/level.dat})
 * @param sha256Hex lowercase 64-character hex digest of the file's content
 * @param sizeBytes length of the file in bytes
 * @param lastModifiedMillis file modification timestamp in milliseconds since epoch
 */
public record ManifestEntry(String path, String sha256Hex, long sizeBytes, long lastModifiedMillis) {

    public ManifestEntry {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(sha256Hex, "sha256Hex");
        if (sha256Hex.length() != HashedContent.SHA256_HEX_LENGTH) {
            throw new IllegalArgumentException(
                    "sha256Hex must be " + HashedContent.SHA256_HEX_LENGTH + " hex chars, got " + sha256Hex.length());
        }
        for (int i = 0; i < sha256Hex.length(); i++) {
            char c = sha256Hex.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                throw new IllegalArgumentException("sha256Hex must be lowercase hex, found '" + c + "'");
            }
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must be >= 0");
        }
        if (lastModifiedMillis < 0) {
            throw new IllegalArgumentException("lastModifiedMillis must be >= 0");
        }
    }
}
