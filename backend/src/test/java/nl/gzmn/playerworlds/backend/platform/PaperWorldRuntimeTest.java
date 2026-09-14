package nl.gzmn.playerworlds.backend.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicReference;
import nl.gzmn.playerworlds.core.concurrent.MainThread;
import nl.gzmn.playerworlds.core.concurrent.WrongThreadException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * Plan 05 section 6's "a Bukkit mutation asserting the main thread" guard: every
 * {@link PaperWorldRuntime} mutator must refuse to run off the thread {@link MainThread} marks.
 *
 * <p>This is the shape {@code QuiesceWatchdog} had until its restore callback was moved back onto
 * the main thread: it called {@code World#setAutoSave} from a background scheduler thread, and
 * nothing — not MockBukkit's {@code WorldMock}, not real Bukkit — complained, so the bug was
 * invisible to every test that did not check the calling thread itself. These tests are that
 * check, one mutator at a time, so the same shape cannot land again unnoticed.
 */
class PaperWorldRuntimeTest {

    private ServerMock server;
    private WorldMock world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("runtime-test");
        MainThread.enter(Thread.currentThread());
    }

    @AfterEach
    void tearDown() {
        MainThread.clear();
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("mutators succeed on the marked main thread")
    void mutatorsSucceedOnMain() {
        assertThatCode(() -> PaperWorldRuntime.INSTANCE.setAutoSave(world, true)).doesNotThrowAnyException();
        assertThatCode(() -> PaperWorldRuntime.INSTANCE.setHardcore(world, false)).doesNotThrowAnyException();
        assertThatCode(() -> PaperWorldRuntime.INSTANCE.setPvp(world, true)).doesNotThrowAnyException();
        assertThatCode(() -> PaperWorldRuntime.INSTANCE.applyBorder(world, DimensionKind.OVERWORLD, 5000, 8))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("setAutoSave off the main thread throws WrongThreadException")
    void setAutoSaveOffMainThrows() throws Exception {
        assertMutatorThrowsOffMain(() -> PaperWorldRuntime.INSTANCE.setAutoSave(world, true));
    }

    @Test
    @DisplayName("setHardcore off the main thread throws WrongThreadException")
    void setHardcoreOffMainThrows() throws Exception {
        assertMutatorThrowsOffMain(() -> PaperWorldRuntime.INSTANCE.setHardcore(world, true));
    }

    @Test
    @DisplayName("setDifficulty off the main thread throws WrongThreadException")
    void setDifficultyOffMainThrows() throws Exception {
        assertMutatorThrowsOffMain(() -> PaperWorldRuntime.INSTANCE.setDifficulty(world, org.bukkit.Difficulty.HARD));
    }

    @Test
    @DisplayName("setPvp off the main thread throws WrongThreadException")
    void setPvpOffMainThrows() throws Exception {
        assertMutatorThrowsOffMain(() -> PaperWorldRuntime.INSTANCE.setPvp(world, true));
    }

    @Test
    @DisplayName("setMobGriefing off the main thread throws WrongThreadException")
    void setMobGriefingOffMainThrows() throws Exception {
        assertMutatorThrowsOffMain(() -> PaperWorldRuntime.INSTANCE.setMobGriefing(world, true));
    }

    @Test
    @DisplayName("save off the main thread throws WrongThreadException")
    void saveOffMainThrows() throws Exception {
        assertMutatorThrowsOffMain(() -> PaperWorldRuntime.INSTANCE.save(world));
    }

    @Test
    @DisplayName("applyBorder off the main thread throws WrongThreadException — this is the QuiesceWatchdog shape")
    void applyBorderOffMainThrows() throws Exception {
        assertMutatorThrowsOffMain(
                () -> PaperWorldRuntime.INSTANCE.applyBorder(world, DimensionKind.OVERWORLD, 5000, 8));
    }

    /**
     * Runs {@code mutation} from a genuinely different thread while {@code MainThread} still has
     * the test thread marked, and asserts it throws. Any assertion failure inside the worker is
     * captured and rethrown on the test thread, so a broken guard fails the test rather than the
     * worker silently swallowing it.
     */
    private void assertMutatorThrowsOffMain(ThrowingCallable mutation) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                assertThatThrownBy(mutation).isInstanceOf(WrongThreadException.class);
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        worker.start();
        worker.join();
        assertThat(failure.get()).isNull();
    }
}
