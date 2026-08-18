package nl.gzmn.playerworlds.backend.storage;

import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.storage.ContentHasher;
import nl.gzmn.playerworlds.core.storage.StorageException;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

/**
 * Packs and unpacks dimension folders into single compressed tarballs (.tar.zst / .tar.gz)
 * and verifies SHA-256 checksums (FR-35, FR-36, plan Task 3).
 */
public final class ArchivePacker {

    private static final int BUFFER_SIZE = 65536;
    private static final int DEFAULT_ZSTD_LEVEL = 3;

    private ArchivePacker() {}

    /**
     * Outcome and summary statistics of an archive pack operation.
     *
     * @param sizeBytes compressed archive size in bytes
     * @param checksum SHA-256 hex digest of the compressed archive
     * @param uncompressedBytes total uncompressed payload bytes packed
     * @param fileCount total number of files included in the archive
     * @param format archive format identifier ("tar.zst" or "tar.gz")
     */
    public record PackResult(long sizeBytes, String checksum, long uncompressedBytes, int fileCount, String format) {

        public PackResult {
            Objects.requireNonNull(checksum, "checksum");
            Objects.requireNonNull(format, "format");
            if (sizeBytes < 0) {
                throw new IllegalArgumentException("sizeBytes must be >= 0: " + sizeBytes);
            }
            if (uncompressedBytes < 0) {
                throw new IllegalArgumentException("uncompressedBytes must be >= 0: " + uncompressedBytes);
            }
            if (fileCount < 0) {
                throw new IllegalArgumentException("fileCount must be >= 0: " + fileCount);
            }
        }
    }

    /**
     * Packs the specified dimension folders into a compressed tarball using default exclude globs.
     *
     * @param dimensionDirs list of root dimension directories to pack (e.g. overworld, nether, end)
     * @param targetArchiveFile destination path for the packed archive
     * @param useZstd whether to use Zstandard compression (true) or Gzip (false)
     * @return summary statistics of the packed archive
     * @throws StorageException if packing fails
     */
    public static PackResult pack(List<Path> dimensionDirs, Path targetArchiveFile, boolean useZstd) {
        return pack(dimensionDirs, targetArchiveFile, useZstd, NetworkPolicy.DEFAULT_EXCLUDE_GLOBS);
    }

