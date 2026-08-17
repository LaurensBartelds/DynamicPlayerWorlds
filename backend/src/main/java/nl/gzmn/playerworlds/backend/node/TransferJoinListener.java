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
import nl.gzmn.playerworlds.core.db.PendingTransferRepository;
import nl.gzmn.playerworlds.core.db.PendingTransferRepository.PendingTransfer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
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
 * where they were told it would be — and each currently leaves them in the
 * holding area with an explanation rather than sending them to lobby, because
 * the return leg needs the proxy to accept a send and that is the other half of
 * this milestone.
 */
public final class TransferJoinListener implements Listener {

    private static final Logger log = LoggerFactory.getLogger(TransferJoinListener.class);

    private final NodeConfig config;
    private final PendingTransferRepository transfers;
    private final WorldLifecycleService lifecycle;
    private final WorldFolders folders;
    private final PluginExecutors executors;
    private final Supplier<NetworkPolicy> policy;

    public TransferJoinListener(
            NodeConfig config,
            PendingTransferRepository transfers,
            WorldLifecycleService lifecycle,
            WorldFolders folders,
            PluginExecutors executors,
            Supplier<NetworkPolicy> policy) {
        this.config = Objects.requireNonNull(config, "config");
        this.transfers = Objects.requireNonNull(transfers, "transfers");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.folders = Objects.requireNonNull(folders, "folders");
        this.executors = Objects.requireNonNull(executors, "executors");
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
                tell(player, "that world moved to another server while you were connecting");
                return;
            }

            var _ = lifecycle
                    .load(transfer.worldId())
                    .whenCompleteAsync(
                            (outcome, failure) -> {
                                if (failure != null) {
                                    log.error("load failed for a routed join into {}", transfer.worldId(), failure);
                                    tell(player, "that world could not be loaded");
                                    return;
                                }
                                switch (outcome) {
                                    case LoadOutcome.Loaded loaded -> {
                                        if (loaded.world().generation() != transfer.generation()) {
                                            // FR-11's generation check. Zero on both sides
                                            // until milestone 7 makes leases real, but the
                                            // comparison is the point: a route resolved
                                            // against a lease that has since moved must not
                                            // be honoured.
                                            log.warn(
                                                    "transfer for {} was routed against generation {} but world {} is at {}",
                                                    player.getUniqueId(),
                                                    transfer.generation(),
                                                    transfer.worldId(),
                                                    loaded.world().generation());
                                            tell(player, "that world moved while you were connecting");
                                            return;
                                        }
                                        sendIn(player, loaded.world());
                                    }
                                    case LoadOutcome.TooNew tooNew ->
                                        tell(
                                                player,
                                                "that world needs a newer server version (world "
                                                        + tooNew.worldDataVersion() + ", this node "
                                                        + tooNew.nodeDataVersion() + ")");
                                    case LoadOutcome.NodeFull full ->
                                        tell(
                                                player,
                                                "this server is holding " + full.loaded()
                                                        + " worlds and cannot take more");
                                    case LoadOutcome.NotFound ignored -> tell(player, "that world no longer exists");
                                    case LoadOutcome.WrongState state ->
                                        tell(
                                                player,
                                                "that world is " + state.state() + " and cannot be entered right now");
                                    case LoadOutcome.Failed reason -> tell(player, reason.reason());
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
            tell(player, "that world loaded but could not be entered");
            return;
        }
        var _ = player.teleportAsync(overworld.getSpawnLocation()).whenComplete((moved, failure) -> {
            if (failure != null) {
                log.warn("could not teleport {} into world {}", player.getUniqueId(), world.id(), failure);
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
