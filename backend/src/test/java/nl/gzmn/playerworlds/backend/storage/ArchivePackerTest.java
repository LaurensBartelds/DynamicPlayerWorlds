package nl.gzmn.playerworlds.backend.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import nl.gzmn.playerworlds.core.storage.StorageException;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchivePackerTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("packs and unpacks .tar.zst world dimensions preserving folder hierarchy and checksum")
    void packAndUnpackZstd_PreservesFilesAndStructure() throws IOException {
        Path overworld = tempDir.resolve("world");
        Path nether = tempDir.resolve("world_nether");
        Path end = tempDir.resolve("world_the_end");

        Files.createDirectories(overworld.resolve("region"));
        Files.createDirectories(nether.resolve("DIM-1/region"));
        Files.createDirectories(end.resolve("DIM1/region"));

        Files.writeString(overworld.resolve("level.dat"), "level-data-sample", StandardCharsets.UTF_8);
        Files.writeString(overworld.resolve("region/r.0.0.mca"), "region-chunk-data", StandardCharsets.UTF_8);
        Files.writeString(nether.resolve("DIM-1/region/r.0.0.mca"), "nether-chunk-data", StandardCharsets.UTF_8);
        Files.writeString(end.resolve("DIM1/region/r.0.0.mca"), "end-chunk-data", StandardCharsets.UTF_8);

        Path archiveFile = tempDir.resolve("archives/world.tar.zst");

        ArchivePacker.PackResult result = ArchivePacker.pack(List.of(overworld, nether, end), archiveFile, true);

        assertThat(Files.exists(archiveFile)).isTrue();
        assertThat(result.sizeBytes()).isEqualTo(Files.size(archiveFile));
        assertThat(result.fileCount()).isEqualTo(4);
        assertThat(result.uncompressedBytes()).isGreaterThan(0);
        assertThat(result.format()).isEqualTo("tar.zst");
        assertThat(result.checksum()).hasSize(64);

        // Verify computed SHA-256 matches pack result
        String computedHash = ArchivePacker.computeSha256(archiveFile);
        assertThat(computedHash).isEqualTo(result.checksum());
        assertThat(ArchivePacker.verifyChecksum(archiveFile, result.checksum())).isTrue();
        assertThat(ArchivePacker.verifyChecksum(archiveFile, "0123456789abcdef"))
                .isFalse();

        // Unpack into clean target directory
        Path extractionDir = tempDir.resolve("extracted");
        ArchivePacker.unpack(archiveFile, extractionDir);

        assertThat(Files.readString(extractionDir.resolve("world/level.dat"), StandardCharsets.UTF_8))
                .isEqualTo("level-data-sample");
        assertThat(Files.readString(extractionDir.resolve("world/region/r.0.0.mca"), StandardCharsets.UTF_8))
                .isEqualTo("region-chunk-data");
        assertThat(Files.readString(
                        extractionDir.resolve("world_nether/DIM-1/region/r.0.0.mca"), StandardCharsets.UTF_8))
                .isEqualTo("nether-chunk-data");
        assertThat(Files.readString(
                        extractionDir.resolve("world_the_end/DIM1/region/r.0.0.mca"), StandardCharsets.UTF_8))
                .isEqualTo("end-chunk-data");
    }

    @Test
    @DisplayName("packs and unpacks .tar.gz format when useZstd is false")
    void packAndUnpackGzip_PreservesFilesAndStructure() throws IOException {
        Path overworld = tempDir.resolve("world_gz");
        Files.createDirectories(overworld.resolve("region"));
        Files.writeString(overworld.resolve("level.dat"), "gzip-level-data", StandardCharsets.UTF_8);
        Files.writeString(overworld.resolve("region/r.0.0.mca"), "gzip-region-data", StandardCharsets.UTF_8);

        Path archiveFile = tempDir.resolve("archives/world.tar.gz");

        ArchivePacker.PackResult result = ArchivePacker.pack(List.of(overworld), archiveFile, false);

        assertThat(result.format()).isEqualTo("tar.gz");
        assertThat(result.fileCount()).isEqualTo(2);

        Path extractionDir = tempDir.resolve("extracted_gz");
        ArchivePacker.unpack(archiveFile, extractionDir);

        assertThat(Files.readString(extractionDir.resolve("world_gz/level.dat"), StandardCharsets.UTF_8))
                .isEqualTo("gzip-level-data");
        assertThat(Files.readString(extractionDir.resolve("world_gz/region/r.0.0.mca"), StandardCharsets.UTF_8))
                .isEqualTo("gzip-region-data");
    }

    @Test
    @DisplayName("excludes default exclude globs (session.lock, uid.dat)")
    void packExcludesDefaultExcludeGlobs() throws IOException {
        Path overworld = tempDir.resolve("world_ex");
        Files.createDirectories(overworld.resolve("region"));
        Files.writeString(overworld.resolve("level.dat"), "level", StandardCharsets.UTF_8);
        Files.writeString(overworld.resolve("session.lock"), "lock", StandardCharsets.UTF_8);
        Files.writeString(overworld.resolve("uid.dat"), "uid", StandardCharsets.UTF_8);

        Path archiveFile = tempDir.resolve("archives/world_ex.tar.zst");
        ArchivePacker.PackResult result = ArchivePacker.pack(List.of(overworld), archiveFile, true);

        assertThat(result.fileCount()).isEqualTo(1);

        Path extractionDir = tempDir.resolve("extracted_ex");
        ArchivePacker.unpack(archiveFile, extractionDir);

        assertThat(Files.exists(extractionDir.resolve("world_ex/level.dat"))).isTrue();
        assertThat(Files.exists(extractionDir.resolve("world_ex/session.lock"))).isFalse();
        assertThat(Files.exists(extractionDir.resolve("world_ex/uid.dat"))).isFalse();
    }

    @Test
    @DisplayName("excludes custom globs matching patterns")
    void packExcludesCustomExcludeGlobs() throws IOException {
        Path overworld = tempDir.resolve("world_custom");
        Files.createDirectories(overworld.resolve("region"));
        Files.writeString(overworld.resolve("level.dat"), "level", StandardCharsets.UTF_8);
        Files.writeString(overworld.resolve("test.tmp"), "tmp", StandardCharsets.UTF_8);
        Files.writeString(overworld.resolve("backup.bak"), "bak", StandardCharsets.UTF_8);

        Path archiveFile = tempDir.resolve("archives/world_custom.tar.zst");
        ArchivePacker.PackResult result =
                ArchivePacker.pack(List.of(overworld), archiveFile, true, List.of("*.tmp", "*.bak"));

        assertThat(result.fileCount()).isEqualTo(1);

        Path extractionDir = tempDir.resolve("extracted_custom");
        ArchivePacker.unpack(archiveFile, extractionDir);

        assertThat(Files.exists(extractionDir.resolve("world_custom/level.dat")))
                .isTrue();
        assertThat(Files.exists(extractionDir.resolve("world_custom/test.tmp"))).isFalse();
        assertThat(Files.exists(extractionDir.resolve("world_custom/backup.bak")))
                .isFalse();
    }

    @Test
    @DisplayName("rejects tar entries that escape the target extraction directory (Zip Slip)")
    void unpackRejectsZipSlipPathTraversal() throws IOException {
        Path maliciousArchive = tempDir.resolve("malicious.tar.gz");

        try (OutputStream fos = Files.newOutputStream(maliciousArchive);
                BufferedOutputStream bos = new BufferedOutputStream(fos);
                GZIPOutputStream gzos = new GZIPOutputStream(bos);
                TarArchiveOutputStream tarOut = new TarArchiveOutputStream(gzos)) {

            TarArchiveEntry maliciousEntry = new TarArchiveEntry("../escaped.txt");
            byte[] data = "escaped-payload".getBytes(StandardCharsets.UTF_8);
            maliciousEntry.setSize(data.length);
            tarOut.putArchiveEntry(maliciousEntry);
            tarOut.write(data);
            tarOut.closeArchiveEntry();
        }

        Path targetDir = tempDir.resolve("target_sandbox");
        Files.createDirectories(targetDir);

        assertThatThrownBy(() -> ArchivePacker.unpack(maliciousArchive, targetDir))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("escapes target extraction directory");

        assertThat(Files.exists(tempDir.resolve("escaped.txt"))).isFalse();
    }

    @Test
    @DisplayName("handles non-existent dimension directories gracefully")
    void handlesMissingDimensionsGracefully() throws IOException {
        Path overworld = tempDir.resolve("world_single");
        Path nonExistentNether = tempDir.resolve("world_single_nether");
        Files.createDirectories(overworld);
        Files.writeString(overworld.resolve("level.dat"), "level", StandardCharsets.UTF_8);

        Path archiveFile = tempDir.resolve("archives/single.tar.zst");
        ArchivePacker.PackResult result = ArchivePacker.pack(List.of(overworld, nonExistentNether), archiveFile, true);

        assertThat(result.fileCount()).isEqualTo(1);
    }
}
