package nl.gzmn.playerworlds.core.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import javax.sql.DataSource;
import nl.gzmn.playerworlds.core.concurrent.MainThread;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The connection pool, and the entry point for every statement this system runs.
 *
 * <p>It implements {@link DbClock} because database time and database access come
 * from the same place and separating them would only invite somebody to
 * substitute a local clock for one of them.
 *
 * <p>Nothing here may be called from the main thread (NFR-2). Every entry point
 * calls {@link MainThread#assertOff()} so a JDBC call that reaches the tick
 * thread fails the build rather than stalling players.
 */
public final class Database implements DbClock, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Database.class);

    private final HikariDataSource dataSource;

    private Database(HikariDataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Opens the pool. Does not connect: Hikari fills lazily on first use. */
    public static Database open(DatabaseSettings settings) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(settings.jdbcUrl());
        config.setUsername(settings.username());
        config.setPassword(settings.password());
        config.setMaximumPoolSize(settings.poolSize());
        config.setConnectionTimeout(settings.connectionTimeout().toMillis());
        config.setPoolName("gzmn-worlds-db");

        // Name the driver rather than letting Hikari resolve it through
        // DriverManager, because inside a plugin jar DriverManager cannot find it.
        //
        // DriverManager builds its registry once, by running a ServiceLoader scan
        // against whichever classloader got there first. On a Paper server that
        // happens long before a plugin is loaded, from a classloader that cannot
        // see inside our jar, so our META-INF/services/java.sql.Driver entry is
        // never read and DriverManager.getDriver reports "No suitable driver" for
        // a driver that is demonstrably present. Naming the class instead sends
        // Hikari down its direct-instantiation path, which loads through its own
        // classloader — and its own classloader, after relocation, is the plugin
        // classloader that does contain the driver.
        //
        // The name is taken from the class rather than written as a string so that
        // relocation rewrites it automatically and a driver rename is a compile
        // error. Unshaded (tests, :testing) it resolves to org.postgresql.Driver;
        // shaded it resolves to the relocated name.
        config.setDriverClassName(org.postgresql.Driver.class.getName());

        // Autocommit off by default. Every multi-statement operation in this
        // system is a transaction whose atomicity is the correctness argument
        // (MN-3a commits a manifest pointer and its profiles together, or not at
        // all), and a pool that hands out autocommit connections makes forgetting
        // that the default rather than the exception.
        config.setAutoCommit(false);

        // Fail fast rather than block startup. A node that cannot reach the
        // database must refuse to enable loudly (plan section 10.4), not sit in a
        // retry loop looking healthy.
        config.setInitializationFailTimeout(-1);

        return new Database(new HikariDataSource(config));
    }

    /** For Flyway, which wants a {@link DataSource} rather than a connection. */
    public DataSource dataSource() {
        return dataSource;
    }

    /**
     * Runs {@code work} in a transaction, committing on normal return and rolling
     * back on any exception.
     *
     * <p>Rollback-on-throw is the whole point. A conditional {@code UPDATE} that
     * affected zero rows is not an error — it is the "lease moved on" path (MN-8,
     * MN-3a) — so the code that decides whether to commit has to be the code that
     * inspected the row count, and it signals "do not commit" by throwing.
     */
    public <T> T inTransaction(SqlFunction<Connection, T> work) throws SQLException {
        MainThread.assertOff();
        try (Connection connection = dataSource.getConnection()) {
            try {
                T result = work.apply(connection);
                connection.commit();
                return result;
            } catch (RuntimeException | SQLException e) {
                rollbackQuietly(connection, e);
                throw e;
            }
        }
    }

    /**
     * Runs {@code work} on a pooled connection without a transaction boundary of
     * its own, for a single self-contained statement.
     *
     * <p>The connection still has autocommit off, so a caller that writes must use
     * {@link #inTransaction} instead. This exists for reads.
     */
    public <T> T withConnection(SqlFunction<Connection, T> work) throws SQLException {
        MainThread.assertOff();
        try (Connection connection = dataSource.getConnection()) {
            T result = work.apply(connection);
            // A read-only connection is returned to the pool with an open
            // transaction otherwise, which holds a snapshot and blocks vacuum.
            connection.rollback();
            return result;
        }
    }

    @Override
    public Instant now() throws SQLException {
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT now()");
                    ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("SELECT now() returned no rows");
                }
                OffsetDateTime value = rows.getObject(1, OffsetDateTime.class);
                if (value == null) {
                    throw new SQLException("SELECT now() returned NULL");
                }
                return value.toInstant();
            }
        });
    }

    @Override
    public void close() {
        dataSource.close();
    }

    private static void rollbackQuietly(Connection connection, Exception cause) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            // Never mask the original failure with the rollback failure: the
            // first one says what went wrong, the second only says the connection
            // was already gone.
            cause.addSuppressed(rollbackFailure);
            log.warn("rollback failed after {}", cause.getClass().getSimpleName(), rollbackFailure);
        }
    }

    /** A body that may throw {@link SQLException}, which {@code Function} cannot. */
    @FunctionalInterface
    public interface SqlFunction<I, O> {
        O apply(I input) throws SQLException;
    }
}
