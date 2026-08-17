package nl.gzmn.playerworlds.core.obs;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One-shot startup capability probe result (plan section 10.4).
 *
 * <p>Logged once, loudly, at enable. {@link #safeToEnable()} is false when a
 * checked probe failed a safety property (free space, database, storage, schema
 * out of range). Unchecked optional probes (no database wired yet) do not fail
 * the enable on their own.
 */
public final class CapabilityReport {

    private final String filesystemType;
    private final ReflinkVerdict reflink;
    private final long freeBytes;
    private final long minFreeBytes;
    private final boolean freeSpaceOk;
    private final @Nullable String minecraftVersion;
    private final @Nullable Integer dataVersion;
    private final @Nullable Integer schemaVersion;
    private final int schemaMinSupported;
    private final int schemaMaxSupported;
    private final boolean schemaOk;
    private final boolean databaseChecked;
    private final boolean databaseReachable;
    private final @Nullable String databaseError;
    private final boolean storageChecked;
    private final boolean storageReachable;
    private final @Nullable String storageError;
    private final List<String> failures;

    private CapabilityReport(Builder builder) {
        this.filesystemType = Objects.requireNonNull(builder.filesystemType, "filesystemType");
        this.reflink = Objects.requireNonNull(builder.reflink, "reflink");
        this.freeBytes = builder.freeBytes;
        this.minFreeBytes = builder.minFreeBytes;
        this.freeSpaceOk = builder.freeSpaceOk;
        this.minecraftVersion = builder.minecraftVersion;
        this.dataVersion = builder.dataVersion;
        this.schemaVersion = builder.schemaVersion;
        this.schemaMinSupported = builder.schemaMinSupported;
        this.schemaMaxSupported = builder.schemaMaxSupported;
        this.schemaOk = builder.schemaOk;
        this.databaseChecked = builder.databaseChecked;
        this.databaseReachable = builder.databaseReachable;
        this.databaseError = builder.databaseError;
        this.storageChecked = builder.storageChecked;
        this.storageReachable = builder.storageReachable;
        this.storageError = builder.storageError;
        this.failures = List.copyOf(builder.failures);
    }

    public String filesystemType() {
        return filesystemType;
    }

    public ReflinkVerdict reflink() {
        return reflink;
    }

    public long freeBytes() {
        return freeBytes;
    }

    public long minFreeBytes() {
        return minFreeBytes;
    }

    public boolean freeSpaceOk() {
        return freeSpaceOk;
    }

    public @Nullable String minecraftVersion() {
        return minecraftVersion;
    }

    public @Nullable Integer dataVersion() {
        return dataVersion;
    }

    public @Nullable Integer schemaVersion() {
        return schemaVersion;
    }

    public int schemaMinSupported() {
        return schemaMinSupported;
    }

    public int schemaMaxSupported() {
        return schemaMaxSupported;
    }

    public boolean schemaOk() {
        return schemaOk;
    }

    public boolean databaseChecked() {
        return databaseChecked;
    }

    public boolean databaseReachable() {
        return databaseReachable;
    }

    public @Nullable String databaseError() {
        return databaseError;
    }

    public boolean storageChecked() {
        return storageChecked;
    }

    public boolean storageReachable() {
        return storageReachable;
    }

    public @Nullable String storageError() {
        return storageError;
    }

    public List<String> failures() {
        return failures;
    }

    /**
     * Whether enable may proceed. False when any safety-critical probe that was
     * actually run failed.
     */
    public boolean safeToEnable() {
        return failures.isEmpty();
    }

    static Builder builder() {
        return new Builder();
    }

    static final class Builder {
        private String filesystemType = "unknown";
        private ReflinkVerdict reflink = ReflinkVerdict.UNKNOWN;
        private long freeBytes;
        private long minFreeBytes;
        private boolean freeSpaceOk = true;
        private @Nullable String minecraftVersion;
        private @Nullable Integer dataVersion;
        private @Nullable Integer schemaVersion;
        private int schemaMinSupported;
        private int schemaMaxSupported;
        private boolean schemaOk = true;
        private boolean databaseChecked;
        private boolean databaseReachable = true;
        private @Nullable String databaseError;
        private boolean storageChecked;
        private boolean storageReachable = true;
        private @Nullable String storageError;
        private final List<String> failures = new ArrayList<>();

        Builder filesystemType(String filesystemType) {
            this.filesystemType = filesystemType;
            return this;
        }

        Builder reflink(ReflinkVerdict reflink) {
            this.reflink = reflink;
            return this;
        }

        Builder freeBytes(long freeBytes) {
            this.freeBytes = freeBytes;
            return this;
        }

        Builder minFreeBytes(long minFreeBytes) {
            this.minFreeBytes = minFreeBytes;
            return this;
        }

        Builder freeSpaceOk(boolean freeSpaceOk) {
            this.freeSpaceOk = freeSpaceOk;
            return this;
        }

        Builder minecraftVersion(@Nullable String minecraftVersion) {
            this.minecraftVersion = minecraftVersion;
            return this;
        }

        Builder dataVersion(@Nullable Integer dataVersion) {
            this.dataVersion = dataVersion;
            return this;
        }

        Builder schemaVersion(@Nullable Integer schemaVersion) {
            this.schemaVersion = schemaVersion;
            return this;
        }

        Builder schemaRange(int min, int max, boolean ok) {
            this.schemaMinSupported = min;
            this.schemaMaxSupported = max;
            this.schemaOk = ok;
            return this;
        }

        Builder databaseChecked(boolean databaseChecked) {
            this.databaseChecked = databaseChecked;
            return this;
        }

        Builder databaseReachable(boolean databaseReachable) {
            this.databaseReachable = databaseReachable;
            return this;
        }

        Builder databaseError(@Nullable String databaseError) {
            this.databaseError = databaseError;
            return this;
        }

        Builder storageChecked(boolean storageChecked) {
            this.storageChecked = storageChecked;
            return this;
        }

        Builder storageReachable(boolean storageReachable) {
            this.storageReachable = storageReachable;
            return this;
        }

        Builder storageError(@Nullable String storageError) {
            this.storageError = storageError;
            return this;
        }

        Builder failure(String message) {
            failures.add(message);
            return this;
        }

        CapabilityReport build() {
            return new CapabilityReport(this);
        }
    }
}