    /**
     * Packs the specified dimension folders into a compressed tarball with custom exclude globs.
     *
     * @param dimensionDirs list of root dimension directories to pack
     * @param targetArchiveFile destination path for the packed archive
     * @param useZstd whether to use Zstandard compression (true) or Gzip (false)
     * @param excludeGlobs glob patterns or file names to exclude (e.g. session.lock, uid.dat)
     * @return summary statistics of the packed archive
     * @throws StorageException if packing fails
     */
    public static PackResult pack(
            List<Path> dimensionDirs, Path targetArchiveFile, boolean useZstd, List<String> excludeGlobs) {
        Objects.requireNonNull(dimensionDirs, "dimensionDirs");
        Objects.requireNonNull(targetArchiveFile, "targetArchiveFile");
        Objects.requireNonNull(excludeGlobs, "excludeGlobs");

        Path parent = targetArchiveFile.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new StorageException("Failed to create parent directory for archive: " + targetArchiveFile, e);
            }
        }

        MessageDigest sha256Digest;
        try {
            sha256Digest = MessageDigest.getInstance(ContentHasher.ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new StorageException("SHA-256 MessageDigest unavailable", e);
        }

        long totalUncompressedBytes = 0;
        int fileCount = 0;

        List<PathMatcher> matchers = excludeGlobs.stream()
                .map(glob -> {
                    try {
                        return FileSystems.getDefault().getPathMatcher("glob:" + glob);
                    } catch (Exception ignored) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();

        try (OutputStream fos = Files.newOutputStream(targetArchiveFile);
                BufferedOutputStream bos = new BufferedOutputStream(fos, BUFFER_SIZE);
                DigestOutputStream dos = new DigestOutputStream(bos, sha256Digest);
                OutputStream compressionStream =
                        useZstd ? new ZstdOutputStream(dos, DEFAULT_ZSTD_LEVEL) : new GZIPOutputStream(dos);
                TarArchiveOutputStream tarOut = new TarArchiveOutputStream(compressionStream)) {

            tarOut.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            tarOut.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);

            for (Path dimDir : dimensionDirs) {
                if (!Files.isDirectory(dimDir)) {
                    continue;
                }
                String dimFolderName = dimDir.getFileName().toString();
                try (Stream<Path> walk = Files.walk(dimDir)) {
                    List<Path> filesToPack = walk.filter(Files::isRegularFile)
                            .filter(path -> {
                                Path fileName = path.getFileName();
                                return fileName != null && !isExcluded(fileName, excludeGlobs, matchers);
                            })
                            .sorted(Comparator.naturalOrder())
                            .toList();

                    for (Path file : filesToPack) {
                        Path rel = dimDir.relativize(file);
                        String tarEntryName =
                                dimFolderName + "/" + rel.toString().replace('\\', '/');
                        long size = Files.size(file);
                        TarArchiveEntry entry = new TarArchiveEntry(tarEntryName);
                        entry.setSize(size);
                        try {
                            entry.setLastModifiedTime(Files.getLastModifiedTime(file));
                        } catch (IOException ignored) {
                            // Fallback to entry creation time
                        }

                        tarOut.putArchiveEntry(entry);
                        Files.copy(file, tarOut);
                        tarOut.closeArchiveEntry();

                        totalUncompressedBytes += size;
                        fileCount++;
                    }
                }
            }
            tarOut.finish();
        } catch (IOException e) {
            throw new StorageException("Failed to pack dimensions into archive: " + targetArchiveFile, e);
        }

        String checksum = HexFormat.of().formatHex(sha256Digest.digest()).toLowerCase(Locale.ROOT);
        long compressedSize;
        try {
            compressedSize = Files.size(targetArchiveFile);
        } catch (IOException e) {
            throw new StorageException("Failed to determine size of packed archive: " + targetArchiveFile, e);
        }

        String format = useZstd ? "tar.zst" : "tar.gz";
        return new PackResult(compressedSize, checksum, totalUncompressedBytes, fileCount, format);
    }

    /**
     * Unpacks a compressed tarball into the target extraction directory with Zip Slip traversal protection.
     *
     * @param archiveFile archive file path to unpack (.tar.zst or .tar.gz)
     * @param targetExtractionDir clean destination directory to extract files into
     * @throws StorageException if unpacking fails or contains an invalid path traversal entry
     */
    public static void unpack(Path archiveFile, Path targetExtractionDir) {
        Objects.requireNonNull(archiveFile, "archiveFile");
        Objects.requireNonNull(targetExtractionDir, "targetExtractionDir");

        Path normalizedTarget = targetExtractionDir.toAbsolutePath().normalize();
        try {
            Files.createDirectories(normalizedTarget);
        } catch (IOException e) {
            throw new StorageException("Failed to create target extraction directory: " + targetExtractionDir, e);
        }

        try (InputStream fis = Files.newInputStream(archiveFile);
                BufferedInputStream bis = new BufferedInputStream(fis, BUFFER_SIZE);
                InputStream decompressor = createDecompressor(bis, archiveFile.toString());
                TarArchiveInputStream tarIn = new TarArchiveInputStream(decompressor)) {

            TarArchiveEntry entry;
            while ((entry = tarIn.getNextEntry()) != null) {
                String entryName = entry.getName();
                Path destination = normalizedTarget.resolve(entryName).normalize();
                if (!destination.startsWith(normalizedTarget)) {
                    throw new StorageException("Tar entry escapes target extraction directory: " + entryName);
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(destination);
                    continue;
                }

                Path parent = destination.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }

                Files.copy(tarIn, destination, StandardCopyOption.REPLACE_EXISTING);
                try {
                    FileTime mtime = entry.getLastModifiedTime();
                    if (mtime != null) {
                        Files.setLastModifiedTime(destination, mtime);
                    }
                } catch (Exception ignored) {
                    // Non-fatal if setting mtime fails
                }
            }
        } catch (IOException e) {
            throw new StorageException("Failed to unpack archive: " + archiveFile, e);
        }
    }

    /**
     * Computes the SHA-256 hex digest of a file.
     *
     * @param file path to the file
     * @return lowercase 64-character hex string
     * @throws StorageException if reading the file fails
     */
    public static String computeSha256(Path file) {
        Objects.requireNonNull(file, "file");
        return ContentHasher.hash(file).sha256Hex();
    }

    /**
     * Verifies that a file's SHA-256 hex digest matches the expected checksum.
     *
     * @param file path to the file
     * @param expectedSha256 expected SHA-256 hex digest
     * @return {@code true} if checksum matches, {@code false} otherwise
     */
    public static boolean verifyChecksum(Path file, String expectedSha256) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(expectedSha256, "expectedSha256");
        try {
            String actual = computeSha256(file);
            return actual.equalsIgnoreCase(expectedSha256.trim());
        } catch (StorageException e) {
            return false;
        }
    }

    private static InputStream createDecompressor(BufferedInputStream bis, String fileName) throws IOException {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".zst")) {
            return new ZstdInputStream(bis);
        }
        if (lower.endsWith(".gz") || lower.endsWith(".tgz")) {
            return new GZIPInputStream(bis);
        }
        // Fallback: check magic header bytes
        bis.mark(4);
        byte[] magic = new byte[4];
        int read = bis.read(magic, 0, 4);
        bis.reset();

        if (read >= 4
                && magic[0] == (byte) 0x28
                && magic[1] == (byte) 0xB5
                && magic[2] == (byte) 0x2F
                && magic[3] == (byte) 0xFD) {
            return new ZstdInputStream(bis);
        }
        if (read >= 2 && magic[0] == (byte) 0x1F && magic[1] == (byte) 0x8B) {
            return new GZIPInputStream(bis);
        }

        return bis;
    }

    private static boolean isExcluded(
            Path fileName, List<String> excludeGlobs, List<java.nio.file.PathMatcher> matchers) {
        String nameStr = fileName.toString();
        String lower = nameStr.toLowerCase(Locale.ROOT);
        for (String glob : excludeGlobs) {
            if (glob.equalsIgnoreCase(nameStr) || glob.equalsIgnoreCase(lower)) {
                return true;
            }
        }
        for (java.nio.file.PathMatcher matcher : matchers) {
            if (matcher.matches(fileName) || matcher.matches(Path.of(lower))) {
                return true;
            }
        }
        return false;
    }
}
