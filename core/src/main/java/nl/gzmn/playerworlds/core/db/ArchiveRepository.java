package nl.gzmn.playerworlds.core.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import nl.gzmn.playerworlds.core.model.WorldArchive;
import nl.gzmn.playerworlds.core.model.WorldId;

/**
 * JDBC access to {@code player_world_archive} (FR-35, FR-36, FR-37).
 *
 * <p>Tracks cold-archived world backups, compression checksums, and restore counts.
 */
public final class ArchiveRepository extends Repository {

    private static final String SELECT_COLUMNS = """
            world_id, object_key, size_bytes, checksum, data_version, archived_at, restore_count
            """;

    public ArchiveRepository(Database database) {
        super(database);
    }

    /**
     * Records a new cold archive entry in database time.
     */
    public WorldArchive recordArchive(
            Connection connection, WorldId worldId, String objectKey, long sizeBytes, String checksum, int dataVersion)
            throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(objectKey, "objectKey");
        Objects.requireNonNull(checksum, "checksum");
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must not be negative: " + sizeBytes);
        }

        return queryOne(
                        connection,
                        "INSERT INTO player_world_archive ("
                                + "world_id, object_key, size_bytes, checksum, data_version"
                                + ") VALUES (?, ?, ?, ?, ?) RETURNING "
                                + SELECT_COLUMNS,
                        statement -> {
                            statement.setObject(1, worldId.value());
                            statement.setString(2, objectKey);
                            statement.setLong(3, sizeBytes);
                            statement.setString(4, checksum);
                            statement.setInt(5, dataVersion);
                        },
                        ArchiveRepository::mapRow)
                .orElseThrow(() -> new SQLException("INSERT player_world_archive RETURNING produced no row"));
    }

    public WorldArchive recordArchive(
            WorldId worldId, String objectKey, long sizeBytes, String checksum, int dataVersion) throws SQLException {
        return database.inTransaction(
                connection -> recordArchive(connection, worldId, objectKey, sizeBytes, checksum, dataVersion));
    }

    /**
     * Finds the most recent archive for a world, if any exists.
     */
    public Optional<WorldArchive> findLatestByWorld(Connection connection, WorldId worldId) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(worldId, "worldId");

        return queryOne(
                connection,
                "SELECT " + SELECT_COLUMNS
                        + " FROM player_world_archive WHERE world_id = ? ORDER BY archived_at DESC LIMIT 1",
                statement -> statement.setObject(1, worldId.value()),
                ArchiveRepository::mapRow);
    }

    public Optional<WorldArchive> findLatestByWorld(WorldId worldId) throws SQLException {
        Objects.requireNonNull(worldId, "worldId");
        return database.withConnection(connection -> findLatestByWorld(connection, worldId));
    }

    /**
     * Finds all archives for a world, ordered from newest to oldest.
     */
    public List<WorldArchive> findAllByWorld(Connection connection, WorldId worldId) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(worldId, "worldId");

        return queryList(
                connection,
                "SELECT " + SELECT_COLUMNS + " FROM player_world_archive WHERE world_id = ? ORDER BY archived_at DESC",
                statement -> statement.setObject(1, worldId.value()),
                ArchiveRepository::mapRow);
    }

    public List<WorldArchive> findAllByWorld(WorldId worldId) throws SQLException {
        Objects.requireNonNull(worldId, "worldId");
        return database.withConnection(connection -> findAllByWorld(connection, worldId));
    }

    /**
     * Increments the restore counter on a specific archive entry.
     */
    public boolean incrementRestoreCount(Connection connection, WorldId worldId, Instant archivedAt)
            throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(archivedAt, "archivedAt");

        return execute(connection, """
                        UPDATE player_world_archive
                           SET restore_count = restore_count + 1
                         WHERE world_id = ?
                           AND archived_at = ?
                        """, statement -> {
                    statement.setObject(1, worldId.value());
                    statement.setObject(2, OffsetDateTime.ofInstant(archivedAt, ZoneOffset.UTC));
                })
                == 1;
    }

    public boolean incrementRestoreCount(WorldId worldId, Instant archivedAt) throws SQLException {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(archivedAt, "archivedAt");
        return database.inTransaction(connection -> incrementRestoreCount(connection, worldId, archivedAt));
    }

    /**
     * Deletes a specific archive record (FR-37).
     */
    public boolean deleteArchive(Connection connection, WorldId worldId, Instant archivedAt) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(archivedAt, "archivedAt");

        return execute(connection, """
                        DELETE FROM player_world_archive
                         WHERE world_id = ?
                           AND archived_at = ?
                        """, statement -> {
                    statement.setObject(1, worldId.value());
                    statement.setObject(2, OffsetDateTime.ofInstant(archivedAt, ZoneOffset.UTC));
                })
                == 1;
    }

    public boolean deleteArchive(WorldId worldId, Instant archivedAt) throws SQLException {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(archivedAt, "archivedAt");
        return database.inTransaction(connection -> deleteArchive(connection, worldId, archivedAt));
    }

    private static WorldArchive mapRow(ResultSet row) throws SQLException {
        UUID worldId = Objects.requireNonNull(row.getObject("world_id", UUID.class), "world_id");
        return new WorldArchive(
                new WorldId(worldId),
                Objects.requireNonNull(row.getString("object_key"), "object_key"),
                row.getLong("size_bytes"),
                Objects.requireNonNull(row.getString("checksum"), "checksum"),
                row.getInt("data_version"),
                requireInstant(row, "archived_at"),
                row.getInt("restore_count"));
    }

    private static Instant requireInstant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        if (value == null) {
            throw new SQLException(column + " was NULL");
        }
        return value.toInstant();
    }
}
