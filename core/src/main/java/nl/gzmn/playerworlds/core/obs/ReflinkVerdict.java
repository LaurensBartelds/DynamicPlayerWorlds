package nl.gzmn.playerworlds.core.obs;

/**
 * Result of the startup reflink probe (plan section 10.4, MN-5a).
 *
 * <p>{@code cp --reflink=auto} silently falls back to a full copy on filesystems
 * that do not support clones (ext4 is the common case). Discovering that from a
 * disk graph six weeks later is the expensive way; the probe records the real
 * behaviour once at enable.
 */
public enum ReflinkVerdict {

    /** File clones share extents; MN-5a snapshots are close to free. */
    REFLINK("reflink"),

    /** Clones are unavailable; every snapshot copies the dirty set in full. */
    FULL_COPY("full-copy"),

    /** The probe could not run (missing {@code cp}, IO error). Assume the worst. */
    UNKNOWN("unknown");

    private final String wire;

    ReflinkVerdict(String wire) {
        this.wire = wire;
    }

    /** Stable token written to logs and capability reports. */
    public String wire() {
        return wire;
    }

    /** Whether MN-5a can budget as if snapshots were cheap. */
    public boolean isCheap() {
        return this == REFLINK;
    }
}
