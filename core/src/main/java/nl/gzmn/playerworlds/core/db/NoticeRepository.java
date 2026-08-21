package nl.gzmn.playerworlds.core.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.jspecify.annotations.Nullable;

/**
 * Messages waiting for a player who was not online when there was something to
 * say (FR-34).
 *
 * <p>FR-34 warns a world's owner before it is auto-archived, and the owner is
 * offline by definition — inactivity is what triggers the archival. So the
 * warning has to be durable and wait for a login.
 *
 * <p>Deliberately not a {@code node_command} on {@code gzmn_proxy}. Those are
 * claimed by the control-plane poll as soon as they appear (CP-2), so a notice
 * meant to sit for days would be claimed within seconds and completed as "no
 * handler" (CP-6), and CP-7's sweep would then delete it. CP-6 also says a
 * command "never carries state that belongs in the tables in section 4" — it
 * carries an instruction to act on state already committed there. A pending
 * warning <em>is</em> that state.
 */
public final class NoticeRepository extends Repository {

    public NoticeRepository(Database database) {
        super(database);
    }

    /** One message waiting for a player. */
    public record Notice(long id, UUID uuid, @Nullable WorldId worldId, String message) {

        public Notice {
            Objects.requireNonNull(uuid, "uuid");
            Objects.requireNonNull(message, "message");
        }
    }

    /**
     * Queues a message for {@code uuid} to receive when they next log in.
     *
     * @param worldId the world it concerns, so the row goes when the world does
     * @return the new row id
     */
    public long queue(Connection connection, UUID uuid, @Nullable WorldId worldId, String message) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(message, "message");
        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        return queryOne(
                        connection,
                        """
                        INSERT INTO player_notice (uuid, world_id, message)
                        VALUES (?, ?, ?)
                        RETURNING id
                        """,
                        statement -> {
                            statement.setObject(1, uuid);
                            statement.setObject(2, worldId == null ? null : worldId.value());
                            statement.setString(3, message);
                        },
                        row -> row.getLong("id"))
                .orElseThrow(() -> new SQLException("INSERT player_notice RETURNING id produced no row"));
    }

    /** {@link #queue(Connection, UUID, WorldId, String)} in its own transaction. */
    public long queue(UUID uuid, @Nullable WorldId worldId, String message) throws SQLException {
        return database.inTransaction(connection -> queue(connection, uuid, worldId, message));
    }

    /**
     * Takes everything waiting for a player and marks it delivered, in one
     * statement.
     *
     * <p>One statement rather than select-then-update so two proxies cannot both
     * hand a player the same notice: whichever {@code UPDATE} lands first is the
     * one that returns the rows.
     *
     * @return the messages to send, oldest first
     */
    public List<Notice> takeUndelivered(UUID uuid) throws SQLException {
        Objects.requireNonNull(uuid, "uuid");
        return database.inTransaction(connection ->
                queryList(connection, """
                UPDATE player_notice
                   SET delivered_at = now()
                 WHERE uuid = ?
                   AND delivered_at IS NULL
                RETURNING id, uuid, world_id, message
                """, statement -> statement.setObject(1, uuid), row -> {
                    UUID world = row.getObject("world_id", UUID.class);
                    return new Notice(
                            row.getLong("id"),
                            Objects.requireNonNull(row.getObject("uuid", UUID.class), "uuid"),
                            world == null ? null : new WorldId(world),
                            Objects.requireNonNull(row.getString("message"), "message"));
                }));
    }

    /**
     * Removes delivered notices older than {@code retain} (FR-40).
     *
     * <p>Kept for a while after delivery rather than deleted on the spot, so an
     * operator answering "did the owner ever get told?" has something to look at.
     *
     * @return rows removed
     */
    public int deleteDeliveredBefore(Duration retain, int limit) throws SQLException {
        Objects.requireNonNull(retain, "retain");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1, was: " + limit);
        }
        return database.inTransaction(connection -> execute(connection, """
                DELETE FROM player_notice
                 WHERE id IN (
                   SELECT id
                     FROM player_notice
                    WHERE delivered_at IS NOT NULL
                      AND delivered_at <= now() - (? * INTERVAL '1 second')
                    ORDER BY id
                    LIMIT ?
                 )
                """, statement -> {
            statement.setDouble(1, retain.toMillis() / 1000.0);
            statement.setInt(2, limit);
        }));
    }
}
