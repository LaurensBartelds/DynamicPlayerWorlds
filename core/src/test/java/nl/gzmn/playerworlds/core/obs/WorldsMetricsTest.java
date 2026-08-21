package nl.gzmn.playerworlds.core.obs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WorldsMetricsTest {

    @Test
    @DisplayName("scrape exposes the section 10.2 meter names in Prometheus form")
    void scrapeContainsMinimumSet() {
        try (WorldsMetrics metrics = WorldsMetrics.create()) {
            metrics.setWorldsLoaded(2);
            metrics.leaseAcquireOk();
            metrics.leaseLost(MetricNames.REASON_FENCED);
            metrics.fenceEvent();
            metrics.commitSucceeded(Duration.ofMillis(40));
            metrics.commitFailed(MetricNames.REASON_STORAGE);
            metrics.syncUploaded(1024, 3);
            metrics.worldLoadCold(Duration.ofSeconds(2));
            metrics.createStall(Duration.ofMillis(15));
            metrics.holdingTimeout();
            metrics.setQuarantineBytes(99);
            metrics.setScratchFreeBytes(1_000_000);
            metrics.dbPoolWait(Duration.ofMillis(5));
            metrics.setObjectStorageUp(false);

            String text = metrics.scrape();
            assertThat(text)
                    .contains("worlds_loaded")
                    .contains("lease_acquire_total")
                    .contains("lease_lost_total")
                    .contains("fence_events_total")
                    .contains("commit_duration_seconds")
                    .contains("commit_failed_total")
                    .contains("sync_bytes_total")
                    .contains("sync_files_total")
                    .contains("world_load_seconds")
                    .contains("create_stall")
                    .contains("holding_timeouts_total")
                    .contains("quarantine_bytes")
                    .contains("scratch_free_bytes")
                    .contains("db_pool_wait_seconds")
                    .contains("object_storage_up");
            assertThat(text)
                    .contains("result=\"ok\"")
                    .contains("reason=\"fenced\"")
                    .contains("kind=\"cold\"");
        }
    }

    @Test
    @DisplayName("object storage defaults to up until a check actually fails")
    void objectStorageDefaultsToUpUntilAFailure() {
        try (WorldsMetrics metrics = WorldsMetrics.create()) {
            assertThat(metrics.objectStorageUp())
                    .as("a node that has not run its first check yet is not a known outage")
                    .isTrue();

            metrics.setObjectStorageUp(false);
            assertThat(metrics.objectStorageUp()).isFalse();

            metrics.setObjectStorageUp(true);
            assertThat(metrics.objectStorageUp()).isTrue();
        }
    }
}
