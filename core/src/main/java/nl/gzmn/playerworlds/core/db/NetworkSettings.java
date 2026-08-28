package nl.gzmn.playerworlds.core.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import nl.gzmn.playerworlds.core.config.MessageCatalog;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import org.jspecify.annotations.Nullable;

/**
 * Access to the {@code network_setting} table, with a process-local cache.
 *
 * <p>Network policy is read on every command path that enforces a cap or expiry
 * (plan section 8.1). Hitting PostgreSQL per tab-completion or per
 * {@code /world create} is the wrong shape; the cache is the hot path and
 * {@link #invalidate()} is what the control-plane {@code INVALIDATE_CACHE}
 * command calls after a write so every node converges without a restart.
 *
 * <p>Lives in {@code core.db} rather than {@code core.config} because it speaks
 * JDBC, and {@code ArchitectureTest} confines that to this package.
 */
public final class NetworkSettings extends Repository {

    private final Object lock = new Object();

    /** key → JSONB value as text. Absent keys are simply not in the map. */
    private Map<String, String> cache = Map.of();

    private boolean loaded;

    public NetworkSettings(Database database) {
        super(database);
    }

    /**
     * Reloads every row into the cache. Safe to call from any non-main thread;
     * concurrent readers see either the previous snapshot or the new one, never a
     * torn mix.
     */
    public void reload() throws SQLException {
        Map<String, String> fresh = database.withConnection(this::loadAll);
        synchronized (lock) {
            cache = fresh;
            loaded = true;
        }
    }

    /**
     * Drops the cache so the next read reloads from the database.
     *
     * <p>Used by the control-plane {@code INVALIDATE_CACHE} handler. Does not
     * itself query: the invalidating node may be under load and the next reader
     * pays the cost.
     */
    public void invalidate() {
        synchronized (lock) {
            cache = Map.of();
            loaded = false;
        }
    }

    /**
     * Current policy, with defaults for any key that has no row.
     *
     * <p>Reloads lazily after {@link #invalidate()}. Callers that need a stable
     * view across several decisions should hold the returned record rather than
     * calling this repeatedly.
     */
    public NetworkPolicy policy() throws SQLException {
        return NetworkPolicy.fromRaw(snapshot());
    }

    /**
     * Current message catalog (NFR-5), with defaults for any key that has no row.
     *
     * <p>Same cache, same reload/invalidate lifecycle as {@link #policy()} — a {@code
     * messages.*} row is picked up by the same {@code INVALIDATE_CACHE} handling, since {@link
     * #invalidate()} and {@link #reload()} know nothing about which logical config they serve.
     */
    public MessageCatalog messages() throws SQLException {
        return MessageCatalog.fromRaw(snapshot());
    }

    /**
     * All stored keys and their JSONB text. Missing keys are absent from the map
     * so {@link NetworkPolicy#fromRaw} applies defaults.
     */
    public Map<String, String> snapshot() throws SQLException {
        ensureLoaded();
        synchronized (lock) {
            return cache;
        }
    }

    /**
     * The JSONB value for {@code key}, if a row exists.
     *
     * <p>Returns empty both when the key was never written and when it was
     * written and later deleted; callers that need a default use
     * {@link #policy()}.
     */
    public Optional<String> get(String key) throws SQLException {
        Objects.requireNonNull(key, "key");
        ensureLoaded();
        synchronized (lock) {
            return Optional.ofNullable(cache.get(key));
        }
    }

    /**
     * Inserts or replaces a setting inside an existing transaction.
     *
     * <p>Does not update the cache: the writer is expected to finish the
     * transaction and then either {@link #reload()} locally or broadcast
     * {@code INVALIDATE_CACHE} so every node — including this one — reloads
     * from the committed state. Updating the cache here would let a rolled-back
     * write look applied.
     *
     * @param jsonValue JSONB contents as text ({@code 3}, {@code "PRIVATE"},
     *     {@code [14, 3]})
     * @param updatedBy staff uuid or {@code "system"}; {@code null} for seeds
     * @return rows affected (1 on insert or update)
     */
    public int put(Connection connection, String key, String jsonValue, @Nullable String updatedBy)
            throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(jsonValue, "jsonValue");
        return execute(connection, """
                INSERT INTO network_setting (key, value, updated_by)
                VALUES (?, ?::jsonb, ?)
                ON CONFLICT (key) DO UPDATE
                  SET value = excluded.value,
                      updated_at = now(),
                      updated_by = excluded.updated_by
                """, statement -> {
            statement.setString(1, key);
            statement.setString(2, jsonValue);
            statement.setString(3, updatedBy);
        });
    }

    /**
     * Convenience put in its own transaction, then reloads this process's cache.
     *
     * <p>For admin paths that change one key and do not need to compose with other
     * statements. Multi-key changes should use {@link #put(Connection, String,
     * String, String)} inside {@link Database#inTransaction} and invalidate once.
     */
    public void putAndReload(String key, String jsonValue, @Nullable String updatedBy) throws SQLException {
        database.inTransaction(connection -> {
            put(connection, key, jsonValue, updatedBy);
            return Boolean.TRUE;
        });
        reload();
    }

    private void ensureLoaded() throws SQLException {
        synchronized (lock) {
            if (loaded) {
                return;
            }
        }
        reload();
    }

    private Map<String, String> loadAll(Connection connection) throws SQLException {
        Map<String, String> values = new HashMap<>();
        for (Map.Entry<String, String> entry : queryList(
                connection,
                "SELECT key, value::text FROM network_setting",
                StatementBinder.NONE,
                row -> Map.entry(
                        Objects.requireNonNull(row.getString(1), "key"),
                        Objects.requireNonNull(row.getString(2), "value")))) {
            values.put(entry.getKey(), entry.getValue());
        }
        return Map.copyOf(values);
    }
}
