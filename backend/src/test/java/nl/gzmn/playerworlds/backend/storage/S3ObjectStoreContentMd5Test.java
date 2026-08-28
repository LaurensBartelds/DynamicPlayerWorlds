package nl.gzmn.playerworlds.backend.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import nl.gzmn.playerworlds.core.storage.ContentHasher;
import nl.gzmn.playerworlds.core.storage.HashedContent;
import nl.gzmn.playerworlds.core.storage.ObjectStore;
import nl.gzmn.playerworlds.core.storage.S3ObjectStore;
import nl.gzmn.playerworlds.core.storage.StorageException;
import nl.gzmn.playerworlds.testing.TestObjectStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code Content-MD5} upload verification against real MinIO (CONTRIBUTING rule 8).
 *
 * <p>Lives under {@code :backend}, not {@code :core}, for the same reason as
 * {@link ObjectStoreHealthCheckTest}: {@code :core} has no S3 container of its own.
 */
class S3ObjectStoreContentMd5Test {

    @TempDir
    Path temp;

    private ObjectStore store;

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    @Test
    @DisplayName("putObject with the correct Content-MD5 uploads normally")
    void putObjectWithCorrectMd5Succeeds() throws Exception {
        store = S3ObjectStore.open(TestObjectStore.settingsForNewBucket());
        Path file = temp.resolve("region.bin");
        byte[] payload = "well-formed region bytes".getBytes(StandardCharsets.UTF_8);
        Files.write(file, payload);
        HashedContent hashed = ContentHasher.hashBytes(payload);

        store.putObject("data/region.bin", file, hashed.md5Base64());

        assertThat(store.getBytes("data/region.bin")).isEqualTo(payload);
    }

    @Test
    @DisplayName("putObject with a mismatched Content-MD5 is rejected by the server, not silently accepted")
    void putObjectWithWrongMd5IsRejected() throws Exception {
        store = S3ObjectStore.open(TestObjectStore.settingsForNewBucket());
        Path file = temp.resolve("region.bin");
        Files.write(file, "these are not the bytes the header claims".getBytes(StandardCharsets.UTF_8));
        String wrongMd5 = Base64.getEncoder().encodeToString(new byte[16]); // valid shape, wrong digest

        assertThatThrownBy(() -> store.putObject("data/region.bin", file, wrongMd5))
                .isInstanceOf(StorageException.class);
        assertThat(store.exists("data/region.bin"))
                .as("a rejected upload must not leave a corrupted object behind")
                .isFalse();
    }

    @Test
    @DisplayName("putBytes with the correct Content-MD5 uploads normally")
    void putBytesWithCorrectMd5Succeeds() {
        store = S3ObjectStore.open(TestObjectStore.settingsForNewBucket());
        byte[] payload = "manifest json bytes".getBytes(StandardCharsets.UTF_8);
        HashedContent hashed = ContentHasher.hashBytes(payload);

        store.putBytes("manifest/1-1.json", payload, "application/json", hashed.md5Base64());

        assertThat(store.getBytes("manifest/1-1.json")).isEqualTo(payload);
    }

    @Test
    @DisplayName("putBytes with a mismatched Content-MD5 is rejected by the server")
    void putBytesWithWrongMd5IsRejected() {
        store = S3ObjectStore.open(TestObjectStore.settingsForNewBucket());
        byte[] payload = "manifest json bytes".getBytes(StandardCharsets.UTF_8);
        String wrongMd5 = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> store.putBytes("manifest/1-1.json", payload, "application/json", wrongMd5))
                .isInstanceOf(StorageException.class);
        assertThat(store.exists("manifest/1-1.json")).isFalse();
    }

    @Test
    @DisplayName("putObject without an expected Content-MD5 still uploads, unverified (backward compatible)")
    void putObjectWithoutMd5SkipsVerification() throws Exception {
        store = S3ObjectStore.open(TestObjectStore.settingsForNewBucket());
        Path file = temp.resolve("region.bin");
        byte[] payload = "no checksum supplied".getBytes(StandardCharsets.UTF_8);
        Files.write(file, payload);

        store.putObject("data/region.bin", file);

        assertThat(store.getBytes("data/region.bin")).isEqualTo(payload);
    }
}
