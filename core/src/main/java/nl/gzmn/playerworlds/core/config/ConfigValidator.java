package nl.gzmn.playerworlds.core.config;

import java.time.Duration;

/**
 * Startup validations from plan section 8.2.
 *
 * <p>Each check exists because the inverted or unbounded value opens a real
 * failure mode — a takeover window against a live node (MN-18), a self-fence
 * that fires on one missed heartbeat, a quiesce that cannot finish inside the
 * commit budget, silent inventory loss from split retention. Invalid config
 * disables the plugin; it does not run with a "close enough" default.
 */
public final class ConfigValidator {

    private ConfigValidator() {}

    /**
     * Validates node-local config against network policy and the local
     * filesystem. Call once at enable, after both sides are loaded.
     *
     * @throws ConfigException if any check fails
     */
    public static void validate(NodeConfig node, NetworkPolicy policy) {
        validatePolicy(policy);
        validateNodeAgainstPolicy(node, policy);
        validatePaths(node);
    }

    /**
     * Validates network policy on its own. The proxy calls this; it has no
     * scratch paths and no heartbeat of its own.
     */
    public static void validatePolicy(NetworkPolicy policy) {
        requirePositive(policy.leaseDuration(), NetworkPolicy.KEY_LEASE_SECONDS);
        requirePositive(policy.deadAfter(), NetworkPolicy.KEY_DEAD_AFTER_SECONDS);
        requirePositive(policy.fenceSafetyMargin(), NetworkPolicy.KEY_FENCE_SAFETY_MARGIN_SECONDS);
        requirePositive(policy.commitTimeout(), NetworkPolicy.KEY_COMMIT_TIMEOUT_SECONDS);
        requirePositive(policy.snapshotQuiesceTimeout(), NetworkPolicy.KEY_SNAPSHOT_QUIESCE_TIMEOUT_MS);
        requirePositive(policy.holdingTimeout(), NetworkPolicy.KEY_HOLDING_TIMEOUT_SECONDS);
        requirePositive(policy.transferExpiry(), NetworkPolicy.KEY_TRANSFER_EXPIRY_SECONDS);
        requirePositive(policy.inviteExpiry(), NetworkPolicy.KEY_INVITE_EXPIRY_MINUTES);
        requirePositive(policy.syncInterval(), NetworkPolicy.KEY_SYNC_MINUTES);
        requirePositive(policy.coldLoadBudget(), NetworkPolicy.KEY_COLD_LOAD_BUDGET_SECONDS);
        requirePositive(policy.controlPollInterval(), NetworkPolicy.KEY_CONTROL_POLL_SECONDS);
        requirePositive(policy.controlClaimTimeout(), NetworkPolicy.KEY_CONTROL_CLAIM_TIMEOUT_SECONDS);
        requirePositive(policy.maintenanceInterval(), NetworkPolicy.KEY_MAINTENANCE_INTERVAL_MINUTES);

        if (policy.maxWorldsPerPlayer() < 1) {
            throw new ConfigException(NetworkPolicy.KEY_MAX_WORLDS_PER_PLAYER + " must be at least 1, was: "
                    + policy.maxWorldsPerPlayer());
        }
        if (policy.maxWorldsPerNode() < 1) {
            throw new ConfigException(
                    NetworkPolicy.KEY_MAX_WORLDS_PER_NODE + " must be at least 1, was: " + policy.maxWorldsPerNode());
        }
        if (policy.manifestRetentionCount() < 1) {
            throw new ConfigException(NetworkPolicy.KEY_MANIFEST_RETENTION + " must be at least 1, was: "
                    + policy.manifestRetentionCount());
        }
        if (policy.parallelTransfers() < 1) {
            throw new ConfigException(
                    NetworkPolicy.KEY_PARALLEL_TRANSFERS + " must be at least 1, was: " + policy.parallelTransfers());
        }
        if (policy.snapshotCopyRetries() < 1) {
            throw new ConfigException(NetworkPolicy.KEY_SNAPSHOT_COPY_RETRIES + " must be at least 1, was: "
                    + policy.snapshotCopyRetries());
        }
        if (policy.maxHeapPercent() < 1 || policy.maxHeapPercent() > 100) {
            throw new ConfigException(
                    NetworkPolicy.KEY_MAX_HEAP_PERCENT + " must be in 1..100, was: " + policy.maxHeapPercent());
        }
        if (policy.minTps() <= 0 || policy.minTps() > 20) {
            throw new ConfigException(NetworkPolicy.KEY_MIN_TPS + " must be in (0, 20], was: " + policy.minTps());
        }
        String visibility = policy.defaultVisibility();
        if (!visibility.equals("PRIVATE") && !visibility.equals("PUBLIC")) {
            throw new ConfigException(
                    NetworkPolicy.KEY_DEFAULT_VISIBILITY + " must be PRIVATE or PUBLIC, was: " + visibility);
        }

        // MN-18: dead-after must stay strictly below the lease. v0.2 had these
        // inverted (90 against a 60-second lease) and opened a 30-second window
        // in which a world could be taken from a node the system still considered
        // alive.
        if (!policy.deadAfter().minus(policy.leaseDuration()).isNegative()) {
            throw new ConfigException(NetworkPolicy.KEY_DEAD_AFTER_SECONDS + " ("
                    + policy.deadAfter().toSeconds() + "s) must be strictly less than "
                    + NetworkPolicy.KEY_LEASE_SECONDS + " ("
                    + policy.leaseDuration().toSeconds() + "s) (MN-18)");
        }

        // Fence margin must sit strictly below the lease: a margin at or above
        // the lease fences immediately and permanently (§9.2 / MN-10b).
        if (!policy.fenceSafetyMargin().minus(policy.leaseDuration()).isNegative()) {
            throw new ConfigException(NetworkPolicy.KEY_FENCE_SAFETY_MARGIN_SECONDS + " ("
                    + policy.fenceSafetyMargin().toSeconds() + "s) must be strictly less than "
                    + NetworkPolicy.KEY_LEASE_SECONDS + " ("
                    + policy.leaseDuration().toSeconds() + "s)");
        }

        // Quiesce runs inside the commit budget (plan §9.1). A quiesce timeout
        // that fills the whole budget leaves no time for copy, hash or upload.
        if (policy.snapshotQuiesceTimeout().compareTo(policy.commitTimeout()) >= 0) {
            throw new ConfigException(NetworkPolicy.KEY_SNAPSHOT_QUIESCE_TIMEOUT_MS + " ("
                    + policy.snapshotQuiesceTimeout().toMillis() + "ms) must be strictly less than "
                    + NetworkPolicy.KEY_COMMIT_TIMEOUT_SECONDS + " ("
                    + policy.commitTimeout().toSeconds() + "s) so the commit budget has room left");
        }

        if (policy.snapshotQuiet().compareTo(policy.snapshotQuiesceTimeout()) > 0) {
            throw new ConfigException(NetworkPolicy.KEY_SNAPSHOT_QUIET_MS + " ("
                    + policy.snapshotQuiet().toMillis() + "ms) must not exceed "
                    + NetworkPolicy.KEY_SNAPSHOT_QUIESCE_TIMEOUT_MS + " ("
                    + policy.snapshotQuiesceTimeout().toMillis() + "ms)");
        }

        // The kick path waits up to commit-timeout for the snapshot that carries
        // the kicked player's profile, then proceeds anyway (spec §9). The holding
        // area on join is a separate budget. Commit must stay strictly inside the
        // holding timeout so a slow commit cannot outlive the join path that
        // triggered an overlapping load.
        if (policy.commitTimeout().compareTo(policy.holdingTimeout()) >= 0) {
            throw new ConfigException(NetworkPolicy.KEY_COMMIT_TIMEOUT_SECONDS + " ("
                    + policy.commitTimeout().toSeconds() + "s) must be strictly less than "
                    + NetworkPolicy.KEY_HOLDING_TIMEOUT_SECONDS + " ("
                    + policy.holdingTimeout().toSeconds()
                    + "s); the kick/join paths cannot wait longer than the holding area allows");
        }

        // The same argument, for the other wait the join path takes: NFR-1's cold
        // load happens while the player stands in the holding area, so a cold-load
        // budget at or above the holding timeout is a budget that can never be
        // spent — the deadline ejects them first. Left unchecked this was the
        // shipped configuration (60s against 30s), which made every cold load
        // slower than half its own budget a guaranteed eject.
        if (policy.coldLoadBudget().compareTo(policy.holdingTimeout()) >= 0) {
            throw new ConfigException(NetworkPolicy.KEY_COLD_LOAD_BUDGET_SECONDS + " ("
                    + policy.coldLoadBudget().toSeconds() + "s) must be strictly less than "
                    + NetworkPolicy.KEY_HOLDING_TIMEOUT_SECONDS + " ("
                    + policy.holdingTimeout().toSeconds()
                    + "s); a cold load cannot outlive the holding area it happens in (NFR-1, FR-11)");
        }
    }

