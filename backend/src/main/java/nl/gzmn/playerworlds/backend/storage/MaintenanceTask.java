package nl.gzmn.playerworlds.backend.storage;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.control.ArchivePayload;
import nl.gzmn.playerworlds.core.control.CommandKind;
import nl.gzmn.playerworlds.core.control.ControlChannels;
import nl.gzmn.playerworlds.core.db.AdvisoryLock;
import nl.gzmn.playerworlds.core.db.Database;
import nl.gzmn.playerworlds.core.db.NodeCommandRepository;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository;
import nl.gzmn.playerworlds.core.db.TransferRequestRepository;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.WorldState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The periodic sweep in FR-40: inactivity archival (FR-34) and recovery of interrupted
 * archival and restore (FR-35, FR-36).
 *
 * <p>Every node in an interchangeable pool would otherwise run every job at once, duplicating
 * archival and racing on cleanup, so the whole run is gated on one PostgreSQL advisory lock.
 * Not holding it is the normal case for all but one node and is not an error.
 *
 * <p>Each sweep is bounded by {@link #BATCH_LIMIT}. A network that has just come back from a
 * long outage may have thousands of worlds due at once, and a sweep that tried to fix all of
 * them in one pass would hold the lock for as long as it took.
 */
public final class MaintenanceTask implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceTask.class);

    /** Worlds acted on per sweep, per category. */
    public static final int BATCH_LIMIT = 50;

    /** How long to wait for the lock. Zero would be racy on a busy pool; long would stack sweeps. */
    private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(2);

    private final Database database;
    private final PlayerWorldRepository worlds;
    private final TransferRequestRepository transferRequests;
    private final NodeCommandRepository nodeCommands;
    private final Supplier<NetworkPolicy> policy;
    private final String nodeId;

    /** What one sweep did, for logging and for tests. */
    public record SweepResult(
            boolean ranWithLock,
            int archivalsQueued,
            int archivingReset,
            int restoringReset,
            int transferRequestsExpired) {

        static SweepResult skipped() {
            return new SweepResult(false, 0, 0, 0, 0);
        }
    }

    public MaintenanceTask(
            Database database,
            PlayerWorldRepository worlds,
            TransferRequestRepository transferRequests,
            NodeCommandRepository nodeCommands,
            Supplier<NetworkPolicy> policy,
            String nodeId) {
        this.database = Objects.requireNonNull(database, "database");
        this.worlds = Objects.requireNonNull(worlds, "worlds");
        this.transferRequests = Objects.requireNonNull(transferRequests, "transferRequests");
        this.nodeCommands = Objects.requireNonNull(nodeCommands, "nodeCommands");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
    }

    @Override
    public void run() {
        try {
            SweepResult result = sweep();
            if (result.ranWithLock()
                    && (result.archivalsQueued() > 0
                            || result.archivingReset() > 0
                            || result.restoringReset() > 0
                            || result.transferRequestsExpired() > 0)) {
                log.info(
                        "maintenance sweep: {} archivals queued, {} ARCHIVING reset, {} RESTORING reset,"
                                + " {} transfer requests expired",
                        result.archivalsQueued(),
                        result.archivingReset(),
                        result.restoringReset(),
                        result.transferRequestsExpired());
            }
        } catch (SQLException e) {
            log.error("maintenance sweep failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("maintenance sweep interrupted while waiting for the advisory lock");
        }
    }

    /**
     * Runs one sweep, if this node can take FR-40's lock.
     *
     * @return what the sweep did, or {@link SweepResult#skipped()} when another node holds the lock
     */
    public SweepResult sweep() throws SQLException, InterruptedException {
        Optional<AdvisoryLock> lock = AdvisoryLock.tryAcquire(database, AdvisoryLock.MAINTENANCE_KEY, LOCK_TIMEOUT);
        if (lock.isEmpty()) {
            return SweepResult.skipped();
        }
        try (AdvisoryLock held = lock.get()) {
            Objects.requireNonNull(held, "held");
            NetworkPolicy current = policy.get();
            // Recovery first. An interrupted archival that is reset to READY becomes eligible for
            // the inactivity pass in the same sweep, rather than waiting out another interval.
            int archivingReset = resetStuck(WorldState.ARCHIVING, WorldState.READY);
            int restoringReset = resetStuck(WorldState.RESTORING, WorldState.ARCHIVED);
            int queued = queueInactiveArchivals(current);
            int expired = transferRequests.deleteExpired();
            return new SweepResult(true, queued, archivingReset, restoringReset, expired);
        }
    }

    /**
     * FR-34: worlds nobody has logged into for {@code archive.after-days} are archived.
     *
     * <p>Addressed to this node, which is the one holding the lock. Archival takes the world's
     * lease, so a broadcast would have every node race for it.
     */
    private int queueInactiveArchivals(NetworkPolicy current) throws SQLException {
        List<PlayerWorld> due = worlds.findInactive(current.archiveAfterDays(), BATCH_LIMIT);
        int queued = 0;
        for (PlayerWorld world : due) {
            // No owner asserted: this is the system archiving on inactivity, not the owner
            // asking, so the node must not refuse on an owner mismatch.
            var _ = nodeCommands.enqueue(
                    nodeId,
                    world.id(),
                    world.generation(),
                    CommandKind.ARCHIVE_WORLD.name(),
                    ArchivePayload.format(null),
                    current.holdingTimeout(),
                    ControlChannels.forNode(nodeId));
            queued++;
            log.info(
                    "world {} ('{}') queued for inactivity archival after {} days (FR-34)",
                    world.id(),
                    world.name(),
                    current.archiveAfterDays());
        }
        return queued;
    }

    /**
     * FR-40: an archival or restore whose node died leaves a transient state and a dead lease.
     *
     * <p>Reset rather than retried in place. FR-35 retries from the beginning and deletes nothing
     * before its checksum verifies, so READY is where an interrupted archival belongs; the next
     * inactivity pass picks it up again. An interrupted restore goes back to ARCHIVED, where the
     * owner can ask for it again — the archive it was reading is never deleted by that flow.
     */
    private int resetStuck(WorldState from, WorldState to) throws SQLException {
        List<PlayerWorld> stuck = worlds.findStuckWithDeadLease(from, BATCH_LIMIT);
        int reset = 0;
        for (PlayerWorld world : stuck) {
            if (worlds.resetStuck(world.id(), from, to)) {
                reset++;
                log.warn(
                        "world {} ('{}') was left in {} with a dead lease and has been reset to {} (FR-40)",
                        world.id(),
                        world.name(),
                        from,
                        to);
            }
        }
        return reset;
    }
}
