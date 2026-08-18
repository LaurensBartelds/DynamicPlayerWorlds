package nl.gzmn.playerworlds.backend.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import nl.gzmn.playerworlds.core.concurrent.MainThread;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.control.ArchivePayload;
import nl.gzmn.playerworlds.core.control.CommandKind;
import nl.gzmn.playerworlds.core.control.NodeCommand;
import nl.gzmn.playerworlds.core.db.Database;
import nl.gzmn.playerworlds.core.db.NodeCommandRepository;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository;
import nl.gzmn.playerworlds.core.db.Schema;
import nl.gzmn.playerworlds.core.db.TransferRequestRepository;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.Visibility;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.model.WorldState;
import nl.gzmn.playerworlds.testing.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MaintenanceTaskTest {

    private Database database;
    private PluginExecutors executors;
    private PlayerWorldRepository worlds;
    private TransferRequestRepository transferRequests;
    private NodeCommandRepository nodeCommands;
    private MaintenanceTask task;

    @BeforeEach
    void setUp() throws Exception {
        database = TestDatabase.openFresh();
        Schema.migrate(database);
        executors = PluginExecutors.create(2, 2, Runnable::run);
        worlds = new PlayerWorldRepository(database);
        transferRequests = new TransferRequestRepository(database);
        nodeCommands = new NodeCommandRepository(database);
        task = new MaintenanceTask(database, worlds, transferRequests, nodeCommands, NetworkPolicy::defaults, "node-1");
        MainThread.enter(Thread.currentThread());
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
}
