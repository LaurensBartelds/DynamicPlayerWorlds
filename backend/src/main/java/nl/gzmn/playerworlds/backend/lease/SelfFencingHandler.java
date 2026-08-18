package nl.gzmn.playerworlds.backend.lease;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import nl.gzmn.playerworlds.backend.platform.DimensionKind;
import nl.gzmn.playerworlds.backend.platform.Platform;
import nl.gzmn.playerworlds.backend.profile.WorldCommitService;
import nl.gzmn.playerworlds.backend.world.LoadedWorld;
import nl.gzmn.playerworlds.backend.world.WorldFolders;
import nl.gzmn.playerworlds.backend.world.WorldRegistry;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.control.CommandKind;
import nl.gzmn.playerworlds.core.control.ControlChannels;
import nl.gzmn.playerworlds.core.control.EjectPayload;
import nl.gzmn.playerworlds.core.db.NodeCommandRepository;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.obs.LogEvent;
import nl.gzmn.playerworlds.core.obs.MetricNames;
import nl.gzmn.playerworlds.core.obs.WorldsMetrics;
import nl.gzmn.playerworlds.core.storage.QuarantineManager;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes self-fencing when a node loses its lease or cannot reach the database (MN-10, MN-10b).
 *
 * <p>Self-fencing immediately stops ticking the world, ejects players to the proxy lobby,
 * unloads all dimensions from memory without saving to object storage or the database,
 * and moves the local scratch copy into quarantine (MN-10, MN-13).
 */
public final class SelfFencingHandler {

    private static final Logger log = LoggerFactory.getLogger(SelfFencingHandler.class);

    private final WorldRegistry registry;
    private final WorldFolders folders;
    private final Platform platform;
    private final PluginExecutors executors;
    private final @Nullable WorldCommitService commitService;
    private final NodeCommandRepository nodeCommands;
    private final WorldsMetrics metrics;
    private final Path scratchPath;
    private final Path quarantinePath;
    private final Supplier<NetworkPolicy> policy;

    public SelfFencingHandler(
            WorldRegistry registry,
            WorldFolders folders,
            Platform platform,
            PluginExecutors executors,
            @Nullable WorldCommitService commitService,
            NodeCommandRepository nodeCommands,
            WorldsMetrics metrics,
            Path scratchPath,
            Path quarantinePath,
            Supplier<NetworkPolicy> policy) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.folders = Objects.requireNonNull(folders, "folders");
        this.platform = Objects.requireNonNull(platform, "platform");
        this.executors = Objects.requireNonNull(executors, "executors");
        this.commitService = commitService;
        this.nodeCommands = Objects.requireNonNull(nodeCommands, "nodeCommands");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.scratchPath = Objects.requireNonNull(scratchPath, "scratchPath");
        this.quarantinePath = Objects.requireNonNull(quarantinePath, "quarantinePath");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public enum FenceReason {
        /** Lease was observed lost (0 rows updated on heartbeat/commit renewal, MN-10b case 1). */
        LEASE_LOST,
        /** Database was unreachable and the safety margin deadline expired (MN-10b case 2). */
        DATABASE_UNREACHABLE_TIMEOUT,
        /** Snapshot commit was rejected due to lease/generation bump (MN-3a). */
        COMMIT_FENCED
    }

    /**
     * Triggers self-fencing for the specified world.
     */
    public void selfFence(WorldId worldId, FenceReason reason) {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(reason, "reason");

        LoadedWorld loaded = registry.find(worldId).orElse(null);
        if (loaded == null) {
            log.debug("Self-fence called for world {} which is no longer registered", worldId);
            return;
        }

        log.error("SELF-FENCING world {} due to {}", worldId, reason);

        // Record metrics
        metrics.fenceEvent();
        String metricReason =
                switch (reason) {
                    case LEASE_LOST -> MetricNames.REASON_FENCED;
                    case DATABASE_UNREACHABLE_TIMEOUT -> MetricNames.REASON_DB;
                    case COMMIT_FENCED -> MetricNames.REASON_FENCED;
                };
        metrics.leaseLost(metricReason);

        // Log structured event (NFR-6)
        LogEvent logEvent =
                switch (reason) {
                    case LEASE_LOST -> LogEvent.LEASE_LOST;
                    case DATABASE_UNREACHABLE_TIMEOUT -> LogEvent.LEASE_SELF_FENCE;
                    case COMMIT_FENCED -> LogEvent.COMMIT_FENCED;
                };
        log.warn("event={} world_id={} reason={}", logEvent.key(), worldId, reason);

        // Remove from registry and forget in commit service immediately so no more commits or sweeps touch it
        registry.unregister(worldId);
        if (commitService != null) {
            commitService.forget(worldId);
        }

        // Execute main-thread portion: eject players and force unload dimensions without saving
        executors.main().execute(() -> {
            List<Player> ejectedPlayers = new ArrayList<>();

            for (DimensionKind dim : DimensionKind.values()) {
                String bukkitName = folders.bukkitWorldName(worldId, dim);
                World bukkitWorld = Bukkit.getWorld(bukkitName);
                if (bukkitWorld != null) {
                    for (Player player : bukkitWorld.getPlayers()) {
                        ejectedPlayers.add(player);
                        player.sendMessage(Component.text(
                                "You have been moved to the lobby because the server lost its lease on this world.",
                                NamedTextColor.RED));
                    }
                    try {
                        platform.worldLifecycle().unload(bukkitName, false);
                    } catch (Exception e) {
                        log.error("Failed to unload Bukkit dimension {} during self-fencing", bukkitName, e);
                    }
                }
            }

            // Enqueue proxy ejects off-main
            if (!ejectedPlayers.isEmpty()) {
                executors.db().execute(() -> {
                    for (Player p : ejectedPlayers) {
                        try {
                            nodeCommands.enqueue(
                                    "proxy",
                                    worldId,
                                    null,
                                    CommandKind.EJECT_PLAYER.name(),
                                    EjectPayload.format(p.getUniqueId(), "Lease lost (" + reason + ")"),
                                    policy.get().holdingTimeout(),
                                    ControlChannels.PROXY);
                        } catch (SQLException e) {
                            log.warn(
                                    "Could not enqueue EJECT_PLAYER during self-fencing for player {}",
                                    p.getUniqueId(),
                                    e);
                        }
                    }
                });
            }

            // Move local scratch directory to quarantine (MN-10, MN-13)
            executors.io().execute(() -> {
                try {
                    QuarantineManager.quarantineWorld(scratchPath, quarantinePath, worldId);
                } catch (IOException e) {
                    log.error("Could not quarantine scratch folder for fenced world {}", worldId, e);
                }
            });
        });
    }
}
