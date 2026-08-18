package nl.gzmn.playerworlds.backend.node;

import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import nl.gzmn.playerworlds.backend.platform.DimensionKind;
import nl.gzmn.playerworlds.backend.world.LoadOutcome;
import nl.gzmn.playerworlds.backend.world.LoadedWorld;
import nl.gzmn.playerworlds.backend.world.WorldFolders;
import nl.gzmn.playerworlds.backend.world.WorldLifecycleService;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.config.NodeConfig;
import nl.gzmn.playerworlds.core.control.CommandKind;
import nl.gzmn.playerworlds.core.control.ControlChannels;
import nl.gzmn.playerworlds.core.control.EjectPayload;
import nl.gzmn.playerworlds.core.db.NodeCommandRepository;
import nl.gzmn.playerworlds.core.db.PendingTransferRepository;
import nl.gzmn.playerworlds.core.db.PendingTransferRepository.PendingTransfer;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FR-11: what a node does when a routed player arrives.
 *
 * <p>The event handler itself does no blocking work — NFR-2 forbids database
 * access on the tick thread, and this runs on it. The transfer lookup, the world
 * load and the profile restore are all asynchronous, and only the teleport comes
 * back to the main thread.
 *
 * <p>Every refusal branch FR-11 lists is here: a missing or expired transfer, a
 * transfer routed to a different node, and a generation that no longer matches
 * the lease. Each means the same thing from the player's side — the world is not
 * where they were told it would be — and each sends them an explanation and
 * enqueues {@code EJECT_PLAYER} to the proxy to return them to the lobby.
 */
public final class TransferJoinListener implements Listener {

    private static final Logger log = LoggerFactory.getLogger(TransferJoinListener.class);

    private final NodeConfig config;
    private final PendingTransferRepository transfers;
    private final WorldLifecycleService lifecycle;
    private final WorldFolders folders;
    private final PluginExecutors executors;
    private final NodeCommandRepository nodeCommands;
    private final Supplier<NetworkPolicy> policy;

