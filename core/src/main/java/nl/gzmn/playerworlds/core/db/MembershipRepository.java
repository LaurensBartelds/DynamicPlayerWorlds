package nl.gzmn.playerworlds.core.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import nl.gzmn.playerworlds.core.model.Role;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.model.WorldInvite;
import nl.gzmn.playerworlds.core.model.WorldMember;
import org.jspecify.annotations.Nullable;

/**
 * {@code player_world_member} and {@code player_world_invite} (FR-6 to FR-9).
 *
 * <p>The two tables share a repository because the operation that matters spans
 * both: FR-7's accept consumes an invite and creates a membership, and it has to
 * do so atomically or a player can end up with neither.
 *
 * <p>Every expiry is a predicate in the SQL, evaluated in database time. Reading
 * {@code expires_at} back and comparing it to a local clock would make an invite
 * live on one node and dead on another, which is the lease-clock mistake in a
 * cheaper costume (CONTRIBUTING rule 5).
 */
public final class MembershipRepository extends Repository {

    public MembershipRepository(Database database) {
        super(database);
    }

    // -----------------------------------------------------------------------
    // Invites (FR-6, FR-7)
    // -----------------------------------------------------------------------

    /**
     * Creates or refreshes an invite, expiring {@code expiry} from <em>database</em>
     * now.
     *
     * <p>Re-inviting somebody who already has a live invite refreshes the clock
     * rather than failing. That is what an owner means by running the command
     * twice, and the alternative is an error message about a row the player
     * cannot see.
     *
     * @return the stored invite
     */
    public WorldInvite invite(WorldId worldId, UUID target, UUID invitedBy, Duration expiry) throws SQLException {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(invitedBy, "invitedBy");
        Objects.requireNonNull(expiry, "expiry");
        if (expiry.isNegative() || expiry.isZero()) {
            throw new IllegalArgumentException("expiry must be positive, was: " + expiry);
        }

        return database.inTransaction(connection -> queryOne(
                        connection,
                        """
                        INSERT INTO player_world_invite (world_id, uuid, invited_by, expires_at)
                        VALUES (?, ?, ?, now() + (? * INTERVAL '1 second'))
                        ON CONFLICT (world_id, uuid) DO UPDATE
                          SET invited_by = excluded.invited_by,
                              expires_at = excluded.expires_at
                        RETURNING world_id, uuid, invited_by, expires_at
                        """,
                        statement -> {
                            statement.setObject(1, worldId.value());
                            statement.setObject(2, target);
                            statement.setObject(3, invitedBy);
                            statement.setDouble(4, expiry.toMillis() / 1000.0);
                        },
                        MembershipRepository::mapInvite)
                .orElseThrow(() -> new SQLException("INSERT player_world_invite RETURNING produced no row")));
    }

    /** A live (unexpired) invite for this player to this world, if one exists. */
    public Optional<WorldInvite> findLiveInvite(WorldId worldId, UUID uuid) throws SQLException {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(uuid, "uuid");
        return database.withConnection(connection -> queryOne(
                connection,
                """
                SELECT world_id, uuid, invited_by, expires_at
                  FROM player_world_invite
                 WHERE world_id = ? AND uuid = ? AND expires_at > now()
                """,
                statement -> {
                    statement.setObject(1, worldId.value());
                    statement.setObject(2, uuid);
                },
                MembershipRepository::mapInvite));
    }

    /** Every live invite for this player, oldest expiry first. */
    public List<WorldInvite> findLiveInvitesFor(UUID uuid) throws SQLException {
        Objects.requireNonNull(uuid, "uuid");
        return database.withConnection(connection ->
                queryList(connection, """
                SELECT world_id, uuid, invited_by, expires_at
                  FROM player_world_invite
                 WHERE uuid = ? AND expires_at > now()
                 ORDER BY expires_at
                """, statement -> statement.setObject(1, uuid), MembershipRepository::mapInvite));
    }

    /** Withdraws an invite. Idempotent. */
    public boolean revokeInvite(WorldId worldId, UUID uuid) throws SQLException {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(uuid, "uuid");
        return database.inTransaction(connection ->
                execute(connection, "DELETE FROM player_world_invite WHERE world_id = ? AND uuid = ?", statement -> {
                            statement.setObject(1, worldId.value());
                            statement.setObject(2, uuid);
                        })
                        == 1);
    }

    /** What {@link #acceptInvite} did. */
    public sealed interface AcceptOutcome {

        /** The invite became a membership at {@link Role#BUILDER} (FR-9c). */
        record Accepted(WorldMember member) implements AcceptOutcome {
            public Accepted {
                Objects.requireNonNull(member, "member");
            }
        }

