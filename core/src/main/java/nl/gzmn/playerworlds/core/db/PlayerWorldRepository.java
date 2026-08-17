package nl.gzmn.playerworlds.core.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.Visibility;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.model.WorldState;
import org.jspecify.annotations.Nullable;

/**
 * JDBC access to {@code player_world}.
 *
 * <p>Milestone 1 uses the creation, lookup and state-transition statements. The
 * lease columns — {@code assigned_node}, {@code lease_expires},
 * {@code generation} — are deliberately not written here: MN-8's acquisition is
 * one conditional {@code UPDATE} whose predicate is the entire correctness
 * argument, and a milestone-1 statement that set those columns without that
 * predicate would read like a lease while providing none of its guarantees. They
 * arrive with milestone 7.
 */
public final class PlayerWorldRepository extends Repository {

    private static final String SELECT_COLUMNS = """
            id, owner_uuid, name, folder, seed, border_radius, visibility, description,
            settings::text AS settings_json, assigned_node, lease_expires, generation,
            manifest_key, data_version, mc_version, created_at, last_played, state
            """;

    public PlayerWorldRepository(Database database) {
        super(database);
    }

    /**
     * Inserts a world in {@link WorldState#CREATING} and returns the stored row.
     *
     * <p>Takes a {@link Connection} so the caller owns the transaction: a create
     * that fails during generation has to remove the row it inserted, and the cap
     * check in FR-1 has to see a consistent count.
     *
     * <p>{@code created_at} comes from {@code now()} rather than from the caller.
     * Node clocks drift and every timestamp in this schema is compared against
     * others written by other nodes (CONTRIBUTING.md rule 5).
     *
     * @param folder must equal {@code id.folder()} (FR-2a); passed explicitly so
     *     the derivation is visible at the call site rather than implied
     */
    public PlayerWorld insertCreating(
            Connection connection,
            WorldId id,
            UUID ownerUuid,
            String name,
            String folder,
            long seed,
            int borderRadius,
            Visibility visibility)
            throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(folder, "folder");
        Objects.requireNonNull(visibility, "visibility");
        if (!folder.equals(id.folder())) {
            throw new IllegalArgumentException(
                    "folder must be derived from the world id (FR-2a): expected " + id.folder() + ", was " + folder);
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (borderRadius < 1) {
            throw new IllegalArgumentException("borderRadius must be at least 1, was: " + borderRadius);
        }

        return queryOne(
                        connection,
                        """
                        INSERT INTO player_world (
                          id, owner_uuid, name, folder, seed, border_radius, visibility, state
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, 'CREATING')
                        RETURNING
                        """ + SELECT_COLUMNS,
                        statement -> {
                            statement.setObject(1, id.value());
                            statement.setObject(2, ownerUuid);
                            statement.setString(3, name);
                            statement.setString(4, folder);
                            statement.setLong(5, seed);
                            statement.setInt(6, borderRadius);
                            statement.setString(7, visibility.wire());
                        },
                        PlayerWorldRepository::mapRow)
                .orElseThrow(() -> new SQLException("INSERT player_world RETURNING produced no row"));
    }

    /**
     * Creates a world in its own transaction (FR-1, FR-2, FR-2a).
     *
     * <p>The overload above exists so a caller inside {@code :core} can compose
     * the insert with other statements; this one exists so a caller <em>outside</em>
     * {@code :core} never has to hold a {@link Connection}. Keeping JDBC types on
     * this side of the module boundary is what the ArchUnit rule in each plugin
     * module enforces, and it is the same rule that keeps NFR-2 structural.
     *
     * @param seed shared by all three dimensions (FR-2)
     */
    public PlayerWorld create(
            WorldId id, UUID ownerUuid, String name, long seed, int borderRadius, Visibility visibility)
            throws SQLException {
        Objects.requireNonNull(id, "id");
        return database.inTransaction(connection ->
                insertCreating(connection, id, ownerUuid, name, id.folder(), seed, borderRadius, visibility));
    }

    /**
     * Promotes a world to {@code READY} and records that it was played, in one
     * transaction.
     *
     * <p>One transaction rather than two: a world that is {@code READY} but has
     * never been played reads as abandoned to FR-34's archival scan, and a crash
     * between the two statements would leave it that way.
     *
     * @return true when this call performed the transition
     */
    public boolean markReadyAndPlayed(WorldId id) throws SQLException {
        Objects.requireNonNull(id, "id");
        return database.inTransaction(connection -> {
            boolean promoted = markReady(connection, id);
            touchLastPlayed(connection, id);
            return promoted;
        });
    }

    /** {@link #touchLastPlayed(Connection, WorldId)} in its own transaction. */
    public boolean touchLastPlayed(WorldId id) throws SQLException {
        Objects.requireNonNull(id, "id");
        return database.inTransaction(connection -> touchLastPlayed(connection, id));
    }

    /** {@link #deleteIfCreating(Connection, WorldId)} in its own transaction. */
    public boolean deleteIfCreating(WorldId id) throws SQLException {
        Objects.requireNonNull(id, "id");
        return database.inTransaction(connection -> deleteIfCreating(connection, id));
    }

    /** One world by id, in any state. */
    public Optional<PlayerWorld> findById(WorldId id) throws SQLException {
        Objects.requireNonNull(id, "id");
        return database.withConnection(connection -> findById(connection, id));
    }

