package nl.gzmn.playerworlds.backend.control;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import nl.gzmn.playerworlds.backend.platform.DimensionKind;
import nl.gzmn.playerworlds.backend.world.LoadedWorld;
import nl.gzmn.playerworlds.backend.world.UnloadOutcome;
import nl.gzmn.playerworlds.backend.world.WorldFolders;
import nl.gzmn.playerworlds.backend.world.WorldLifecycleService;
import nl.gzmn.playerworlds.backend.world.WorldRegistry;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.control.CommandHandler;
import nl.gzmn.playerworlds.core.control.CommandResult;
import nl.gzmn.playerworlds.core.control.ControlChannels;
import nl.gzmn.playerworlds.core.control.EjectPayload;
import nl.gzmn.playerworlds.core.control.NodeCommand;
import nl.gzmn.playerworlds.core.db.NodeCommandRepository;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles {@link nl.gzmn.playerworlds.core.control.CommandKind#UNLOAD_WORLD} on a node.
 *
 * <p>Idempotent (CP-5): if the world is not loaded here, completes with {@code OK}.
 */
public final class UnloadWorldHandler implements CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(UnloadWorldHandler.class);

    private final WorldRegistry registry;
    private final @Nullable WorldLifecycleService lifecycle;
    private final @Nullable WorldFolders folders;
    private final @Nullable PluginExecutors executors;
    private final @Nullable NodeCommandRepository nodeCommands;
    private final @Nullable Supplier<NetworkPolicy> policy;

    public UnloadWorldHandler(
            WorldRegistry registry,
            @Nullable WorldLifecycleService lifecycle,
            @Nullable WorldFolders folders,
            @Nullable PluginExecutors executors,
            @Nullable NodeCommandRepository nodeCommands,
            @Nullable Supplier<NetworkPolicy> policy) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.lifecycle = lifecycle;
        this.folders = folders;
        this.executors = executors;
        this.nodeCommands = nodeCommands;
        this.policy = policy;
    }

    @Override
    public CommandResult handle(NodeCommand command) throws Exception {
        WorldId worldId = command.worldId();
        if (worldId == null) {
            return CommandResult.error("missing world_id");
        }
        Optional<LoadedWorld> found = registry.find(worldId);
        if (found.isEmpty()) {
            return CommandResult.ok();
        }
        if (lifecycle == null || folders == null || executors == null) {
            return CommandResult.ok();
        }

        LoadedWorld loaded = found.get();
        CompletableFuture<CommandResult> future = new CompletableFuture<>();

        executors.main().execute(() -> {
            try {
                // Find a holding / fallback world not belonging to this world
                World fallbackWorld = null;
                for (World w : Bukkit.getWorlds()) {
                    if (!folders.isPlayerWorld(w.getName())) {
                        fallbackWorld = w;
                        break;
                    }
                }
                if (fallbackWorld == null) {
                    for (World w : Bukkit.getWorlds()) {
                        if (!w.getName().startsWith(worldId.folder())) {
                            fallbackWorld = w;
                            break;
                        }
                    }
                }

                // Eject online players in any dimension of this world
                for (DimensionKind dimension : DimensionKind.values()) {
                    String bukkitName = folders.bukkitWorldName(worldId, dimension);
                    World bukkitWorld = Bukkit.getWorld(bukkitName);
                    if (bukkitWorld != null) {
                        for (Player player : bukkitWorld.getPlayers()) {
                            player.sendMessage(Component.text("World is unloading...", NamedTextColor.RED));
                            if (fallbackWorld != null) {
                                player.teleport(fallbackWorld.getSpawnLocation());
                            }
                            if (nodeCommands != null && policy != null) {
                                executors.db().execute(() -> {
                                    try {
                                        nodeCommands.enqueue(
                                                "proxy",
                                                worldId,
                                                null,
                                                "EJECT_PLAYER",
                                                EjectPayload.format(player.getUniqueId(), "World unloaded"),
                                                policy.get().holdingTimeout(),
                                                ControlChannels.PROXY);
                                    } catch (Exception e) {
                                        log.warn("could not enqueue EJECT_PLAYER for {}", player.getUniqueId(), e);
                                    }
                                });
                            }
                        }
                    }
                }

                UnloadOutcome outcome = lifecycle.unloadOnMain(loaded);
                switch (outcome) {
                    case UnloadOutcome.Complete complete -> {
                        lifecycle.afterUnload(loaded);
                        future.complete(CommandResult.ok());
                    }
                    case UnloadOutcome.Blocked blocked -> {
                        future.complete(CommandResult.error("unload blocked on " + blocked.dimension() + ": "
                                + String.join(", ", blocked.blockers())));
                    }
                }
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        return future.get();
    }
}
