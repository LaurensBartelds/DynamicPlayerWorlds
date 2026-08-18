package nl.gzmn.playerworlds.backend.lease;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import nl.gzmn.playerworlds.backend.world.LoadedWorld;
import nl.gzmn.playerworlds.backend.world.WorldRegistry;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates periodic lease heartbeats, renewal, and watchdog fencing checks (MN-9, MN-10a, MN-10b).
 */
public final class LeaseCoordinator {

    private static final Logger log = LoggerFactory.getLogger(LeaseCoordinator.class);

    private static final Duration WATCHDOG_INTERVAL = Duration.ofSeconds(2);

    private final String nodeId;
    private final WorldRegistry registry;
    private final PlayerWorldRepository repository;
    private final SelfFencingHandler fencingHandler;
    private final PluginExecutors executors;
    private final Supplier<NetworkPolicy> policy;
    private final Duration heartbeatInterval;

    public LeaseCoordinator(
            String nodeId,
            WorldRegistry registry,
            PlayerWorldRepository repository,
            SelfFencingHandler fencingHandler,
            PluginExecutors executors,
            Supplier<NetworkPolicy> policy,
            Duration heartbeatInterval) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.fencingHandler = Objects.requireNonNull(fencingHandler, "fencingHandler");
        this.executors = Objects.requireNonNull(executors, "executors");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.heartbeatInterval = Objects.requireNonNull(heartbeatInterval, "heartbeatInterval");
    }

    /** Starts periodic heartbeat renewals and the safety margin watchdog. */
    public void start(ScheduledExecutorService sched) {
        Objects.requireNonNull(sched, "sched");
        long heartbeatSecs = Math.max(1, heartbeatInterval.toSeconds());
        var _ = sched.scheduleWithFixedDelay(this::heartbeatAll, heartbeatSecs, heartbeatSecs, TimeUnit.SECONDS);

        long watchdogSecs = Math.max(1, WATCHDOG_INTERVAL.toSeconds());
        var _ = sched.scheduleWithFixedDelay(this::checkWatchdog, watchdogSecs, watchdogSecs, TimeUnit.SECONDS);
    }

    /**
     * Heartbeats to renew all active world leases held by this node (MN-9, MN-10b).
     */
    public void heartbeatAll() {
        for (LoadedWorld world : registry.loadedWorlds()) {
            executors.db().execute(() -> heartbeatOne(world));
        }
    }

    /** Heartbeats one world on the calling thread (used by periodic task and direct tests). */
    public void heartbeatOne(LoadedWorld world) {
        NetworkPolicy pol = policy.get();
        try {
            Optional<Instant> renewed =
                    repository.renewLease(world.id(), nodeId, world.generation(), pol.leaseDuration());

            if (renewed.isPresent()) {
                world.recordHeartbeatSuccess(renewed.get());
                log.debug(
                        "Renewed lease for world {} generation {} expires {}",
                        world.id(),
                        world.generation(),
                        renewed.get());
            } else {
                // MN-10b Case 1: Lease observed lost with database UP -> immediate self-fence!
                log.warn(
                        "Lease renewal for world {} generation {} returned 0 rows; lease was taken away!",
                        world.id(),
                        world.generation());
                fencingHandler.selfFence(world.id(), SelfFencingHandler.FenceReason.LEASE_LOST);
            }
        } catch (Exception e) {
            // MN-10b Case 2: Database unreachable / error -> mark failure & evaluate deadline
            log.warn("Could not reach database to renew lease for world {}: {}", world.id(), e.getMessage());
            world.recordHeartbeatFailure();
            if (world.isFencedByDeadlineToDb(pol.leaseDuration(), pol.fenceSafetyMargin())) {
                log.error(
                        "World {} crossed self-fencing safety margin under unreachable database; fencing!", world.id());
                fencingHandler.selfFence(world.id(), SelfFencingHandler.FenceReason.DATABASE_UNREACHABLE_TIMEOUT);
            }
        }
    }

    /**
     * Watchdog check: evaluates whether any loaded world has crossed its self-fencing deadline
     * even if DB queries are hanging (MN-10b).
     */
    public void checkWatchdog() {
        NetworkPolicy pol = policy.get();
        for (LoadedWorld world : registry.loadedWorlds()) {
            if (world.isFencedByDeadlineToDb(pol.leaseDuration(), pol.fenceSafetyMargin())) {
                log.error("Watchdog detected world {} crossed fence deadline; initiating self-fence!", world.id());
                fencingHandler.selfFence(world.id(), SelfFencingHandler.FenceReason.DATABASE_UNREACHABLE_TIMEOUT);
            }
        }
    }
}
