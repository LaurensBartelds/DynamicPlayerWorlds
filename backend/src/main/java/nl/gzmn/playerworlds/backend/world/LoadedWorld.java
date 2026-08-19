package nl.gzmn.playerworlds.backend.world;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import nl.gzmn.playerworlds.backend.platform.DimensionKind;
import nl.gzmn.playerworlds.core.db.DbClock;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.jspecify.annotations.Nullable;

/**
 * A player world as this node currently holds it.
 *
 * <p>Not a {@link PlayerWorld}: that is a snapshot of a database row, and this is
 * node-local runtime state. What it carries from the row is exactly the values
 * the main thread needs and cannot go and fetch — the seed, because
 * {@code PlayerPortalEvent} materialises a dimension on the tick thread and must
 * not touch JDBC (NFR-2, FR-2), and the border radius, re-asserted on every load
 * (FR-3).
 *
 * <p>It holds no {@code World} reference (FR-25b). Dimensions are named, and
 * resolved through the server at the moment of use.
 *
 * <p>The idle bookkeeping is deliberately counted in sweeps rather than measured
 * against a clock. FR-25's grace period is node-local policy, not a lease
 * decision, and counting the sweeps that have already happened keeps the whole
 * state machine a pure function — testable without a server, and without an
 * {@code Instant.now()} anywhere near lifecycle code.
 */
public final class LoadedWorld {

    private final WorldId id;
    private final UUID ownerUuid;
    private final String name;
    private final long seed;
    private final int borderRadius;

    /**
     * The lease generation this world was loaded against (FR-11's fencing token).
     */
    private final long generation;

    /** Expiration timestamp of the lease issued by the database. */
    private volatile @Nullable Instant leaseExpires;

    /** Local monotonic nanoTime of the last successful lease acquisition or renewal. */
    private volatile long lastHeartbeatNanoTime;

    /** Set when a lease heartbeat fails, indicating joins must be refused (MN-10b). */
    private volatile boolean leaseDegraded;

    /**
     * Which of the three dimensions exist on disk and are loaded.
     *
     * <p>Copy-on-write behind {@code volatile} rather than a mutable set: the
     * portal handler reads it on the main thread while a load completing on the
     * database executor may be adding to it, and a three-element immutable set is
     * cheaper to replace than to lock around. Writes are serialised by
     * {@link #materialisedLock} so two dimensions arriving at once cannot lose
     * one another.
     */
    private volatile Set<DimensionKind> materialised = Set.of();

    private final Object materialisedLock = new Object();

    /** Consecutive idle sweeps observed. Main-thread only. */
    private int idleSweeps;

    /** Sweeps still to wait before retrying a failed unload (FR-25a). Main-thread only. */
    private int retryWaitSweeps;

    /**
     * FR-9e settings JSON snapshot for the tick thread.
     *
     * <p>Volatile and updatable: {@code APPLY_SETTINGS} (R9) rewrites it when the
     * owner changes settings on a loaded world, so a dimension materialised later
     * (portal) applies the same values rather than the load-time snapshot.
     */
    private volatile String settingsJson;

    public LoadedWorld(WorldId id, UUID ownerUuid, String name, long seed, int borderRadius) {
        this(id, ownerUuid, name, seed, borderRadius, 0L, PlayerWorld.EMPTY_SETTINGS);
    }

    public LoadedWorld(WorldId id, UUID ownerUuid, String name, long seed, int borderRadius, long generation) {
        this(id, ownerUuid, name, seed, borderRadius, generation, PlayerWorld.EMPTY_SETTINGS);
    }

    public LoadedWorld(
            WorldId id,
            UUID ownerUuid,
            String name,
            long seed,
            int borderRadius,
            long generation,
            String settingsJson) {
        this.id = Objects.requireNonNull(id, "id");
        this.ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        this.name = Objects.requireNonNull(name, "name");
        this.seed = seed;
        if (borderRadius < 1) {
            throw new IllegalArgumentException("borderRadius must be at least 1, was: " + borderRadius);
        }
        this.borderRadius = borderRadius;
        this.generation = generation;
        this.settingsJson = Objects.requireNonNull(settingsJson, "settingsJson");
        this.lastHeartbeatNanoTime = System.nanoTime();
    }

    /** From a database row, keeping only what the tick thread cannot re-read. */
    public static LoadedWorld of(PlayerWorld row) {
        Objects.requireNonNull(row, "row");
        LoadedWorld world = new LoadedWorld(
                row.id(),
                row.ownerUuid(),
                row.name(),
                row.seed(),
                row.borderRadius(),
                row.generation(),
                row.settingsJson());
        if (row.leaseExpires() != null) {
            world.recordLeaseGrant(row.leaseExpires());
        }
        return world;
    }

    public WorldId id() {
        return id;
    }

    public UUID ownerUuid() {
        return ownerUuid;
    }

    public String name() {
        return name;
    }

    public String settingsJson() {
        return settingsJson;
    }

    /**
     * Replaces the FR-9e settings snapshot (R9 / {@code APPLY_SETTINGS}).
     *
     * <p>Called after the database row and {@link WorldSettingsCache} have already
     * been updated, so a later dimension materialisation reads the same values.
     */
    public void updateSettingsJson(String settingsJson) {
        this.settingsJson = Objects.requireNonNull(settingsJson, "settingsJson");
    }

    /** Shared by all three dimensions, so one materialised later matches (FR-2). */
    public long seed() {
        return seed;
    }

