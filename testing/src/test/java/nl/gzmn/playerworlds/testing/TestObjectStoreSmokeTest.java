package nl.gzmn.playerworlds.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import nl.gzmn.playerworlds.core.config.StorageClientSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Object-storage-layer smoke through the shared {@link TestObjectStore} factory
 * (plan section 11, NFR-8).
 *
 * <p>A real put/get against MinIO is the whole point: mocks do not reproduce
 * path-style addressing, bucket creation or eventual consistency quirks the
 * storage engine will hit.
 */
class TestObjectStoreSmokeTest {

    @Test
    @DisplayName("settingsForNewBucket creates a bucket and round-trips an object")
    void settingsForNewBucketRoundTripsAnObject() {
        StorageClientSettings settings = TestObjectStore.settingsForNewBucket();

        try (S3Client client = TestObjectStore.openClient(settings)) {
            assertThat(TestObjectStore.bucketExists(client, settings.bucket())).isTrue();

            byte[] payload = "gzmn-worlds-object-store-smoke\n".getBytes(StandardCharsets.UTF_8);
            String key = "smoke/hello.txt";

            client.putObject(
                    PutObjectRequest.builder()
                            .bucket(settings.bucket())
                            .key(key)
                            .build(),
                    RequestBody.fromBytes(payload));

            byte[] read = client.getObject(
                            GetObjectRequest.builder()
                                    .bucket(settings.bucket())
                                    .key(key)
                                    .build(),
                            ResponseTransformer.toBytes())
                    .asByteArray();

            assertThat(read).isEqualTo(payload);
        }
    }

    @Test
    @DisplayName("two settingsForNewBucket calls get distinct buckets")
    void twoCallsGetDistinctBuckets() {
        StorageClientSettings first = TestObjectStore.settingsForNewBucket();
        StorageClientSettings second = TestObjectStore.settingsForNewBucket();

        assertThat(first.bucket()).isNotEqualTo(second.bucket());
        assertThat(first.endpoint()).isEqualTo(second.endpoint());
    }
}
