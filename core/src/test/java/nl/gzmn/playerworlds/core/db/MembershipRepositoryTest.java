package nl.gzmn.playerworlds.core.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.Role;
import nl.gzmn.playerworlds.core.model.Visibility;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.model.WorldInvite;
import nl.gzmn.playerworlds.core.model.WorldMember;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Membership and invites against a real PostgreSQL (FR-6 to FR-9). */
class MembershipRepositoryTest {

    private Database database;
    private PlayerWorldRepository worlds;
    private MembershipRepository membership;
    private UUID owner;
    private WorldId worldId;

    @BeforeEach
    void openDatabase() throws Exception {
        database = TestPostgres.freshDatabase();
        Schema.migrate(database);
        worlds = new PlayerWorldRepository(database);
        membership = new MembershipRepository(database);
        owner = UUID.randomUUID();
        PlayerWorld world = worlds.create(WorldId.random(), owner, "home", 1L, 5000, Visibility.PRIVATE);
        worldId = world.id();
    }

    @AfterEach
    void closeDatabase() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    @DisplayName("creating a world gives the owner a membership row (FR-31a)")
    void ownerIsAMemberOfTheirOwnWorld() throws Exception {
        List<WorldMember> members = membership.listMembers(worldId);

        assertThat(members).hasSize(1);
        assertThat(members.getFirst().uuid()).isEqualTo(owner);
        assertThat(members.getFirst().role()).isEqualTo(Role.OWNER);
    }

    @Test
    @DisplayName("an invite becomes a BUILDER membership on accept (FR-7, FR-9c)")
    void acceptPromotesAnInviteToMembership() throws Exception {
        UUID target = UUID.randomUUID();
        membership.invite(worldId, target, owner, Duration.ofMinutes(10));

        MembershipRepository.AcceptOutcome outcome = membership.acceptInvite(worldId, target);

        assertThat(outcome).isInstanceOf(MembershipRepository.AcceptOutcome.Accepted.class);
        assertThat(membership.findMember(worldId, target).orElseThrow().role()).isEqualTo(Role.BUILDER);
        // The invite is consumed, so a second accept cannot act on it.
        assertThat(membership.findLiveInvite(worldId, target)).isEmpty();
    }

    @Test
    @DisplayName("an expired invite cannot be accepted, and expiry is decided by the database")
    void expiredInvitesAreRefused() throws Exception {
        UUID target = UUID.randomUUID();
        membership.invite(worldId, target, owner, Duration.ofMinutes(10));
        // Push expiry into the past using database time rather than the JVM's, so
        // the test proves the predicate rather than agreeing with a local clock.
        expireInvite(target);

        assertThat(membership.findLiveInvite(worldId, target)).isEmpty();
        assertThat(membership.acceptInvite(worldId, target))
                .isInstanceOf(MembershipRepository.AcceptOutcome.NoLiveInvite.class);
        assertThat(membership.findMember(worldId, target)).isEmpty();
    }

    @Test
    @DisplayName("accepting with no invite is the same answer as accepting an expired one")
    void missingAndExpiredInvitesAreIndistinguishable() throws Exception {
        // Telling an uninvited player that an invite once existed leaks that the
        // world exists and that somebody was invited to it.
        assertThat(membership.acceptInvite(worldId, UUID.randomUUID()))
                .isEqualTo(new MembershipRepository.AcceptOutcome.NoLiveInvite());
    }

    @Test
    @DisplayName("re-inviting refreshes the expiry rather than failing")
    void reInvitingRefreshesTheClock() throws Exception {
        UUID target = UUID.randomUUID();
        WorldInvite first = membership.invite(worldId, target, owner, Duration.ofMinutes(1));
        WorldInvite second = membership.invite(worldId, target, owner, Duration.ofMinutes(30));

        assertThat(second.expiresAt()).isAfter(first.expiresAt());
        assertThat(membership.findLiveInvitesFor(target)).hasSize(1);
    }

    @Test
    @DisplayName("accepting when already a member consumes the invite and changes nothing")
    void acceptingTwiceIsSafe() throws Exception {
        UUID target = UUID.randomUUID();
        membership.invite(worldId, target, owner, Duration.ofMinutes(10));
        membership.acceptInvite(worldId, target);
        membership.setRole(worldId, target, Role.VISITOR);
        membership.invite(worldId, target, owner, Duration.ofMinutes(10));

        MembershipRepository.AcceptOutcome outcome = membership.acceptInvite(worldId, target);

        assertThat(outcome).isEqualTo(new MembershipRepository.AcceptOutcome.AlreadyMember(Role.VISITOR));
        // Not silently re-promoted to BUILDER, and no invite left behind.
        assertThat(membership.findMember(worldId, target).orElseThrow().role()).isEqualTo(Role.VISITOR);
        assertThat(membership.findLiveInvite(worldId, target)).isEmpty();
    }

    @Test
    @DisplayName("a member can be kicked, and the owner cannot (FR-8)")
    void kickRemovesMembersButNeverTheOwner() throws Exception {
        UUID target = UUID.randomUUID();
        membership.invite(worldId, target, owner, Duration.ofMinutes(10));
        membership.acceptInvite(worldId, target);

        assertThat(membership.removeMember(worldId, target)).isTrue();
        assertThat(membership.findMember(worldId, target)).isEmpty();

        // Kicking the owner would leave owner_uuid pointing at a non-member.
        // Ownership only moves through FR-29's transfer.
        assertThat(membership.removeMember(worldId, owner)).isFalse();
        assertThat(membership.findMember(worldId, owner)).isPresent();
    }

