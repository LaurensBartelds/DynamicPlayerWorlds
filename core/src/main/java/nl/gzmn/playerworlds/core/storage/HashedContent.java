package nl.gzmn.playerworlds.core.storage;

import java.util.Objects;

/**
 * Result of hashing a file for content-addressed object storage (MN-2, MN-3).
 *
 * @param sha256Hex lowercase hex SHA-256 of the file bytes
 * @param sizeBytes file length in bytes (must match the bytes that were hashed)
 */
public record HashedContent(String sha256Hex, long sizeBytes) {

    public static final int SHA256_HEX_LENGTH = 64;

    public HashedContent {
        Objects.requireNonNull(sha256Hex, "sha256Hex");
        if (sha256Hex.length() != SHA256_HEX_LENGTH) {
            throw new IllegalArgumentException(
                    "sha256Hex must be " + SHA256_HEX_LENGTH + " hex chars, got " + sha256Hex.length());
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must be >= 0");
        }
        for (int i = 0; i < sha256Hex.length(); i++) {
            char c = sha256Hex.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                throw new IllegalArgumentException("sha256Hex must be lowercase hex, found '" + c + "'");
            }
        }
    }

    /** Object key suffix {@code worlds/<id>/data/<sha256>} uses this hex string. */
    public String objectKeySuffix() {
        return sha256Hex;
    }
}
