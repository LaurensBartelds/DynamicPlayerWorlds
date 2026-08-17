package nl.gzmn.playerworlds.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import nl.gzmn.playerworlds.core.config.StorageClientSettings;
import nl.gzmn.playerworlds.core.storage.S3ObjectStore;
import nl.gzmn.playerworlds.core.storage.StorageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.s3.S3Client;

class S3ObjectStoreTest {

    @Test
    @DisplayName("putBytes, getBytes and exists round-trip against MinIO")
    void putAndGetBytesSuccessfully() {
        StorageClientSettings settings = TestObjectStore.settingsForNewBucket();
        try (S3ObjectStore store = S3ObjectStore.open(settings)) {
            String key = "test/bytes.txt";
            byte[] content = "hello-gzmn-bytes".getBytes(StandardCharsets.UTF_8);

            assertThat(store.exists(key)).isFalse();

            store.putBytes(key, content, "text/plain");

            assertThat(store.exists(key)).isTrue();
            assertThat(store.exists("test/non-existent")).isFalse();

            byte[] fetched = store.getBytes(key);
            assertThat(fetched).isEqualTo(content);
        }
    }

    @Test
    @DisplayName("putBytes with null contentType defaults to binary octet stream")
    void putBytesWithNullContentType() {
        StorageClientSettings settings = TestObjectStore.settingsForNewBucket();
        try (S3ObjectStore store = S3ObjectStore.open(settings)) {
            String key = "test/raw.bin";
            byte[] content = new byte[] {0x10, 0x20, 0x30};

            store.putBytes(key, content, null);

            assertThat(store.exists(key)).isTrue();
            assertThat(store.getBytes(key)).isEqualTo(content);
        }
    }

    @Test
    @DisplayName("putObject and getObject round-trip files atomically against MinIO")
    void putAndGetObjectSuccessfully(@TempDir Path tempDir) throws Exception {
        StorageClientSettings settings = TestObjectStore.settingsForNewBucket();
        try (S3ObjectStore store = S3ObjectStore.open(settings)) {
            String key = "test/file.bin";
            byte[] content = new byte[] {1, 2, 3, 4, 5, 42, 100};

            Path source = tempDir.resolve("source.bin");
            Files.write(source, content);

            store.putObject(key, source);

            assertThat(store.exists(key)).isTrue();

            Path nestedDest = tempDir.resolve("nested/dir/downloaded.bin");
            store.getObject(key, nestedDest);

            assertThat(Files.exists(nestedDest)).isTrue();
            assertThat(Files.readAllBytes(nestedDest)).isEqualTo(content);
        }
    }

    @Test
    @DisplayName("getBytes and getObject throw StorageException for non-existent keys")
    void nonExistentKeyThrowsStorageException(@TempDir Path tempDir) {
        StorageClientSettings settings = TestObjectStore.settingsForNewBucket();
        try (S3ObjectStore store = S3ObjectStore.open(settings)) {
            assertThatThrownBy(() -> store.getBytes("missing/key.txt")).isInstanceOf(StorageException.class);

            Path dest = tempDir.resolve("missing.txt");
            assertThatThrownBy(() -> store.getObject("missing/key.txt", dest)).isInstanceOf(StorageException.class);
        }
    }

    @Test
    @DisplayName("constructor and close properly manage client lifecycle")
    void customClientLifecycle() {
        StorageClientSettings settings = TestObjectStore.settingsForNewBucket();
        S3Client client = TestObjectStore.openClient(settings);
        S3ObjectStore store = new S3ObjectStore(client, settings.bucket());
        store.putBytes("test/lifecycle.txt", new byte[] {1, 2}, "text/plain");
        assertThat(store.exists("test/lifecycle.txt")).isTrue();
        store.close();
    }
}
