package nl.gzmn.playerworlds.core.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.Visibility;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.model.WorldState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@code player_world} against a real PostgreSQL (CONTRIBUTING.md, Tests). */
class PlayerWorldRepositoryTest {

    private Database database;
    private PlayerWorldRepository worlds;

    @BeforeEach
    void openDatabase() throws Exception {
        database = TestPostgres.freshDatabase();
        Schema.migrate(database);
        worlds = new PlayerWorldRepository(database);
    }

    @AfterEach
    void closeDatabase() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    @DisplayName("a created world starts in CREATING with no lease and no data version")
    void insertStartsInCreating() throws Exception {
        UUID owner = UUID.randomUUID();
        WorldId id = WorldId.random();

        PlayerWorld created = create(id, owner, "home", 1234L);

        assertThat(created.id()).isEqualTo(id);
        assertThat(created.ownerUuid()).isEqualTo(owner);
        assertThat(created.name()).isEqualTo("home");
        assertThat(created.folder()).isEqualTo(id.folder());
        assertThat(created.seed()).isEqualTo(1234L);
        assertThat(created.state()).isEqualTo(WorldState.CREATING);
        assertThat(created.visibility()).isEqualTo(Visibility.PRIVATE);
        assertThat(created.settingsJson()).isEqualTo(PlayerWorld.EMPTY_SETTINGS);
        assertThat(created.createdAt()).isNotNull();

        // Milestone 1 writes no lease. A half-lease that looks like a real one is
        // worse than none, because MN-8's predicate is the whole guarantee.
        assertThat(created.assignedNode()).isNull();
        assertThat(created.leaseExpires()).isNull();
        assertThat(created.generation()).isZero();
        assertThat(created.manifestKey()).isNull();
        assertThat(created.dataVersion()).isNull();
        assertThat(created.lastPlayed()).isNull();
    }

