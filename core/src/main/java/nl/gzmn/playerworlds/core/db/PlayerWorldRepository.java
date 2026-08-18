package nl.gzmn.playerworlds.core.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
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
            manifest_key, data_version, mc_version, created_at, last_played, state, storage_bytes
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
                           mc_version   = ?,
                           last_node    = COALESCE(?, last_node)
                     WHERE id = ?
                       AND (?::text IS NULL OR assigned_node IS NULL OR assigned_node = ?)
                       AND generation = ?
                    """, statement -> {
                statement.setString(1, manifestKey);
                statement.setInt(2, dataVersion);
                statement.setString(3, mcVersion);
                statement.setString(4, nodeId);
                statement.setObject(5, id.value());
                statement.setString(6, nodeId);
                statement.setString(7, nodeId);
                statement.setLong(8, generation);
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
     * Lists all PUBLIC worlds in READY state for {@code /world browse} (FR-9b).
     *
     * <p>Ordered by {@code last_played DESC NULLS LAST}, using the {@code player_world_public_idx} index.
     */
    public List<PlayerWorld> listPublicWorlds() throws SQLException {
        return database.withConnection(connection -> queryList(
                connection,
                "SELECT " + SELECT_COLUMNS
                        + " FROM player_world WHERE visibility = 'PUBLIC' AND state = 'READY' ORDER BY last_played DESC NULLS LAST",
                StatementBinder.NONE,
                PlayerWorldRepository::mapRow));
    }

    /**
     * Updates visibility and description of a world (FR-9a, FR-9f).
     *
     * @return true if the world was updated
     */
    public boolean updateVisibility(WorldId id, Visibility visibility, @Nullable String description)
            throws SQLException {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(visibility, "visibility");
        return database.inTransaction(connection -> execute(
                        connection,
                        "UPDATE player_world SET visibility = ?, description = ? WHERE id = ?",
                        statement -> {
                            statement.setString(1, visibility.wire());
                            statement.setString(2, description);
                            statement.setObject(3, id.value());
                        })
                == 1);
    }

    /**
     * Updates per-world owner settings (FR-9e).
     *
     * @return true if the world was updated
     */
    public boolean updateSettings(WorldId id, String settingsJson) throws SQLException {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(settingsJson, "settingsJson");
        return database.inTransaction(connection ->
                execute(connection, "UPDATE player_world SET settings = ?::jsonb WHERE id = ?", statement -> {
                            statement.setString(1, settingsJson);
                            statement.setObject(2, id.value());
                        })
                        == 1);
    }

    /**
     * Atomically transfers world ownership to a new owner (FR-31, FR-31a).
     *
     * <p>In a single transaction:
     * <ol>
     *   <li>Updates {@code player_world.owner_uuid} to {@code newOwnerUuid} conditionally on {@code oldOwnerUuid}</li>
     *   <li>Ensures {@code newOwnerUuid} exists in {@code player_world_member} and sets role to {@link Role#OWNER}</li>
     *   <li>Demotes {@code oldOwnerUuid} in {@code player_world_member} to {@link Role#BUILDER}</li>
     *   <li>Inserts a row into {@code player_world_ownership_log}</li>
     *   <li>Removes any pending {@code player_world_transfer_request} for this world and target</li>
     * </ol>
     *
     * @return true if the transfer succeeded, false if the old owner did not match
     */
    public boolean transferOwnership(WorldId worldId, UUID oldOwnerUuid, UUID newOwnerUuid, String reason)
            throws SQLException {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(oldOwnerUuid, "oldOwnerUuid");
        Objects.requireNonNull(newOwnerUuid, "newOwnerUuid");
        Objects.requireNonNull(reason, "reason");

        return database.inTransaction(connection -> {
            int updated = execute(
                    connection, "UPDATE player_world SET owner_uuid = ? WHERE id = ? AND owner_uuid = ?", statement -> {
                        statement.setObject(1, newOwnerUuid);
                        statement.setObject(2, worldId.value());
                        statement.setObject(3, oldOwnerUuid);
                    });

            if (updated != 1) {
                return false;
            }

            // 1. Ensure target member is OWNER
            execute(connection, """
                    INSERT INTO player_world_member (world_id, uuid, role)
                    VALUES (?, ?, 'OWNER')
                    ON CONFLICT (world_id, uuid) DO UPDATE SET role = 'OWNER'
                    """, statement -> {
                statement.setObject(1, worldId.value());
                statement.setObject(2, newOwnerUuid);
            });

            // 2. Demote old owner to BUILDER
            execute(
                    connection,
                    "UPDATE player_world_member SET role = 'BUILDER' WHERE world_id = ? AND uuid = ?",
                    statement -> {
                        statement.setObject(1, worldId.value());
                        statement.setObject(2, oldOwnerUuid);
                    });

            // 3. Insert audit log
            execute(connection, """
                    INSERT INTO player_world_ownership_log (world_id, from_uuid, to_uuid, reason)
                    VALUES (?, ?, ?, ?)
                    """, statement -> {
                statement.setObject(1, worldId.value());
                statement.setObject(2, oldOwnerUuid);
                statement.setObject(3, newOwnerUuid);
                statement.setString(4, reason);
            });

            // 4. Delete any pending transfer requests
            execute(
                    connection,
                    "DELETE FROM player_world_transfer_request WHERE world_id = ? AND to_uuid = ?",
                    statement -> {
                        statement.setObject(1, worldId.value());
                        statement.setObject(2, newOwnerUuid);
                    });

            return true;
        });
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

    /**
     * Destroys a world row and everything that references it (FR-37).
     *
     * <p>The only path in the system that removes an archive. FR-37 makes it an administrator
     * action with typed confirmation for that reason: {@code /world delete} archives, FR-40's
     * sweeps prune snapshots and manifests, and nothing else deletes a {@code player_world_archive}
     * row at all. Members, bans, invites, archives and queued commands go with it through the
     * schema's {@code ON DELETE CASCADE}.
     *
     * <p>The archive objects themselves outlive this call and are collected by FR-40's object
     * storage sweep (MN-2b), which is the only component that can see the bucket.
     *
     * @return true when a row was removed
     */
    public boolean deleteHard(Connection connection, WorldId id) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(id, "id");
        return execute(
                        connection,
                        "DELETE FROM player_world WHERE id = ?",
                        statement -> statement.setObject(1, id.value()))
                == 1;
    }

    /** {@link #deleteHard(Connection, WorldId)} in its own transaction. */
    public boolean deleteHard(WorldId id) throws SQLException {
        Objects.requireNonNull(id, "id");
        return database.inTransaction(connection -> deleteHard(connection, id));
    }

    // -----------------------------------------------------------------------
    // Placement (MN-14, MN-15a, MN-16)
    // -----------------------------------------------------------------------

    /**
     * Everything placement needs about one world, read in database time.
     *
     * @param leaseHolder the node whose lease on this world is live <em>now</em>,
     *     or {@code null} when no lease is live. MN-14 routes to it without
     *     scoring and MN-16 requires that every member resolve to the same node,
     *     so this is a hard answer rather than a preference.
     * @param warmNode the node that wrote {@code manifest_key}, and therefore the
     *     node most likely to hold a matching local copy (MN-15a, MN-5). A
     *     preference: the copy may have been evicted or quarantined.
     * @param dataVersion the world's committed chunk {@code DataVersion}, the
     *     MN-28 hard filter, or {@code null} when it has never been committed
     * @param visibility drives MN-15a's public/private separation
     * @param state so a caller can refuse a world that is not enterable without a
     *     second round trip
     */
    public record PlacementContext(
            @Nullable String leaseHolder,
            @Nullable String warmNode,
            @Nullable Integer dataVersion,
            Visibility visibility,
            WorldState state) {

        public PlacementContext {
            Objects.requireNonNull(visibility, "visibility");
            Objects.requireNonNull(state, "state");
        }
    }

    /**
     * Reads one world's placement context (MN-14, MN-15a, MN-28).
     *
     * <p>Lease liveness is evaluated by the database, in the same {@code now()}
     * that MN-8's acquisition compares against. Comparing {@code lease_expires} to
     * a node's own clock instead is the bug CONTRIBUTING rule 5 exists to prevent:
     * the proxy, the holding node and the taking node all have independent clocks,
     * and the answer to "is this lease live" has to be the same for all three.
     */
    public Optional<PlacementContext> placementContext(WorldId id) throws SQLException {
        Objects.requireNonNull(id, "id");
        return database.withConnection(connection ->
                queryOne(connection, """
                SELECT CASE WHEN lease_expires > now() THEN assigned_node END AS lease_holder,
                       last_node, data_version, visibility, state
                  FROM player_world
                 WHERE id = ?
                """, statement -> statement.setObject(1, id.value()), row -> {
                    int dataVersionRaw = row.getInt("data_version");
                    Integer dataVersion = row.wasNull() ? null : dataVersionRaw;
                    return new PlacementContext(
                            row.getString("lease_holder"),
                            row.getString("last_node"),
                            dataVersion,
                            Visibility.fromWire(Objects.requireNonNull(row.getString("visibility"), "visibility")),
                            WorldState.fromWire(Objects.requireNonNull(row.getString("state"), "state")));
                }));
    }

    /**
     * The node holding a live lease on this world right now, in database time.
     *
     * <p>The narrow form of {@link #placementContext(WorldId)}, for the node-side
     * question "do I still hold this" where nothing else is wanted.
     */
    public Optional<String> leaseHolder(WorldId id) throws SQLException {
        Objects.requireNonNull(id, "id");
        return database.withConnection(connection -> queryOne(
                        connection,
                        """
                        SELECT assigned_node
                          FROM player_world
                         WHERE id = ?
                           AND assigned_node IS NOT NULL
                           AND lease_expires > now()
                        """,
                        statement -> statement.setObject(1, id.value()),
                        row -> row.getString("assigned_node"))
                .filter(node -> node != null));
    }

    /** How many live-leased worlds of each visibility a node is holding (MN-15a). */
    public record NodeOccupancy(int publicWorlds, int privateWorlds) {

        public static final NodeOccupancy EMPTY = new NodeOccupancy(0, 0);

        /** Total live-leased worlds on the node. */
        public int total() {
            return publicWorlds + privateWorlds;
        }
    }

    /**
     * Live-lease occupancy per node, for MN-15a's public/private separation.
     *
     * <p>Counted from the lease rather than from the node heartbeat's
     * {@code loaded_worlds}, because the heartbeat carries a total and MN-15a
     * needs the split — and because the lease is the fact the world's placement
     * was decided against, up to `node.heartbeat-seconds` fresher.
     */
    public Map<String, NodeOccupancy> liveLeaseOccupancy() throws SQLException {
        Map<String, NodeOccupancy> byNode = new HashMap<>();
        List<Map.Entry<String, NodeOccupancy>> rows = database.withConnection(connection -> queryList(
                connection,
                """
                SELECT assigned_node,
                       count(*) FILTER (WHERE visibility = 'PUBLIC')  AS public_worlds,
                       count(*) FILTER (WHERE visibility = 'PRIVATE') AS private_worlds
                  FROM player_world
                 WHERE assigned_node IS NOT NULL
                   AND lease_expires > now()
                 GROUP BY assigned_node
                """,
                StatementBinder.NONE,
                row -> Map.entry(
                        Objects.requireNonNull(row.getString("assigned_node"), "assigned_node"),
                        new NodeOccupancy(row.getInt("public_worlds"), row.getInt("private_worlds")))));
        for (Map.Entry<String, NodeOccupancy> entry : rows) {
            byNode.put(entry.getKey(), entry.getValue());
        }
        return Map.copyOf(byNode);
    }

    /**
     * Worlds a node currently holds a live lease on, for MN-22's drain.
     *
     * <p>Read from the lease rather than from the node's own registry so a drain
     * issued from the proxy can report what it is about to move without a round
     * trip to the node.
     */
    public List<WorldId> worldsLeasedTo(String nodeId) throws SQLException {
        Objects.requireNonNull(nodeId, "nodeId");
        return database.withConnection(connection -> queryList(
                connection,
                """
                SELECT id
                  FROM player_world
                 WHERE assigned_node = ?
                   AND lease_expires > now()
                 ORDER BY id
                """,
                statement -> statement.setString(1, nodeId),
                row -> new WorldId(Objects.requireNonNull(row.getObject("id", UUID.class), "id"))));
    }

    /**
     * Returns the total storage bytes used by all worlds owned by the specified player (FR-34, FR-35, FR-36).
     */
    public long totalStorageUsedBy(Connection connection, UUID ownerUuid) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        return queryOne(
                        connection,
                        "SELECT COALESCE(SUM(storage_bytes), 0) FROM player_world WHERE owner_uuid = ?",
                        statement -> statement.setObject(1, ownerUuid),
                        row -> row.getLong(1))
                .orElse(0L);
    }

    public long totalStorageUsedBy(UUID ownerUuid) throws SQLException {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        return database.withConnection(connection -> totalStorageUsedBy(connection, ownerUuid));
    }

    /**
     * Updates the storage footprint in bytes for a world.
     */
    public boolean updateStorageBytes(Connection connection, WorldId worldId, long storageBytes) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(worldId, "worldId");
        if (storageBytes < 0) {
            throw new IllegalArgumentException("storageBytes must not be negative: " + storageBytes);
        }
        return execute(connection, "UPDATE player_world SET storage_bytes = ? WHERE id = ?", statement -> {
                    statement.setLong(1, storageBytes);
                    statement.setObject(2, worldId.value());
                })
                == 1;
    }

    public boolean updateStorageBytes(WorldId worldId, long storageBytes) throws SQLException {
        Objects.requireNonNull(worldId, "worldId");
        return database.inTransaction(connection -> updateStorageBytes(connection, worldId, storageBytes));
    }

    /**
     * Moves a world to {@link WorldState#ARCHIVED}, clears its lease and manifest pointers,
     * updates its storage footprint, and records the archive entry in a single transaction (FR-35).
     *
     * @return true if the world was transitioned and the archive entry recorded
     */
    public boolean transitionToArchived(
            Connection connection, WorldId worldId, String objectKey, long sizeBytes, String checksum, int dataVersion)
            throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(objectKey, "objectKey");
        Objects.requireNonNull(checksum, "checksum");
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must not be negative: " + sizeBytes);
        }
        int updated = execute(connection, """
                UPDATE player_world
                   SET state = 'ARCHIVED',
                       storage_bytes = ?,
                       manifest_key = NULL,
                       assigned_node = NULL,
                       lease_expires = NULL
                 WHERE id = ?
                   AND state IN ('ARCHIVING', 'READY')
                """, statement -> {
            statement.setLong(1, sizeBytes);
            statement.setObject(2, worldId.value());
        });
        if (updated != 1) {
            return false;
        }
        ArchiveRepository archiveRepository = new ArchiveRepository(database);
        archiveRepository.recordArchive(connection, worldId, objectKey, sizeBytes, checksum, dataVersion);
        return true;
    }

    public boolean transitionToArchived(
            WorldId worldId, String objectKey, long sizeBytes, String checksum, int dataVersion) throws SQLException {
        return database.inTransaction(
                connection -> transitionToArchived(connection, worldId, objectKey, sizeBytes, checksum, dataVersion));
    }

    /**
     * Acquires a lease on an archived world and moves its state to {@link WorldState#RESTORING} (FR-36).
     *
     * @return true if the world was transitioned and the lease granted to {@code node}
     */
    public boolean transitionToRestoring(Connection connection, WorldId worldId, String node, Duration leaseDuration)
            throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        return execute(connection, """
                        UPDATE player_world
                           SET state = 'RESTORING',
                               assigned_node = ?,
                               lease_expires = now() + (? * interval '1 second'),
                               generation = generation + 1
                         WHERE id = ?
                           AND state IN ('ARCHIVED', 'RESTORING')
                           AND (assigned_node IS NULL OR lease_expires < now())
                        """, statement -> {
                    statement.setString(1, node);
                    statement.setLong(2, leaseDuration.toSeconds());
                    statement.setObject(3, worldId.value());
                })
                == 1;
    }

    public boolean transitionToRestoring(WorldId worldId, String node, Duration leaseDuration) throws SQLException {
        return database.inTransaction(connection -> transitionToRestoring(connection, worldId, node, leaseDuration));
    }

    /**
     * Returns a failed restore to {@link WorldState#ARCHIVED} and releases the lease (FR-36).
     *
     * <p>FR-36 leaves a <em>crashed</em> restore at RESTORING with an expired lease, which the
     * FR-40 sweep retries. A restore that fails cleanly is a different case: this node knows the
     * attempt is over, so the world goes back to the state it was in and the lease is dropped at
     * once, rather than making the owner wait out the lease for a failure already diagnosed.
     * Safe at any point in the flow, because restore never deletes the archive it reads.
     *
     * <p>Fenced on {@code assigned_node}: a node whose lease has already been taken over must not
     * rewrite the state underneath whoever holds it now.
     *
     * @return true when this node held the world and the rollback was applied
     */
    public boolean abandonRestore(Connection connection, WorldId worldId, String node) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(node, "node");
        return execute(connection, """
                UPDATE player_world
                   SET state = 'ARCHIVED',
                       assigned_node = NULL,
                       lease_expires = NULL
                 WHERE id = ?
                   AND state = 'RESTORING'
                   AND assigned_node = ?
                """, statement -> {
                    statement.setObject(1, worldId.value());
                    statement.setString(2, node);
                })
                == 1;
    }

    /** {@link #abandonRestore(Connection, WorldId, String)} in its own transaction. */
    public boolean abandonRestore(WorldId worldId, String node) throws SQLException {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(node, "node");
        return database.inTransaction(connection -> abandonRestore(connection, worldId, node));
    }

    /**
     * Completes a world restore by advancing its state to {@link WorldState#READY}, setting its
     * manifest pointer and storage bytes, updating versions, and releasing its lease (FR-36).
     *
     * @return true if the restore was committed
     */
    public boolean completeRestore(
            Connection connection,
            WorldId worldId,
            String manifestKey,
            long storageBytes,
            int dataVersion,
            String mcVersion)
            throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(manifestKey, "manifestKey");
        Objects.requireNonNull(mcVersion, "mcVersion");
        if (storageBytes < 0) {
            throw new IllegalArgumentException("storageBytes must not be negative: " + storageBytes);
        }
        return execute(connection, """
                        UPDATE player_world
                           SET state = 'READY',
                               manifest_key = ?,
                               storage_bytes = ?,
                               data_version = ?,
                              mc_version = ?,
                               last_played = now(),
                               assigned_node = NULL,
                               lease_expires = NULL
                         WHERE id = ?
                           AND state = 'RESTORING'
                        """, statement -> {
                    statement.setString(1, manifestKey);
                    statement.setLong(2, storageBytes);
                    statement.setInt(3, dataVersion);
                    statement.setString(4, mcVersion);
                    statement.setObject(5, worldId.value());
                })
                == 1;
    }

    public boolean completeRestore(
            WorldId worldId, String manifestKey, long storageBytes, int dataVersion, String mcVersion)
            throws SQLException {
        return database.inTransaction(
                connection -> completeRestore(connection, worldId, manifestKey, storageBytes, dataVersion, mcVersion));
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
                WorldState.fromWire(Objects.requireNonNull(row.getString("state"), "state")),
                row.getLong("storage_bytes"));
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