    private static void validateNodeAgainstPolicy(NodeConfig node, NetworkPolicy policy) {
        Duration heartbeat = node.heartbeatInterval();

        // MN-9's tolerance argument: three missed heartbeats must still fit
        // inside the lease (30/180 gives six; the floor is three).
        if (heartbeat.multipliedBy(3).compareTo(policy.leaseDuration()) > 0) {
            throw new ConfigException("node.heartbeat-seconds ("
                    + heartbeat.toSeconds() + "s) * 3 must be <= " + NetworkPolicy.KEY_LEASE_SECONDS
                    + " (" + policy.leaseDuration().toSeconds() + "s) (MN-9)");
        }

        // A fence margin below one heartbeat interval fences on a single missed
        // beat, which is noise rather than an outage (§9.2).
        if (heartbeat.compareTo(policy.fenceSafetyMargin()) > 0) {
            throw new ConfigException("node.heartbeat-seconds ("
                    + heartbeat.toSeconds() + "s) must be <= " + NetworkPolicy.KEY_FENCE_SAFETY_MARGIN_SECONDS
                    + " (" + policy.fenceSafetyMargin().toSeconds() + "s)");
        }
    }

    private static void validatePaths(NodeConfig node) {
        PathChecks.requireWritableDirectory(node.scratchPath(), "storage.local-scratch-path");
        PathChecks.requireWritableDirectory(node.cachePath(), "storage.local-cache-path");
        PathChecks.requireWritableDirectory(node.quarantinePath(), "storage.quarantine-path");
        PathChecks.requireSameFilesystem(
                node.scratchPath(), node.cachePath(), "storage.local-scratch-path", "storage.local-cache-path");
        PathChecks.requireSameFilesystem(
                node.scratchPath(), node.quarantinePath(), "storage.local-scratch-path", "storage.quarantine-path");
        PathChecks.requireFreeSpace(node.scratchPath(), node.minFreeSpaceBytes(), "storage.local-scratch-path");
    }

    private static void requirePositive(Duration value, String key) {
        if (value.isNegative() || value.isZero()) {
            throw new ConfigException(key + " must be positive, was: " + value);
        }
    }
}