    /** The lease generation this world was loaded against (FR-11). */
    public long generation() {
        return generation;
    }

    public @Nullable Instant leaseExpires() {
        return leaseExpires;
    }

    public boolean isLeaseDegraded() {
        return leaseDegraded;
    }

    /** Records initial lease acquisition grant or renewal. */
    public void recordLeaseGrant(Instant expiresAt) {
        this.leaseExpires = Objects.requireNonNull(expiresAt, "expiresAt");
        this.lastHeartbeatNanoTime = System.nanoTime();
        this.leaseDegraded = false;
    }

    /** Records a successful lease renewal heartbeat (MN-9). */
    public void recordHeartbeatSuccess(Instant newExpiresAt) {
        recordLeaseGrant(newExpiresAt);
    }

    /** Records that a lease heartbeat failed due to database unreachability (MN-10b). */
    public void recordHeartbeatFailure() {
        this.leaseDegraded = true;
    }

    /**
     * Checks if this world has crossed its self-fencing deadline under an unreachable database (MN-10b).
     *
     * <p>Uses the local monotonic clock via {@code nanoTime} against the last successful
     * database grant, avoiding wall-clock drift (CONTRIBUTING.md rule 5).
     */
    public boolean isFencedByDeadlineToDb(Duration leaseDuration, Duration safetyMargin) {
        Duration timeout = leaseDuration.minus(safetyMargin);
        if (timeout.isNegative() || timeout.isZero()) {
            return true;
        }
        return DbClock.elapsedSince(lastHeartbeatNanoTime).compareTo(timeout) >= 0;
    }

    /** Overworld and end radius; the nether divides it (FR-3). */
    public int borderRadius() {
        return borderRadius;
    }

    /** Dimensions currently on disk and loaded. */
    public Set<DimensionKind> materialised() {
        return materialised;
    }

    public boolean isMaterialised(DimensionKind dimension) {
        Objects.requireNonNull(dimension, "dimension");
        return materialised.contains(dimension);
    }

    /** Records that a dimension is now loaded. Idempotent. */
    public void markMaterialised(DimensionKind dimension) {
        Objects.requireNonNull(dimension, "dimension");
        synchronized (materialisedLock) {
            if (materialised.contains(dimension)) {
                return;
            }
            EnumSet<DimensionKind> next = EnumSet.noneOf(DimensionKind.class);
            next.addAll(materialised);
            next.add(dimension);
            materialised = Set.copyOf(next);
        }
    }

    /** Records that a dimension is no longer loaded. Idempotent. */
    public void markUnloaded(DimensionKind dimension) {
        Objects.requireNonNull(dimension, "dimension");
        synchronized (materialisedLock) {
            if (!materialised.contains(dimension)) {
                return;
            }
            EnumSet<DimensionKind> next = EnumSet.noneOf(DimensionKind.class);
            next.addAll(materialised);
            next.remove(dimension);
            materialised = Set.copyOf(next);
        }
    }

    /** What the idle sweep should do with this world on this pass. */
    public enum IdleDecision {
        /** Nothing to do: players are present, the grace period has not elapsed, or a retry is pending. */
        WAIT,
        /** The grace period has elapsed with no players anywhere in the world (FR-25). */
        UNLOAD
    }

    /**
     * Advances the idle state machine by one sweep.
     *
     * <p>Any player in any of the three dimensions resets the grace period and
     * cancels a pending unload, exactly as FR-25 requires — including a retry that
     * was waiting, because a world somebody has rejoined must not be taken from
     * under them two minutes later.
     *
     * @param playersPresent whether any dimension of this world holds a player
     * @param idleThresholdSweeps sweeps that make up {@code worlds.idle-unload-minutes}
     */
    public IdleDecision onSweep(boolean playersPresent, int idleThresholdSweeps) {
        if (idleThresholdSweeps < 1) {
            throw new IllegalArgumentException("idleThresholdSweeps must be at least 1, was: " + idleThresholdSweeps);
        }
        if (playersPresent) {
            idleSweeps = 0;
            retryWaitSweeps = 0;
            return IdleDecision.WAIT;
        }
        if (retryWaitSweeps > 0) {
            retryWaitSweeps--;
            return IdleDecision.WAIT;
        }
        if (idleSweeps < idleThresholdSweeps) {
            idleSweeps++;
        }
        return idleSweeps >= idleThresholdSweeps ? IdleDecision.UNLOAD : IdleDecision.WAIT;
    }

    /**
     * Records that an unload attempt did not complete, so the next attempt waits
     * {@code worlds.unload-retry-minutes} (FR-25a).
     *
     * <p>The idle counter is left at its threshold rather than reset: the world is
     * still idle, and the retry should fire the moment the wait elapses rather
     * than serving a second full grace period.
     */
    public void unloadDeferred(int retryWaitSweeps) {
        if (retryWaitSweeps < 0) {
            throw new IllegalArgumentException("retryWaitSweeps must not be negative, was: " + retryWaitSweeps);
        }
        this.retryWaitSweeps = retryWaitSweeps;
    }

    /** Consecutive idle sweeps, for logs and tests. */
    public int idleSweeps() {
        return idleSweeps;
    }

    /** Sweeps still to wait before the FR-25a retry, for logs and tests. */
    public int retryWaitSweeps() {
        return retryWaitSweeps;
    }

    @Override
    public String toString() {
        return "LoadedWorld[" + id + " '" + name + "' " + materialised + "]";
    }
}
