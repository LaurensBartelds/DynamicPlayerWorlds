package nl.gzmn.playerworlds.backend.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import nl.gzmn.playerworlds.core.config.StorageClientSettings;
import nl.gzmn.playerworlds.core.storage.S3ObjectStore;
import nl.gzmn.playerworlds.testing.TestObjectStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchiveStorageTest {

    @TempDir
    Path tempDir;

    @Nested
    @DisplayName("Filesystem Backend")
    class FilesystemBackendTests {

        @Test
        @DisplayName("uploads, downloads, checks existence, and deletes archive on local filesystem")
        void filesystemUploadDownloadDelete() throws IOException {
            Path localRoot = tempDir.resolve("local_archives");
            ArchiveStorage storage = ArchiveStorage.filesystem(localRoot);

            assertThat(storage.isS3()).isFalse();

            Path sourceArchive = tempDir.resolve("sample.tar.zst");
            Files.writeString(sourceArchive, "test-archive-payload-bytes", StandardCharsets.UTF_8);

            String key = "worlds/pw-1234/archive/pw-1234-20260818.tar.zst";

            assertThat(storage.exists(key)).isFalse();

            // Upload
            storage.uploadArchive(key, sourceArchive);
            assertThat(storage.exists(key)).isTrue();
            assertThat(storage.getArchiveSize(key)).isEqualTo(Files.size(sourceArchive));

            // Download
            Path downloadedFile = tempDir.resolve("downloaded.tar.zst");
            storage.downloadArchive(key, downloadedFile);
            assertThat(Files.readString(downloadedFile, StandardCharsets.UTF_8))
                    .isEqualTo("test-archive-payload-bytes");

            // Delete
            storage.deleteArchive(key);
            assertThat(storage.exists(key)).isFalse();
        }

        @Test
        @DisplayName("deletes prefix recursively on local filesystem")
        void filesystemDeletePrefix() throws IOException {
            Path localRoot = tempDir.resolve("local_archives_prefix");
            ArchiveStorage storage = ArchiveStorage.filesystem(localRoot);

            Path sourceFile = tempDir.resolve("test.bin");
            Files.writeString(sourceFile, "test", StandardCharsets.UTF_8);

            storage.uploadArchive("worlds/pw-1/archive/a1.tar.zst", sourceFile);
            storage.uploadArchive("worlds/pw-1/archive/a2.tar.zst", sourceFile);
            storage.uploadArchive("worlds/pw-2/archive/b1.tar.zst", sourceFile);

            assertThat(storage.exists("worlds/pw-1/archive/a1.tar.zst")).isTrue();
            assertThat(storage.exists("worlds/pw-1/archive/a2.tar.zst")).isTrue();
            assertThat(storage.exists("worlds/pw-2/archive/b1.tar.zst")).isTrue();

            storage.deletePrefix("worlds/pw-1/");

            assertThat(storage.exists("worlds/pw-1/archive/a1.tar.zst")).isFalse();
            assertThat(storage.exists("worlds/pw-1/archive/a2.tar.zst")).isFalse();
            assertThat(storage.exists("worlds/pw-2/archive/b1.tar.zst")).isTrue();
        }
    }

    @Nested
    @DisplayName("S3 Object Storage Backend")
    class S3BackendTests {

        @Test
        @DisplayName("uploads, downloads, checks existence, and deletes archive on S3")
        void s3UploadDownloadDelete() throws IOException {
            StorageClientSettings settings = TestObjectStore.settingsForNewBucket();
            try (S3ObjectStore s3Store = S3ObjectStore.open(settings)) {
                ArchiveStorage storage = ArchiveStorage.s3(s3Store);
                assertThat(storage.isS3()).isTrue();

                Path sourceArchive = tempDir.resolve("sample_s3.tar.zst");
                Files.writeString(sourceArchive, "s3-test-archive-payload-data", StandardCharsets.UTF_8);

                String key = "worlds/pw-s3-1/archive/backup.tar.zst";

                assertThat(storage.exists(key)).isFalse();

                // Upload
                storage.uploadArchive(key, sourceArchive);
                assertThat(storage.exists(key)).isTrue();
                assertThat(storage.getArchiveSize(key)).isEqualTo(Files.size(sourceArchive));

                // Download
                Path downloaded = tempDir.resolve("downloaded_s3.tar.zst");
                storage.downloadArchive(key, downloaded);
                assertThat(Files.readString(downloaded, StandardCharsets.UTF_8))
                        .isEqualTo("s3-test-archive-payload-data");

                // Delete
                storage.deleteArchive(key);
                assertThat(storage.exists(key)).isFalse();
            }
        }

        @Test
        @DisplayName("deletes prefix recursively on S3")
        void s3DeletePrefix() throws IOException {
            StorageClientSettings settings = TestObjectStore.settingsForNewBucket();
            try (S3ObjectStore s3Store = S3ObjectStore.open(settings)) {
                ArchiveStorage storage = ArchiveStorage.s3(s3Store);

                Path source = tempDir.resolve("dummy.bin");
                Files.writeString(source, "dummy-data", StandardCharsets.UTF_8);

                storage.uploadArchive("worlds/pw-del/archive/1.tar.zst", source);
                storage.uploadArchive("worlds/pw-del/archive/2.tar.zst", source);
                storage.uploadArchive("worlds/pw-keep/archive/3.tar.zst", source);

                assertThat(storage.exists("worlds/pw-del/archive/1.tar.zst")).isTrue();
                assertThat(storage.exists("worlds/pw-del/archive/2.tar.zst")).isTrue();
                assertThat(storage.exists("worlds/pw-keep/archive/3.tar.zst")).isTrue();

                storage.deletePrefix("worlds/pw-del/");

                assertThat(storage.exists("worlds/pw-del/archive/1.tar.zst")).isFalse();
                assertThat(storage.exists("worlds/pw-del/archive/2.tar.zst")).isFalse();
                assertThat(storage.exists("worlds/pw-keep/archive/3.tar.zst")).isTrue();
            }
        }
    }

    @Test
    @DisplayName("rejects null configurations")
    void rejectsInvalidConstructors() {
        assertThatThrownBy(() -> new ArchiveStorage(null, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
