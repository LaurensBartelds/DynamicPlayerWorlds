package nl.gzmn.playerworlds.backend.world;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;
import nl.gzmn.playerworlds.backend.platform.DimensionKind;
import nl.gzmn.playerworlds.backend.platform.WorldLifecycle;
import nl.gzmn.playerworlds.core.concurrent.MainThread;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.bukkit.World;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The FR-25 idle sweep: unloads a world once nobody has been in any of its three
 * dimensions for {@code worlds.idle-unload-minutes}.
 *
 * <p>Runs on the main thread, because counting players and calling
 * {@code unloadWorld} both require it. Everything it does is O(loaded worlds),
 * which is at most {@code nodes.max-worlds} — five by default.
 *
 * <p>Time is counted in sweeps rather than read from a clock. The grace period
 * is node-local policy rather than a lease decision, so a clock would be
 * permitted here, but counting sweeps makes the whole state machine a pure
 * function of {@link LoadedWorld} and therefore testable without a server.
 *
 * <p>FR-25 orders the three steps: <em>commit, unload, release</em>. The commit
 * is not optional and not best-effort — between the last sync and the unload
 * there is up to {@code storage.sync-minutes} of play that exists only in the
 * scratch directory, and unloading without committing it discards exactly that
 * much of the session for the world and every profile in it together (FR-15).
 * So an unload that cannot commit does not happen: it defers on the FR-25a retry
 * and the world stays loaded, holding a node slot, until the commit succeeds or
 * the node is fenced.
 */
