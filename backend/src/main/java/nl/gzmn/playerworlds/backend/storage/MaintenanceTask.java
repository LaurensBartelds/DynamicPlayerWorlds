package nl.gzmn.playerworlds.backend.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
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
import nl.gzmn.playerworlds.core.db.ProfileRepository;
import nl.gzmn.playerworlds.core.db.TransferRequestRepository;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.model.WorldState;
import nl.gzmn.playerworlds.core.storage.LocalObjectCache;
import nl.gzmn.playerworlds.core.storage.QuarantineManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The periodic sweep in FR-40: inactivity archival (FR-34) and recovery of interrupted
 * archival and restore (FR-35, FR-36).
 *
 * <h2>Two halves, and only one of them is elected</h2>
 *
 * <p>Every node in an interchangeable pool would otherwise run every network-wide job at once,
 * duplicating archival and racing on cleanup, so that half is gated on one PostgreSQL advisory
 * lock. Not holding it is the normal case for all but one node and is not an error.
 *
 * <p>The <em>node-local</em> half — the warm cache (MN-5) and the quarantine directory (MN-13a) —
 * runs outside the lock, on every node, every sweep. Each node fills its own disk, so gating
 * these on an election would leave every node but one unpruned, which is the failure MN-13a
 * describes: a scratch volume that fills until NFR-3's free-space check fails at the next
 * enable.
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
    private final ProfileRepository profiles;
    private final TransferRequestRepository transferRequests;
    private final NodeCommandRepository nodeCommands;
    private final LocalObjectCache localCache;
    private final Path quarantineRoot;
    private final Supplier<NetworkPolicy> policy;
    private final String nodeId;
    private final Clock clock;

    /**
     * What one sweep did, for logging and for tests.
     *
     * @param ranWithLock whether this node took FR-40's lock and so ran the
     *     network-wide half; the node-local counters are filled either way
     * @param cacheBytesEvicted warm-cache bytes freed (MN-5, {@code storage.local-cache-max-gb})
     * @param quarantineEntriesPruned quarantine directories removed (MN-13a)
     * @param profileSnapshotsPruned profile rows removed (FR-15c)
     */
    public record SweepResult(
            boolean ranWithLock,
            int archivalsQueued,
            int archivingReset,
            int restoringReset,
            int transferRequestsExpired,
            long cacheBytesEvicted,
            int quarantineEntriesPruned,
            int profileSnapshotsPruned) {

        /** No lock, so only the node-local half ran. */
        static SweepResult localOnly(long cacheBytesEvicted, int quarantineEntriesPruned) {
            return new SweepResult(false, 0, 0, 0, 0, cacheBytesEvicted, quarantineEntriesPruned, 0);
        }
    }

    public MaintenanceTask(
            Database database,
            PlayerWorldRepository worlds,
            ProfileRepository profiles,
            TransferRequestRepository transferRequests,
            NodeCommandRepository nodeCommands,
            LocalObjectCache localCache,
            Path quarantineRoot,
            Supplier<NetworkPolicy> policy,
            String nodeId) {
        this(
                database,
                worlds,
                profiles,
                transferRequests,
                nodeCommands,
                localCache,
                quarantineRoot,
                policy,
                nodeId,
                Clock.systemUTC());
    }

    /** As above, with an injectable clock for MN-13a's retention window in tests. */
    public MaintenanceTask(
            Database database,
            PlayerWorldRepository worlds,
            ProfileRepository profiles,
            TransferRequestRepository transferRequests,
            NodeCommandRepository nodeCommands,
            LocalObjectCache localCache,
            Path quarantineRoot,
            Supplier<NetworkPolicy> policy,
            String nodeId,
            Clock clock) {
        this.database = Objects.requireNonNull(database, "database");
        this.worlds = Objects.requireNonNull(worlds, "worlds");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.transferRequests = Objects.requireNonNull(transferRequests, "transferRequests");
        this.nodeCommands = Objects.requireNonNull(nodeCommands, "nodeCommands");
        this.localCache = Objects.requireNonNull(localCache, "localCache");
        this.quarantineRoot = Objects.requireNonNull(quarantineRoot, "quarantineRoot");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void run() {
        try {
            SweepResult result = sweep();
            if (result.cacheBytesEvicted() > 0 || result.quarantineEntriesPruned() > 0) {
                log.info(
                        "maintenance sweep (node-local): {} bytes evicted from the warm cache, {} quarantine"
                                + " entries pruned",
                        result.cacheBytesEvicted(),
                        result.quarantineEntriesPruned());
            }
            if (result.ranWithLock()
                    && (result.archivalsQueued() > 0
                            || result.archivingReset() > 0
                            || result.restoringReset() > 0
                            || result.transferRequestsExpired() > 0
                            || result.profileSnapshotsPruned() > 0)) {
                log.info(
                        "maintenance sweep: {} archivals queued, {} ARCHIVING reset, {} RESTORING reset,"
                                + " {} transfer requests expired, {} profile rows pruned",
                        result.archivalsQueued(),
                        result.archivingReset(),
                        result.restoringReset(),
                        result.transferRequestsExpired(),
                        result.profileSnapshotsPruned());
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
        NetworkPolicy current = policy.get();

        // Node-local, and first: this node's disk is nobody else's problem, and
        // an election that this node loses must not leave it unpruned (MN-13a).
        long cacheEvicted = localCache.evictLru(current.localCacheMaxBytes());
        int quarantinePruned = pruneQuarantine(current);

        Optional<AdvisoryLock> lock = AdvisoryLock.tryAcquire(database, AdvisoryLock.MAINTENANCE_KEY, LOCK_TIMEOUT);
        if (lock.isEmpty()) {
            return SweepResult.localOnly(cacheEvicted, quarantinePruned);
        }
        try (AdvisoryLock held = lock.get()) {
            Objects.requireNonNull(held, "held");
            // Recovery first. An interrupted archival that is reset to READY becomes eligible for
            // the inactivity pass in the same sweep, rather than waiting out another interval.
            int archivingReset = resetStuck(WorldState.ARCHIVING, WorldState.READY);
            int restoringReset = resetStuck(WorldState.RESTORING, WorldState.ARCHIVED);
            int queued = queueInactiveArchivals(current);
            int expired = transferRequests.deleteExpired();
            int profilesPruned = pruneProfileSnapshots(current);
            return new SweepResult(
                    true,
                    queued,
                    archivingReset,
                    restoringReset,
                    expired,
                    cacheEvicted,
                    quarantinePruned,
                    profilesPruned);
        }
    }

    /**
     * MN-13a: quarantine is bounded by {@code storage.quarantine-max-gb} and
     * {@code storage.quarantine-retain-days}, oldest first.
     *
     * <p>An IO failure here is logged rather than thrown: the rest of the sweep
     * still has work to do, and a full quarantine directory is a slow problem
     * where a skipped archival recovery is a fast one.
     */
    private int pruneQuarantine(NetworkPolicy current) {
        try {
            return QuarantineManager.prune(
                    quarantineRoot, current.quarantineMaxBytes(), current.quarantineRetainDays(), clock.instant());
        } catch (IOException e) {
            log.error("could not prune the quarantine directory {} (MN-13a)", quarantineRoot, e);
            return 0;
        }
    }

    /**
     * FR-15c: a world keeps its newest {@code storage.manifest-retention-count}
     * profile snapshots.
     *
     * <p>Profiles only. Manifest objects are pruned with MN-2b's collection in
     * R20, which is the pass that can enumerate the bucket — and the ordering
     * ADR 0007 warns about only bites in the other direction: manifests pruned
     * faster than profiles make a load find a {@code manifest_key} whose profiles
     * are gone and issue every player a fresh inventory. Profiles pruned first
     * leaves unreferenced manifests, which is exactly what MN-2b collects.
     */
    private int pruneProfileSnapshots(NetworkPolicy current) throws SQLException {
        int keep = current.manifestRetentionCount();
        int pruned = 0;
        for (WorldId worldId : profiles.worldsWithSnapshotsOver(keep, BATCH_LIMIT)) {
            pruned += profiles.pruneToLatest(worldId, keep);
        }
        return pruned;
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
