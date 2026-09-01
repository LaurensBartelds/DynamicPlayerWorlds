package nl.gzmn.playerworlds.core.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuiescenceWaiterTest {

    @TempDir
    Path temp;

    @Test
    @DisplayName("empty dirty set settles immediately without waiting")
    void emptySetIsTriviallyQuiescent() throws Exception {
        long start = System.nanoTime();
        boolean settled = QuiescenceWaiter.await(temp, List.of(), Duration.ofSeconds(30), Duration.ofSeconds(30));
        assertThat(settled).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("zero quiet interval settles immediately without polling")
    void zeroQuietSkipsWaiting() throws Exception {
        Path file = temp.resolve("r.0.0.mca");
        Files.writeString(file, "v0", StandardCharsets.UTF_8);

        boolean settled =
                QuiescenceWaiter.await(temp, List.of(Path.of("r.0.0.mca")), Duration.ZERO, Duration.ofSeconds(30));

        assertThat(settled).isTrue();
    }

    @Test
    @DisplayName("a file left untouched settles after one quiet interval")
    void untouchedFileSettles() throws Exception {
        Path file = temp.resolve("r.0.0.mca");
        Files.writeString(file, "stable", StandardCharsets.UTF_8);

        boolean settled = QuiescenceWaiter.await(
                temp, List.of(Path.of("r.0.0.mca")), Duration.ofMillis(20), Duration.ofSeconds(5));

        assertThat(settled).isTrue();
    }

    @Test
    @DisplayName("a file that keeps changing gives up once the timeout elapses")
    void continuouslyChangingFileTimesOut() throws Exception {
        Path file = temp.resolve("r.0.0.mca");
        Files.writeString(file, "v0", StandardCharsets.UTF_8);

        AtomicBoolean stop = new AtomicBoolean(false);
        Thread writer = new Thread(() -> {
            int i = 0;
            while (!stop.get()) {
                try {
                    Files.writeString(file, "v" + i++, StandardCharsets.UTF_8);
                    Thread.sleep(5);
                } catch (Exception ignored) {
                    // Best-effort background mutator; the assertion is on await()'s outcome.
                }
            }
        });
        writer.setDaemon(true);
        writer.start();
        try {
            boolean settled = QuiescenceWaiter.await(
                    temp, List.of(Path.of("r.0.0.mca")), Duration.ofMillis(50), Duration.ofMillis(200));
            assertThat(settled).isFalse();
        } finally {
            stop.set(true);
            writer.join();
        }
    }

    @Test
    @DisplayName("a file that vanishes during polling is treated as still moving, not an error")
    void vanishedFileKeepsPollingRatherThanThrowing() throws Exception {
        Path file = temp.resolve("gone.dat");
        Files.writeString(file, "x", StandardCharsets.UTF_8);

        Thread deleter = new Thread(() -> {
            try {
                Thread.sleep(10);
                Files.deleteIfExists(file);
            } catch (Exception ignored) {
                // Best-effort.
            }
        });
        deleter.setDaemon(true);
        deleter.start();

        // No exception is the assertion: a vanished file is not a fault (MN-5a), and an
        // IOException from a mid-poll stat must not escape await().
        QuiescenceWaiter.await(temp, List.of(Path.of("gone.dat")), Duration.ofMillis(20), Duration.ofMillis(200));
        deleter.join();
    }
}
