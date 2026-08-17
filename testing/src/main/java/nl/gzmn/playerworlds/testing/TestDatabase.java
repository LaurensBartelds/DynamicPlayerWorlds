package nl.gzmn.playerworlds.testing;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import nl.gzmn.playerworlds.core.db.Database;
import nl.gzmn.playerworlds.core.db.DatabaseSettings;
import nl.gzmn.playerworlds.core.db.Schema;
import org.jspecify.annotations.Nullable;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Shared PostgreSQL for tests outside {@code :core} (plan section 11).
 *
 * <p>One container per JVM; isolation is a schema reset via {@link #openFresh()},
 * which is far cheaper than starting PostgreSQL repeatedly. The suite has to
 * stay under five minutes in CI.
 *
 * <p>Image is pinned, not floating: conditional {@code UPDATE} and advisory-lock
 * semantics are exactly what a major version can change, and a floating tag turns
 * "the build broke" into an investigation.
 *
 * <p>{@code :core} keeps a twin ({@code TestPostgres}) because it cannot depend
 * on this module. Keep the image coordinates in lockstep.
 */
public final class TestDatabase {

    /** Must match {@code TestPostgres} in {@code :core}. */
    public static final String IMAGE = "postgres:18.3";

    private static final int POOL_SIZE = 4;
    private static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(10);

    private static @Nullable PostgreSQLContainer container;

    private TestDatabase() {}

    /** Starts the shared container on first use. */
    public static synchronized PostgreSQLContainer container() {
        PostgreSQLContainer current = container;
        if (current == null) {
            PostgreSQLContainer started = new PostgreSQLContainer(IMAGE);
            started.start();
            container = started;
            current = started;
        }
        return current;
    }

    /** Settings for the shared container (any schema state currently in it). */
    public static DatabaseSettings settings() {
        PostgreSQLContainer postgres = container();
        return new DatabaseSettings(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), POOL_SIZE, CONNECTION_TIMEOUT);
    }

    /**
     * An empty database: public schema dropped and recreated. Caller owns and
     * must close the returned pool.
     */
    public static Database openFresh() throws SQLException {
        Database database = Database.open(settings());
        try {
            database.inTransaction(connection -> {
                dropEverything(connection);
                return Boolean.TRUE;
            });
        } catch (SQLException e) {
            database.close();
            throw e;
        }
        return database;
    }

    /**
     * A fresh database with the baseline migration applied. Caller owns and must
     * close the returned pool.
     */
    public static Database openMigrated() throws SQLException, InterruptedException {
        Database database = openFresh();
        try {
            Schema.migrate(database);
        } catch (SQLException | InterruptedException | RuntimeException e) {
            database.close();
            throw e;
        }
        return database;
    }

    private static void dropEverything(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA public CASCADE");
            statement.execute("CREATE SCHEMA public");
        }
    }
}
