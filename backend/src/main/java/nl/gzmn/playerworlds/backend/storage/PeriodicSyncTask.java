package nl.gzmn.playerworlds.backend.storage;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import nl.gzmn.playerworlds.backend.control.WorldHandoff;
import nl.gzmn.playerworlds.backend.profile.WorldCommitService;
import nl.gzmn.playerworlds.backend.world.LoadedWorld;
import nl.gzmn.playerworlds.backend.world.WorldRegistry;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.obs.EventLogger;
import nl.gzmn.playerworlds.core.obs.LogEvent;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Periodically triggers incremental snapshot commits for all loaded worlds (MN-6), and enforces
 * MN-11a's bound on how long a world may go on being played while those commits fail.
 *
 * <p>Single-flight commit throttling is enforced by {@link WorldCommitService}'s
 * commit queue, so an active commit safely absorbs redundant triggers.
 */
public final class PeriodicSyncTask implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(PeriodicSyncTask.class);
    private static final EventLogger events = EventLogger.create(PeriodicSyncTask.class);

    /** MN-21's warning before the forced unload, so nobody is teleported mid-swing. */
    static final int DEFAULT_UNLOAD_COUNTDOWN_SECONDS = 10;

    private final WorldRegistry registry;
    private final WorldCommitService commits;
    private final Supplier<NetworkPolicy> policySupplier;
    private final Supplier<@Nullable WorldHandoff> handoffSupplier;
    private final int unloadCountdownSeconds;

    /**
     * Worlds whose MN-11a unload is in flight.
     *
     * <p>{@link WorldHandoff#discard} runs asynchronously and only leaves the registry once it
     * reaches {@code afterUnload}, so without this a sweep landing during the countdown would
     * start a second one and eject the same players twice.
     */
    private final Set<WorldId> unloading = ConcurrentHashMap.newKeySet();

    public PeriodicSyncTask(
            WorldRegistry registry, WorldCommitService commits, Supplier<NetworkPolicy> policySupplier) {
        this(registry, commits, policySupplier, () -> null);
    }

    public PeriodicSyncTask(
            WorldRegistry registry,
            WorldCommitService commits,
            Supplier<NetworkPolicy> policySupplier,
            Supplier<@Nullable WorldHandoff> handoffSupplier) {
        this(registry, commits, policySupplier, handoffSupplier, DEFAULT_UNLOAD_COUNTDOWN_SECONDS);
    }

    /** Visible for tests, which cannot afford to wait out MN-21's countdown. */
    PeriodicSyncTask(
            WorldRegistry registry,
            WorldCommitService commits,
            Supplier<NetworkPolicy> policySupplier,
            Supplier<@Nullable WorldHandoff> handoffSupplier,
            int unloadCountdownSeconds) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.commits = Objects.requireNonNull(commits, "commits");
        this.policySupplier = Objects.requireNonNull(policySupplier, "policySupplier");
        this.handoffSupplier = Objects.requireNonNull(handoffSupplier, "handoffSupplier");
        this.unloadCountdownSeconds = unloadCountdownSeconds;
    }

    public WorldRegistry registry() {
        return registry;
    }

    public WorldCommitService commits() {
        return commits;
    }

    public Supplier<NetworkPolicy> policySupplier() {
        return policySupplier;
    }

    @Override
    public void run() {
        Duration maxSyncFailure = policySupplier.get().maxSyncFailure();
        for (LoadedWorld world : registry.loadedWorlds()) {
            // Checked before the request, not after: the outcome of the commit started here
            // arrives asynchronously, and a world already over the bound has nothing to gain
            // from one more attempt against storage that has been refusing for half an hour.
            if (world.isSyncFailingFor(maxSyncFailure)) {
                forceUnload(world, maxSyncFailure);
                continue;
            }
            try {
                var _ = commits.requestCommit(world.id()).exceptionally(t -> {
                    // WorldCommitService counts the failure and logs the detail (12.7's alert);
                    // this is only the trigger's own record that the attempt was made.
                    log.warn("Periodic incremental sync failed asynchronously for world {}", world.id(), t);
                    return null;
                });
            } catch (Exception e) {
                log.warn("Periodic incremental sync failed to request commit for world {}", world.id(), e);
            }
        }
    }

    /**
     * MN-11a: stops a world that object storage has not accepted for {@code maxSyncFailure}.
     *
     * <p>Discarded rather than released: {@link WorldHandoff#release} commits first and abandons
     * the unload when that fails, which here is every time — the failing commit is the whole
     * reason this is happening. The folders stay on disk and are the newest copy of the world
     * that exists, so nothing is quarantined and nothing is deleted; what ends is play on top of
     * data object storage has never seen.
     */
    private void forceUnload(LoadedWorld world, Duration maxSyncFailure) {
        WorldHandoff handoff = handoffSupplier.get();
        if (handoff == null) {
            // No control plane on this node, so there is no way to eject anyone. Say so on every
            // sweep rather than quietly going on playing a world that is not being saved.
            log.error(
                    "world {} has not committed for {} minutes and cannot be unloaded here:"
                            + " no handoff is configured (MN-11a)",
                    world.id(),
                    maxSyncFailure.toMinutes());
            return;
        }
        if (!unloading.add(world.id())) {
            return;
        }

        int failures = world.consecutiveCommitFailures();
        events.error(
                LogEvent.SYNC_ABANDONED,
                "no snapshot commit has succeeded in " + maxSyncFailure.toMinutes() + " minutes after " + failures
                        + " consecutive failures; unloading the world (MN-11a)",
                world.id());
        log.error(
                "world {} ('{}') has not reached object storage for {} minutes ({} consecutive failed commits);"
                        + " ejecting players and unloading it (MN-11a). The world folders are kept and are the"
                        + " newest copy that exists.",
                world.id(),
                world.name(),
                maxSyncFailure.toMinutes(),
                failures);

        String reason = "This world cannot be saved right now and is being closed";
        var _ = handoff.discard(world.id(), unloadCountdownSeconds, reason).whenComplete((outcome, failure) -> {
            var _ = unloading.remove(world.id());
            if (failure != null) {
                log.error("MN-11a forced unload of world {} failed", world.id(), failure);
                return;
            }
            switch (outcome) {
                case WorldHandoff.Outcome.Released released ->
                    log.warn(
                            "world {} unloaded by MN-11a; {} player(s) moved out", world.id(), released.playersMoved());
                case WorldHandoff.Outcome.Blocked blocked ->
                    log.error(
                            "MN-11a could not unload dimension {} of world {}: {}. It is still loaded and"
                                    + " still not being saved; the next sweep retries.",
                            blocked.dimension(),
                            world.id(),
                            String.join(", ", blocked.blockers()));
                // discard() never commits, so CommitFailed cannot arise; NotHeld means
                // something else unloaded the world first, which is the desired end state.
                case WorldHandoff.Outcome.NotHeld ignored -> {}
                case WorldHandoff.Outcome.CommitFailed ignored -> {}
            }
        });
    }
}
