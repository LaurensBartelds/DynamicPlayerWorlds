package nl.gzmn.playerworlds.backend.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import nl.gzmn.playerworlds.core.concurrent.MainThread;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.control.ArchivePayload;
import nl.gzmn.playerworlds.core.control.CommandKind;
import nl.gzmn.playerworlds.core.control.CommandResult;
import nl.gzmn.playerworlds.core.control.NodeCommand;
import nl.gzmn.playerworlds.core.db.AdvisoryLock;
import nl.gzmn.playerworlds.core.db.Database;
import nl.gzmn.playerworlds.core.db.MembershipRepository;
import nl.gzmn.playerworlds.core.db.NodeCommandRepository;
import nl.gzmn.playerworlds.core.db.PendingTransferRepository;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository;
import nl.gzmn.playerworlds.core.db.ProfileRepository;
import nl.gzmn.playerworlds.core.db.Schema;
import nl.gzmn.playerworlds.core.db.TransferRequestRepository;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.Visibility;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.model.WorldState;
import nl.gzmn.playerworlds.core.storage.LocalObjectCache;
import nl.gzmn.playerworlds.core.storage.PlainFileCloner;
import nl.gzmn.playerworlds.testing.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MaintenanceTaskTest {

    @TempDir
    Path tempDir;

    private Database database;
    private PluginExecutors executors;
    private PlayerWorldRepository worlds;
    private ProfileRepository profiles;
    private MembershipRepository membership;
    private PendingTransferRepository pendingTransfers;
    private TransferRequestRepository transferRequests;
    private NodeCommandRepository nodeCommands;
    private LocalObjectCache localCache;
    private Path quarantineRoot;
    private MaintenanceTask task;

    @BeforeEach
    void setUp() throws Exception {
        database = TestDatabase.openFresh();
        Schema.migrate(database);
        executors = PluginExecutors.create(2, 2, Runnable::run);
        worlds = new PlayerWorldRepository(database);
        profiles = new ProfileRepository(database);
        membership = new MembershipRepository(database);
        pendingTransfers = new PendingTransferRepository(database);
        transferRequests = new TransferRequestRepository(database);
        nodeCommands = new NodeCommandRepository(database);
        localCache = new LocalObjectCache(tempDir.resolve("cache"), PlainFileCloner.INSTANCE);
        quarantineRoot = tempDir.resolve("quarantine");
        Files.createDirectories(quarantineRoot);
        task = maintenanceWith(NetworkPolicy::defaults, Clock.systemUTC());
        MainThread.enter(Thread.currentThread());
    }

    private MaintenanceTask maintenanceWith(Supplier<NetworkPolicy> policy, Clock clock) {
        return new MaintenanceTask(
                database,
                worlds,
                profiles,
                membership,
                pendingTransfers,
                transferRequests,
                nodeCommands,
                localCache,
                quarantineRoot,
                policy,
                "node-1",
                clock);
    }

    @AfterEach
    void tearDown() {
        MainThread.clear();
        executors.shutdown(Duration.ofSeconds(5));
        database.close();
    }

    private <T> T onDb(Callable<T> work) throws Exception {
        return executors.db().submit(work).get(60, TimeUnit.SECONDS);
    }

    /** Backdates last_played so the world looks untouched for longer than archive.after-days. */
    private void setLastPlayedDaysAgo(WorldId worldId, int days) throws Exception {
        onDb(() -> database.inTransaction(connection -> {
            try (var stmt = connection.prepareStatement(
                    "UPDATE player_world SET last_played = now() - (? * interval '1 day') WHERE id = ?")) {
                stmt.setInt(1, days);
                stmt.setObject(2, worldId.value());
                stmt.executeUpdate();
            }
            return null;
        }));
    }

    /** Puts a world into a transient state with a lease that has already run out. */
    private void setStateWithDeadLease(WorldId worldId, WorldState state) throws Exception {
        onDb(() -> database.inTransaction(connection -> {
            try (var stmt = connection.prepareStatement("""
                    UPDATE player_world
                       SET state = ?, assigned_node = 'node-dead', lease_expires = now() - interval '1 hour'
                     WHERE id = ?
                    """)) {
                stmt.setString(1, state.wire());
                stmt.setObject(2, worldId.value());
                stmt.executeUpdate();
            }
            return null;
        }));
    }

    @Test
    @DisplayName("Queues archival for a world nobody has played for longer than archive.after-days")
    void queuesInactivityArchival() throws Exception {
        UUID owner = UUID.randomUUID();
        WorldId worldId = WorldId.random();
        PlayerWorld created = onDb(() -> {
            PlayerWorld world = worlds.create(worldId, owner, "forgotten", 12345L, 5000, Visibility.PRIVATE);
            worlds.markReadyAndPlayed(worldId);
            return world;
        });
        setLastPlayedDaysAgo(worldId, NetworkPolicy.defaults().archiveAfterDays() + 5);

        MaintenanceTask.SweepResult result = onDb(task::sweep);

        assertThat(result.ranWithLock()).isTrue();
        assertThat(result.archivalsQueued()).isEqualTo(1);

        List<Long> ids = onDb(() ->
                nodeCommands.findClaimableIds("node-1", NetworkPolicy.defaults().controlClaimTimeout(), 10));
        assertThat(ids).hasSize(1);
        NodeCommand command = onDb(() -> nodeCommands.findById(ids.getFirst()).orElseThrow());
        assertThat(command.command()).isEqualTo(CommandKind.ARCHIVE_WORLD.name());
        assertThat(command.worldId()).isEqualTo(worldId);
        assertThat(command.generation()).isEqualTo(created.generation());
        // System archival asserts no owner, so WorldArchiver's owner check cannot refuse it.
        assertThat(ArchivePayload.parse(command.payloadJson()).orElseThrow().ownerUuid())
                .isNull();
    }

    @Test
    @DisplayName("Leaves a recently played world alone")
    void skipsRecentlyPlayedWorld() throws Exception {
        UUID owner = UUID.randomUUID();
        WorldId worldId = WorldId.random();
        onDb(() -> {
            worlds.create(worldId, owner, "busy", 12345L, 5000, Visibility.PRIVATE);
            worlds.markReadyAndPlayed(worldId);
            return null;
        });
        setLastPlayedDaysAgo(worldId, 1);

        MaintenanceTask.SweepResult result = onDb(task::sweep);

        assertThat(result.archivalsQueued()).isZero();
        assertThat(onDb(() -> nodeCommands.findClaimableIds(
                        "node-1", NetworkPolicy.defaults().controlClaimTimeout(), 10)))
                .isEmpty();
    }

    @Test
    @DisplayName("Resets an interrupted archival to READY so FR-35 can retry from the beginning")
    void resetsStuckArchivingToReady() throws Exception {
        UUID owner = UUID.randomUUID();
        WorldId worldId = WorldId.random();
        onDb(() -> {
            worlds.create(worldId, owner, "halfpacked", 12345L, 5000, Visibility.PRIVATE);
            worlds.markReadyAndPlayed(worldId);
            return null;
        });
        setStateWithDeadLease(worldId, WorldState.ARCHIVING);

        MaintenanceTask.SweepResult result = onDb(task::sweep);

        assertThat(result.archivingReset()).isEqualTo(1);
        PlayerWorld recovered = onDb(() -> worlds.findById(worldId).orElseThrow());
        assertThat(recovered.state()).isEqualTo(WorldState.READY);
        assertThat(recovered.assignedNode()).isNull();
        assertThat(recovered.leaseExpires()).isNull();
    }

    @Test
    @DisplayName("Resets an interrupted restore to ARCHIVED, where the archive is still intact")
    void resetsStuckRestoringToArchived() throws Exception {
        UUID owner = UUID.randomUUID();
        WorldId worldId = WorldId.random();
        onDb(() -> {
            worlds.create(worldId, owner, "halfrestored", 12345L, 5000, Visibility.PRIVATE);
            worlds.markReadyAndPlayed(worldId);
            return null;
        });
        setStateWithDeadLease(worldId, WorldState.RESTORING);

        MaintenanceTask.SweepResult result = onDb(task::sweep);

        assertThat(result.restoringReset()).isEqualTo(1);
        PlayerWorld recovered = onDb(() -> worlds.findById(worldId).orElseThrow());
        assertThat(recovered.state()).isEqualTo(WorldState.ARCHIVED);
        assertThat(recovered.assignedNode()).isNull();
        assertThat(recovered.leaseExpires()).isNull();
    }

    @Test
    @DisplayName("Leaves a world alone while its archival lease is still live")
    void leavesLiveLeaseAlone() throws Exception {
        UUID owner = UUID.randomUUID();
        WorldId worldId = WorldId.random();
        onDb(() -> {
            worlds.create(worldId, owner, "inprogress", 12345L, 5000, Visibility.PRIVATE);
            worlds.markReadyAndPlayed(worldId);
            return null;
        });
        onDb(() -> database.inTransaction(connection -> {
            try (var stmt = connection.prepareStatement("""
                    UPDATE player_world
                       SET state = 'ARCHIVING', assigned_node = 'node-2', lease_expires = now() + interval '10 minutes'
                     WHERE id = ?
                    """)) {
                stmt.setObject(1, worldId.value());
                stmt.executeUpdate();
            }
            return null;
        }));

        MaintenanceTask.SweepResult result = onDb(task::sweep);

        assertThat(result.archivingReset()).isZero();
        assertThat(onDb(() -> worlds.findById(worldId).orElseThrow()).state()).isEqualTo(WorldState.ARCHIVING);
    }

    @Test
    @DisplayName("Skips the whole sweep when another process holds the FR-40 advisory lock")
    void skipsWhenLockHeldElsewhere() throws Exception {
        UUID owner = UUID.randomUUID();
        WorldId worldId = WorldId.random();
        onDb(() -> {
            worlds.create(worldId, owner, "forgotten", 12345L, 5000, Visibility.PRIVATE);
            worlds.markReadyAndPlayed(worldId);
            return null;
        });
        setLastPlayedDaysAgo(worldId, NetworkPolicy.defaults().archiveAfterDays() + 5);

        try (var _ = onDb(() -> nl.gzmn.playerworlds.core.db.AdvisoryLock.tryAcquire(
                        database, nl.gzmn.playerworlds.core.db.AdvisoryLock.MAINTENANCE_KEY, Duration.ofSeconds(5))
                .orElseThrow())) {
            MaintenanceTask.SweepResult result = onDb(task::sweep);

            assertThat(result.ranWithLock()).isFalse();
            assertThat(result.archivalsQueued()).isZero();
            assertThat(onDb(() -> nodeCommands.findClaimableIds(
                            "node-1", NetworkPolicy.defaults().controlClaimTimeout(), 10)))
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("the warm cache is evicted down to storage.local-cache-max-gb (MN-5, FR-15c)")
    void theWarmCacheIsEvictedToItsBound() throws Exception {
        // Three cached objects of 1 KiB each, oldest first.
        for (int i = 0; i < 3; i++) {
            String sha = Integer.toString(i).repeat(64);
            Path cached = localCache.pathOf(sha);
            Files.createDirectories(cached.getParent());
            Files.write(cached, new byte[1024]);
            Files.setLastModifiedTime(cached, FileTime.fromMillis(1000L + i));
        }
        NetworkPolicy tightCache = NetworkPolicy.fromRaw(Map.of(NetworkPolicy.KEY_LOCAL_CACHE_MAX_GB, "0"));

        MaintenanceTask task = maintenanceWith(() -> tightCache, Clock.systemUTC());
        MaintenanceTask.SweepResult result = onDb(task::sweep);

        assertThat(result.cacheBytesEvicted()).isEqualTo(3 * 1024L);
        assertThat(localCache.contains("0".repeat(64))).isFalse();
    }

    @Test
    @DisplayName("quarantine is pruned past its retention window (MN-13a)")
    void quarantineIsPrunedPastItsRetentionWindow() throws Exception {
        Path stale = quarantineRoot.resolve("pw_stale_crash_1");
        Files.createDirectories(stale);
        Files.write(stale.resolve("region.mca"), new byte[64]);
        Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minus(Duration.ofDays(30))));

        Path fresh = quarantineRoot.resolve("pw_fresh_crash_2");
        Files.createDirectories(fresh);
        Files.write(fresh.resolve("region.mca"), new byte[64]);

        MaintenanceTask.SweepResult result = onDb(task::sweep);

        assertThat(result.quarantineEntriesPruned()).isEqualTo(1);
        assertThat(Files.exists(stale)).isFalse();
        assertThat(Files.exists(fresh)).isTrue();
    }

    @Test
    @DisplayName("node-local pruning runs even when another node holds FR-40's lock")
    void nodeLocalPruningRunsWithoutTheLock() throws Exception {
        Path stale = quarantineRoot.resolve("pw_stale_crash_1");
        Files.createDirectories(stale);
        Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minus(Duration.ofDays(30))));

        // Somebody else is sweeping. Every node still fills its own disk, and
        // MN-13a is explicit about where that ends: NFR-3's free-space check
        // failing at the next enable.
        try (var _ = AdvisoryLock.tryAcquire(database, AdvisoryLock.MAINTENANCE_KEY, Duration.ofSeconds(5))
                .orElseThrow()) {
            MaintenanceTask.SweepResult result = onDb(task::sweep);

            assertThat(result.ranWithLock()).isFalse();
            assertThat(result.quarantineEntriesPruned()).isEqualTo(1);
            assertThat(Files.exists(stale)).isFalse();
        }
    }

    @Test
    @DisplayName("profile snapshots are pruned to storage.manifest-retention-count (FR-15c)")
    void profileSnapshotsArePrunedToTheRetentionCount() throws Exception {
        UUID owner = UUID.randomUUID();
        WorldId worldId = WorldId.random();
        onDb(() -> {
            worlds.create(worldId, owner, "pruned-world", 1L, 5000, Visibility.PRIVATE);
            // Five snapshots; the default retention is three.
            for (long generation = 1; generation <= 5; generation++) {
                profiles.commit(worldId, generation, 1, Map.of(owner, new byte[] {(byte) generation}));
            }
            return null;
        });

        MaintenanceTask.SweepResult result = onDb(task::sweep);

        assertThat(result.ranWithLock()).isTrue();
        assertThat(result.profileSnapshotsPruned()).isEqualTo(2);
        // The newest three survive, and the newest of all is the one FR-15b reads.
        assertThat(onDb(() -> profiles.listSnapshots(worldId, owner))).hasSize(3);
        assertThat(onDb(() -> profiles.latestSnapshot(worldId)))
                .get()
                .extracting(ProfileRepository.Snapshot::generation)
                .isEqualTo(5L);
    }

    @Test
    @DisplayName("completed and expired node_command rows are swept after their retention (CP-7)")
    void completedCommandsAreSweptAfterTheirRetention_CP7() throws Exception {
        UUID owner = UUID.randomUUID();
        WorldId worldId = WorldId.random();
        onDb(() -> {
            worlds.create(worldId, owner, "swept-world", 1L, 5000, Visibility.PRIVATE);
            return null;
        });

        long completedLongAgo = onDb(() -> nodeCommands.enqueue(
                "node-1",
                worldId,
                null,
                CommandKind.INVALIDATE_CACHE.name(),
                NodeCommand.EMPTY_PAYLOAD,
                Duration.ofMinutes(5),
                "gzmn_node_node-1"));
        long expiredLongAgo = onDb(() -> nodeCommands.enqueue(
                "node-1",
                worldId,
                null,
                CommandKind.INVALIDATE_CACHE.name(),
                NodeCommand.EMPTY_PAYLOAD,
                Duration.ofMinutes(5),
                "gzmn_node_node-1"));
        long stillLive = onDb(() -> nodeCommands.enqueue(
                "node-1",
                worldId,
                null,
                CommandKind.INVALIDATE_CACHE.name(),
                NodeCommand.EMPTY_PAYLOAD,
                Duration.ofMinutes(5),
                "gzmn_node_node-1"));

        onDb(() -> {
            nodeCommands.complete(completedLongAgo, CommandResult.ok());
            return null;
        });
        // Age both past the retention window, in database time.
        backdate("UPDATE node_command SET completed_at = now() - interval '2 hours' WHERE id = ?", completedLongAgo);
        // Never completed, and its window closed two hours ago: findClaimableIds
        // already refuses to hand it out, so nothing will ever finish it.
        backdate("UPDATE node_command SET expires_at = now() - interval '2 hours' WHERE id = ?", expiredLongAgo);

        MaintenanceTask.SweepResult result = onDb(task::sweep);

        assertThat(result.ranWithLock()).isTrue();
        assertThat(result.commandsSwept()).isEqualTo(2);
        assertThat(onDb(() -> nodeCommands.findById(completedLongAgo))).isEmpty();
        assertThat(onDb(() -> nodeCommands.findById(expiredLongAgo))).isEmpty();
        assertThat(onDb(() -> nodeCommands.findById(stillLive))).isPresent();
    }

    @Test
    @DisplayName("expired invites and pending transfers are swept (FR-40)")
    void expiredInvitesAndPendingTransfersAreSwept() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID invitee = UUID.randomUUID();
        UUID stillInvited = UUID.randomUUID();
        UUID traveller = UUID.randomUUID();
        WorldId worldId = WorldId.random();

        onDb(() -> {
            worlds.create(worldId, owner, "invite-world", 1L, 5000, Visibility.PRIVATE);
            var _ = membership.invite(worldId, invitee, owner, Duration.ofMinutes(10));
            var _ = membership.invite(worldId, stillInvited, owner, Duration.ofMinutes(10));
            pendingTransfers.route(traveller, worldId, "node-1", 0L);
            return null;
        });
        backdate("UPDATE player_world_invite SET expires_at = now() - interval '1 minute' WHERE uuid = ?", invitee);
        backdate("UPDATE pending_transfer SET created_at = now() - interval '1 hour' WHERE uuid = ?", traveller);

        MaintenanceTask.SweepResult result = onDb(task::sweep);

        assertThat(result.invitesExpired()).isEqualTo(1);
        assertThat(result.pendingTransfersExpired()).isEqualTo(1);
        // Asserted on the rows, not on findLiveInvite: that already filters on
        // expires_at, so it cannot tell a swept row from an expired one.
        assertThat(inviteRows(worldId)).containsExactly(stillInvited);
    }

    /** Every invite row for a world, expired or not. */
    private List<UUID> inviteRows(WorldId worldId) throws Exception {
        return onDb(() -> database.withConnection(connection -> {
            try (var statement =
                    connection.prepareStatement("SELECT uuid FROM player_world_invite WHERE world_id = ?")) {
                statement.setObject(1, worldId.value());
                List<UUID> found = new ArrayList<>();
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        found.add(rows.getObject(1, UUID.class));
                    }
                }
                return found;
            }
        }));
    }

    /** Ages a row in database time (rule 5): node clocks drift, the server's does not. */
    private void backdate(String sql, Object key) throws Exception {
        onDb(() -> database.inTransaction(connection -> {
            try (var statement = connection.prepareStatement(sql)) {
                statement.setObject(1, key);
                return statement.executeUpdate();
            }
        }));
    }
}
