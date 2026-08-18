package nl.gzmn.playerworlds.core.db;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import nl.gzmn.playerworlds.core.model.WorldBan;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.jspecify.annotations.Nullable;

/**
 * {@code player_world_ban} (FR-9d).
 *
 * <p>Per-world bans independent of network-wide bans. Banning removes membership,
 * revokes active invites, and prevents rejoin even while the world is public.
 */
public final class WorldBanRepository extends Repository {

    public WorldBanRepository(Database database) {
        super(database);
    }

    /**
     * Creates or updates a per-world ban.
     *
     * @param worldId the world
     * @param target the target player to ban
     * @param bannedBy the player issuing the ban
     * @param reason optional ban reason
     * @return the created/updated ban
     */
    public WorldBan ban(WorldId worldId, UUID target, UUID bannedBy, @Nullable String reason) throws SQLException {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(bannedBy, "bannedBy");

        return database.inTransaction(connection -> queryOne(
                        connection,
                        """
                        INSERT INTO player_world_ban (world_id, uuid, banned_by, reason)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT (world_id, uuid) DO UPDATE
                          SET banned_by = excluded.banned_by,
                              reason = excluded.reason,
                              banned_at = now()
                        RETURNING world_id, uuid, banned_by, reason, banned_at
                        """,
                        statement -> {
                            statement.setObject(1, worldId.value());
                            statement.setObject(2, target);
                            statement.setObject(3, bannedBy);
                            statement.setString(4, reason);
                        },
                        WorldBanRepository::mapBan)
                .orElseThrow(() -> new SQLException("INSERT player_world_ban RETURNING produced no row")));
    }

    /**
     * Removes a ban from a world.
     *
     * @return true if a ban row was removed
     */
    public boolean unban(WorldId worldId, UUID target) throws SQLException {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(target, "target");

        return database.inTransaction(connection ->
                execute(connection, "DELETE FROM player_world_ban WHERE world_id = ? AND uuid = ?", statement -> {
                            statement.setObject(1, worldId.value());
                            statement.setObject(2, target);
                        })
                        == 1);
    }

    /** Checks whether a player is banned from a world. */
    public boolean isBanned(WorldId worldId, UUID target) throws SQLException {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(target, "target");

        return database.withConnection(connection -> queryOne(
                        connection,
                        "SELECT world_id, uuid, banned_by, reason, banned_at FROM player_world_ban WHERE world_id = ? AND uuid = ?",
                        statement -> {
                            statement.setObject(1, worldId.value());
                            statement.setObject(2, target);
                        },
                        WorldBanRepository::mapBan)
                .isPresent());
    }

    /** Finds a specific ban if present. */
    public Optional<WorldBan> findBan(WorldId worldId, UUID target) throws SQLException {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(target, "target");

        return database.withConnection(connection -> queryOne(
                connection,
                "SELECT world_id, uuid, banned_by, reason, banned_at FROM player_world_ban WHERE world_id = ? AND uuid = ?",
                statement -> {
                    statement.setObject(1, worldId.value());
                    statement.setObject(2, target);
                },
                WorldBanRepository::mapBan));
    }

    /** Lists all bans for a world, newest first. */
    public List<WorldBan> listBans(WorldId worldId) throws SQLException {
        Objects.requireNonNull(worldId, "worldId");

        return database.withConnection(connection -> queryList(
                connection,
                "SELECT world_id, uuid, banned_by, reason, banned_at FROM player_world_ban WHERE world_id = ? ORDER BY banned_at DESC",
                statement -> statement.setObject(1, worldId.value()),
                WorldBanRepository::mapBan));
    }

    private static WorldBan mapBan(ResultSet row) throws SQLException {
        OffsetDateTime bannedAt = row.getObject("banned_at", OffsetDateTime.class);
        if (bannedAt == null) {
            throw new SQLException("banned_at was NULL");
        }
        return new WorldBan(
                new WorldId(Objects.requireNonNull(row.getObject("world_id", UUID.class), "world_id")),
                Objects.requireNonNull(row.getObject("uuid", UUID.class), "uuid"),
                Objects.requireNonNull(row.getObject("banned_by", UUID.class), "banned_by"),
                row.getString("reason"),
                bannedAt.toInstant());
    }
}
