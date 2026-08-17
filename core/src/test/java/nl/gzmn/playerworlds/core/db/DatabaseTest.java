package nl.gzmn.playerworlds.core.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The pool, database time, the FR-40 advisory lock and the repository seam. */
class DatabaseTest {

    private Database database;

    @BeforeEach
    void openDatabase() throws Exception {
        database = TestPostgres.freshDatabase();
        Schema.migrate(database);
    }

    @AfterEach
    void closeDatabase() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    @DisplayName("now() comes from the database and moves forward (MN-10b)")
    void nowComesFromTheDatabase() throws Exception {
        Instant first = database.now();
        Instant second = database.now();

        assertThat(first).isNotNull();
        assertThat(second).isAfterOrEqualTo(first);
    }

    @Test
    @DisplayName("elapsedSince measures on the monotonic clock, not the wall clock")
    void elapsedSinceUsesTheMonotonicClock() {
        long start = System.nanoTime();

        Duration elapsed = DbClock.elapsedSince(start);

        assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ZERO);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("the advisory lock admits exactly one holder (FR-40)")
    void advisoryLockAdmitsExactlyOneHolder() throws Exception {
        Optional<AdvisoryLock> first =
                AdvisoryLock.tryAcquire(database, AdvisoryLock.MAINTENANCE_KEY, Duration.ofSeconds(1));
        assertThat(first).isPresent();

        // A second attempt must fail rather than wait forever: every node in an
        // interchangeable pool tries this, and all but one has to get on with
        // being a node.
        Optional<AdvisoryLock> second =
                AdvisoryLock.tryAcquire(database, AdvisoryLock.MAINTENANCE_KEY, Duration.ofMillis(500));
        assertThat(second).isEmpty();

        first.get().close();

        Optional<AdvisoryLock> afterRelease =
                AdvisoryLock.tryAcquire(database, AdvisoryLock.MAINTENANCE_KEY, Duration.ofSeconds(1));
        assertThat(afterRelease).isPresent();
        afterRelease.get().close();
    }

    @Test
    @DisplayName("different lock keys do not exclude each other")
    void differentLockKeysDoNotExcludeEachOther() throws Exception {
        try (AdvisoryLock maintenance = AdvisoryLock.tryAcquire(
                        database, AdvisoryLock.MAINTENANCE_KEY, Duration.ofSeconds(1))
                .orElseThrow()) {
            Optional<AdvisoryLock> other =
                    AdvisoryLock.tryAcquire(database, AdvisoryLock.MAINTENANCE_KEY + 1, Duration.ofMillis(500));

            assertThat(other).isPresent();
            other.get().close();
            assertThat(maintenance).isNotNull();
        }
    }

    @Test
    @DisplayName("a failed transaction rolls back rather than committing part of itself")
    void aFailedTransactionRollsBack() throws Exception {
        NetworkSettings settings = new NetworkSettings(database);

        assertThatThrownBy(() -> database.inTransaction(connection -> {
                    settings.put(connection, "worlds.max-per-player", "3");
                    throw new SQLException("deliberate");
                }))
                .isInstanceOf(SQLException.class)
                .hasMessage("deliberate");

        Optional<String> stored =
                database.withConnection(connection -> settings.get(connection, "worlds.max-per-player"));
        assertThat(stored).isEmpty();
    }

    @Test
    @DisplayName("execute returns the affected row count, which is the answer not a diagnostic")
    void executeReturnsTheAffectedRowCount() throws Exception {
        NetworkSettings settings = new NetworkSettings(database);

        int inserted = database.inTransaction(connection -> settings.put(connection, "invites.expiry-minutes", "60"));
        assertThat(inserted).isEqualTo(1);

        // The conditional update every lease and commit predicate is built from:
        // a key that is not there affects zero rows, and that zero is the signal.
        int missing = database.inTransaction(connection -> settings.touch(connection, "does.not.exist"));
        assertThat(missing).isZero();

        int present = database.inTransaction(connection -> settings.touch(connection, "invites.expiry-minutes"));
        assertThat(present).isEqualTo(1);
    }

    @Test
    @DisplayName("queryOne refuses to choose between two matching rows")
    void queryOneRefusesToChooseBetweenTwoRows() throws Exception {
        NetworkSettings settings = new NetworkSettings(database);
        database.inTransaction(connection -> {
            settings.put(connection, "a", "1");
            settings.put(connection, "b", "2");
            return null;
        });

        assertThat(database.withConnection(settings::allKeys)).containsExactly("a", "b");

        assertThatThrownBy(() -> database.withConnection(settings::theOnlySetting))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("expected at most one row");
    }

    /**
     * A concrete repository, written the way milestone-1 repositories will be: the
     * SQL is visible, the predicate is readable and the row count is returned.
     * F4 owns the real one, with caching and control-plane invalidation.
     */
    private static final class NetworkSettings extends Repository {

        NetworkSettings(Database database) {
            super(database);
        }

        int put(Connection connection, String key, String json) throws SQLException {
            return execute(connection, """
                    INSERT INTO network_setting (key, value) VALUES (?, ?::jsonb)
                    ON CONFLICT (key) DO UPDATE SET value = excluded.value, updated_at = now()
                    """, statement -> {
                statement.setString(1, key);
                statement.setString(2, json);
            });
        }

        int touch(Connection connection, String key) throws SQLException {
            return execute(
                    connection,
                    "UPDATE network_setting SET updated_at = now() WHERE key = ?",
                    statement -> statement.setString(1, key));
        }

        Optional<String> get(Connection connection, String key) throws SQLException {
            return queryOne(
                    connection,
                    "SELECT value::text FROM network_setting WHERE key = ?",
                    statement -> statement.setString(1, key),
                    row -> row.getString(1));
        }

        List<String> allKeys(Connection connection) throws SQLException {
            return queryList(
                    connection,
                    "SELECT key FROM network_setting ORDER BY key",
                    StatementBinder.NONE,
                    row -> row.getString(1));
        }

        Optional<String> theOnlySetting(Connection connection) throws SQLException {
            return queryOne(
                    connection, "SELECT key FROM network_setting", StatementBinder.NONE, row -> row.getString(1));
        }
    }
}
