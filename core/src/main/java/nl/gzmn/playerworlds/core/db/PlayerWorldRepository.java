package nl.gzmn.playerworlds.core.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.Role;
import nl.gzmn.playerworlds.core.model.Visibility;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.model.WorldState;
import org.jspecify.annotations.Nullable;

/**
 * JDBC access to {@code player_world}.
 *
 * <p>Handles world creation, lookups, state transitions, snapshot commits,
 * and atomic lease operations (MN-8, MN-9, MN-12, MN-26).
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
     * Grant returned on successful lease acquisition (MN-8).
     */
    public record LeaseGrant(long generation, Instant expiresAt) {
        public LeaseGrant {
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    /**
     * Atomically acquires a lease on a world for a node (MN-8, MN-26).
     *
     * <p>Acquisition succeeds only if the world is currently unassigned or its existing
     * lease has expired, and the node's chunk DataVersion satisfies the version predicate.
     *
     * @return the new lease generation and expiration timestamp, or empty if acquisition failed
     */
    public Optional<LeaseGrant> acquireLease(
            Connection connection, WorldId id, String nodeId, int myDataVersion, Duration leaseDuration)
            throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(leaseDuration, "leaseDuration");

        return queryOne(
                connection,
                """
                UPDATE player_world
                   SET assigned_node = ?,
                       lease_expires = now() + (? * interval '1 second'),
                       generation    = generation + 1
                 WHERE id = ?
                   AND (assigned_node IS NULL OR lease_expires < now())
                   AND (data_version IS NULL OR data_version <= ?)
                RETURNING generation, lease_expires
                """,
                statement -> {
                    statement.setString(1, nodeId);
                    statement.setLong(2, leaseDuration.toSeconds());
                    statement.setObject(3, id.value());
                    statement.setInt(4, myDataVersion);
                },
                row -> new LeaseGrant(row.getLong("generation"), requireInstant(row, "lease_expires")));
    }

    public Optional<LeaseGrant> acquireLease(WorldId id, String nodeId, int myDataVersion, Duration leaseDuration)
            throws SQLException {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        return database.inTransaction(connection -> acquireLease(connection, id, nodeId, myDataVersion, leaseDuration));
    }

    /**
     * Heartbeats to renew a lease currently held by this node under the specified generation (MN-9).
     *
     * @return the new expiration timestamp in DB time, or empty if the lease was lost / fenced
     */
    public Optional<Instant> renewLease(
            Connection connection, WorldId id, String nodeId, long generation, Duration leaseDuration)
            throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(leaseDuration, "leaseDuration");

        return queryOne(
                connection,
                """
                UPDATE player_world
                   SET lease_expires = now() + (? * interval '1 second')
                 WHERE id = ?
                   AND assigned_node = ?
                   AND generation = ?
                RETURNING lease_expires
                """,
                statement -> {
                    statement.setLong(1, leaseDuration.toSeconds());
                    statement.setObject(2, id.value());
                    statement.setString(3, nodeId);
                    statement.setLong(4, generation);
                },
                row -> requireInstant(row, "lease_expires"));
    }

    public Optional<Instant> renewLease(WorldId id, String nodeId, long generation, Duration leaseDuration)
            throws SQLException {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        return database.inTransaction(connection -> renewLease(connection, id, nodeId, generation, leaseDuration));
    }

    /**
     * Atomically releases a held lease on clean unload or server shutdown (MN-12, FR-25, FR-28).
     */
    public boolean releaseLease(Connection connection, WorldId id, String nodeId, long generation) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(nodeId, "nodeId");

        return execute(connection, """
                UPDATE player_world
                   SET assigned_node = NULL,
                       lease_expires = NULL
                 WHERE id = ?
                   AND assigned_node = ?
                   AND generation = ?
                """, statement -> {
                    statement.setObject(1, id.value());
                    statement.setString(2, nodeId);
                    statement.setLong(3, generation);
                })
                == 1;
    }

    public boolean releaseLease(WorldId id, String nodeId, long generation) throws SQLException {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(nodeId, "nodeId");
        return database.inTransaction(connection -> releaseLease(connection, id, nodeId, generation));
    }

    /**
     * Inserts a world in {@link WorldState#CREATING} and returns the stored row.
     *
     * <p>Takes a {@link Connection} so the caller owns the transaction: a create
     * that fails during generation has to remove the row it inserted, and the cap
     * check in FR-1 has to see a consistent count.
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
            Visibility visibility,
            @Nullable String assignedNode,
            @Nullable Duration initialLease)
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

        if (assignedNode != null && initialLease != null) {
            return queryOne(
                            connection,
                            """
                            INSERT INTO player_world (
                              id, owner_uuid, name, folder, seed, border_radius, visibility, state,
                              assigned_node, lease_expires, generation
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, 'CREATING', ?, now() + (? * interval '1 second'), 1)
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
                                statement.setString(8, assignedNode);
                                statement.setLong(9, initialLease.toSeconds());
                            },
                            PlayerWorldRepository::mapRow)
                    .orElseThrow(() -> new SQLException("INSERT player_world RETURNING produced no row"));
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
        return insertCreating(connection, id, ownerUuid, name, folder, seed, borderRadius, visibility, null, null);
    }

    /**
     * Creates a world in its own transaction (FR-1, FR-2, FR-2a).
     */
    public PlayerWorld create(
            WorldId id,
            UUID ownerUuid,
            String name,
            long seed,
            int borderRadius,
            Visibility visibility,
            @Nullable String assignedNode,
            @Nullable Duration initialLease)
            throws SQLException {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        MembershipRepository membership = new MembershipRepository(database);
        return database.inTransaction(connection -> {
            PlayerWorld row = insertCreating(
                    connection,
                    id,
                    ownerUuid,
                    name,
                    id.folder(),
                    seed,
                    borderRadius,
                    visibility,
                    assignedNode,
                    initialLease);
            membership.insertMember(connection, id, ownerUuid, Role.OWNER, null);
            return row;
        });
    }

    public PlayerWorld create(
            WorldId id, UUID ownerUuid, String name, long seed, int borderRadius, Visibility visibility)
            throws SQLException {
        return create(id, ownerUuid, name, seed, borderRadius, visibility, null, null);
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

    /**
     * Moves a world between states, only from the state the caller expects.
     *
     * <p>Conditional on {@code from} so two concurrent operations cannot both
     * believe they won: FR-35's archival and FR-36's restore are each written as
     * a state transition precisely so a crash leaves a state the FR-40 sweep can
     * recognise and resume from.
     *
     * @return true when this call performed the transition
     */
    public boolean transitionState(WorldId id, WorldState from, WorldState to) throws SQLException {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        return database.inTransaction(connection ->
                execute(connection, "UPDATE player_world SET state = ? WHERE id = ? AND state = ?", statement -> {
                            statement.setString(1, to.wire());
                            statement.setObject(2, id.value());
                            statement.setString(3, from.wire());
                        })
                        == 1);
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

    /**
     * Commits a world snapshot and its player profiles in a single transaction (FR-15, FR-16, MN-3a).
     *
     * <p>Updates the manifest pointer, version tags, and {@code last_played} timestamp
     * conditionally on the lease generation and assigned node matching. If the fencing
     * check passes and profiles are non-empty, saves all profile rows within the same
     * database transaction.
     *
     * @return true if the commit succeeded, false if fenced by lease expiration or generation bump
     */
    public boolean commitSnapshot(
            WorldId id,
            long generation,
            @Nullable String nodeId,
            String manifestKey,
            int dataVersion,
            String mcVersion,
            ProfileRepository.Snapshot snapshot,
            int profileFormatVersion,
            Map<UUID, byte[]> profiles,
            ProfileRepository profileRepository)
            throws SQLException {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(manifestKey, "manifestKey");
        Objects.requireNonNull(mcVersion, "mcVersion");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(profiles, "profiles");
        Objects.requireNonNull(profileRepository, "profileRepository");

        return database.inTransaction(connection -> {
            int updated = execute(connection, """
                    UPDATE player_world
                       SET manifest_key = ?,
                           last_played  = now(),
                           data_version = ?,
                           mc_version   = ?
                     WHERE id = ?
                       AND (?::text IS NULL OR assigned_node IS NULL OR assigned_node = ?)
                       AND generation = ?
                    """, statement -> {
                statement.setString(1, manifestKey);
                statement.setInt(2, dataVersion);
                statement.setString(3, mcVersion);
                statement.setObject(4, id.value());
                statement.setString(5, nodeId);
                statement.setString(6, nodeId);
                statement.setLong(7, generation);
            });

            if (updated != 1) {
                return false;
            }

            if (!profiles.isEmpty()) {
                profileRepository.saveAll(connection, id, snapshot, profileFormatVersion, profiles);
            }
            return true;
        });
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
