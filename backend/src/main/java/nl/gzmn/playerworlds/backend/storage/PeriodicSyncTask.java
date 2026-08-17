package nl.gzmn.playerworlds.backend.storage;

import java.util.Objects;
import java.util.function.Supplier;
import nl.gzmn.playerworlds.backend.profile.WorldCommitService;
import nl.gzmn.playerworlds.backend.world.LoadedWorld;
import nl.gzmn.playerworlds.backend.world.WorldRegistry;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Periodically triggers incremental snapshot commits for all loaded worlds (MN-6).
 *
 * <p>Single-flight commit throttling is enforced by {@link WorldCommitService}'s
 * commit queue, so an active commit safely absorbs redundant triggers.
 */
public final class PeriodicSyncTask implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(PeriodicSyncTask.class);

    private final WorldRegistry registry;
    private final WorldCommitService commits;
    private final Supplier<NetworkPolicy> policySupplier;

    public PeriodicSyncTask(
            WorldRegistry registry, WorldCommitService commits, Supplier<NetworkPolicy> policySupplier) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.commits = Objects.requireNonNull(commits, "commits");
        this.policySupplier = Objects.requireNonNull(policySupplier, "policySupplier");
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
        for (LoadedWorld world : registry.loadedWorlds()) {
            try {
                commits.requestCommit(world.id()).exceptionally(t -> {
                    log.warn("Periodic incremental sync failed asynchronously for world {}", world.id(), t);
                    return null;
                });
            } catch (Exception e) {
                log.warn("Periodic incremental sync failed to request commit for world {}", world.id(), e);
            }
        }
    }
}
