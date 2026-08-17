package nl.gzmn.playerworlds.backend.world;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import nl.gzmn.playerworlds.backend.platform.DimensionKind;
import nl.gzmn.playerworlds.backend.platform.WorldLifecycle;
import nl.gzmn.playerworlds.core.concurrent.MainThread;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import org.bukkit.World;
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

    public IdleUnloadTask(
            WorldRegistry registry,
            WorldLifecycleService lifecycle,
            WorldLifecycle worldLifecycle,
            WorldFolders folders,
            Supplier<NetworkPolicy> policy) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.worldLifecycle = Objects.requireNonNull(worldLifecycle, "worldLifecycle");
        this.folders = Objects.requireNonNull(folders, "folders");
        this.policy = Objects.requireNonNull(policy, "policy");
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
