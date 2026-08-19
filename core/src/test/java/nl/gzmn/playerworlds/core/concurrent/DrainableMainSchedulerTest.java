package nl.gzmn.playerworlds.core.concurrent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DrainableMainSchedulerTest {

    private ExecutorService worker;
    private Thread mainThread;
    private ConcurrentLinkedQueue<Runnable> platformTasks;
    private DrainableMainScheduler scheduler;

    @BeforeEach
    void setUp() {
        worker = Executors.newSingleThreadExecutor();
        mainThread = Thread.currentThread();
        platformTasks = new ConcurrentLinkedQueue<>();
        scheduler = new DrainableMainScheduler(() -> Thread.currentThread() == mainThread, platformTasks::add);
    }

    @AfterEach
    void tearDown() {
        worker.shutdownNow();
    }

    @Test
    @DisplayName("work submitted from the main thread runs inline, shutting down or not")
    void workFromMainRunsInline() {
        AtomicBoolean ran = new AtomicBoolean();
        scheduler.execute(() -> ran.set(true));
        assertThat(ran).isTrue();
        assertThat(platformTasks).isEmpty();

        ran.set(false);
        scheduler.beginShutdown();
        scheduler.execute(() -> ran.set(true));
        assertThat(ran).isTrue();
    }

    @Test
    @DisplayName("work from another thread goes to the platform until shutdown, then queues")
    void workFromElsewhereQueuesOnceShuttingDown() throws Exception {
        worker.submit(() -> scheduler.execute(() -> {})).get(5, TimeUnit.SECONDS);
        assertThat(platformTasks).hasSize(1);

        scheduler.beginShutdown();
        List<String> ran = new java.util.ArrayList<>();
        worker.submit(() -> scheduler.execute(() -> ran.add("queued"))).get(5, TimeUnit.SECONDS);

        // Still one: the platform saw nothing after beginShutdown.
        assertThat(platformTasks).hasSize(1);
        assertThat(ran).isEmpty();

        scheduler.drain();
        assertThat(ran).containsExactly("queued");
    }

    @Test
    @DisplayName("awaitDraining completes a future whose completion needs the main thread")
    void awaitDrainingRunsTheWorkTheFutureIsWaitingOn() throws Exception {
        scheduler.beginShutdown();
        CompletableFuture<String> done = new CompletableFuture<>();

        // The shape every asynchronous path here ends in: finish off-thread, then
        // hop to main to complete. Without the drain this never completes.
        var _ = worker.submit(() -> scheduler.execute(() -> done.complete("landed")));

        assertThat(scheduler.awaitDraining(done, Duration.ofSeconds(5))).isEqualTo("landed");
    }

    @Test
    @DisplayName("awaitDraining gives up at its budget rather than blocking shutdown forever")
    void awaitDrainingTimesOut() {
        scheduler.beginShutdown();
        CompletableFuture<String> never = new CompletableFuture<>();

        assertThatThrownBy(() -> scheduler.awaitDraining(never, Duration.ofMillis(200)))
                .isInstanceOf(TimeoutException.class);
        assertThat(never).isNotDone();
    }

    @Test
    @DisplayName("a task that throws does not abandon the rest of the queue")
    void oneFailingTaskDoesNotStopTheDrain() throws Exception {
        scheduler.beginShutdown();
        List<String> ran = new java.util.ArrayList<>();
        worker.submit(() -> {
                    scheduler.execute(() -> {
                        throw new IllegalStateException("boom");
                    });
                    scheduler.execute(() -> ran.add("after"));
                })
                .get(5, TimeUnit.SECONDS);

        scheduler.drain();

        assertThat(ran).containsExactly("after");
    }
}
