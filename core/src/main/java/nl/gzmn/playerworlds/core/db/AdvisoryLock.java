package nl.gzmn.playerworlds.core.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A PostgreSQL session-level advisory lock, used to elect exactly one process for
 * work that must not run twice (FR-40).
 *
 * <p>Every node in an interchangeable pool would otherwise run every periodic job
 * simultaneously, duplicating archival and racing on cleanup. The same lock also
 * guards migrations (plan section 6), so a rolling restart cannot land a schema
 * change on top of a maintenance sweep that is halfway through reading the shape
 * it is changing.
 *
 * <p>The lock is tied to the <em>connection</em>, not the transaction, so this
 * class holds a connection out of the pool for as long as it is open. That is
 * deliberate and is why it is {@link AutoCloseable}: if the process dies, the
 * connection dies with it and PostgreSQL releases the lock, which is exactly the
 * property an election needs.
 */
public final class AdvisoryLock implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AdvisoryLock.class);

    /**
     * The maintenance and migration lock key.
     *
     * <p>Written as a literal rather than derived from a hash of a name on
     * purpose. A hash means the lock key is a function of code, and the day
     * somebody changes how the name is hashed, two versions of this software stop
     * excluding each other and both run the maintenance job — silently, because
     * taking a lock nobody else holds always succeeds.
     *
     * <p>The value is {@code 'G' 'Z' 'M' 'N'} followed by a 16-bit purpose id, so
     * a DBA looking at {@code pg_locks} can recognise it.
     */
    public static final long MAINTENANCE_KEY = 0x475A_4D4E_0001L;

    /** How long to wait between attempts while a lock is held elsewhere. */
    private static final Duration RETRY_INTERVAL = Duration.ofMillis(250);

    private final Database database;
    private final Connection connection;
    private final long key;

    private AdvisoryLock(Database database, Connection connection, long key) {
        this.database = database;
        this.connection = connection;
        this.key = key;
    }

    /**
     * Test seam: wraps {@code connection} as though it held {@code key}, without actually taking
     * the lock. Package-private on purpose — production code only ever gets an {@code
     * AdvisoryLock} back from a successful {@link #tryAcquire}. This exists so {@code close()}'s
     * unlock-failure path can be driven deterministically: {@code pg_advisory_unlock} on a key
     * the session never locked legitimately returns {@code false}, without needing to fake a
     * connection failure.
     */
    static AdvisoryLock forTesting(Database database, Connection connection, long key) {
        return new AdvisoryLock(database, connection, key);
    }

    /**
     * Tries to take the lock, retrying until {@code timeout} elapses.
     *
     * <p>Bounded rather than blocking, because the caller is usually a node
     * starting up: a plugin that hangs forever waiting for a lock looks identical
     * to a plugin that is working, and a 24/7 process cannot afford an unbounded
     * wait (plan section 9). An empty result means "somebody else has it", which
     * for maintenance is the normal case and not an error.
     *
     * @return the held lock, or empty if it could not be taken in time
     */
    public static Optional<AdvisoryLock> tryAcquire(Database database, long key, Duration timeout)
            throws SQLException, InterruptedException {
        long start = System.nanoTime();
        Connection connection = database.dataSource().getConnection();
        boolean acquired = false;
        try {
            while (true) {
                if (tryLock(connection, key)) {
                    acquired = true;
                    log.debug("acquired advisory lock {}", Long.toHexString(key));
                    return Optional.of(new AdvisoryLock(database, connection, key));
                }
                if (DbClock.elapsedSince(start).compareTo(timeout) >= 0) {
                    log.debug("advisory lock {} held elsewhere after {}", Long.toHexString(key), timeout);
                    return Optional.empty();
                }
                Thread.sleep(RETRY_INTERVAL.toMillis());
            }
        } finally {
            if (!acquired) {
                closeQuietly(connection);
            }
        }
    }

    /**
     * Releases the lock and returns the connection to the pool — unless the unlock did not
     * actually happen, in which case the connection is evicted instead (plan 05 section 6).
     *
     * <p>{@code pg_advisory_unlock} returning {@code false}, or throwing, both mean the same
     * thing this session's advisory-lock state cannot be trusted. Returning that connection to
     * Hikari anyway would hand a future, unrelated borrower a connection that silently still
     * holds {@code key} — MAINTENANCE_KEY's election would then have two holders that both think
     * they are the only one, exactly what this lock exists to prevent. Evicting closes the
     * physical connection, which is the same "ending the session releases the lock anyway"
     * property {@link #tryAcquire} relies on for a crashed holder, applied deliberately instead
     * of by accident.
     */
    @Override
    public void close() {
        boolean released;
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            statement.setLong(1, key);
            try (ResultSet rows = statement.executeQuery()) {
                released = rows.next() && rows.getBoolean(1);
            }
            connection.commit();
        } catch (SQLException e) {
            log.error(
                    "failed to release advisory lock {}; evicting the connection rather than pooling it",
                    Long.toHexString(key),
                    e);
            database.evictConnection(connection);
            return;
        }
        if (!released) {
            log.error(
                    "pg_advisory_unlock({}) returned false — this session did not hold it; evicting the "
                            + "connection rather than pooling it",
                    Long.toHexString(key));
            database.evictConnection(connection);
            return;
        }
        closeQuietly(connection);
    }

    private static boolean tryLock(Connection connection, long key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            statement.setLong(1, key);
            try (ResultSet rows = statement.executeQuery()) {
                boolean locked = rows.next() && rows.getBoolean(1);
                connection.commit();
                return locked;
            }
        }
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException e) {
            log.warn("failed to close advisory lock connection", e);
        }
    }
}
