package nl.gzmn.playerworlds.core.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import nl.gzmn.playerworlds.core.model.TransferRequest;
import nl.gzmn.playerworlds.core.model.WorldId;

/**
 * JDBC access to {@code player_world_transfer_request} (FR-32).
 */
public final class TransferRequestRepository extends Repository {

    public TransferRequestRepository(Database database) {
        super(database);
    }

    public TransferRequest requestTransfer(
            Connection connection, WorldId worldId, UUID toUuid, UUID fromUuid, Duration expiry) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(toUuid, "toUuid");
        Objects.requireNonNull(fromUuid, "fromUuid");
        Objects.requireNonNull(expiry, "expiry");

        return queryOne(
                        connection,
                        """
                        INSERT INTO player_world_transfer_request (world_id, to_uuid, from_uuid, expires_at)
                        VALUES (?, ?, ?, now() + (? * interval '1 second'))
                        ON CONFLICT (world_id, to_uuid) DO UPDATE
                          SET from_uuid = EXCLUDED.from_uuid,
                              expires_at = EXCLUDED.expires_at,
                              created_at = now()
                        RETURNING world_id, to_uuid, from_uuid, expires_at, created_at
                        """,
                        statement -> {
                            statement.setObject(1, worldId.value());
                            statement.setObject(2, toUuid);
                            statement.setObject(3, fromUuid);
                            statement.setLong(4, expiry.toSeconds());
                        },
                        TransferRequestRepository::mapRow)
                .orElseThrow(() -> new SQLException("INSERT player_world_transfer_request RETURNING produced no row"));
    }

    public TransferRequest requestTransfer(WorldId worldId, UUID toUuid, UUID fromUuid, Duration expiry)
            throws SQLException {
        return database.inTransaction(connection -> requestTransfer(connection, worldId, toUuid, fromUuid, expiry));
    }

    public Optional<TransferRequest> findLiveRequest(WorldId worldId, UUID toUuid) throws SQLException {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(toUuid, "toUuid");
        return database.withConnection(connection -> queryOne(
                connection,
                """
                SELECT world_id, to_uuid, from_uuid, expires_at, created_at
                  FROM player_world_transfer_request
                 WHERE world_id = ? AND to_uuid = ? AND expires_at > now()
                """,
                statement -> {
                    statement.setObject(1, worldId.value());
                    statement.setObject(2, toUuid);
                },
                TransferRequestRepository::mapRow));
    }

    public List<TransferRequest> findLiveRequestsFor(UUID toUuid) throws SQLException {
        Objects.requireNonNull(toUuid, "toUuid");
        return database.withConnection(connection -> queryList(
                connection, """
                SELECT world_id, to_uuid, from_uuid, expires_at, created_at
                  FROM player_world_transfer_request
                 WHERE to_uuid = ? AND expires_at > now()
                 ORDER BY created_at DESC
                """, statement -> statement.setObject(1, toUuid), TransferRequestRepository::mapRow));
    }

    public boolean deleteRequest(Connection connection, WorldId worldId, UUID toUuid) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(toUuid, "toUuid");
        return execute(
                        connection,
                        "DELETE FROM player_world_transfer_request WHERE world_id = ? AND to_uuid = ?",
                        statement -> {
                            statement.setObject(1, worldId.value());
                            statement.setObject(2, toUuid);
                        })
                >= 1;
    }

    public boolean deleteRequest(WorldId worldId, UUID toUuid) throws SQLException {
        return database.inTransaction(connection -> deleteRequest(connection, worldId, toUuid));
    }

    public int deleteExpired() throws SQLException {
        return database.inTransaction(connection -> execute(
                connection,
                "DELETE FROM player_world_transfer_request WHERE expires_at <= now()",
                StatementBinder.NONE));
    }

    private static TransferRequest mapRow(ResultSet row) throws SQLException {
        UUID worldId = Objects.requireNonNull(row.getObject("world_id", UUID.class), "world_id");
        UUID toUuid = Objects.requireNonNull(row.getObject("to_uuid", UUID.class), "to_uuid");
        UUID fromUuid = Objects.requireNonNull(row.getObject("from_uuid", UUID.class), "from_uuid");
        Instant expiresAt = requireInstant(row, "expires_at");
        Instant createdAt = requireInstant(row, "created_at");
        return new TransferRequest(new WorldId(worldId), toUuid, fromUuid, expiresAt, createdAt);
    }

    private static Instant requireInstant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        if (value == null) {
            throw new SQLException(column + " was NULL");
        }
        return value.toInstant();
    }
}
