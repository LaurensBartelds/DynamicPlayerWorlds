package nl.gzmn.playerworlds.core.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/**
 * SHA-256 content addressing with optional fused region-structure validation
 * (MN-2, MN-5c, plan §9.1 step 7).
 *
 * <p>Validation shares the read that hashing already requires: the file is loaded
 * once, hashed, and — when {@code verifyRegionStructure} is on and the name is
 * a region file — checked by {@link RegionStructure} against the same bytes. A
 * failure aborts before any upload.
 *
 * <p>{@code storage.verify-region-structure} is a kill switch, not a tuning knob.
 * Default is on.
 */
public final class ContentHasher {

    public static final String ALGORITHM = "SHA-256";

    private ContentHasher() {}

    /**
     * Hashes {@code path}. When {@code verifyRegionStructure} is true and the
     * file name ends in {@code .mca}/{@code .mcr}, also runs MN-5c validation.
     *
     * @throws RegionStructureException if region validation fails
     * @throws StorageException on IO or digest failure
     */
    public static HashedContent hashAndValidate(Path path, boolean verifyRegionStructure) {
        Objects.requireNonNull(path, "path");
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new StorageException("could not read " + path + " for hashing", e);
        }
        if (verifyRegionStructure && RegionStructure.isRegionFileName(path)) {
            RegionStructure.validate(bytes, path.toString());
        }
        return hashBytes(bytes);
    }

    /** Hashes {@code path} without region validation. */
    public static HashedContent hash(Path path) {
        return hashAndValidate(path, false);
    }

    /** Hashes an in-memory buffer (tests and callers that already hold the bytes). */
    public static HashedContent hashBytes(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        MessageDigest digest = newDigest();
        digest.update(bytes);
        String hex = HexFormat.of().formatHex(digest.digest()).toLowerCase(Locale.ROOT);
        return new HashedContent(hex, bytes.length);
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required by the Java platform specification.
            throw new StorageException("SHA-256 MessageDigest unavailable", e);
        }
    }
}
