package nl.gzmn.playerworlds.core.concurrent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MainThreadTest {

    @AfterEach
    void clearMainThread() {
        MainThread.clear();
    }

    @Test
    @DisplayName("assertOff is a no-op before the main thread is marked")
    void assertOffBeforeEnterIsNoOp() {
        assertThat(MainThread.isMain()).isFalse();
        assertThatCode(MainThread::assertOff).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("assertOn and assertOff recognise the marked main thread")
    void assertOnAndOff() {
        MainThread.enter(Thread.currentThread());

        assertThat(MainThread.isMain()).isTrue();
        assertThatCode(MainThread::assertOn).doesNotThrowAnyException();
        assertThatThrownBy(MainThread::assertOff)
                .isInstanceOf(WrongThreadException.class)
                .hasMessageContaining("NFR-2");
    }

    @Test
    @DisplayName("a worker thread is not the main thread once main is marked")
    void workerIsNotMain() throws Exception {
        MainThread.enter(Thread.currentThread());
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread worker = new Thread(() -> {
            try {
                assertThat(MainThread.isMain()).isFalse();
                MainThread.assertOff();
                assertThatThrownBy(MainThread::assertOn).isInstanceOf(WrongThreadException.class);
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        worker.start();
        worker.join();

        assertThat(failure.get()).isNull();
    }

    @Test
    @DisplayName("enter refuses to replace a different main thread")
    void enterRefusesReplacement() throws Exception {
        MainThread.enter(Thread.currentThread());
        Thread other = new Thread(() -> {});
        other.setName("other");

        assertThatThrownBy(() -> MainThread.enter(other))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already set");
    }
}
