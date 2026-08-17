package nl.gzmn.playerworlds.core.obs;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Micrometer registry and the minimum meter set from plan section 10.2.
 *
 * <p>Feature code records through this type rather than inventing meter names.
 * Gauges that need a live reading ({@code worlds_loaded}, free space,
 * quarantine size) are updated by the owners of that state; counters and timers
 * are incremented at the moment of the event.
 */
public final class WorldsMetrics implements AutoCloseable {

    private final PrometheusMeterRegistry registry;
    private final AtomicInteger worldsLoaded = new AtomicInteger();
    private final AtomicLong quarantineBytes = new AtomicLong();
    private final AtomicLong scratchFreeBytes = new AtomicLong();

    private final Counter fenceEvents;
    private final Counter holdingTimeouts;
    private final Counter syncBytes;
    private final Counter syncFiles;
    private final Timer commitDuration;
    private final Timer dbPoolWait;
    private final DistributionSummary createStallMs;

    private WorldsMetrics(PrometheusMeterRegistry registry) {
        this.registry = registry;

        Gauge.builder(MetricNames.WORLDS_LOADED, worldsLoaded, AtomicInteger::get)
                .description("Worlds currently loaded on this node")
                .register(registry);
        Gauge.builder(MetricNames.QUARANTINE_BYTES, quarantineBytes, AtomicLong::get)
                .description("Bytes held under the quarantine path (MN-13)")
                .baseUnit("bytes")
                .register(registry);
        Gauge.builder(MetricNames.SCRATCH_FREE_BYTES, scratchFreeBytes, AtomicLong::get)
                .description("Usable free bytes on the scratch volume (NFR-3)")
                .baseUnit("bytes")
                .register(registry);

        this.fenceEvents = Counter.builder(MetricNames.FENCE_EVENTS)
                .description("Fencing aborts observed on this node")
                .register(registry);
        this.holdingTimeouts = Counter.builder(MetricNames.HOLDING_TIMEOUTS)
                .description("Join holding-area timeouts")
                .register(registry);
        this.syncBytes = Counter.builder(MetricNames.SYNC_BYTES)
                .description("Bytes uploaded during world sync")
                .baseUnit("bytes")
                .register(registry);
        this.syncFiles = Counter.builder(MetricNames.SYNC_FILES)
                .description("Files uploaded during world sync")
                .register(registry);
        this.commitDuration = Timer.builder(MetricNames.COMMIT_DURATION)
                .description("Wall time of a world commit")
                .register(registry);
        this.dbPoolWait = Timer.builder(MetricNames.DB_POOL_WAIT)
                .description("Time waiting for a Hikari connection")
                .register(registry);
        this.createStallMs = DistributionSummary.builder(MetricNames.CREATE_STALL)
                .description("Main-thread stall of createWorld in milliseconds (FR-4)")
                .baseUnit("milliseconds")
                .register(registry);

        // Pre-register tagged counters/timers so dashboards see zero series
        // before the first event, and so tag cardinality is fixed here.
        leaseAcquireCounter(MetricNames.RESULT_OK);
        leaseAcquireCounter(MetricNames.RESULT_DENIED);
        leaseLostCounter(MetricNames.REASON_FENCED);
        leaseLostCounter(MetricNames.REASON_DB);
        commitFailedCounter(MetricNames.REASON_FENCED);
        commitFailedCounter(MetricNames.REASON_DB);
        commitFailedCounter(MetricNames.REASON_STORAGE);
        worldLoadTimer(MetricNames.KIND_WARM);
        worldLoadTimer(MetricNames.KIND_COLD);
    }

    public static WorldsMetrics create() {
        return new WorldsMetrics(new PrometheusMeterRegistry(PrometheusConfig.DEFAULT));
    }

    /** The underlying registry, for binders (Hikari, JVM) and the scrape endpoint. */
    public PrometheusMeterRegistry registry() {
        return registry;
    }

    /** Prometheus text exposition of the current samples. */
    public String scrape() {
        return registry.scrape();
    }

    public MeterRegistry meterRegistry() {
        return registry;
    }

    public void setWorldsLoaded(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("worldsLoaded must not be negative, was: " + count);
        }
        worldsLoaded.set(count);
    }

    public int worldsLoaded() {
        return worldsLoaded.get();
    }

    public void setQuarantineBytes(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("quarantineBytes must not be negative, was: " + bytes);
        }
        quarantineBytes.set(bytes);
    }

    public void setScratchFreeBytes(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("scratchFreeBytes must not be negative, was: " + bytes);
        }
        scratchFreeBytes.set(bytes);
    }

    public void leaseAcquireOk() {
        leaseAcquireCounter(MetricNames.RESULT_OK).increment();
    }

    public void leaseAcquireDenied() {
        leaseAcquireCounter(MetricNames.RESULT_DENIED).increment();
    }

    public void leaseLost(String reason) {
        leaseLostCounter(reason).increment();
    }

    public void fenceEvent() {
        fenceEvents.increment();
    }

    public void commitSucceeded(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        commitDuration.record(duration);
    }

    public void commitFailed(String reason) {
        commitFailedCounter(reason).increment();
    }

    public void syncUploaded(long bytes, long files) {
        if (bytes < 0 || files < 0) {
            throw new IllegalArgumentException("bytes and files must not be negative");
        }
        syncBytes.increment((double) bytes);
        syncFiles.increment((double) files);
    }

    public void worldLoadWarm(Duration duration) {
        worldLoadTimer(MetricNames.KIND_WARM).record(duration);
    }

    public void worldLoadCold(Duration duration) {
        worldLoadTimer(MetricNames.KIND_COLD).record(duration);
    }

    public void createStall(Duration stall) {
        Objects.requireNonNull(stall, "stall");
        createStallMs.record((double) stall.toMillis());
    }

    public void holdingTimeout() {
        holdingTimeouts.increment();
    }

    public void dbPoolWait(Duration waited) {
        Objects.requireNonNull(waited, "waited");
        dbPoolWait.record(waited.toNanos(), TimeUnit.NANOSECONDS);
    }

    private Counter leaseAcquireCounter(String result) {
        return Counter.builder(MetricNames.LEASE_ACQUIRE)
                .description("Lease acquisition attempts")
                .tag(MetricNames.TAG_RESULT, result)
                .register(registry);
    }

    private Counter leaseLostCounter(String reason) {
        return Counter.builder(MetricNames.LEASE_LOST)
                .description("Leases lost after this node believed it held them")
                .tag(MetricNames.TAG_REASON, Objects.requireNonNull(reason, "reason"))
                .register(registry);
    }

    private Counter commitFailedCounter(String reason) {
        return Counter.builder(MetricNames.COMMIT_FAILED)
                .description("Failed world commits")
                .tag(MetricNames.TAG_REASON, Objects.requireNonNull(reason, "reason"))
                .register(registry);
    }

    private Timer worldLoadTimer(String kind) {
        return Timer.builder(MetricNames.WORLD_LOAD)
                .description("Time to load a world onto this node")
                .tag(MetricNames.TAG_KIND, kind)
                .register(registry);
    }

    @Override
    public void close() {
        registry.close();
    }
}