    public TransferJoinListener(
            NodeConfig config,
            PendingTransferRepository transfers,
            WorldLifecycleService lifecycle,
            WorldFolders folders,
            PluginExecutors executors,
            NodeCommandRepository nodeCommands,
            Supplier<NetworkPolicy> policy) {
        this.config = Objects.requireNonNull(config, "config");
        this.transfers = Objects.requireNonNull(transfers, "transfers");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.folders = Objects.requireNonNull(folders, "folders");
        this.executors = Objects.requireNonNull(executors, "executors");
        this.nodeCommands = Objects.requireNonNull(nodeCommands, "nodeCommands");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /**
     * Claims the transfer and follows it, all off the tick thread (FR-11).
     *
     * <p>Runs at {@code MONITOR} so the visibility listener has already placed the
     * player in a group before anything moves them.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        NetworkPolicy current = policy.get();

        executors.db().execute(() -> {
            final Optional<PendingTransfer> claimed;
            try {
                claimed = transfers.claim(player.getUniqueId(), current.transferExpiry());
            } catch (SQLException e) {
                log.error("could not read the pending transfer for {}", player.getUniqueId(), e);
                tell(player, "could not look up where you were going; you are in the holding area");
                return;
            }

            if (claimed.isEmpty()) {
                // Not every join is a routed one: an operator connecting directly
                // to a node has no transfer and is not an error.
                log.debug("no pending transfer for {}; leaving them where they landed", player.getUniqueId());
                return;
            }

            PendingTransfer transfer = claimed.get();
            if (!transfer.nodeId().equals(config.nodeId())) {
                // The world moved between routing and arrival (FR-11).
                log.warn(
                        "transfer for {} names node {} but this is {}; refusing",
                        player.getUniqueId(),
                        transfer.nodeId(),
                        config.nodeId());
                refuse(
                        player,
                        transfer.worldId(),
                        "that world moved to another server while you were connecting",
                        "World moved to another server");
                return;
            }

            var _ = lifecycle
                    .load(transfer.worldId())
                    .whenCompleteAsync(
                            (outcome, failure) -> {
                                if (failure != null) {
                                    log.error("load failed for a routed join into {}", transfer.worldId(), failure);
                                    refuse(
                                            player,
                                            transfer.worldId(),
                                            "that world could not be loaded",
                                            "World load failed");
                                    return;
                                }
                                switch (outcome) {
                                    case LoadOutcome.Loaded loaded -> {
                                        if (loaded.world().generation() != transfer.generation()) {
                                            log.warn(
                                                    "transfer generation mismatch for player {}: transfer had generation {}, loaded world {} has generation {}",
                                                    player.getUniqueId(),
                                                    transfer.generation(),
                                                    transfer.worldId(),
                                                    loaded.world().generation());
                                            refuse(
                                                    player,
                                                    transfer.worldId(),
                                                    "that world moved while you were connecting",
                                                    "World moved to another server");
                                            return;
                                        }
                                        if (loaded.world().isLeaseDegraded()) {
                                            log.warn(
                                                    "refusing join for player {} to world {}: lease is degraded (DB unreachable)",
                                                    player.getUniqueId(),
                                                    transfer.worldId());
                                            refuse(
                                                    player,
                                                    transfer.worldId(),
                                                    "that world is currently unavailable due to database connectivity; try again shortly",
                                                    "Database connectivity issue");
                                            return;
                                        }
                                        sendIn(player, loaded.world());
                                    }
                                    case LoadOutcome.TooNew tooNew ->
                                        refuse(
                                                player,
                                                transfer.worldId(),
                                                "that world needs a newer server version (world "
                                                        + tooNew.worldDataVersion() + ", this node "
                                                        + tooNew.nodeDataVersion() + ")",
                                                "World requires newer server version");
                                    case LoadOutcome.NodeFull full ->
                                        refuse(
                                                player,
                                                transfer.worldId(),
                                                "this server is holding " + full.loaded()
                                                        + " worlds and cannot take more",
                                                "Node is full");
                                    case LoadOutcome.NotFound ignored ->
                                        refuse(player, null, "that world no longer exists", "World no longer exists");
                                    case LoadOutcome.WrongState state ->
                                        refuse(
                                                player,
                                                transfer.worldId(),
                                                "that world is " + state.state() + " and cannot be entered right now",
                                                "World is " + state.state());
                                    case LoadOutcome.Failed reason ->
                                        refuse(
                                                player,
                                                transfer.worldId(),
                                                "world generation failed",
                                                "Generation failed: " + reason.reason());
                                }
                            },
                            executors.main());
        });
    }

    /**
     * Puts the player in the world's overworld spawn.
     *
     * <p>The profile arrives separately: {@code ProfileListener} sees the world
     * change this teleport causes and restores it (FR-15b). Keeping the two apart
     * is deliberate — the teleport is the only part that has to be on this tick.
     */
    private void sendIn(Player player, LoadedWorld world) {
        String bukkitName = folders.bukkitWorldName(world.id(), DimensionKind.OVERWORLD);
        World overworld = Bukkit.getWorld(bukkitName);
        if (overworld == null) {
            log.error("world {} loaded but its overworld {} is not available", world.id(), bukkitName);
            refuse(player, world.id(), "that world loaded but could not be entered", "Overworld not available");
            return;
        }
        player.teleport(overworld.getSpawnLocation());
    }

    /** Informs the player and enqueues an EJECT_PLAYER command to route them to the lobby (FR-11). */
    private void refuse(Player player, @Nullable WorldId worldId, String message, String ejectReason) {
        tell(player, message);
        executors.db().execute(() -> {
            try {
                nodeCommands.enqueue(
                        "proxy",
                        worldId,
                        null,
                        CommandKind.EJECT_PLAYER.name(),
                        EjectPayload.format(player.getUniqueId(), ejectReason),
                        policy.get().holdingTimeout(),
                        ControlChannels.PROXY);
            } catch (SQLException e) {
                log.warn("could not enqueue EJECT_PLAYER for {}", player.getUniqueId(), e);
            }
        });
    }

    /** Messages the player if they are still connected. */
    private void tell(Player player, String message) {
        executors.main().execute(() -> {
            if (player.isOnline()) {
                player.sendMessage(Component.text(message, NamedTextColor.RED));
            }
        });
    }
}
