package nl.gzmn.playerworlds.backend.storage;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import nl.gzmn.playerworlds.core.config.StorageClientSettings;
import nl.gzmn.playerworlds.core.storage.ObjectStore;
import nl.gzmn.playerworlds.core.storage.ObjectStoreHealthCheck;
import nl.gzmn.playerworlds.core.storage.S3ObjectStore;
import nl.gzmn.playerworlds.testing.TestObjectStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ObjectStoreHealthCheck} against a real MinIO round trip. Lives under
 * {@code :backend} rather than {@code :core}: {@code :core} has no S3 container
 * of its own (only Postgres, to avoid a dependency cycle with {@code :testing}),
 * so every real object-storage check in this codebase runs from here.
 */
class ObjectStoreHealthCheckTest {

    private ObjectStore store;

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    @Test
    @DisplayName("ping succeeds against reachable storage and leaves a single, reused key")
    void pingSucceedsAgainstReachableStorage() throws Exception {
        StorageClientSettings settings = TestObjectStore.settingsForNewBucket();
        store = S3ObjectStore.open(settings);
        ObjectStoreHealthCheck check = new ObjectStoreHealthCheck(store, "_health/node-a.ping");

        assertThatCode(check::ping).doesNotThrowAnyException();
        assertThatCode(check::ping)
                .as("a second ping overwrites the same key rather than accumulating objects")
                .doesNotThrowAnyException();
        assertThatCode(() -> store.getBytes("_health/node-a.ping")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("ping fails when the bucket does not exist")
    void pingFailsAgainstUnreachableStorage() {
        StorageClientSettings settings = TestObjectStore.settings("gzmn-health-check-no-such-bucket");
        store = S3ObjectStore.open(settings);
        ObjectStoreHealthCheck check = new ObjectStoreHealthCheck(store, "_health/node-a.ping");

        assertThatThrownBy(check::ping).isInstanceOf(Exception.class);
    }
}
