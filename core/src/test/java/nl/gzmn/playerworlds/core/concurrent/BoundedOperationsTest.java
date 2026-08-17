package nl.gzmn.playerworlds.core.concurrent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BoundedOperationsTest {

    private ExecutorService executor;

    @BeforeEach
    void openExecutor() {
        executor = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void closeExecutor() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("call returns when the task finishes inside the budget")
    void callSucceedsInsideBudget() throws Exception {
        String result = BoundedOperations.call(executor, Duration.ofSeconds(2), () -> "ok");
        assertThat(result).isEqualTo("ok");
    }

    @Test
    @DisplayName("call times out and cancels when the task overruns the budget")
    void callTimesOut() throws InterruptedException {
        AtomicBoolean interrupted = new AtomicBoolean(false);

        assertThatThrownBy(() -> BoundedOperations.call(executor, Duration.ofMillis(100), () -> {
                    try {
                        Thread.sleep(10_000);
                    } catch (InterruptedException e) {
                        interrupted.set(true);
                        Thread.currentThread().interrupt();
                    }
                    return "late";
                }))
                .isInstanceOf(TimeoutException.class);

        // cancel(true) is asynchronous; wait briefly for the worker to observe it.
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!interrupted.get() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(interrupted.get()).isTrue();
    }
}
