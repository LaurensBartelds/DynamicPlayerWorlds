package nl.gzmn.playerworlds.backend.world;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import nl.gzmn.playerworlds.backend.platform.DimensionKind;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.WorldId;

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
     *
     * <p>Zero until milestone 7 makes leases real. Carried now because FR-11's
     * transfer check compares against it, and a comparison that silently had
     * nothing to compare would pass for the wrong reason.
     */
    private final long generation;

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

    public LoadedWorld(WorldId id, UUID ownerUuid, String name, long seed, int borderRadius) {
        this(id, ownerUuid, name, seed, borderRadius, 0L);
    }

    public LoadedWorld(WorldId id, UUID ownerUuid, String name, long seed, int borderRadius, long generation) {
        this.id = Objects.requireNonNull(id, "id");
        this.ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        this.name = Objects.requireNonNull(name, "name");
        this.seed = seed;
        if (borderRadius < 1) {
            throw new IllegalArgumentException("borderRadius must be at least 1, was: " + borderRadius);
        }
        this.borderRadius = borderRadius;
        this.generation = generation;
    }

    /** From a database row, keeping only what the tick thread cannot re-read. */
    public static LoadedWorld of(PlayerWorld row) {
        Objects.requireNonNull(row, "row");
        return new LoadedWorld(row.id(), row.ownerUuid(), row.name(), row.seed(), row.borderRadius(), row.generation());
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

    /** Shared by all three dimensions, so one materialised later matches (FR-2). */
    public long seed() {
        return seed;
    }

    /** The lease generation this world was loaded against (FR-11). */
    public long generation() {
        return generation;
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
