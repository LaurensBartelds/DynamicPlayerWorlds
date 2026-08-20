package nl.gzmn.playerworlds.core.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import nl.gzmn.playerworlds.core.control.CommandResult;
import nl.gzmn.playerworlds.core.control.NodeCommand;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.jspecify.annotations.Nullable;

/**
 * JDBC access to {@code node_command} (CP-2 to CP-5).
 *
 * <p>Lives in {@code core.db} because it speaks SQL. The control-plane protocol
 * types it returns live in {@code core.control}; this class is only the
 * statements.
 *
 * <p>Insert and {@code pg_notify} share one transaction so a notification can
 * only be delivered for a row that committed — that ordering is the whole
 * safety argument (CP-2, ADR 0002).
 */
public final class NodeCommandRepository extends Repository {

    /** Default lifetime when a producer does not pick one. Administrative. */
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

    private static final String SELECT_COLUMNS = """
            id, target_node, world_id, generation, command, payload::text,
            created_at, expires_at, claimed_at, completed_at, attempts, result
            """;

    public NodeCommandRepository(Database database) {
        super(database);
    }

    /**
     * Inserts a command and notifies {@code notifyChannel} in the same
     * transaction.
     *
     * @param notifyChannel {@code gzmn_node_<id>} or {@code gzmn_proxy}
     * @param payloadJson JSON object text; use {@link NodeCommand#EMPTY_PAYLOAD}
     * @return the new row id (also the NOTIFY payload)
     */
    public long enqueue(
            Connection connection,
            String targetNode,
            @Nullable WorldId worldId,
            @Nullable Long generation,
            String command,
            String payloadJson,
            Duration ttl,
            String notifyChannel)
            throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(targetNode, "targetNode");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(payloadJson, "payloadJson");
        Objects.requireNonNull(ttl, "ttl");
        Objects.requireNonNull(notifyChannel, "notifyChannel");
        if (targetNode.isBlank()) {
            throw new IllegalArgumentException("targetNode must not be blank");
        }
        if (command.isBlank()) {
            throw new IllegalArgumentException("command must not be blank");
        }
        if (notifyChannel.isBlank()) {
            throw new IllegalArgumentException("notifyChannel must not be blank");
        }
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive, was: " + ttl);
        }

        long id;
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO node_command (
                  target_node, world_id, generation, command, payload, expires_at
                ) VALUES (
                  ?, ?, ?, ?, ?::jsonb, now() + (? * INTERVAL '1 second')
                )
                RETURNING id
                """)) {
            statement.setString(1, targetNode);
            if (worldId == null) {
                statement.setNull(2, Types.OTHER);
            } else {
                statement.setObject(2, worldId.value());
            }
            if (generation == null) {
                statement.setNull(3, Types.BIGINT);
            } else {
                statement.setLong(3, generation);
            }
            statement.setString(4, command);
            statement.setString(5, payloadJson);
            statement.setDouble(6, ttl.toMillis() / 1000.0);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("INSERT node_command RETURNING id produced no row");
                }
                id = rows.getLong(1);
            }
        }

        // Same transaction as the insert (CP-2). Payload is the id so the
        // listener can claim without a second lookup of "what changed".
        try (PreparedStatement notify = connection.prepareStatement("SELECT pg_notify(?, ?)")) {
            notify.setString(1, notifyChannel);
            notify.setString(2, Long.toString(id));
            notify.execute();
        }
        return id;
    }

    /**
     * Convenience enqueue in its own transaction.
     *
     * @see #enqueue(Connection, String, WorldId, Long, String, String, Duration, String)
     */
    public long enqueue(
            String targetNode,
            @Nullable WorldId worldId,
            @Nullable Long generation,
            String command,
            String payloadJson,
            Duration ttl,
            String notifyChannel)
            throws SQLException {
        return database.inTransaction(connection ->
                enqueue(connection, targetNode, worldId, generation, command, payloadJson, ttl, notifyChannel));
    }

    /**
     * Claims one command if it is still open, unexpired, and either unclaimed or
     * past the claim timeout (CP-5).
     *
     * @return the claimed row, or empty when another claimer won or the row is
     *     no longer claimable
     */
    public Optional<NodeCommand> claim(long id, Duration claimTimeout) throws SQLException {
        Objects.requireNonNull(claimTimeout, "claimTimeout");
        if (claimTimeout.isNegative() || claimTimeout.isZero()) {
            throw new IllegalArgumentException("claimTimeout must be positive, was: " + claimTimeout);
        }
        return database.inTransaction(connection -> claim(connection, id, claimTimeout));
    }

    public Optional<NodeCommand> claim(Connection connection, long id, Duration claimTimeout) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(claimTimeout, "claimTimeout");
        return queryOne(
                connection,
                """
                UPDATE node_command
                   SET claimed_at = now(),
                       attempts = attempts + 1
                 WHERE id = ?
                   AND completed_at IS NULL
                   AND expires_at > now()
                   AND (
                     claimed_at IS NULL
                     OR claimed_at < now() - (? * INTERVAL '1 second')
                   )
                RETURNING
                """ + SELECT_COLUMNS,
                statement -> {
                    statement.setLong(1, id);
                    statement.setDouble(2, claimTimeout.toMillis() / 1000.0);
                },
                NodeCommandRepository::mapRow);
    }

    /**
     * Ids this target may try to claim, oldest first. Includes rows whose claim
     * has timed out so CP-5 retries land on the poll path as well as on a late
     * NOTIFY.
     */
    public List<Long> findClaimableIds(String targetNode, Duration claimTimeout, int limit) throws SQLException {
        Objects.requireNonNull(targetNode, "targetNode");
        Objects.requireNonNull(claimTimeout, "claimTimeout");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1, was: " + limit);
        }
        return database.withConnection(connection -> queryList(
                connection,
                """
                SELECT id
                  FROM node_command
                 WHERE target_node = ?
                   AND completed_at IS NULL
                   AND expires_at > now()
                   AND (
                     claimed_at IS NULL
                     OR claimed_at < now() - (? * INTERVAL '1 second')
                   )
                 ORDER BY id
                 LIMIT ?
                """,
                statement -> {
                    statement.setString(1, targetNode);
                    statement.setDouble(2, claimTimeout.toMillis() / 1000.0);
                    statement.setInt(3, limit);
                },
                row -> row.getLong(1)));
    }

    /**
     * Marks the command finished. Idempotent: a second complete on an already
     * finished row affects zero rows and returns false.
     */
    public boolean complete(long id, CommandResult result) throws SQLException {
        Objects.requireNonNull(result, "result");
        return database.inTransaction(connection -> complete(connection, id, result));
    }

    public boolean complete(Connection connection, long id, CommandResult result) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(result, "result");
        int updated = execute(connection, """
                UPDATE node_command
                   SET completed_at = now(),
                       result = ?
                 WHERE id = ?
                   AND completed_at IS NULL
                """, statement -> {
            statement.setString(1, result.wire());
            statement.setLong(2, id);
        });
        return updated == 1;
    }

    /** Loads one row by id, including completed ones. */
    public Optional<NodeCommand> findById(long id) throws SQLException {
        return database.withConnection(connection -> queryOne(
                connection,
                "SELECT " + SELECT_COLUMNS + " FROM node_command WHERE id = ?",
                statement -> statement.setLong(1, id),
                NodeCommandRepository::mapRow));
    }

    /**
     * Current lease generation of a world, if the world still exists.
     *
     * <p>Used for CP-4 staleness rejection after claim. Empty means the world is
     * gone; callers treat that as stale rather than running a handler against
     * nothing.
     */
    public Optional<Long> worldGeneration(UUID worldId) throws SQLException {
        Objects.requireNonNull(worldId, "worldId");
        return database.withConnection(connection -> queryOne(
                connection,
                "SELECT generation FROM player_world WHERE id = ?",
                statement -> statement.setObject(1, worldId),
                row -> row.getLong(1)));
    }

    private static NodeCommand mapRow(ResultSet row) throws SQLException {
        long id = row.getLong("id");
        String targetNode = Objects.requireNonNull(row.getString("target_node"), "target_node");
        UUID worldUuid = row.getObject("world_id", UUID.class);
        WorldId worldId = worldUuid == null ? null : new WorldId(worldUuid);
        long generationRaw = row.getLong("generation");
        Long generation = row.wasNull() ? null : generationRaw;
        String command = Objects.requireNonNull(row.getString("command"), "command");
        String payload = Objects.requireNonNull(row.getString("payload"), "payload");
        Instant createdAt = requireInstant(row, "created_at");
        Instant expiresAt = requireInstant(row, "expires_at");
        Instant claimedAt = optionalInstant(row, "claimed_at");
        Instant completedAt = optionalInstant(row, "completed_at");
        int attempts = row.getInt("attempts");
        String result = row.getString("result");
        return new NodeCommand(
                id,
                targetNode,
                worldId,
                generation,
                command,
                payload,
                createdAt,
                expiresAt,
                claimedAt,
                completedAt,
                attempts,
                result);
    }

    private static Instant requireInstant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        if (value == null) {
            throw new SQLException(column + " was NULL");
        }
        return value.toInstant();
    }

    private static @Nullable Instant optionalInstant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    /**
     * Removes rows that are finished with (CP-7, FR-40).
     *
     * <p>CP-7 says "expired and completed rows are swept by the maintenance job
     * in FR-40", and nothing swept them: the table grew for the life of the
     * network and {@code findClaimableIds} scanned an ever-larger index on every
     * poll of every node.
     *
     * <p>Both shapes go: a completed row, and a row whose {@code expires_at} has
     * passed without anyone completing it — the second is a command whose window
     * closed, which {@code findClaimableIds} already refuses to hand out.
     *
     * @param retain how long a finished row is kept for diagnostics and for the
     *     producer to read its result back
     * @param limit most rows to delete in one sweep, so the lock is not held
     *     while a long-neglected table is emptied
     * @return rows removed
     */
    public int deleteFinishedBefore(Duration retain, int limit) throws SQLException {
        Objects.requireNonNull(retain, "retain");
        if (retain.isNegative()) {
            throw new IllegalArgumentException("retain must not be negative, was: " + retain);
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1, was: " + limit);
        }
        return database.inTransaction(connection -> execute(connection, """
                DELETE FROM node_command
                 WHERE id IN (
                   SELECT id
                     FROM node_command
                    WHERE (completed_at IS NOT NULL AND completed_at <= now() - (? * INTERVAL '1 second'))
                       OR (completed_at IS NULL AND expires_at <= now() - (? * INTERVAL '1 second'))
                    ORDER BY id
                    LIMIT ?
                 )
                """, statement -> {
            statement.setDouble(1, retain.toMillis() / 1000.0);
            statement.setDouble(2, retain.toMillis() / 1000.0);
            statement.setInt(3, limit);
        }));
    }
}