public final class IdleUnloadTask implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(IdleUnloadTask.class);

    /**
     * How often the sweep runs. Frequent enough that the grace period is accurate
     * to within a sweep, rare enough to be invisible: twenty seconds against a
     * ten-minute default is thirty sweeps of counting five integers.
     */
    public static final Duration SWEEP_INTERVAL = Duration.ofSeconds(20);

    private final WorldRegistry registry;
    private final WorldLifecycleService lifecycle;
    private final WorldLifecycle worldLifecycle;
    private final WorldFolders folders;
    private final Supplier<NetworkPolicy> policy;

    /** FR-25's pre-unload snapshot commit, or {@code null} when object storage is not configured. */
    private final @Nullable Function<WorldId, CompletableFuture<Void>> preUnloadCommit;

    /** Hops back to the tick thread once a commit completes; unloading needs main. */
    private final @Nullable Executor main;

    /** Worlds whose pre-unload commit is in flight. Main-thread only. */
    private final Set<WorldId> committing = new HashSet<>();

    /**
     * Without a commit hook, for the single-node tests written before object
     * storage existed. A node wired this way loses the last sync interval on
     * unload, which is why the production wiring passes one.
     */
    public IdleUnloadTask(
            WorldRegistry registry,
            WorldLifecycleService lifecycle,
            WorldLifecycle worldLifecycle,
            WorldFolders folders,
            Supplier<NetworkPolicy> policy) {
        this(registry, lifecycle, worldLifecycle, folders, policy, null, null);
    }

    /**
     * @param preUnloadCommit MN-6a's snapshot commit for one world, which FR-25
     *     requires to complete before the dimensions come down
     * @param main the tick thread, which the unload has to run back on
     */
    public IdleUnloadTask(
            WorldRegistry registry,
            WorldLifecycleService lifecycle,
            WorldLifecycle worldLifecycle,
            WorldFolders folders,
            Supplier<NetworkPolicy> policy,
            @Nullable Function<WorldId, CompletableFuture<Void>> preUnloadCommit,
            @Nullable Executor main) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.worldLifecycle = Objects.requireNonNull(worldLifecycle, "worldLifecycle");
        this.folders = Objects.requireNonNull(folders, "folders");
        this.policy = Objects.requireNonNull(policy, "policy");
        if ((preUnloadCommit == null) != (main == null)) {
            throw new IllegalArgumentException("preUnloadCommit and main are wired together or not at all");
        }
        this.preUnloadCommit = preUnloadCommit;
        this.main = main;
    }

    @Override
    public void run() {
        MainThread.assertOn();
        NetworkPolicy current = policy.get();
        int idleThreshold = sweepsIn(current.idleUnload());
        int retryWait = sweepsIn(current.unloadRetry());

        for (LoadedWorld world : registry.all()) {
            try {
                sweepOne(world, idleThreshold, retryWait);
            } catch (RuntimeException e) {
                // One misbehaving world must not stop the sweep for the others; a
                // node that stops unloading anything leaks worlds until it hits
                // nodes.max-worlds and refuses every join.
                log.error("idle sweep failed for world {}", world.id(), e);
            }
        }
    }

    private void sweepOne(LoadedWorld world, int idleThreshold, int retryWait) {
        boolean playersPresent = hasPlayers(world);
        if (world.onSweep(playersPresent, idleThreshold) == LoadedWorld.IdleDecision.WAIT) {
            return;
        }

        Function<WorldId, CompletableFuture<Void>> commit = preUnloadCommit;
        Executor tick = main;
        if (commit == null || tick == null) {
            unloadNow(world, retryWait);
            return;
        }

        // A commit already running for this world. The idle counter stays at its
        // threshold, so the next sweep asks again rather than restarting the
        // grace period.
        if (!committing.add(world.id())) {
            return;
        }
        var _ = commit.apply(world.id()).whenComplete((ignored, failure) -> tick.execute(() -> {
            committing.remove(world.id());
            if (failure != null) {
                // Not unloading is the safe direction: the world keeps ticking and
                // the next sync or the next sweep tries again. Losing it here would
                // discard the very state the commit failed to save.
                log.error(
                        "pre-unload snapshot commit failed for world {}; leaving it loaded and retrying "
                                + "in {} sweeps (FR-25)",
                        world.id(),
                        retryWait,
                        failure);
                world.unloadDeferred(retryWait);
                return;
            }
            if (registry.find(world.id()).isEmpty()) {
                // Fenced, migrated or shut down while the commit was in flight.
                return;
            }
            if (hasPlayers(world)) {
                // FR-25: "any join into any dimension of that world resets the
                // timer and cancels the pending unload" — including one that
                // arrived during the commit.
                log.info("world {} was rejoined during its pre-unload commit; staying loaded (FR-25)", world.id());
                return;
            }
            unloadNow(world, retryWait);
        }));
    }

    /** The unload half of FR-25, after the commit has landed. Main thread. */
    private void unloadNow(LoadedWorld world, int retryWait) {
        UnloadOutcome outcome = lifecycle.unloadOnMain(world);
        if (outcome instanceof UnloadOutcome.Complete complete) {
            log.info(
                    "unloaded world {} after {} idle sweeps; dimensions {} (FR-25)",
                    world.id(),
                    world.idleSweeps(),
                    complete.unloaded());
            lifecycle.afterUnload(world);
            return;
        }

        UnloadOutcome.Blocked blocked = (UnloadOutcome.Blocked) outcome;
        // FR-25a: log the holding cause, abandon the rest of this world's
        // unloads, retry the whole world later. The world stays registered, so
        // the next sweep still sees it and a rejoin still resets the timer.
        log.warn(
                "world {} would not unload at dimension {}: {}. Remaining dimensions left loaded; "
                        + "retrying the whole world in {} sweeps (FR-25a)",
                world.id(),
                blocked.dimension(),
                blocked.blockers().isEmpty() ? "no determinable cause" : String.join("; ", blocked.blockers()),
                retryWait);
        world.unloadDeferred(retryWait);
    }

    /** Whether any of the three dimensions holds a player (FR-25). */
    private boolean hasPlayers(LoadedWorld world) {
        for (DimensionKind dimension : DimensionKind.values()) {
            World bukkit = worldLifecycle.loaded(folders.bukkitWorldName(world.id(), dimension));
            if (bukkit != null && !bukkit.getPlayers().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sweeps that make up {@code duration}, rounded up and never below one.
     *
     * <p>Rounding up rather than down: a grace period configured shorter than one
     * sweep should unload on the next sweep, not on the same one that observed
     * the world go quiet.
     */
    static int sweepsIn(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        long interval = SWEEP_INTERVAL.toMillis();
        long sweeps = (duration.toMillis() + interval - 1) / interval;
        return (int) Math.max(1L, Math.min(sweeps, Integer.MAX_VALUE));
    }

    /**
     * Unloads every registered world now, for the FR-28 shutdown path.
     *
     * <p>Ignores the grace period and the retry wait: the server is going down
     * and the alternative to unloading is leaving the folders as the crash path
     * would. Reports what would not come down so an operator has it in the log
     * before the process exits.
     */
    public void unloadAllForShutdown() {
        MainThread.assertOn();
        for (LoadedWorld world : registry.all()) {
            try {
                UnloadOutcome outcome = lifecycle.unloadOnMain(world);
                if (outcome instanceof UnloadOutcome.Blocked blocked) {
                    List<String> blockers = blocked.blockers();
                    log.warn(
                            "world {} would not unload at dimension {} during shutdown: {}",
                            world.id(),
                            blocked.dimension(),
                            blockers.isEmpty() ? "no determinable cause" : String.join("; ", blockers));
                } else {
                    log.info("unloaded world {} for shutdown (FR-28)", world.id());
                }
            } catch (RuntimeException e) {
                log.error("failed to unload world {} during shutdown", world.id(), e);
            }
        }
        registry.clear();
    }
}
