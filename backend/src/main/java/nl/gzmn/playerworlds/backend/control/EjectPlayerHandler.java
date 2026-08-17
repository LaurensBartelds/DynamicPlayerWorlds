package nl.gzmn.playerworlds.backend.control;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import nl.gzmn.playerworlds.backend.platform.DimensionKind;
import nl.gzmn.playerworlds.backend.world.MembershipCache;
import nl.gzmn.playerworlds.backend.world.WorldFolders;
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
 * Handles {@link nl.gzmn.playerworlds.core.control.CommandKind#KICK_MEMBER} and
 * {@link nl.gzmn.playerworlds.core.control.CommandKind#EJECT_PLAYER} on a backend node.
 */
public final class EjectPlayerHandler implements CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(EjectPlayerHandler.class);

    private final MembershipCache membershipCache;
    private final @Nullable WorldFolders folders;
    private final @Nullable PluginExecutors executors;
    private final @Nullable NodeCommandRepository nodeCommands;
    private final @Nullable Supplier<NetworkPolicy> policy;

    public EjectPlayerHandler(
            MembershipCache membershipCache,
            @Nullable WorldFolders folders,
            @Nullable PluginExecutors executors,
            @Nullable NodeCommandRepository nodeCommands,
            @Nullable Supplier<NetworkPolicy> policy) {
        this.membershipCache = Objects.requireNonNull(membershipCache, "membershipCache");
        this.folders = folders;
        this.executors = executors;
        this.nodeCommands = nodeCommands;
        this.policy = policy;
    }

    @Override
    public CommandResult handle(NodeCommand command) {
        WorldId worldId = command.worldId();
        if (worldId != null) {
            membershipCache.invalidate(worldId);
        }

        Optional<EjectPayload> payload = EjectPayload.parse(command.payloadJson());
        if (payload.isEmpty()) {
            return CommandResult.ok();
        }

        UUID targetUuid = payload.get().playerUuid();
        String reason = payload.get().reason();

        if (executors != null && folders != null) {
            executors.main().execute(() -> {
                Player player = Bukkit.getPlayer(targetUuid);
                if (player != null && player.isOnline()) {
                    if (worldId != null) {
                        boolean inWorld = false;
                        for (DimensionKind dim : DimensionKind.values()) {
                            String name = folders.bukkitWorldName(worldId, dim);
                            World w = Bukkit.getWorld(name);
                            if (w != null && player.getWorld().equals(w)) {
                                inWorld = true;
                                break;
                            }
                        }
                        if (!inWorld) {
                            return;
                        }
                    }
                    String msg = reason != null ? reason : "You were removed from the world.";
                    player.sendMessage(Component.text(msg, NamedTextColor.RED));
                    if (nodeCommands != null && policy != null) {
                        executors.db().execute(() -> {
                            try {
                                nodeCommands.enqueue(
                                        "proxy",
                                        worldId,
                                        null,
                                        "EJECT_PLAYER",
                                        EjectPayload.format(targetUuid, reason),
                                        policy.get().holdingTimeout(),
                                        ControlChannels.PROXY);
                            } catch (Exception e) {
                                log.warn("could not enqueue EJECT_PLAYER for {}", targetUuid, e);
                            }
                        });
                    }
                }
            });
        }
        return CommandResult.ok();
    }
}