    @Test
    @DisplayName("the folder is derived from the id, never from the name (FR-2a)")
    void folderMustFollowFromTheId() throws Exception {
        WorldId id = WorldId.random();
        UUID owner = UUID.randomUUID();

        assertThatThrownBy(() -> database.inTransaction(connection -> worlds.insertCreating(
                        connection, id, owner, "home", "some_other_folder", 1L, 5000, Visibility.PRIVATE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FR-2a");
    }

    @Test
    @DisplayName("a world is found by id and by (owner, name)")
    void lookupsResolveTheSameRow() throws Exception {
        UUID owner = UUID.randomUUID();
        WorldId id = WorldId.random();
        create(id, owner, "home", 7L);

        Optional<PlayerWorld> byId = worlds.findById(id);
        Optional<PlayerWorld> byName = worlds.findByOwnerAndName(owner, "home");

        assertThat(byId).isPresent();
        assertThat(byName).isPresent();
        assertThat(byName.orElseThrow().id()).isEqualTo(id);
        assertThat(worlds.findByOwnerAndName(owner, "nothing-here")).isEmpty();
        assertThat(worlds.findById(WorldId.random())).isEmpty();
    }

    @Test
    @DisplayName("two players may each own a world of the same name")
    void nameIsUniquePerOwnerNotGlobally() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        create(WorldId.random(), first, "home", 1L);
        create(WorldId.random(), second, "home", 2L);

        assertThat(worlds.countOwnedBy(first)).isEqualTo(1);
        assertThat(worlds.countOwnedBy(second)).isEqualTo(1);
    }

    @Test
    @DisplayName("one player may not own two worlds of the same name")
    void nameIsUniqueWithinAnOwner() throws Exception {
        UUID owner = UUID.randomUUID();
        create(WorldId.random(), owner, "home", 1L);

        assertThatThrownBy(() -> create(WorldId.random(), owner, "home", 2L)).isInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("the FR-1 cap counts owned worlds and ignores archived ones")
    void capCountsOwnedAndSkipsArchived() throws Exception {
        UUID owner = UUID.randomUUID();
        WorldId kept = WorldId.random();
        WorldId archived = WorldId.random();
        create(kept, owner, "keep", 1L);
        create(archived, owner, "gone", 2L);

        assertThat(worlds.countOwnedBy(owner)).isEqualTo(2);

        // /world delete is the FR-35 archival flow, so it archives rather than
        // removing the row. If archived worlds counted, delete would never free a
        // slot — which is the one thing it exists to do.
        setState(archived, WorldState.ARCHIVED);

        assertThat(worlds.countOwnedBy(owner)).isEqualTo(1);
    }

    @Test
    @DisplayName("membership of somebody else's world never counts against the cap (FR-1)")
    void membershipDoesNotCountAgainstTheCap() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        WorldId id = WorldId.random();
        create(id, owner, "home", 1L);

        database.inTransaction(connection -> {
            try (var statement = connection.prepareStatement(
                    "INSERT INTO player_world_member (world_id, uuid, role) VALUES (?, ?, 'BUILDER')")) {
                statement.setObject(1, id.value());
                statement.setObject(2, member);
                statement.executeUpdate();
            }
            return null;
        });

        assertThat(worlds.countOwnedBy(member)).isZero();
        assertThat(worlds.countOwnedBy(owner)).isEqualTo(1);
    }

    @Test
    @DisplayName("markReady promotes CREATING once and refuses a second time")
    void markReadyIsConditionalOnCreating() throws Exception {
        WorldId id = WorldId.random();
        create(id, UUID.randomUUID(), "home", 1L);

        boolean first = database.inTransaction(connection -> worlds.markReady(connection, id));
        boolean second = database.inTransaction(connection -> worlds.markReady(connection, id));

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(worlds.findById(id).orElseThrow().state()).isEqualTo(WorldState.READY);
    }

    @Test
    @DisplayName("a failed create removes its own row so the cap is not consumed")
    void deleteIfCreatingReclaimsAFailedCreate() throws Exception {
        UUID owner = UUID.randomUUID();
        WorldId id = WorldId.random();
        create(id, owner, "home", 1L);

        boolean removed = database.inTransaction(connection -> worlds.deleteIfCreating(connection, id));

        assertThat(removed).isTrue();
        assertThat(worlds.findById(id)).isEmpty();
        assertThat(worlds.countOwnedBy(owner)).isZero();
    }

    @Test
    @DisplayName("a world that has been played is never removed by the create-failure path")
    void deleteIfCreatingRefusesAReadyWorld() throws Exception {
        WorldId id = WorldId.random();
        create(id, UUID.randomUUID(), "home", 1L);
        database.inTransaction(connection -> worlds.markReady(connection, id));

        boolean removed = database.inTransaction(connection -> worlds.deleteIfCreating(connection, id));

        assertThat(removed).isFalse();
        assertThat(worlds.findById(id)).isPresent();
    }

    @Test
    @DisplayName("last_played is written in database time, not by the node")
    void lastPlayedComesFromTheDatabase() throws Exception {
        WorldId id = WorldId.random();
        create(id, UUID.randomUUID(), "home", 1L);

        boolean touched = database.inTransaction(connection -> worlds.touchLastPlayed(connection, id));
        PlayerWorld reloaded = worlds.findById(id).orElseThrow();

        assertThat(touched).isTrue();
        assertThat(reloaded.lastPlayed()).isNotNull();
        assertThat(reloaded.lastPlayed()).isAfterOrEqualTo(reloaded.createdAt());
    }

    @Test
    @DisplayName("listOwnedBy returns only that player's worlds")
    void listIsScopedToTheOwner() throws Exception {
        UUID owner = UUID.randomUUID();
        create(WorldId.random(), owner, "one", 1L);
        create(WorldId.random(), owner, "two", 2L);
        create(WorldId.random(), UUID.randomUUID(), "theirs", 3L);

        List<PlayerWorld> owned = worlds.listOwnedBy(owner);

        assertThat(owned).hasSize(2);
        assertThat(owned).allSatisfy(world -> assertThat(world.ownerUuid()).isEqualTo(owner));
    }

    @Test
    @DisplayName("a world newer than this node is refused, an unversioned one is not (MN-26)")
    void versionGateRefusesNewerWorlds() throws Exception {
        WorldId id = WorldId.random();
        PlayerWorld fresh = create(id, UUID.randomUUID(), "home", 1L);

        // No committed snapshot yet: nothing has pinned a data version, so any
        // node may open it.
        assertThat(fresh.isOpenableBy(4903)).isTrue();

        setDataVersion(id, 4903);
        assertThat(worlds.findById(id).orElseThrow().isOpenableBy(4903)).isTrue();
        assertThat(worlds.findById(id).orElseThrow().isOpenableBy(4902)).isFalse();
        assertThat(worlds.findById(id).orElseThrow().isOpenableBy(5000)).isTrue();
    }

    @Test
    @DisplayName("commitSnapshot updates manifest, version info, last_played, and profiles atomically")
    void commitsSnapshotAndProfilesInOneTransaction() throws Exception {
        WorldId id = WorldId.random();
        UUID owner = UUID.randomUUID();
        create(id, owner, "commit-world", 1234L);

        ProfileRepository profiles = new ProfileRepository(database);
        ProfileRepository.Snapshot snap = new ProfileRepository.Snapshot(0L, 1);
        UUID player1 = UUID.randomUUID();
        byte[] payload = new byte[] {1, 2, 3};

        boolean committed = worlds.commitSnapshot(
                id,
                0L,
                "node-a",
                "worlds/" + id.value() + "/manifest/0-1.json",
                4903,
                "26.2",
                snap,
                1,
                Map.of(player1, payload),
                profiles);

        assertThat(committed).isTrue();

        PlayerWorld updated = worlds.findById(id).orElseThrow();
        assertThat(updated.manifestKey()).isEqualTo("worlds/" + id.value() + "/manifest/0-1.json");
        assertThat(updated.dataVersion()).isEqualTo(4903);
        assertThat(updated.mcVersion()).isEqualTo("26.2");
        assertThat(updated.lastPlayed()).isNotNull();

        assertThat(profiles.load(id, player1, snap)).isPresent();
        assertThat(profiles.load(id, player1, snap).orElseThrow().data()).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("commitSnapshot returns false and writes no profiles on generation mismatch")
    void commitSnapshotRefusesGenerationMismatch() throws Exception {
        WorldId id = WorldId.random();
        UUID owner = UUID.randomUUID();
        create(id, owner, "fenced-world", 1234L);

        ProfileRepository profiles = new ProfileRepository(database);
        ProfileRepository.Snapshot snap = new ProfileRepository.Snapshot(1L, 1);
        UUID player1 = UUID.randomUUID();
        byte[] payload = new byte[] {1, 2, 3};

        boolean committed = worlds.commitSnapshot(
                id,
                1L,
                "node-a",
                "worlds/" + id.value() + "/manifest/1-1.json",
                4903,
                "26.2",
                snap,
                1,
                Map.of(player1, payload),
                profiles);

        assertThat(committed).isFalse();

        PlayerWorld unchanged = worlds.findById(id).orElseThrow();
        assertThat(unchanged.manifestKey()).isNull();
        assertThat(unchanged.dataVersion()).isNull();

        assertThat(profiles.load(id, player1, snap)).isEmpty();
    }

    @Test
    @DisplayName("commitSnapshot returns false and writes no profiles on node mismatch")
    void commitSnapshotRefusesNodeMismatch() throws Exception {
        WorldId id = WorldId.random();
        UUID owner = UUID.randomUUID();
        create(id, owner, "node-fenced", 1234L);
        updateColumn("UPDATE player_world SET assigned_node = ? WHERE id = ?", "node-b", id);

        ProfileRepository profiles = new ProfileRepository(database);
        ProfileRepository.Snapshot snap = new ProfileRepository.Snapshot(0L, 1);
        UUID player1 = UUID.randomUUID();
        byte[] payload = new byte[] {1, 2, 3};

        boolean committed = worlds.commitSnapshot(
                id,
                0L,
                "node-a",
                "worlds/" + id.value() + "/manifest/0-1.json",
                4903,
                "26.2",
                snap,
                1,
                Map.of(player1, payload),
                profiles);

        assertThat(committed).isFalse();

        PlayerWorld unchanged = worlds.findById(id).orElseThrow();
        assertThat(unchanged.manifestKey()).isNull();
        assertThat(profiles.load(id, player1, snap)).isEmpty();
    }

    @Test
    @DisplayName("commitSnapshot succeeds with empty profiles map")
    void commitSnapshotSucceedsWithEmptyProfiles() throws Exception {
        WorldId id = WorldId.random();
        UUID owner = UUID.randomUUID();
        create(id, owner, "empty-profiles", 1234L);

        ProfileRepository profiles = new ProfileRepository(database);
        ProfileRepository.Snapshot snap = new ProfileRepository.Snapshot(0L, 0);

        boolean committed = worlds.commitSnapshot(
                id,
                0L,
                "node-a",
                "worlds/" + id.value() + "/manifest/0-0.json",
                4903,
                "26.2",
                snap,
                1,
                Map.of(),
                profiles);

        assertThat(committed).isTrue();

        PlayerWorld updated = worlds.findById(id).orElseThrow();
        assertThat(updated.manifestKey()).isEqualTo("worlds/" + id.value() + "/manifest/0-0.json");
    }

    @Test
    @DisplayName("acquireLease grants lease and increments generation for unleased world (MN-8)")
    void acquireLeaseGrantsForUnleasedWorld() throws Exception {
        WorldId id = WorldId.random();
        create(id, UUID.randomUUID(), "lease-test", 1L);

        Optional<PlayerWorldRepository.LeaseGrant> grant =
                worlds.acquireLease(id, "node-1", 4903, java.time.Duration.ofMinutes(3));

        assertThat(grant).isPresent();
        assertThat(grant.get().generation()).isEqualTo(1L);
        assertThat(grant.get().expiresAt()).isAfter(java.time.Instant.now());

        PlayerWorld loaded = worlds.findById(id).orElseThrow();
        assertThat(loaded.assignedNode()).isEqualTo("node-1");
        assertThat(loaded.generation()).isEqualTo(1L);
        assertThat(loaded.leaseExpires()).isNotNull();
    }

    @Test
    @DisplayName("acquireLease refuses acquisition when active lease is held by another node (MN-8)")
    void acquireLeaseRefusesActiveLease() throws Exception {
        WorldId id = WorldId.random();
        create(id, UUID.randomUUID(), "lease-contended", 1L);

        Optional<PlayerWorldRepository.LeaseGrant> grant1 =
                worlds.acquireLease(id, "node-1", 4903, java.time.Duration.ofMinutes(3));
        assertThat(grant1).isPresent();

        Optional<PlayerWorldRepository.LeaseGrant> grant2 =
                worlds.acquireLease(id, "node-2", 4903, java.time.Duration.ofMinutes(3));
        assertThat(grant2).isEmpty();
    }

    @Test
    @DisplayName("acquireLease succeeds after existing lease expires and increments generation (MN-8)")
    void acquireLeaseSucceedsAfterExpiration() throws Exception {
        WorldId id = WorldId.random();
        create(id, UUID.randomUUID(), "lease-expired", 1L);

        // Node 1 acquired lease in the past (already expired)
        updateColumn(
                "UPDATE player_world SET assigned_node = 'node-1', lease_expires = now() - interval '10 seconds', generation = 1 WHERE id = ?",
                null,
                id);

        Optional<PlayerWorldRepository.LeaseGrant> grant =
                worlds.acquireLease(id, "node-2", 4903, java.time.Duration.ofMinutes(3));

        assertThat(grant).isPresent();
        assertThat(grant.get().generation()).isEqualTo(2L);

        PlayerWorld loaded = worlds.findById(id).orElseThrow();
        assertThat(loaded.assignedNode()).isEqualTo("node-2");
        assertThat(loaded.generation()).isEqualTo(2L);
    }

    @Test
    @DisplayName("acquireLease refuses worlds with newer DataVersion (MN-26)")
    void acquireLeaseRefusesNewerDataVersion() throws Exception {
        WorldId id = WorldId.random();
        create(id, UUID.randomUUID(), "lease-version", 1L);
        setDataVersion(id, 4905);

        // Node with older dataVersion 4903 attempts acquisition
        Optional<PlayerWorldRepository.LeaseGrant> grant =
                worlds.acquireLease(id, "node-1", 4903, java.time.Duration.ofMinutes(3));

        assertThat(grant).isEmpty();

        // Node with compatible dataVersion 4905 attempts acquisition
        Optional<PlayerWorldRepository.LeaseGrant> grantOk =
                worlds.acquireLease(id, "node-1", 4905, java.time.Duration.ofMinutes(3));

        assertThat(grantOk).isPresent();
    }

    @Test
    @DisplayName("renewLease extends lease_expires when node and generation match (MN-9)")
    void renewLeaseExtendsExpiration() throws Exception {
        WorldId id = WorldId.random();
        create(id, UUID.randomUUID(), "renew-test", 1L);
        PlayerWorldRepository.LeaseGrant grant = worlds.acquireLease(
                        id, "node-1", 4903, java.time.Duration.ofMinutes(3))
                .orElseThrow();

        Optional<java.time.Instant> renewed =
                worlds.renewLease(id, "node-1", grant.generation(), java.time.Duration.ofMinutes(3));

        assertThat(renewed).isPresent();
        assertThat(renewed.get()).isAfterOrEqualTo(grant.expiresAt().minusSeconds(1));
    }

    @Test
    @DisplayName("renewLease fails when generation or node mismatch (MN-9, MN-10b)")
    void renewLeaseFailsOnMismatch() throws Exception {
        WorldId id = WorldId.random();
        create(id, UUID.randomUUID(), "renew-mismatch", 1L);
        PlayerWorldRepository.LeaseGrant grant = worlds.acquireLease(
                        id, "node-1", 4903, java.time.Duration.ofMinutes(3))
                .orElseThrow();

        // Wrong generation
        assertThat(worlds.renewLease(id, "node-1", grant.generation() + 1, java.time.Duration.ofMinutes(3)))
                .isEmpty();

        // Wrong node
        assertThat(worlds.renewLease(id, "node-2", grant.generation(), java.time.Duration.ofMinutes(3)))
                .isEmpty();
    }

    @Test
    @DisplayName("releaseLease clears assigned_node and lease_expires on clean unload (MN-12)")
    void releaseLeaseClearsAssignment() throws Exception {
        WorldId id = WorldId.random();
        create(id, UUID.randomUUID(), "release-test", 1L);
        PlayerWorldRepository.LeaseGrant grant = worlds.acquireLease(
                        id, "node-1", 4903, java.time.Duration.ofMinutes(3))
                .orElseThrow();

        boolean released = worlds.releaseLease(id, "node-1", grant.generation());
        assertThat(released).isTrue();

        PlayerWorld loaded = worlds.findById(id).orElseThrow();
        assertThat(loaded.assignedNode()).isNull();
        assertThat(loaded.leaseExpires()).isNull();
        assertThat(loaded.generation()).isEqualTo(grant.generation());

        // Second release or mismatch returns false
        assertThat(worlds.releaseLease(id, "node-1", grant.generation())).isFalse();
    }

    // -----------------------------------------------------------------------
    // Milestone 8: placement inputs, read in database time (MN-14 to MN-16, MN-28)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("placementContext reports the lease holder only while the lease is live (MN-14)")
    void placementContextReportsALiveLeaseHolder() throws Exception {
        WorldId id = WorldId.random();
        create(id, UUID.randomUUID(), "placement", 1L);

        assertThat(worlds.placementContext(id).orElseThrow().leaseHolder()).isNull();

        worlds.acquireLease(id, "node-1", 4903, java.time.Duration.ofMinutes(3)).orElseThrow();
        assertThat(worlds.placementContext(id).orElseThrow().leaseHolder()).isEqualTo("node-1");

        // Expired in database time. The row still names node-1 in assigned_node,
        // and placement must not route to it: MN-8 lets anyone take the lease now.
        updateColumn("UPDATE player_world SET lease_expires = now() - interval '1 second' WHERE id = ?", null, id);
        assertThat(worlds.placementContext(id).orElseThrow().leaseHolder()).isNull();
        assertThat(worlds.leaseHolder(id)).isEmpty();
    }

    @Test
    @DisplayName("a snapshot commit records the node that wrote it as the warm copy (MN-15a)")
    void commitRecordsTheWarmNode() throws Exception {
        WorldId id = WorldId.random();
        create(id, UUID.randomUUID(), "warm", 1L);
        PlayerWorldRepository.LeaseGrant grant = worlds.acquireLease(
                        id, "node-1", 4903, java.time.Duration.ofMinutes(3))
                .orElseThrow();

        assertThat(worlds.placementContext(id).orElseThrow().warmNode()).isNull();

        boolean committed = worlds.commitSnapshot(
                id,
                grant.generation(),
                "node-1",
                "manifests/" + id.value() + "/1",
                4903,
                "26.2",
                new ProfileRepository.Snapshot(1L, 1),
                1,
                Map.of(),
                new ProfileRepository(database));

        assertThat(committed).isTrue();
        assertThat(worlds.placementContext(id).orElseThrow().warmNode()).isEqualTo("node-1");
    }

    @Test
    @DisplayName("a fenced commit does not move the warm copy either (MN-3a, MN-15a)")
    void aFencedCommitDoesNotMoveTheWarmNode() throws Exception {
        WorldId id = WorldId.random();
        create(id, UUID.randomUUID(), "warm-fenced", 1L);
        PlayerWorldRepository.LeaseGrant first = worlds.acquireLease(
                        id, "node-1", 4903, java.time.Duration.ofMinutes(3))
                .orElseThrow();
        worlds.commitSnapshot(
                id,
                first.generation(),
                "node-1",
                "manifests/a",
                4903,
                "26.2",
                new ProfileRepository.Snapshot(1L, 1),
                1,
                Map.of(),
                new ProfileRepository(database));

        // node-2 takes over; node-1 wakes up and tries to finish its commit.
        updateColumn("UPDATE player_world SET lease_expires = now() - interval '1 second' WHERE id = ?", null, id);
        worlds.acquireLease(id, "node-2", 4903, java.time.Duration.ofMinutes(3)).orElseThrow();

        boolean stale = worlds.commitSnapshot(
                id,
                first.generation(),
                "node-1",
                "manifests/b",
                4903,
                "26.2",
                new ProfileRepository.Snapshot(2L, 1),
                1,
                Map.of(),
                new ProfileRepository(database));

        assertThat(stale).isFalse();
        assertThat(worlds.placementContext(id).orElseThrow().warmNode()).isEqualTo("node-1");
        assertThat(worlds.findById(id).orElseThrow().manifestKey()).isEqualTo("manifests/a");
    }

    @Test
    @DisplayName("occupancy counts live leases per node and splits them by visibility (MN-15a)")
    void occupancyIsCountedFromLiveLeases() throws Exception {
        WorldId privateOnOne = WorldId.random();
        WorldId publicOnOne = WorldId.random();
        WorldId expiredOnOne = WorldId.random();
        WorldId privateOnTwo = WorldId.random();
        create(privateOnOne, UUID.randomUUID(), "p1", 1L);
        create(publicOnOne, UUID.randomUUID(), "u1", 1L);
        create(expiredOnOne, UUID.randomUUID(), "e1", 1L);
        create(privateOnTwo, UUID.randomUUID(), "p2", 1L);
        updateColumn("UPDATE player_world SET visibility = 'PUBLIC' WHERE id = ?", null, publicOnOne);

        worlds.acquireLease(privateOnOne, "node-1", 4903, java.time.Duration.ofMinutes(3))
                .orElseThrow();
        worlds.acquireLease(publicOnOne, "node-1", 4903, java.time.Duration.ofMinutes(3))
                .orElseThrow();
        worlds.acquireLease(expiredOnOne, "node-1", 4903, java.time.Duration.ofMinutes(3))
                .orElseThrow();
        worlds.acquireLease(privateOnTwo, "node-2", 4903, java.time.Duration.ofMinutes(3))
                .orElseThrow();
        updateColumn(
                "UPDATE player_world SET lease_expires = now() - interval '1 second' WHERE id = ?", null, expiredOnOne);

        Map<String, PlayerWorldRepository.NodeOccupancy> occupancy = worlds.liveLeaseOccupancy();

        // The expired one is not counted: it is not on node-1 any more, whatever
        // assigned_node still says.
        assertThat(occupancy.get("node-1")).isEqualTo(new PlayerWorldRepository.NodeOccupancy(1, 1));
        assertThat(occupancy.get("node-2")).isEqualTo(new PlayerWorldRepository.NodeOccupancy(0, 1));
    }

    @Test
    @DisplayName("worldsLeasedTo lists what a drain has to move (MN-22)")
    void worldsLeasedToListsWhatADrainHasToMove() throws Exception {
        WorldId held = WorldId.random();
        WorldId lapsed = WorldId.random();
        create(held, UUID.randomUUID(), "held", 1L);
        create(lapsed, UUID.randomUUID(), "lapsed", 1L);
        worlds.acquireLease(held, "node-1", 4903, java.time.Duration.ofMinutes(3))
                .orElseThrow();
        worlds.acquireLease(lapsed, "node-1", 4903, java.time.Duration.ofMinutes(3))
                .orElseThrow();
        updateColumn("UPDATE player_world SET lease_expires = now() - interval '1 second' WHERE id = ?", null, lapsed);

        assertThat(worlds.worldsLeasedTo("node-1")).containsExactly(held);
        assertThat(worlds.worldsLeasedTo("node-2")).isEmpty();
    }

    @Test
    @DisplayName("an older node cannot take a world a newer node has committed (MN-26, section 11 milestone 8)")
    void anOlderNodeCannotTakeAWorldANewerNodeCommitted() throws Exception {
        // The two-node version-gating case, on the database half. node-new opens a
        // world and commits; node-old must not be able to acquire it afterwards,
        // and no supported path returns the chunks to the older format.
        WorldId id = WorldId.random();
        create(id, UUID.randomUUID(), "upgraded", 1L);

        PlayerWorldRepository.LeaseGrant onOld = worlds.acquireLease(
                        id, "node-old", 4189, java.time.Duration.ofMinutes(3))
                .orElseThrow();
        // Before any commit the world has no data version and either node may take
        // it (MN-27: the version advances only at a commit, never speculatively).
        assertThat(worlds.findById(id).orElseThrow().dataVersion()).isNull();
        worlds.releaseLease(id, "node-old", onOld.generation());

        PlayerWorldRepository.LeaseGrant onNew = worlds.acquireLease(
                        id, "node-new", 4903, java.time.Duration.ofMinutes(3))
                .orElseThrow();
        worlds.commitSnapshot(
                id,
                onNew.generation(),
                "node-new",
                "manifests/new",
                4903,
                "26.2",
                new ProfileRepository.Snapshot(1L, 1),
                1,
                Map.of(),
                new ProfileRepository(database));
        worlds.releaseLease(id, "node-new", onNew.generation());

        assertThat(worlds.acquireLease(id, "node-old", 4189, java.time.Duration.ofMinutes(3)))
                .isEmpty();
        assertThat(worlds.acquireLease(id, "node-new", 4903, java.time.Duration.ofMinutes(3)))
                .isPresent();
    }

    private PlayerWorld create(WorldId id, UUID owner, String name, long seed) throws SQLException {
        return database.inTransaction(connection ->
                worlds.insertCreating(connection, id, owner, name, id.folder(), seed, 5000, Visibility.PRIVATE));
    }

    private void setState(WorldId id, WorldState state) throws SQLException {
        updateColumn("UPDATE player_world SET state = ? WHERE id = ?", state.wire(), id);
    }

    private void setDataVersion(WorldId id, int dataVersion) throws SQLException {
        updateColumn("UPDATE player_world SET data_version = ? WHERE id = ?", dataVersion, id);
    }

    private void updateColumn(String sql, Object value, WorldId id) throws SQLException {
        database.inTransaction((Connection connection) -> {
            try (var statement = connection.prepareStatement(sql)) {
                if (value != null) {
                    statement.setObject(1, value);
                    statement.setObject(2, id.value());
                } else {
                    statement.setObject(1, id.value());
                }
                statement.executeUpdate();
            }
            return null;
        });
    }
}