    @Test
    @DisplayName("promote moves VISITOR to BUILDER but cannot grant OWNER (FR-9c, FR-29)")
    void setRoleCannotGrantOwnership() throws Exception {
        UUID target = UUID.randomUUID();
        membership.invite(worldId, target, owner, Duration.ofMinutes(10));
        membership.acceptInvite(worldId, target);
        membership.setRole(worldId, target, Role.VISITOR);

        assertThat(membership.setRole(worldId, target, Role.BUILDER)).isTrue();
        assertThat(membership.findMember(worldId, target).orElseThrow().role()).isEqualTo(Role.BUILDER);

        assertThatThrownBy(() -> membership.setRole(worldId, target, Role.OWNER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FR-29");

        // And the owner's own row cannot be demoted out from under owner_uuid.
        assertThat(membership.setRole(worldId, owner, Role.BUILDER)).isFalse();
    }

    @Test
    @DisplayName("rolesIn gives a node the whole membership in one read (NFR-2)")
    void rolesInReturnsEveryMember() throws Exception {
        UUID builder = UUID.randomUUID();
        UUID visitor = UUID.randomUUID();
        membership.invite(worldId, builder, owner, Duration.ofMinutes(10));
        membership.acceptInvite(worldId, builder);
        membership.invite(worldId, visitor, owner, Duration.ofMinutes(10));
        membership.acceptInvite(worldId, visitor);
        membership.setRole(worldId, visitor, Role.VISITOR);

        Map<UUID, Role> roles = membership.rolesIn(worldId);

        assertThat(roles).containsEntry(owner, Role.OWNER);
        assertThat(roles).containsEntry(builder, Role.BUILDER);
        assertThat(roles).containsEntry(visitor, Role.VISITOR);
    }

    @Test
    @DisplayName("members are listed owner first")
    void membersAreOrderedByRole() throws Exception {
        UUID visitor = UUID.randomUUID();
        membership.invite(worldId, visitor, owner, Duration.ofMinutes(10));
        membership.acceptInvite(worldId, visitor);
        membership.setRole(worldId, visitor, Role.VISITOR);

        assertThat(membership.listMembers(worldId))
                .extracting(WorldMember::role)
                .containsExactly(Role.OWNER, Role.VISITOR);
    }

    @Test
    @DisplayName("deleting a world takes its members and invites with it")
    void membershipCascadesFromTheWorld() throws Exception {
        UUID target = UUID.randomUUID();
        membership.invite(worldId, target, owner, Duration.ofMinutes(10));
        membership.acceptInvite(worldId, target);

        database.inTransaction(connection -> worlds.deleteIfCreating(connection, worldId));

        assertThat(membership.listMembers(worldId)).isEmpty();
        assertThat(membership.membershipsOf(target)).isEmpty();
    }

    @Test
    @DisplayName("membership of somebody else's world is visible to the member")
    void membershipsOfListsTheirWorlds() throws Exception {
        UUID target = UUID.randomUUID();
        membership.invite(worldId, target, owner, Duration.ofMinutes(10));
        membership.acceptInvite(worldId, target);

        assertThat(membership.membershipsOf(target))
                .singleElement()
                .satisfies(member -> assertThat(member.worldId()).isEqualTo(worldId));
    }

    @Test
    @DisplayName("markJoined records database time, once")
    void markJoinedIsIdempotent() throws Exception {
        UUID target = UUID.randomUUID();
        membership.invite(worldId, target, owner, Duration.ofMinutes(10));
        membership.acceptInvite(worldId, target);

        assertThat(membership.markJoined(worldId, target)).isTrue();
        assertThat(membership.markJoined(worldId, target)).isFalse();
        assertThat(membership.findMember(worldId, target).orElseThrow().joinedAt())
                .isNotNull();
    }

    @Test
    @DisplayName("revoking an invite is idempotent")
    void revokeIsIdempotent() throws Exception {
        UUID target = UUID.randomUUID();
        membership.invite(worldId, target, owner, Duration.ofMinutes(10));

        assertThat(membership.revokeInvite(worldId, target)).isTrue();
        assertThat(membership.revokeInvite(worldId, target)).isFalse();
    }

    @Test
    @DisplayName("addVisitorIfAbsent adds VISITOR role and does not downgrade BUILDER (FR-9c)")
    void addVisitorIfAbsent() throws Exception {
        UUID visitor = UUID.randomUUID();
        boolean added = membership.addVisitorIfAbsent(worldId, visitor);
        assertThat(added).isTrue();
        assertThat(membership.findMember(worldId, visitor).orElseThrow().role()).isEqualTo(Role.VISITOR);

        // Second call is idempotent
        boolean addedAgain = membership.addVisitorIfAbsent(worldId, visitor);
        assertThat(addedAgain).isFalse();

        // If member is BUILDER, addVisitorIfAbsent leaves them as BUILDER
        UUID builder = UUID.randomUUID();
        membership.invite(worldId, builder, owner, Duration.ofMinutes(10));
        membership.acceptInvite(worldId, builder);
        assertThat(membership.findMember(worldId, builder).orElseThrow().role()).isEqualTo(Role.BUILDER);

        membership.addVisitorIfAbsent(worldId, builder);
        assertThat(membership.findMember(worldId, builder).orElseThrow().role()).isEqualTo(Role.BUILDER);
    }

    private void expireInvite(UUID target) throws SQLException {
        database.inTransaction(connection -> {
            try (var statement = connection.prepareStatement(
                    "UPDATE player_world_invite SET expires_at = now() - INTERVAL '1 second' "
                            + "WHERE world_id = ? AND uuid = ?")) {
                statement.setObject(1, worldId.value());
                statement.setObject(2, target);
                return statement.executeUpdate();
            }
        });
    }
}
