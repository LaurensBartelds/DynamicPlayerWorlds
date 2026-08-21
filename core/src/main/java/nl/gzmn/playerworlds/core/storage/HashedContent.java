package nl.gzmn.playerworlds.core.storage;

import java.util.Objects;

/**
 * Result of hashing a file for content-addressed object storage (MN-2, MN-3).
 *
 * @param sha256Hex lowercase hex SHA-256 of the file bytes; the content-addressing key
 * @param sizeBytes file length in bytes (must match the bytes that were hashed)
 * @param md5Base64 standard base64 MD5 of the same bytes, for the upload's {@code
 *     Content-MD5} header. Not the content-addressing key — MD5 is broken for that — but
 *     it is the one integrity check every S3-compatible server has understood since the
 *     original API, so the object storage layer rejects a corrupted upload itself rather
 *     than trusting whatever arrived (CONTRIBUTING rule 8).
 */
public record HashedContent(String sha256Hex, long sizeBytes, String md5Base64) {

    public static final int SHA256_HEX_LENGTH = 64;

    /** Base64 length of a 128-bit MD5 digest (16 bytes, padded). */
    public static final int MD5_BASE64_LENGTH = 24;

    public HashedContent {
        Objects.requireNonNull(sha256Hex, "sha256Hex");
        Objects.requireNonNull(md5Base64, "md5Base64");
        if (sha256Hex.length() != SHA256_HEX_LENGTH) {
            throw new IllegalArgumentException(
                    "sha256Hex must be " + SHA256_HEX_LENGTH + " hex chars, got " + sha256Hex.length());
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must be >= 0");
        }
        if (md5Base64.length() != MD5_BASE64_LENGTH) {
            throw new IllegalArgumentException(
                    "md5Base64 must be " + MD5_BASE64_LENGTH + " chars, got " + md5Base64.length());
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
