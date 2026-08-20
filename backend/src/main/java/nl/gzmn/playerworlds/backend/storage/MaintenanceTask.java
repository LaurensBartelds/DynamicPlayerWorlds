package nl.gzmn.playerworlds.backend.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
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
import nl.gzmn.playerworlds.core.db.MembershipRepository;
import nl.gzmn.playerworlds.core.db.NodeCommandRepository;
import nl.gzmn.playerworlds.core.db.NoticeRepository;
import nl.gzmn.playerworlds.core.db.PendingTransferRepository;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository;
import nl.gzmn.playerworlds.core.db.ProfileRepository;
import nl.gzmn.playerworlds.core.db.TransferRequestRepository;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.model.WorldState;
import nl.gzmn.playerworlds.core.storage.LocalObjectCache;
import nl.gzmn.playerworlds.core.storage.QuarantineManager;
import nl.gzmn.playerworlds.core.storage.SnapshotCollector;
import org.jspecify.annotations.Nullable;
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

    /**
     * How long a finished {@code node_command} row is kept (CP-7).
     *
     * <p>Not a configuration key, because §7 does not name one and CP-7 does not
     * ask for one. An hour is comfortably longer than the longest TTL any
     * producer sets ({@code transfers.holding-timeout-seconds}, 90s by default),
     * so a producer waiting on a result still finds the row, and short enough
     * that the table stays a queue rather than a log.
     */
    private static final Duration COMMAND_RETENTION = Duration.ofHours(1);

    /**
     * How long a delivered notice is kept (FR-34, FR-40).
     *
     * <p>Long enough that "was the owner ever warned?" has an answer after the
     * fact, which is the question that gets asked when a world is archived and
     * somebody disputes it.
     */
    private static final Duration NOTICE_RETENTION = Duration.ofDays(30);

    private final Database database;
    private final PlayerWorldRepository worlds;
    private final ProfileRepository profiles;
    private final MembershipRepository membership;
    private final PendingTransferRepository pendingTransfers;
    private final NoticeRepository notices;

    /** MN-2b's collection, or {@code null} on a node with no object storage to collect. */
    private final @Nullable SnapshotCollector collector;

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
     * @param commandsSwept finished {@code node_command} rows removed (CP-7)
     * @param invitesExpired expired invite rows removed (FR-40)
     * @param pendingTransfersExpired expired {@code pending_transfer} rows removed (FR-40)
     * @param archivalWarningsSent FR-34 warnings queued for offline owners
     * @param objectsCollected data and manifest objects reclaimed (MN-2b)
     */
    public record SweepResult(
            boolean ranWithLock,
            int archivalsQueued,
            int archivingReset,
            int restoringReset,
            int transferRequestsExpired,
            long cacheBytesEvicted,
            int quarantineEntriesPruned,
            int profileSnapshotsPruned,
            int commandsSwept,
            int invitesExpired,
            int pendingTransfersExpired,
            int archivalWarningsSent,
            int objectsCollected) {

        /** No lock, so only the node-local half ran. */
        static SweepResult localOnly(long cacheBytesEvicted, int quarantineEntriesPruned) {
            return new SweepResult(false, 0, 0, 0, 0, cacheBytesEvicted, quarantineEntriesPruned, 0, 0, 0, 0, 0, 0);
        }
    }

    public MaintenanceTask(
            Database database,
            PlayerWorldRepository worlds,
            ProfileRepository profiles,
            MembershipRepository membership,
            PendingTransferRepository pendingTransfers,
            NoticeRepository notices,
            TransferRequestRepository transferRequests,
            NodeCommandRepository nodeCommands,
            LocalObjectCache localCache,
            Path quarantineRoot,
            @Nullable SnapshotCollector collector,
            Supplier<NetworkPolicy> policy,
            String nodeId) {
        this(
                database,
                worlds,
                profiles,
                membership,
                pendingTransfers,
                notices,
                transferRequests,
                nodeCommands,
                localCache,
                quarantineRoot,
                collector,
                policy,
                nodeId,
                Clock.systemUTC());
    }

    /** As above, with an injectable clock for MN-13a's retention window in tests. */
    public MaintenanceTask(
            Database database,
            PlayerWorldRepository worlds,
            ProfileRepository profiles,
            MembershipRepository membership,
            PendingTransferRepository pendingTransfers,
            NoticeRepository notices,
            TransferRequestRepository transferRequests,
            NodeCommandRepository nodeCommands,
            LocalObjectCache localCache,
            Path quarantineRoot,
            @Nullable SnapshotCollector collector,
            Supplier<NetworkPolicy> policy,
            String nodeId,
            Clock clock) {
        this.database = Objects.requireNonNull(database, "database");
        this.worlds = Objects.requireNonNull(worlds, "worlds");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.membership = Objects.requireNonNull(membership, "membership");
        this.pendingTransfers = Objects.requireNonNull(pendingTransfers, "pendingTransfers");
        this.notices = Objects.requireNonNull(notices, "notices");
        this.transferRequests = Objects.requireNonNull(transferRequests, "transferRequests");
        this.nodeCommands = Objects.requireNonNull(nodeCommands, "nodeCommands");
        this.localCache = Objects.requireNonNull(localCache, "localCache");
        this.quarantineRoot = Objects.requireNonNull(quarantineRoot, "quarantineRoot");
        this.collector = collector;
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
                            || result.profileSnapshotsPruned() > 0
                            || result.commandsSwept() > 0
                            || result.invitesExpired() > 0
                            || result.pendingTransfersExpired() > 0
                            || result.archivalWarningsSent() > 0
                            || result.objectsCollected() > 0)) {
                log.info(
                        "maintenance sweep: {} archivals queued, {} ARCHIVING reset, {} RESTORING reset,"
                                + " {} transfer requests expired, {} profile rows pruned, {} commands swept,"
                                + " {} invites expired, {} pending transfers expired, {} archival warnings sent,"
                                + " {} storage objects collected",
                        result.archivalsQueued(),
                        result.archivingReset(),
                        result.restoringReset(),
                        result.transferRequestsExpired(),
                        result.profileSnapshotsPruned(),
                        result.commandsSwept(),
                        result.invitesExpired(),
                        result.pendingTransfersExpired(),
                        result.archivalWarningsSent(),
                        result.objectsCollected());
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
     * @return what the sweep did; the node-local counters are filled whether or not
     *     this node took the lock, and the rest are zero when it did not
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
            // CP-7 and FR-40's remaining sweeps. All three tables filter their
            // reads on expiry already, so this is hygiene — but an unswept table
            // grows for the life of the network, and node_command's is the one
            // every poll of every node scans an index of.
            int commandsSwept = nodeCommands.deleteFinishedBefore(COMMAND_RETENTION, BATCH_LIMIT);
            int invitesExpired = membership.sweepExpiredInvites(BATCH_LIMIT);
            int transfersExpired = pendingTransfers.sweepExpired(current.transferExpiry());
            var _ = notices.deleteDeliveredBefore(NOTICE_RETENTION, BATCH_LIMIT);
            // After the archival pass, so a world archived in this same sweep is
            // not also warned that it is about to be.
            int warningsSent = queueArchivalWarnings(current);
            int objectsCollected = collectObjectStorage(current);
            return new SweepResult(
                    true,
                    queued,
                    archivingReset,
                    restoringReset,
                    expired,
                    cacheEvicted,
                    quarantinePruned,
                    profilesPruned,
                    commandsSwept,
                    invitesExpired,
                    transfersExpired,
                    warningsSent,
                    objectsCollected);
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
     * MN-2b: reclaims data objects no retained manifest references.
     *
     * <p>Only worth running since R21 made manifests able to shrink. Before that
     * nearly every object was still referenced by a retained manifest, because a
     * manifest could never lose an entry, and a pass would have reclaimed almost
     * nothing while listing the whole bucket to find out.
     *
     * <p>A failure on one world does not stop the others: object storage being
     * unreachable is a 12.7 condition the rest of the sweep survives.
     */
    private int collectObjectStorage(NetworkPolicy current) throws SQLException {
        SnapshotCollector snapshotCollector = collector;
        if (snapshotCollector == null) {
            return 0;
        }
        int collected = 0;
        for (PlayerWorld world : worlds.findCollectable(BATCH_LIMIT)) {
            try {
                SnapshotCollector.Collected result =
                        snapshotCollector.collect(world.id(), world.manifestKey(), current.manifestRetentionCount());
                collected += result.dataObjectsDeleted() + result.manifestsDeleted();
            } catch (RuntimeException e) {
                log.warn("could not collect object storage for world {} (MN-2b)", world.id(), e);
            }
        }
        return collected;
    }

    /**
     * FR-34: the owner is warned before their world is archived.
     *
     * <p>Thresholds are taken tightest-first, so a world that has gone past both
     * of them — a node that was down for a fortnight, say — is told the nearer
     * one rather than the one it has already sailed past.
     *
     * <p>The message is queued rather than sent: the owner is offline by
     * definition, since inactivity is what triggers the archival. The proxy hands
     * it over on their next login.
     */
    private int queueArchivalWarnings(NetworkPolicy current) throws SQLException {
        List<Integer> thresholds = new ArrayList<>(current.archiveWarnDays());
        thresholds.sort(Comparator.naturalOrder());
        int sent = 0;
        for (int warnDays : thresholds) {
            if (warnDays < 1 || warnDays >= current.archiveAfterDays()) {
                log.warn(
                        "ignoring archive.warn-days entry {}: it must be between 1 and archive.after-days ({})",
                        warnDays,
                        current.archiveAfterDays());
                continue;
            }
            for (PlayerWorld world :
                    worlds.findDueForArchiveWarning(current.archiveAfterDays(), warnDays, BATCH_LIMIT)) {
                if (queueOneWarning(world, warnDays)) {
                    sent++;
                }
            }
        }
        return sent;
    }

    /**
     * Queues one warning and records it, in one transaction.
     *
     * <p>Together, so a crash between them cannot leave a world marked warned
     * with nothing waiting for its owner — which would be a world archived in
     * silence, and FR-34 exists to prevent exactly that.
     */
    private boolean queueOneWarning(PlayerWorld world, int warnDays) throws SQLException {
        return database.inTransaction(connection -> {
            if (!worlds.recordArchiveWarning(connection, world.id(), warnDays)) {
                // Another node's sweep got there first.
                return false;
            }
            var _ = notices.queue(
                    connection,
                    world.ownerUuid(),
                    world.id(),
                    "Your world '" + world.name() + "' has not been visited recently and will be archived in "
                            + warnDays + " day" + (warnDays == 1 ? "" : "s")
                            + ". Visit it to keep it, or use /world archive to archive it now.");
            log.info("warned the owner of world {} that it is {} days from archival (FR-34)", world.id(), warnDays);
            return true;
        });
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
