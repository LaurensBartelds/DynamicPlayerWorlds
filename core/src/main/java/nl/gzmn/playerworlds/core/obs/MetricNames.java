package nl.gzmn.playerworlds.core.obs;

/**
 * Micrometer meter names for the minimum set in plan section 10.2.
 *
 * <p>Micrometer's Prometheus registry turns dots into underscores, so
 * {@code worlds.loaded} scrapes as {@code worlds_loaded}. Tag names
 * ({@code result}, {@code reason}, {@code kind}) are part of the same interface:
 * renaming one breaks a dashboard.
 */
public final class MetricNames {

    public static final String WORLDS_LOADED = "worlds.loaded";
    public static final String LEASE_ACQUIRE = "lease.acquire";
    public static final String LEASE_LOST = "lease.lost";
    public static final String FENCE_EVENTS = "fence.events";
    public static final String COMMIT_DURATION = "commit.duration";
    public static final String COMMIT_FAILED = "commit.failed";
    public static final String SYNC_BYTES = "sync.bytes";
    public static final String SYNC_FILES = "sync.files";
    public static final String WORLD_LOAD = "world.load";
    public static final String CREATE_STALL = "create.stall";
    public static final String HOLDING_TIMEOUTS = "holding.timeouts";
    public static final String QUARANTINE_BYTES = "quarantine.bytes";
    public static final String SCRATCH_FREE_BYTES = "scratch.free.bytes";
    public static final String DB_POOL_WAIT = "db.pool.wait";
    public static final String OBJECT_STORAGE_UP = "object.storage.up";

    public static final String TAG_RESULT = "result";
    public static final String TAG_REASON = "reason";
    public static final String TAG_KIND = "kind";

    public static final String RESULT_OK = "ok";
    public static final String RESULT_DENIED = "denied";

    public static final String REASON_FENCED = "fenced";
    public static final String REASON_DB = "db";
    public static final String REASON_STORAGE = "storage";

    public static final String KIND_WARM = "warm";
    public static final String KIND_COLD = "cold";

    private MetricNames() {}
}