    public Optional<PlayerWorld> findById(Connection connection, WorldId id) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(id, "id");
        return queryOne(
                connection,
                "SELECT " + SELECT_COLUMNS + " FROM player_world WHERE id = ?",
                statement -> statement.setObject(1, id.value()),
                PlayerWorldRepository::mapRow);
    }

    /**
     * One world by owner and name. Backs {@code /world join <owner> [name]} and
     * the duplicate-name refusal at create, which exists so a player gets a
     * message rather than a unique-constraint violation.
     */
    public Optional<PlayerWorld> findByOwnerAndName(UUID ownerUuid, String name) throws SQLException {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(name, "name");
        return database.withConnection(connection -> findByOwnerAndName(connection, ownerUuid, name));
    }

    public Optional<PlayerWorld> findByOwnerAndName(Connection connection, UUID ownerUuid, String name)
            throws SQLException {
        Objects.requireNonNull(connection, "connection");
        return queryOne(
                connection,
                "SELECT " + SELECT_COLUMNS + " FROM player_world WHERE owner_uuid = ? AND name = ?",
                statement -> {
                    statement.setObject(1, ownerUuid);
                    statement.setString(2, name);
                },
                PlayerWorldRepository::mapRow);
    }

    /** Every world this player owns, newest first. */
    public List<PlayerWorld> listOwnedBy(UUID ownerUuid) throws SQLException {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        return database.withConnection(connection -> queryList(
                connection,
                "SELECT " + SELECT_COLUMNS + " FROM player_world WHERE owner_uuid = ? ORDER BY created_at DESC",
                statement -> statement.setObject(1, ownerUuid),
                PlayerWorldRepository::mapRow));
    }

    /**
     * Worlds counting against {@code worlds.max-per-player} (FR-1).
     *
     * <p>{@code ARCHIVED} worlds are excluded, and that follows from FR-27 rather
     * than from preference: {@code /world delete} <em>is</em> the archival flow in
     * FR-35, so it sets {@code state} to {@code ARCHIVED} rather than removing the
     * row. If archived worlds counted, deleting a world would not free a slot and
     * a player could never make room — which is the one thing delete is for.
     * Restoring an archive (FR-36) brings the world back to {@code READY} and so
     * back under the cap, where FR-30's repeat-the-check-at-acceptance rule
     * already establishes the pattern of re-checking at the moment a world
     * re-enters the count.
     *
     * <p>Membership of someone else's world never counts (FR-1), which is why this
     * reads {@code owner_uuid} and not {@code player_world_member}.
     */
    public int countOwnedBy(UUID ownerUuid) throws SQLException {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        return database.withConnection(connection -> countOwnedBy(connection, ownerUuid));
    }

    public int countOwnedBy(Connection connection, UUID ownerUuid) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        return queryOne(
                        connection,
                        "SELECT count(*) FROM player_world WHERE owner_uuid = ? AND state <> 'ARCHIVED'",
                        statement -> statement.setObject(1, ownerUuid),
                        row -> row.getInt(1))
                .orElse(0);
    }

    /**
     * Promotes a world from {@code CREATING} to {@code READY}.
     *
     * <p>Conditional on the current state, so a concurrent archival or a retry of
     * a create that already finished cannot drag a world backwards. Zero rows
     * affected means the world was not in {@code CREATING}; the caller decides
     * whether that is a problem.
     *
     * @return true when this call performed the transition
     */
    public boolean markReady(Connection connection, WorldId id) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(id, "id");
        return execute(
                        connection,
                        "UPDATE player_world SET state = 'READY' WHERE id = ? AND state = 'CREATING'",
                        statement -> statement.setObject(1, id.value()))
                == 1;
    }

    /**
     * Records that the world was played, in database time.
     *
     * <p>Feeds the auto-archival scan (FR-34) and the browse ordering (FR-9b), so
     * it must be database time: a node with a fast clock would otherwise push its
     * worlds to the top of the browse list and out of the archival window.
     */
    public boolean touchLastPlayed(Connection connection, WorldId id) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(id, "id");
        return execute(
                        connection,
                        "UPDATE player_world SET last_played = now() WHERE id = ?",
                        statement -> statement.setObject(1, id.value()))
                == 1;
    }

    /**
     * Removes a world row outright.
     *
     * <p>This is <em>not</em> {@code /world delete}, which archives (FR-27, FR-37).
     * It exists for one case: a create that failed before the world reached
     * {@code READY}, where the row is the only thing that exists and leaving it
     * would consume the owner's cap forever. Conditional on {@code CREATING} so it
     * can never remove a world somebody has played.
     *
     * @return true when a row was removed
     */
    public boolean deleteIfCreating(Connection connection, WorldId id) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(id, "id");
        return execute(
                        connection,
                        "DELETE FROM player_world WHERE id = ? AND state = 'CREATING'",
                        statement -> statement.setObject(1, id.value()))
                == 1;
    }

    private static PlayerWorld mapRow(ResultSet row) throws SQLException {
        UUID id = Objects.requireNonNull(row.getObject("id", UUID.class), "id");
        UUID ownerUuid = Objects.requireNonNull(row.getObject("owner_uuid", UUID.class), "owner_uuid");
        int dataVersionRaw = row.getInt("data_version");
        Integer dataVersion = row.wasNull() ? null : dataVersionRaw;
        return new PlayerWorld(
                new WorldId(id),
                ownerUuid,
                Objects.requireNonNull(row.getString("name"), "name"),
                Objects.requireNonNull(row.getString("folder"), "folder"),
                row.getLong("seed"),
                row.getInt("border_radius"),
                Visibility.fromWire(Objects.requireNonNull(row.getString("visibility"), "visibility")),
                row.getString("description"),
                Objects.requireNonNull(row.getString("settings_json"), "settings"),
                row.getString("assigned_node"),
                optionalInstant(row, "lease_expires"),
                row.getLong("generation"),
                row.getString("manifest_key"),
                dataVersion,
                row.getString("mc_version"),
                requireInstant(row, "created_at"),
                optionalInstant(row, "last_played"),
                WorldState.fromWire(Objects.requireNonNull(row.getString("state"), "state")));
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
}