        /** No invite, or it had expired. The two are one answer on purpose: see below. */
        record NoLiveInvite() implements AcceptOutcome {}

        /** Already a member; the invite is consumed anyway so it cannot linger. */
        record AlreadyMember(Role role) implements AcceptOutcome {
            public AlreadyMember {
                Objects.requireNonNull(role, "role");
            }
        }
    }

    /**
     * Consumes a live invite and creates the membership, atomically (FR-7).
     *
     * <p>One transaction because the two halves are worthless apart: a consumed
     * invite with no membership locks the player out of a world they were invited
     * to, and a membership with a surviving invite leaves a row that FR-40 has to
     * sweep and that a second accept would act on.
     *
     * <p>"No invite" and "expired invite" collapse into one outcome deliberately.
     * Distinguishing them tells an uninvited player that a world exists and that
     * somebody was invited to it, which is a small leak of exactly the kind
     * section 5.5 spends its length preventing.
     */
    public AcceptOutcome acceptInvite(WorldId worldId, UUID uuid) throws SQLException {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(uuid, "uuid");

        return database.inTransaction(connection -> {
            Optional<UUID> invitedBy = queryOne(
                    connection,
                    """
                    DELETE FROM player_world_invite
                     WHERE world_id = ? AND uuid = ? AND expires_at > now()
                    RETURNING invited_by
                    """,
                    statement -> {
                        statement.setObject(1, worldId.value());
                        statement.setObject(2, uuid);
                    },
                    row -> Objects.requireNonNull(row.getObject("invited_by", UUID.class), "invited_by"));

            if (invitedBy.isEmpty()) {
                return new AcceptOutcome.NoLiveInvite();
            }

            Optional<WorldMember> existing = findMember(connection, worldId, uuid);
            if (existing.isPresent()) {
                // The invite is already gone by the DELETE above, which is what we
                // want: a member holding an unusable invite is only litter.
                return new AcceptOutcome.AlreadyMember(existing.get().role());
            }

            // FR-9c: an invite may grant BUILDER directly, unlike walking into a
            // public world, which grants VISITOR.
            insertMember(connection, worldId, uuid, Role.BUILDER, invitedBy.get());
            return new AcceptOutcome.Accepted(new WorldMember(worldId, uuid, Role.BUILDER, invitedBy.get(), null));
        });
    }

    // -----------------------------------------------------------------------
    // Membership (FR-8, FR-9)
    // -----------------------------------------------------------------------

