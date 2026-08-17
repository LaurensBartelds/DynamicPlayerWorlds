package nl.gzmn.playerworlds.core.db;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import nl.gzmn.playerworlds.core.concurrent.MainThread;
import nl.gzmn.playerworlds.core.concurrent.WrongThreadException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F6 acceptance: a JDBC call from the main thread fails (NFR-2).
 *
 * <p>The guard lives in {@link Database}, not in each repository, so one test
 * against {@link Database#now()} covers every statement path that goes through
 * {@code withConnection} / {@code inTransaction}.
 */
class JdbcOnMainThreadTest {

    private Database database;

    @BeforeEach
    void openDatabase() throws Exception {
        database = TestPostgres.freshDatabase();
        Schema.migrate(database);
    }

    @AfterEach
    void tearDown() {
        MainThread.clear();
        if (database != null) {
            database.close();
        }
    }

    @Test
    @DisplayName("JDBC from the marked main thread throws WrongThreadException (NFR-2)")
    void jdbcFromMainThreadFails_NFR2() {
        MainThread.enter(Thread.currentThread());

        assertThatThrownBy(() -> database.now())
                .isInstanceOf(WrongThreadException.class)
                .hasMessageContaining("NFR-2");

        assertThatThrownBy(() -> database.withConnection(connection -> 1)).isInstanceOf(WrongThreadException.class);

        assertThatThrownBy(() -> database.inTransaction(connection -> 1)).isInstanceOf(WrongThreadException.class);
    }

    @Test
    @DisplayName("JDBC from a worker thread is allowed once main is marked")
    void jdbcFromWorkerIsAllowed() throws Exception {
        MainThread.enter(Thread.currentThread());

        Thread worker = new Thread(() -> assertThatCode(() -> database.now()).doesNotThrowAnyException());
        worker.start();
        worker.join();
    }
}
