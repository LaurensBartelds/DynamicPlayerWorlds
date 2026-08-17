package nl.gzmn.playerworlds.core.concurrent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PluginExecutorsTest {

    @Test
    @DisplayName("create opens db, io and sched pools at the requested sizes")
    void createOpensPools() throws Exception {
        try (PluginExecutors executors = PluginExecutors.create(3, 2, Runnable::run)) {
            assertThat(executors.dbThreads()).isEqualTo(3);
            assertThat(executors.ioThreads()).isEqualTo(2);

            AtomicReference<String> dbName = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            executors.db().execute(() -> {
                dbName.set(Thread.currentThread().getName());
                done.countDown();
            });
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(dbName.get()).startsWith("gzmn-db-");
        }
    }

    @Test
    @DisplayName("main executor delegates to the platform MainScheduler")
    void mainDelegatesToScheduler() {
        AtomicBoolean ran = new AtomicBoolean(false);
        try (PluginExecutors executors = PluginExecutors.create(1, 1, task -> {
            ran.set(true);
            task.run();
        })) {
            AtomicBoolean taskRan = new AtomicBoolean(false);
            executors.main().execute(() -> taskRan.set(true));
            assertThat(ran.get()).isTrue();
            assertThat(taskRan.get()).isTrue();
        }
    }

    @Test
    @DisplayName("shutdown is ordered and leaves pools terminated")
    void shutdownTerminatesPools() {
        PluginExecutors executors = PluginExecutors.create(2, 2, Runnable::run);
        executors.shutdown(Duration.ofSeconds(5));

        assertThat(executors.db().isTerminated()).isTrue();
        assertThat(executors.io().isTerminated()).isTrue();
        assertThat(executors.sched().isTerminated()).isTrue();
    }

    @Test
    @DisplayName("create rejects non-positive pool sizes")
    void createRejectsBadSizes() {
        assertThatThrownBy(() -> PluginExecutors.create(0, 1, Runnable::run))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dbPoolSize");
        assertThatThrownBy(() -> PluginExecutors.create(1, 0, Runnable::run))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ioParallelism");
    }
}
