package nl.gzmn.playerworlds.core.db;

import java.time.Duration;
import java.util.Objects;

/**
 * Node-local database settings. These stay in a file rather than in
 * {@code network_setting}, because they have to be readable before the database
 * is (plan section 8.1).
 *
 * @param jdbcUrl a {@code jdbc:postgresql://} URL
 * @param username database user
 * @param password database password
 * @param poolSize maximum pooled connections. This also sizes the {@code db}
 *     executor in the threading foundation: a pool smaller than the executor
 *     turns every burst into queueing inside Hikari where it is invisible,
 *     instead of in the executor where it is measured.
 * @param connectionTimeout how long a caller waits for a connection before
 *     failing. Bounded on purpose — a 24/7 process cannot afford an unbounded
 *     wait, and a database outage must surface as a fast failure that the
 *     MN-10b self-fence deadline can act on, not as a thread parked forever.
 */
public record DatabaseSettings(
        String jdbcUrl, String username, String password, int poolSize, Duration connectionTimeout) {

    /**
     * Small by design. A node hosts at most {@code nodes.max-worlds} worlds and a
     * few dozen players, and every database call is short; the work that is slow
     * here is object storage, which has its own bounded executor (NFR-7). A large
     * pool would only move contention from a place that is measured to the
     * database server, which is shared with every other node.
     */
    public static final int DEFAULT_POOL_SIZE = 8;

    public static final Duration DEFAULT_CONNECTION_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Below this, a node deadlocks itself at startup.
     *
     * <p>{@link Schema#migrate} holds one pooled connection for the FR-40 advisory
     * lock across the whole migration, and Flyway independently takes two — one
     * for its schema-history table and one to run migrations on. A pool of three
     * or fewer therefore hands out every connection and then waits for one that
     * cannot arrive, until {@link #connectionTimeout} expires and the enable
     * fails. Refusing the value up front turns a silent startup hang into a
     * message naming the key.
     */
    public static final int MIN_POOL_SIZE = 4;

    public DatabaseSettings {
        Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(connectionTimeout, "connectionTimeout");

        if (!jdbcUrl.startsWith("jdbc:postgresql:")) {
            throw new IllegalArgumentException("jdbcUrl must be a jdbc:postgresql: URL, was: " + jdbcUrl);
        }
        if (poolSize < MIN_POOL_SIZE) {
            throw new IllegalArgumentException("database.pool-size must be at least " + MIN_POOL_SIZE
                    + " (the migration advisory lock holds one connection while Flyway takes two), was: " + poolSize);
        }
        if (connectionTimeout.isNegative() || connectionTimeout.isZero()) {
            throw new IllegalArgumentException("connectionTimeout must be positive, was: " + connectionTimeout);
        }
    }

    public static DatabaseSettings of(String jdbcUrl, String username, String password) {
        return new DatabaseSettings(jdbcUrl, username, password, DEFAULT_POOL_SIZE, DEFAULT_CONNECTION_TIMEOUT);
    }
}
