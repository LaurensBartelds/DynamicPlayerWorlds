package nl.gzmn.playerworlds.core.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A real PostgreSQL for the database tests, started once per JVM and shared.
 *
 * <p>Shared rather than one container per class because the whole test suite has
 * to stay under five minutes in CI (plan section 11) and starting PostgreSQL is
 * most of the cost. Isolation comes from {@link #freshDatabase()} resetting the
 * schema instead, which is far cheaper than a container.
 *
 * <p>Not managed by the JUnit extension: the container outlives any single test
 * class, and Testcontainers' own reaper removes it when the JVM exits.
 *
 * <p>F9 will grow this into a shared fixture in {@code :testing}. It lives here
 * for now because {@code :testing} depends on {@code :core}, so {@code :core}
 * cannot depend on it back.
 */
final class TestPostgres {

    /**
     * Pinned, not floating. A test suite whose database version changes under it
     * turns "the build broke" into an investigation, and the conditional
     * {@code UPDATE} and advisory-lock semantics this suite exists to pin are
     * exactly the things a major version can change.
     */
    private static final String IMAGE = "postgres:18.3";

    private static PostgreSQLContainer container;

    private TestPostgres() {}

    static synchronized PostgreSQLContainer container() {
        if (container == null) {
            PostgreSQLContainer started = new PostgreSQLContainer(IMAGE);
            started.start();
            container = started;
        }
        return container;
    }

    /**
     * An empty database, with any previous test's tables dropped.
     *
     * <p>The caller owns the returned pool and must close it.
     */
    static Database freshDatabase() throws SQLException {
        PostgreSQLContainer postgres = container();
        Database database = Database.open(new DatabaseSettings(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword(),
                4,
                java.time.Duration.ofSeconds(10)));
        try {
            database.inTransaction(connection -> {
                dropEverything(connection);
                return null;
            });
        } catch (SQLException e) {
            database.close();
            throw e;
        }
        return database;
    }

    /**
     * Drops the whole public schema, including Flyway's history table, so a test
     * starts from genuinely nothing rather than from "nothing except the bits that
     * happened to survive".
     */
    private static void dropEverything(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA public CASCADE");
            statement.execute("CREATE SCHEMA public");
        }
    }
}