    /**
     * Inserts a membership inside an existing transaction.
     *
     * <p>Takes a {@link Connection} so world creation can write the owner's own
     * row in the same transaction as the world (FR-31a expects both to exist),
     * and so accept can pair it with consuming the invite.
     */
    public int insertMember(Connection connection, WorldId worldId, UUID uuid, Role role, @Nullable UUID invitedBy)
            throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(role, "role");
        return execute(connection, """
                INSERT INTO player_world_member (world_id, uuid, role, invited_by)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (world_id, uuid) DO NOTHING
                """, statement -> {
            statement.setObject(1, worldId.value());
            statement.setObject(2, uuid);
            statement.setString(3, role.wire());
            statement.setObject(4, invitedBy);
        });
    }

    public Optional<WorldMember> findMember(WorldId worldId, UUID uuid) throws SQLException {
        return database.withConnection(connection -> findMember(connection, worldId, uuid));
    }

    public Optional<WorldMember> findMember(Connection connection, WorldId worldId, UUID uuid) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(uuid, "uuid");
        return queryOne(
                connection,
                """
                SELECT world_id, uuid, role, invited_by, joined_at
                  FROM player_world_member
                 WHERE world_id = ? AND uuid = ?
                """,
                statement -> {
                    statement.setObject(1, worldId.value());
                    statement.setObject(2, uuid);
                },
                MembershipRepository::mapMember);
    }

    /**
     * Every member of a world, owner first and then by role.
     *
     * <p>Ordered by role rather than by name so {@code /world members} reads as a
     * hierarchy, which is what an owner is looking at it for.
     */
    public List<WorldMember> listMembers(WorldId worldId) throws SQLException {
        Objects.requireNonNull(worldId, "worldId");
        return database.withConnection(connection -> queryList(
                connection,
                """
                SELECT world_id, uuid, role, invited_by, joined_at
                  FROM player_world_member
                 WHERE world_id = ?
                 ORDER BY CASE role WHEN 'OWNER' THEN 0 WHEN 'BUILDER' THEN 1 ELSE 2 END, joined_at NULLS LAST
                """,
                statement -> statement.setObject(1, worldId.value()),
                MembershipRepository::mapMember));
    }

    /**
     * Every member's role, for a node to cache while the world is loaded.
     *
     * <p>Role enforcement runs on the tick thread and cannot query (NFR-2), so
     * the node reads this once at load and keeps it until the control plane says
     * membership changed.
     */
    public Map<UUID, Role> rolesIn(WorldId worldId) throws SQLException {
        Objects.requireNonNull(worldId, "worldId");
        List<WorldMember> members = listMembers(worldId);
        Map<UUID, Role> roles = new LinkedHashMap<>(members.size());
        for (WorldMember member : members) {
            roles.put(member.uuid(), member.role());
        }
        return Map.copyOf(roles);
    }

    /** Worlds this player is a member of, for {@code /world join} and tab completion. */
    public List<WorldMember> membershipsOf(UUID uuid) throws SQLException {
        Objects.requireNonNull(uuid, "uuid");
        return database.withConnection(connection ->
                queryList(connection, """
                SELECT world_id, uuid, role, invited_by, joined_at
                  FROM player_world_member
                 WHERE uuid = ?
                """, statement -> statement.setObject(1, uuid), MembershipRepository::mapMember));
    }

    /**
     * Removes a membership (FR-8).
     *
     * <p>Refuses to remove the world's owner. The caller is expected to have
     * checked, but this is the statement that would otherwise leave a world whose
     * {@code owner_uuid} points at a non-member — and ownership only moves through
     * FR-29's transfer, never through a kick.
     *
     * @return true when a row was removed
     */
    public boolean removeMember(WorldId worldId, UUID uuid) throws SQLException {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(uuid, "uuid");
        return database.inTransaction(connection -> execute(connection, """
                        DELETE FROM player_world_member m
                         WHERE m.world_id = ? AND m.uuid = ?
                           AND NOT EXISTS (
                             SELECT 1 FROM player_world w
                              WHERE w.id = m.world_id AND w.owner_uuid = m.uuid
                           )
                        """, statement -> {
                    statement.setObject(1, worldId.value());
                    statement.setObject(2, uuid);
                })
                == 1);
    }

    /**
     * Changes a member's role (FR-9c's promote).
     *
     * <p>Cannot set or clear {@code OWNER}: that is FR-29's transfer, which also
     * has to move {@code player_world.owner_uuid} and write an ownership-log row,
     * and a role change that did only half of it would leave the two disagreeing
     * — the exact state FR-31a exists to rule out.
     *
     * @return true when a row changed
     */
    public boolean setRole(WorldId worldId, UUID uuid, Role role) throws SQLException {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(role, "role");
        if (role == Role.OWNER) {
            throw new IllegalArgumentException(
                    "ownership moves through the FR-29 transfer, which also updates player_world.owner_uuid "
                            + "and writes an ownership-log row; setRole cannot grant OWNER");
        }
        return database.inTransaction(connection -> execute(connection, """
                        UPDATE player_world_member
                           SET role = ?
                         WHERE world_id = ? AND uuid = ? AND role <> 'OWNER'
                        """, statement -> {
                    statement.setString(1, role.wire());
                    statement.setObject(2, worldId.value());
                    statement.setObject(3, uuid);
                })
                == 1);
    }

    /** Records that a member has entered the world, in database time. */
    public boolean markJoined(WorldId worldId, UUID uuid) throws SQLException {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(uuid, "uuid");
        return database.inTransaction(connection -> execute(connection, """
                        UPDATE player_world_member
                           SET joined_at = now()
                         WHERE world_id = ? AND uuid = ? AND joined_at IS NULL
                        """, statement -> {
                    statement.setObject(1, worldId.value());
                    statement.setObject(2, uuid);
                })
                == 1);
    }

    private static WorldMember mapMember(ResultSet row) throws SQLException {
        return new WorldMember(
                new WorldId(Objects.requireNonNull(row.getObject("world_id", UUID.class), "world_id")),
                Objects.requireNonNull(row.getObject("uuid", UUID.class), "uuid"),
                Role.fromWire(Objects.requireNonNull(row.getString("role"), "role")),
                row.getObject("invited_by", UUID.class),
                optionalInstant(row, "joined_at"));
    }

    private static WorldInvite mapInvite(ResultSet row) throws SQLException {
        OffsetDateTime expires = row.getObject("expires_at", OffsetDateTime.class);
        if (expires == null) {
            throw new SQLException("expires_at was NULL");
        }
        return new WorldInvite(
                new WorldId(Objects.requireNonNull(row.getObject("world_id", UUID.class), "world_id")),
                Objects.requireNonNull(row.getObject("uuid", UUID.class), "uuid"),
                Objects.requireNonNull(row.getObject("invited_by", UUID.class), "invited_by"),
                expires.toInstant());
    }

    private static @Nullable Instant optionalInstant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
