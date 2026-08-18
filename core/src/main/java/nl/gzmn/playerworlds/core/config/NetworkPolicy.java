package nl.gzmn.playerworlds.core.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Network-wide policy, loaded from {@code network_setting}.
 *
 * <p>One value for the whole network. The proxy and every backend read the same
 * rows, so {@code worlds.max-per-player} checked at {@code /world create} cannot
 * disagree with anything a node believes, and changing a cap does not require a
 * rolling restart (plan section 8.1, OQ-16).
 *
 * <p>Missing rows fall back to the defaults below, which match specification
 * sections 7 and 12.8. Defaults live in code rather than as seeded migration
 * rows so a fresh database is useful before any admin has written settings, and
 * so a default change is a code change rather than an uneditable migration.
 *
 * <h2>Keys that used to be two names</h2>
 *
 * <ul>
 *   <li>{@code profiles.retain-snapshots} and {@code storage.manifest-retention-count}
 *       are one key: {@link #KEY_MANIFEST_RETENTION}. Pruning manifests faster
 *       than profiles issues every player a fresh inventory under FR-15b —
 *       silent, total loss for that world. See ADR 0007.
 *   <li>{@code worlds.storage-path} is gone; the path is node-local
 *       {@code storage.local-scratch-path} on {@link NodeConfig}.
 *   <li>{@code archive.s3.*} credentials are gone; archives share
 *       {@link StorageClientSettings} with a bucket override.
 * </ul>
 */
public record NetworkPolicy(
        int maxWorldsPerPlayer,
        Duration idleUnload,
        Duration unloadRetry,
        int defaultBorderRadius,
        int netherBorderDivisor,
        int pregenSpawnChunks,
        Duration createStallBudget,
        String defaultVisibility,
        int browsePageSize,
        List<String> allowedCommands,
        int archiveAfterDays,
        List<Integer> archiveWarnDays,
        String archiveCompression,
        Duration inviteExpiry,
        Duration transferPendingExpiry,
        Duration transferExpiry,
        Duration holdingTimeout,
        Duration maintenanceInterval,
        Duration controlPollInterval,
        Duration controlClaimTimeout,
        Duration leaseDuration,
        Duration deadAfter,
        Duration fenceSafetyMargin,
        int maxWorldsPerNode,
        int maxHeapPercent,
        double minTps,
        Duration syncInterval,
        Duration maxSyncFailure,
        Duration snapshotQuiet,
        Duration snapshotQuiesceTimeout,
        int snapshotCopyRetries,
        boolean verifyRegionStructure,
        Duration commitTimeout,
        Duration coldLoadBudget,
        int manifestRetentionCount,
        int parallelTransfers,
        long localCacheMaxBytes,
        long quarantineMaxBytes,
        int quarantineRetainDays,
        List<String> excludeGlobs,
        long defaultStorageLimitBytes,
        List<String> storageQuotaTiers) {

    // --- key names (the network_setting primary key) -----------------------

    public static final String KEY_MAX_WORLDS_PER_PLAYER = "worlds.max-per-player";
    public static final String KEY_IDLE_UNLOAD_MINUTES = "worlds.idle-unload-minutes";
    public static final String KEY_UNLOAD_RETRY_MINUTES = "worlds.unload-retry-minutes";
    public static final String KEY_DEFAULT_BORDER_RADIUS = "worlds.default-border-radius";
    public static final String KEY_NETHER_BORDER_DIVISOR = "worlds.nether-border-divisor";
    public static final String KEY_PREGEN_SPAWN_CHUNKS = "worlds.pregen-spawn-chunks";
    public static final String KEY_CREATE_STALL_BUDGET_MS = "worlds.create-stall-budget-ms";
    public static final String KEY_DEFAULT_VISIBILITY = "worlds.default-visibility";
    public static final String KEY_BROWSE_PAGE_SIZE = "worlds.public.browse-page-size";
    public static final String KEY_ALLOWED_COMMANDS = "worlds.allowed-commands";
    public static final String KEY_ARCHIVE_AFTER_DAYS = "archive.after-days";
    public static final String KEY_ARCHIVE_WARN_DAYS = "archive.warn-days";
    public static final String KEY_ARCHIVE_COMPRESSION = "archive.compression";
    public static final String KEY_INVITE_EXPIRY_MINUTES = "invites.expiry-minutes";
    public static final String KEY_TRANSFER_PENDING_EXPIRY_DAYS = "transfers.pending-expiry-days";
    public static final String KEY_TRANSFER_EXPIRY_SECONDS = "transfers.expiry-seconds";
    public static final String KEY_HOLDING_TIMEOUT_SECONDS = "transfers.holding-timeout-seconds";
    public static final String KEY_MAINTENANCE_INTERVAL_MINUTES = "maintenance.interval-minutes";
    public static final String KEY_CONTROL_POLL_SECONDS = "control.poll-seconds";
    public static final String KEY_CONTROL_CLAIM_TIMEOUT_SECONDS = "control.claim-timeout-seconds";
    public static final String KEY_LEASE_SECONDS = "nodes.lease-seconds";
    public static final String KEY_DEAD_AFTER_SECONDS = "nodes.dead-after-seconds";
    public static final String KEY_FENCE_SAFETY_MARGIN_SECONDS = "nodes.fence-safety-margin-seconds";
    public static final String KEY_MAX_WORLDS_PER_NODE = "nodes.max-worlds";
    public static final String KEY_MAX_HEAP_PERCENT = "nodes.max-heap-percent";
    public static final String KEY_MIN_TPS = "nodes.min-tps";
    public static final String KEY_SYNC_MINUTES = "storage.sync-minutes";
    public static final String KEY_MAX_SYNC_FAILURE_MINUTES = "storage.max-sync-failure-minutes";
    public static final String KEY_SNAPSHOT_QUIET_MS = "storage.snapshot-quiet-ms";
    public static final String KEY_SNAPSHOT_QUIESCE_TIMEOUT_MS = "storage.snapshot-quiesce-timeout-ms";
    public static final String KEY_SNAPSHOT_COPY_RETRIES = "storage.snapshot-copy-retries";
    public static final String KEY_VERIFY_REGION_STRUCTURE = "storage.verify-region-structure";
    public static final String KEY_COMMIT_TIMEOUT_SECONDS = "storage.commit-timeout-seconds";
    public static final String KEY_COLD_LOAD_BUDGET_SECONDS = "storage.cold-load-budget-seconds";
    /**
     * Retention count for both manifests and profiles. Was previously two keys
     * ({@code profiles.retain-snapshots} and {@code storage.manifest-retention-count})
     * that "should be aligned"; they are now one key because misalignment is
     * silent inventory loss (ADR 0007).
     */
    public static final String KEY_MANIFEST_RETENTION = "storage.manifest-retention-count";
    /**
     * Former name of {@link #KEY_MANIFEST_RETENTION}. Kept only so a leftover row
     * can be refused by name; never write this key.
     */
    public static final String REJECTED_PROFILES_RETAIN_SNAPSHOTS = "profiles.retain-snapshots";

    public static final String KEY_PARALLEL_TRANSFERS = "storage.parallel-transfers";
    public static final String KEY_LOCAL_CACHE_MAX_GB = "storage.local-cache-max-gb";
    public static final String KEY_QUARANTINE_MAX_GB = "storage.quarantine-max-gb";
    public static final String KEY_QUARANTINE_RETAIN_DAYS = "storage.quarantine-retain-days";
    public static final String KEY_EXCLUDE_GLOBS = "storage.exclude-globs";
    public static final String KEY_DEFAULT_STORAGE_LIMIT_GB = "storage.default-limit-gb";

    /**
     * Storage tiers an operator has actually granted, as {@code gzmn.worlds.storage.<amount><unit>}
     * suffixes (§4).
     *
     * <p>Only consulted where permissions cannot be enumerated. LuckPerms can list what a player
     * holds, so with it installed every tier works whether or not it appears here; without it the
     * proxy has to ask about specific nodes, and this is the list it asks about. An operator who
     * invents a tier outside this list and has no enumerable permission backend gets the default
     * allowance, so the two need to be kept in step.
     */
    public static final String KEY_STORAGE_QUOTA_TIERS = "storage.quota-tiers";

    // --- defaults (specification sections 7 and 12.8) ----------------------

    public static final int DEFAULT_MAX_WORLDS_PER_PLAYER = 2;
    public static final Duration DEFAULT_IDLE_UNLOAD = Duration.ofMinutes(10);
    public static final Duration DEFAULT_UNLOAD_RETRY = Duration.ofMinutes(2);
    public static final int DEFAULT_BORDER_RADIUS = 5000;
    public static final int DEFAULT_NETHER_BORDER_DIVISOR = 8;
    public static final int DEFAULT_PREGEN_SPAWN_CHUNKS = 3;
    public static final Duration DEFAULT_CREATE_STALL_BUDGET = Duration.ofMillis(1500);
    public static final String DEFAULT_VISIBILITY = "PRIVATE";
    public static final int DEFAULT_BROWSE_PAGE_SIZE = 10;
    public static final List<String> DEFAULT_ALLOWED_COMMANDS = List.of();
    public static final int DEFAULT_ARCHIVE_AFTER_DAYS = 90;
    public static final List<Integer> DEFAULT_ARCHIVE_WARN_DAYS = List.of(14, 3);
    public static final String DEFAULT_ARCHIVE_COMPRESSION = "zstd-3";
    public static final Duration DEFAULT_INVITE_EXPIRY = Duration.ofMinutes(10);
    public static final Duration DEFAULT_TRANSFER_PENDING_EXPIRY = Duration.ofDays(7);
    public static final Duration DEFAULT_TRANSFER_EXPIRY = Duration.ofSeconds(60);
    public static final Duration DEFAULT_HOLDING_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration DEFAULT_MAINTENANCE_INTERVAL = Duration.ofMinutes(5);
    public static final Duration DEFAULT_CONTROL_POLL = Duration.ofSeconds(5);
    public static final Duration DEFAULT_CONTROL_CLAIM_TIMEOUT = Duration.ofSeconds(60);
    public static final Duration DEFAULT_LEASE_DURATION = Duration.ofMinutes(3);
    public static final Duration DEFAULT_DEAD_AFTER = Duration.ofSeconds(60);
    public static final Duration DEFAULT_FENCE_SAFETY_MARGIN = Duration.ofSeconds(30);
    public static final int DEFAULT_MAX_WORLDS_PER_NODE = 5;
    public static final int DEFAULT_MAX_HEAP_PERCENT = 85;
    public static final double DEFAULT_MIN_TPS = 18.0;
    public static final Duration DEFAULT_SYNC_INTERVAL = Duration.ofMinutes(5);
    public static final Duration DEFAULT_MAX_SYNC_FAILURE = Duration.ofMinutes(30);
    public static final Duration DEFAULT_SNAPSHOT_QUIET = Duration.ofMillis(250);
    public static final Duration DEFAULT_SNAPSHOT_QUIESCE_TIMEOUT = Duration.ofSeconds(5);
    public static final int DEFAULT_SNAPSHOT_COPY_RETRIES = 3;
    public static final boolean DEFAULT_VERIFY_REGION_STRUCTURE = true;
    public static final Duration DEFAULT_COMMIT_TIMEOUT = Duration.ofSeconds(15);
    public static final Duration DEFAULT_COLD_LOAD_BUDGET = Duration.ofSeconds(60);
    public static final int DEFAULT_MANIFEST_RETENTION = 3;
    public static final int DEFAULT_PARALLEL_TRANSFERS = 4;
    public static final long DEFAULT_LOCAL_CACHE_MAX_BYTES = 100L * 1024 * 1024 * 1024;
    public static final long DEFAULT_QUARANTINE_MAX_BYTES = 50L * 1024 * 1024 * 1024;
    public static final int DEFAULT_QUARANTINE_RETAIN_DAYS = 7;
    public static final List<String> DEFAULT_EXCLUDE_GLOBS = List.of("session.lock", "uid.dat");

    /** A ladder covering the tier sizes a network is likely to sell, smallest first. */
    public static final List<String> DEFAULT_STORAGE_QUOTA_TIERS = List.of(
            "100mb", "250mb", "500mb", "750mb", "1gb", "2gb", "3gb", "5gb", "10gb", "15gb", "20gb", "25gb", "50gb",
            "75gb", "100gb", "250gb", "500gb", "1tb", "2tb", "5tb");

    public static final long DEFAULT_STORAGE_LIMIT_BYTES = 5L * 1024 * 1024 * 1024;

    public NetworkPolicy {
        Objects.requireNonNull(idleUnload, "idleUnload");
        Objects.requireNonNull(unloadRetry, "unloadRetry");
        Objects.requireNonNull(createStallBudget, "createStallBudget");
        Objects.requireNonNull(defaultVisibility, "defaultVisibility");
        Objects.requireNonNull(allowedCommands, "allowedCommands");
        Objects.requireNonNull(archiveWarnDays, "archiveWarnDays");
        Objects.requireNonNull(archiveCompression, "archiveCompression");
        Objects.requireNonNull(inviteExpiry, "inviteExpiry");
        Objects.requireNonNull(transferPendingExpiry, "transferPendingExpiry");
        Objects.requireNonNull(transferExpiry, "transferExpiry");
        Objects.requireNonNull(holdingTimeout, "holdingTimeout");
        Objects.requireNonNull(maintenanceInterval, "maintenanceInterval");
        Objects.requireNonNull(controlPollInterval, "controlPollInterval");
        Objects.requireNonNull(controlClaimTimeout, "controlClaimTimeout");
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        Objects.requireNonNull(deadAfter, "deadAfter");
        Objects.requireNonNull(fenceSafetyMargin, "fenceSafetyMargin");
        Objects.requireNonNull(syncInterval, "syncInterval");
        Objects.requireNonNull(maxSyncFailure, "maxSyncFailure");
        Objects.requireNonNull(snapshotQuiet, "snapshotQuiet");
        Objects.requireNonNull(snapshotQuiesceTimeout, "snapshotQuiesceTimeout");
        Objects.requireNonNull(commitTimeout, "commitTimeout");
        Objects.requireNonNull(coldLoadBudget, "coldLoadBudget");
        Objects.requireNonNull(excludeGlobs, "excludeGlobs");
        Objects.requireNonNull(storageQuotaTiers, "storageQuotaTiers");
        if (defaultStorageLimitBytes < 0) {
            throw new IllegalArgumentException(
                    "defaultStorageLimitBytes must not be negative: " + defaultStorageLimitBytes);
        }
        allowedCommands = List.copyOf(allowedCommands);
        archiveWarnDays = List.copyOf(archiveWarnDays);
        excludeGlobs = List.copyOf(excludeGlobs);
        storageQuotaTiers = List.copyOf(storageQuotaTiers);
    }

    /** Specification defaults, used when {@code network_setting} has no row. */
    public static NetworkPolicy defaults() {
        return new NetworkPolicy(
                DEFAULT_MAX_WORLDS_PER_PLAYER,
                DEFAULT_IDLE_UNLOAD,
                DEFAULT_UNLOAD_RETRY,
                DEFAULT_BORDER_RADIUS,
                DEFAULT_NETHER_BORDER_DIVISOR,
                DEFAULT_PREGEN_SPAWN_CHUNKS,
                DEFAULT_CREATE_STALL_BUDGET,
                DEFAULT_VISIBILITY,
                DEFAULT_BROWSE_PAGE_SIZE,
                DEFAULT_ALLOWED_COMMANDS,
                DEFAULT_ARCHIVE_AFTER_DAYS,
                DEFAULT_ARCHIVE_WARN_DAYS,
                DEFAULT_ARCHIVE_COMPRESSION,
                DEFAULT_INVITE_EXPIRY,
                DEFAULT_TRANSFER_PENDING_EXPIRY,
                DEFAULT_TRANSFER_EXPIRY,
                DEFAULT_HOLDING_TIMEOUT,
                DEFAULT_MAINTENANCE_INTERVAL,
                DEFAULT_CONTROL_POLL,
                DEFAULT_CONTROL_CLAIM_TIMEOUT,
                DEFAULT_LEASE_DURATION,
                DEFAULT_DEAD_AFTER,
                DEFAULT_FENCE_SAFETY_MARGIN,
                DEFAULT_MAX_WORLDS_PER_NODE,
                DEFAULT_MAX_HEAP_PERCENT,
                DEFAULT_MIN_TPS,
                DEFAULT_SYNC_INTERVAL,
                DEFAULT_MAX_SYNC_FAILURE,
                DEFAULT_SNAPSHOT_QUIET,
                DEFAULT_SNAPSHOT_QUIESCE_TIMEOUT,
                DEFAULT_SNAPSHOT_COPY_RETRIES,
                DEFAULT_VERIFY_REGION_STRUCTURE,
                DEFAULT_COMMIT_TIMEOUT,
                DEFAULT_COLD_LOAD_BUDGET,
                DEFAULT_MANIFEST_RETENTION,
                DEFAULT_PARALLEL_TRANSFERS,
                DEFAULT_LOCAL_CACHE_MAX_BYTES,
                DEFAULT_QUARANTINE_MAX_BYTES,
                DEFAULT_QUARANTINE_RETAIN_DAYS,
                DEFAULT_EXCLUDE_GLOBS,
                DEFAULT_STORAGE_LIMIT_BYTES,
                DEFAULT_STORAGE_QUOTA_TIERS);
    }

    /**
     * Builds a policy from raw JSON texts keyed by setting name.
     *
     * <p>Values are the JSONB contents as text (for example {@code 3},
     * {@code "PRIVATE"}, {@code [14, 3]}). Absent keys keep their default. The
     * rejected alias {@link #REJECTED_PROFILES_RETAIN_SNAPSHOTS} is refused
     * rather than honoured, so a leftover row cannot silently reintroduce the
     * split-brain retention bug.
     */
    public static NetworkPolicy fromRaw(Map<String, String> rawJsonByKey) {
        Objects.requireNonNull(rawJsonByKey, "rawJsonByKey");
        if (rawJsonByKey.containsKey(REJECTED_PROFILES_RETAIN_SNAPSHOTS)) {
            throw new ConfigException("network_setting key '"
                    + REJECTED_PROFILES_RETAIN_SNAPSHOTS
                    + "' is rejected; use '"
                    + KEY_MANIFEST_RETENTION
                    + "' for both manifest and profile retention (ADR 0007)");
        }
        return new NetworkPolicy(
                intVal(rawJsonByKey, KEY_MAX_WORLDS_PER_PLAYER, DEFAULT_MAX_WORLDS_PER_PLAYER),
                minutes(rawJsonByKey, KEY_IDLE_UNLOAD_MINUTES, DEFAULT_IDLE_UNLOAD),
                minutes(rawJsonByKey, KEY_UNLOAD_RETRY_MINUTES, DEFAULT_UNLOAD_RETRY),
                intVal(rawJsonByKey, KEY_DEFAULT_BORDER_RADIUS, DEFAULT_BORDER_RADIUS),
                intVal(rawJsonByKey, KEY_NETHER_BORDER_DIVISOR, DEFAULT_NETHER_BORDER_DIVISOR),
                intVal(rawJsonByKey, KEY_PREGEN_SPAWN_CHUNKS, DEFAULT_PREGEN_SPAWN_CHUNKS),
                millis(rawJsonByKey, KEY_CREATE_STALL_BUDGET_MS, DEFAULT_CREATE_STALL_BUDGET),
                stringVal(rawJsonByKey, KEY_DEFAULT_VISIBILITY, DEFAULT_VISIBILITY),
                intVal(rawJsonByKey, KEY_BROWSE_PAGE_SIZE, DEFAULT_BROWSE_PAGE_SIZE),
                stringList(rawJsonByKey, KEY_ALLOWED_COMMANDS, DEFAULT_ALLOWED_COMMANDS),
                intVal(rawJsonByKey, KEY_ARCHIVE_AFTER_DAYS, DEFAULT_ARCHIVE_AFTER_DAYS),
                intList(rawJsonByKey, KEY_ARCHIVE_WARN_DAYS, DEFAULT_ARCHIVE_WARN_DAYS),
                stringVal(rawJsonByKey, KEY_ARCHIVE_COMPRESSION, DEFAULT_ARCHIVE_COMPRESSION),
                minutes(rawJsonByKey, KEY_INVITE_EXPIRY_MINUTES, DEFAULT_INVITE_EXPIRY),
                days(rawJsonByKey, KEY_TRANSFER_PENDING_EXPIRY_DAYS, DEFAULT_TRANSFER_PENDING_EXPIRY),
                seconds(rawJsonByKey, KEY_TRANSFER_EXPIRY_SECONDS, DEFAULT_TRANSFER_EXPIRY),
                seconds(rawJsonByKey, KEY_HOLDING_TIMEOUT_SECONDS, DEFAULT_HOLDING_TIMEOUT),
                minutes(rawJsonByKey, KEY_MAINTENANCE_INTERVAL_MINUTES, DEFAULT_MAINTENANCE_INTERVAL),
                seconds(rawJsonByKey, KEY_CONTROL_POLL_SECONDS, DEFAULT_CONTROL_POLL),
                seconds(rawJsonByKey, KEY_CONTROL_CLAIM_TIMEOUT_SECONDS, DEFAULT_CONTROL_CLAIM_TIMEOUT),
                seconds(rawJsonByKey, KEY_LEASE_SECONDS, DEFAULT_LEASE_DURATION),
                seconds(rawJsonByKey, KEY_DEAD_AFTER_SECONDS, DEFAULT_DEAD_AFTER),
                seconds(rawJsonByKey, KEY_FENCE_SAFETY_MARGIN_SECONDS, DEFAULT_FENCE_SAFETY_MARGIN),
                intVal(rawJsonByKey, KEY_MAX_WORLDS_PER_NODE, DEFAULT_MAX_WORLDS_PER_NODE),
                intVal(rawJsonByKey, KEY_MAX_HEAP_PERCENT, DEFAULT_MAX_HEAP_PERCENT),
                doubleVal(rawJsonByKey, KEY_MIN_TPS, DEFAULT_MIN_TPS),
                minutes(rawJsonByKey, KEY_SYNC_MINUTES, DEFAULT_SYNC_INTERVAL),
                minutes(rawJsonByKey, KEY_MAX_SYNC_FAILURE_MINUTES, DEFAULT_MAX_SYNC_FAILURE),
                millis(rawJsonByKey, KEY_SNAPSHOT_QUIET_MS, DEFAULT_SNAPSHOT_QUIET),
                millis(rawJsonByKey, KEY_SNAPSHOT_QUIESCE_TIMEOUT_MS, DEFAULT_SNAPSHOT_QUIESCE_TIMEOUT),
                intVal(rawJsonByKey, KEY_SNAPSHOT_COPY_RETRIES, DEFAULT_SNAPSHOT_COPY_RETRIES),
                boolVal(rawJsonByKey, KEY_VERIFY_REGION_STRUCTURE, DEFAULT_VERIFY_REGION_STRUCTURE),
                seconds(rawJsonByKey, KEY_COMMIT_TIMEOUT_SECONDS, DEFAULT_COMMIT_TIMEOUT),
                seconds(rawJsonByKey, KEY_COLD_LOAD_BUDGET_SECONDS, DEFAULT_COLD_LOAD_BUDGET),
                intVal(rawJsonByKey, KEY_MANIFEST_RETENTION, DEFAULT_MANIFEST_RETENTION),
                intVal(rawJsonByKey, KEY_PARALLEL_TRANSFERS, DEFAULT_PARALLEL_TRANSFERS),
                gib(rawJsonByKey, KEY_LOCAL_CACHE_MAX_GB, DEFAULT_LOCAL_CACHE_MAX_BYTES),
                gib(rawJsonByKey, KEY_QUARANTINE_MAX_GB, DEFAULT_QUARANTINE_MAX_BYTES),
                intVal(rawJsonByKey, KEY_QUARANTINE_RETAIN_DAYS, DEFAULT_QUARANTINE_RETAIN_DAYS),
                stringList(rawJsonByKey, KEY_EXCLUDE_GLOBS, DEFAULT_EXCLUDE_GLOBS),
                gib(rawJsonByKey, KEY_DEFAULT_STORAGE_LIMIT_GB, DEFAULT_STORAGE_LIMIT_BYTES),
                stringList(rawJsonByKey, KEY_STORAGE_QUOTA_TIERS, DEFAULT_STORAGE_QUOTA_TIERS));
    }

    private static int intVal(Map<String, String> raw, String key, int defaultValue) {
        String value = raw.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(unwrapScalar(value));
        } catch (NumberFormatException e) {
            throw new ConfigException("network_setting '" + key + "' must be an integer, was: " + value, e);
        }
    }

    private static double doubleVal(Map<String, String> raw, String key, double defaultValue) {
        String value = raw.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(unwrapScalar(value));
        } catch (NumberFormatException e) {
            throw new ConfigException("network_setting '" + key + "' must be a number, was: " + value, e);
        }
    }

    private static boolean boolVal(Map<String, String> raw, String key, boolean defaultValue) {
        String value = raw.get(key);
        if (value == null) {
            return defaultValue;
        }
        String scalar = unwrapScalar(value);
        if ("true".equalsIgnoreCase(scalar)) {
            return true;
        }
        if ("false".equalsIgnoreCase(scalar)) {
            return false;
        }
        throw new ConfigException("network_setting '" + key + "' must be true or false, was: " + value);
    }

    private static String stringVal(Map<String, String> raw, String key, String defaultValue) {
        String value = raw.get(key);
        if (value == null) {
            return defaultValue;
        }
        return unwrapJsonString(value);
    }

    private static Duration seconds(Map<String, String> raw, String key, Duration defaultValue) {
        String value = raw.get(key);
        if (value == null) {
            return defaultValue;
        }
        return Duration.ofSeconds(intVal(raw, key, (int) defaultValue.toSeconds()));
    }

    private static Duration minutes(Map<String, String> raw, String key, Duration defaultValue) {
        String value = raw.get(key);
        if (value == null) {
            return defaultValue;
        }
        return Duration.ofMinutes(intVal(raw, key, (int) defaultValue.toMinutes()));
    }

    private static Duration days(Map<String, String> raw, String key, Duration defaultValue) {
        String value = raw.get(key);
        if (value == null) {
            return defaultValue;
        }
        return Duration.ofDays(intVal(raw, key, (int) defaultValue.toDays()));
    }

    private static Duration millis(Map<String, String> raw, String key, Duration defaultValue) {
        String value = raw.get(key);
        if (value == null) {
            return defaultValue;
        }
        return Duration.ofMillis(intVal(raw, key, (int) defaultValue.toMillis()));
    }

    private static long gib(Map<String, String> raw, String key, long defaultBytes) {
        String value = raw.get(key);
        if (value == null) {
            return defaultBytes;
        }
        int gib = intVal(raw, key, (int) (defaultBytes / (1024L * 1024 * 1024)));
        if (gib < 0) {
            throw new ConfigException("network_setting '" + key + "' must not be negative, was: " + gib);
        }
        return gib * 1024L * 1024 * 1024;
    }

    private static List<Integer> intList(Map<String, String> raw, String key, List<Integer> defaultValue) {
        String value = raw.get(key);
        if (value == null) {
            return defaultValue;
        }
        String body = value.strip();
        if (body.equals("[]")) {
            return List.of();
        }
        if (!body.startsWith("[") || !body.endsWith("]")) {
            throw new ConfigException("network_setting '" + key + "' must be a JSON array of integers, was: " + value);
        }
        String inner = body.substring(1, body.length() - 1).strip();
        if (inner.isEmpty()) {
            return List.of();
        }
        List<String> parts = splitOnComma(inner);
        Integer[] parsed = new Integer[parts.size()];
        for (int i = 0; i < parts.size(); i++) {
            try {
                parsed[i] = Integer.parseInt(parts.get(i));
            } catch (NumberFormatException e) {
                throw new ConfigException(
                        "network_setting '" + key + "' must be a JSON array of integers, was: " + value, e);
            }
        }
        return List.of(parsed);
    }

    private static List<String> stringList(Map<String, String> raw, String key, List<String> defaultValue) {
        String value = raw.get(key);
        if (value == null) {
            return defaultValue;
        }
        String body = value.strip();
        if (body.equals("[]")) {
            return List.of();
        }
        if (!body.startsWith("[") || !body.endsWith("]")) {
            throw new ConfigException("network_setting '" + key + "' must be a JSON array of strings, was: " + value);
        }
        String inner = body.substring(1, body.length() - 1).strip();
        if (inner.isEmpty()) {
            return List.of();
        }
        // Comma-split is enough for our values: command names and globs contain
        // neither commas nor escaped quotes.
        List<String> parts = splitOnComma(inner);
        String[] parsed = new String[parts.size()];
        for (int i = 0; i < parts.size(); i++) {
            parsed[i] = unwrapJsonString(parts.get(i));
        }
        return List.of(parsed);
    }

    /**
     * Splits on {@code ','} and strips each piece. Avoids {@link String#split}
     * because its trailing-empty behaviour is a standing footgun and Error Prone
     * rejects it; we do not want a Guava dependency just for {@code Splitter}.
     */
    private static List<String> splitOnComma(String inner) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < inner.length(); i++) {
            if (inner.charAt(i) == ',') {
                parts.add(inner.substring(start, i).strip());
                start = i + 1;
            }
        }
        parts.add(inner.substring(start).strip());
        return parts;
    }

    /** JSONB scalar text: {@code 3}, {@code true}, or {@code "PRIVATE"}. */
    private static String unwrapScalar(String json) {
        String stripped = json.strip();
        if (stripped.length() >= 2 && stripped.charAt(0) == '"' && stripped.charAt(stripped.length() - 1) == '"') {
            return unwrapJsonString(stripped);
        }
        return stripped;
    }

    private static String unwrapJsonString(String json) {
        String stripped = json.strip();
        if (stripped.length() >= 2 && stripped.charAt(0) == '"' && stripped.charAt(stripped.length() - 1) == '"') {
            return stripped.substring(1, stripped.length() - 1);
        }
        // Bare words are accepted so a hand-written row without quotes still works.
        return stripped;
    }
}
