package nl.gzmn.playerworlds.core.obs;

/**
 * The structured log events NFR-6 requires. An enum rather than free-text
 * strings so that an event cannot be logged under a misspelled name and quietly
 * vanish from a dashboard that is filtering for the correct one.
 *
 * <p>Every entry here is expected to appear in monitoring. Adding an event is
 * cheap; renaming one breaks alerts, so treat these names as an interface.
 */
public enum LogEvent {
    WORLD_CREATE("world.create"),
    WORLD_JOIN("world.join"),
    WORLD_INVITE("world.invite"),
    WORLD_KICK("world.kick"),
    WORLD_UNLOAD("world.unload"),
    WORLD_DELETE("world.delete"),
    LEASE_ACQUIRE("lease.acquire"),
    LEASE_RELEASE("lease.release"),
    /** A node discovering it has been fenced (MN-10, MN-10a). */
    LEASE_LOST("lease.lost"),
    /** A self-fence on an unreachable database, at the MN-10b deadline. */
    LEASE_SELF_FENCE("lease.self_fence"),
    SYNC_START("sync.start"),
    SYNC_FINISH("sync.finish"),

    /** A snapshot commit did not reach object storage (12.7, MN-11a). */
    SYNC_FAILED("sync.failed"),

    /** MN-11a's forced unload: commits have failed for storage.max-sync-failure-minutes. */
    SYNC_ABANDONED("sync.abandoned"),
    /** A commit rejected because the lease moved; expected, not an error (MN-3a). */
    COMMIT_FENCED("commit.fenced"),
    /** A region file that failed structural validation (MN-5c). */
    SNAPSHOT_INVALID_REGION("snapshot.invalid_region"),
    /** A world refused because it is newer than this node (MN-26). */
    VERSION_REFUSED("version.refused");

    private final String key;

    LogEvent(String key) {
        this.key = key;
    }

    /** The stable string written to logs and matched by monitoring. */
    public String key() {
        return key;
    }
}
