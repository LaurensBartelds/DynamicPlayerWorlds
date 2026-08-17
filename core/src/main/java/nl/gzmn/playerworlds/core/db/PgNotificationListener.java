package nl.gzmn.playerworlds.core.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import nl.gzmn.playerworlds.core.concurrent.MainThread;
import org.jspecify.annotations.Nullable;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One dedicated JDBC connection held on {@code LISTEN} (CP-3).
 *
 * <p>Not taken from the Hikari pool: a LISTEN connection is held for the life of
 * the process and would permanently shrink the pool by one. Autocommit is on so
 * {@code LISTEN} takes effect immediately. Failures close the connection; the
 * caller reconnects. A dropped LISTEN loses notifications silently — that is
 * why the control plane also polls.
 *
 * <p>JDBC-only, so this class lives in {@code core.db}.
 */
public final class PgNotificationListener implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PgNotificationListener.class);

    private final DatabaseSettings settings;
    private final String channel;

    private final Object lock = new Object();

    private @Nullable Connection connection;
    private boolean closed;

    public PgNotificationListener(DatabaseSettings settings, String channel) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.channel = Objects.requireNonNull(channel, "channel");
        if (channel.isBlank()) {
            throw new IllegalArgumentException("channel must not be blank");
        }
    }

    public String channel() {
        return channel;
    }

    /**
     * Ensures a live LISTEN connection. Safe to call repeatedly; reconnects when
     * the previous connection is gone.
     */
    public void ensureListening() throws SQLException {
        MainThread.assertOff();
        synchronized (lock) {
            if (closed) {
                throw new SQLException("PgNotificationListener is closed");
            }
            if (connection != null && !connection.isClosed()) {
                return;
            }
            openAndListen();
        }
    }

    /**
     * Waits up to {@code timeout} for the next notification payload on this
     * channel.
     *
     * @return the NOTIFY payload (command id as text), or empty on timeout
     */
    public Optional<String> await(Duration timeout) throws SQLException {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative, was: " + timeout);
        }
        MainThread.assertOff();

        int timeoutMs = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, timeout.toMillis()));
        synchronized (lock) {
            if (closed) {
                throw new SQLException("PgNotificationListener is closed");
            }
            ensureOpenLocked();
            Connection conn = Objects.requireNonNull(connection, "connection");
            try {
                PGConnection pg = conn.unwrap(PGConnection.class);
                PGNotification[] notifications = pg.getNotifications(timeoutMs);
                if (notifications == null || notifications.length == 0) {
                    return Optional.empty();
                }
                // Several may have queued; return the first and leave the rest
                // for the next await. Polling still covers anything we skip.
                PGNotification first = notifications[0];
                if (first == null) {
                    return Optional.empty();
                }
                String payload = first.getParameter();
                return payload == null || payload.isBlank() ? Optional.empty() : Optional.of(payload);
            } catch (SQLException e) {
                closeQuietlyLocked();
                throw e;
            }
        }
    }

    /**
     * Drops the current connection so the next {@link #ensureListening()} or
     * {@link #await(Duration)} opens a fresh one. Used by tests that simulate a
     * killed listener, and by the control plane after a failure.
     */
    public void disconnect() {
        synchronized (lock) {
            closeQuietlyLocked();
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            closeQuietlyLocked();
        }
    }

    private void ensureOpenLocked() throws SQLException {
        if (connection == null || connection.isClosed()) {
            openAndListen();
        }
    }

    private void openAndListen() throws SQLException {
        closeQuietlyLocked();
        Properties props = new Properties();
        props.setProperty("user", settings.username());
        props.setProperty("password", settings.password());
        // Fail fast on a dead database rather than parking the listen thread.
        props.setProperty("connectTimeout", "5");
        // Driver#connect rather than DriverManager.getConnection, for the reason
        // Database.open names: DriverManager builds its registry from whichever
        // classloader scans first, which inside a Paper plugin is never the one
        // holding our relocated driver, so it reports "No suitable driver" for a
        // driver that is present. This connection cannot come from the pool —
        // LISTEN needs a dedicated one held open — so it instantiates the driver
        // itself, which is what the pool does internally anyway.
        Connection opened = new org.postgresql.Driver().connect(settings.jdbcUrl(), props);
        if (opened == null) {
            throw new SQLException("postgresql driver refused the url: " + settings.jdbcUrl());
        }
        try {
            opened.setAutoCommit(true);
            try (Statement statement = opened.createStatement()) {
                // quote_ident so node ids with hyphens remain valid channel names.
                statement.execute("LISTEN " + quoteIdent(channel));
            }
            connection = opened;
            log.debug("LISTEN on {}", channel);
        } catch (SQLException e) {
            try {
                opened.close();
            } catch (SQLException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }

    private void closeQuietlyLocked() {
        Connection toClose = connection;
        connection = null;
        if (toClose == null) {
            return;
        }
        try {
            toClose.close();
        } catch (SQLException e) {
            log.debug("closing LISTEN connection on {}: {}", channel, e.toString());
        }
    }

    /** Double-quote an identifier the way PostgreSQL's quote_ident does. */
    static String quoteIdent(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }
}
