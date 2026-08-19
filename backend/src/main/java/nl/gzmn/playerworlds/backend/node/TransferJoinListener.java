package nl.gzmn.playerworlds.backend.node;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import nl.gzmn.playerworlds.backend.platform.DimensionKind;
import nl.gzmn.playerworlds.backend.world.LoadOutcome;
import nl.gzmn.playerworlds.backend.world.LoadedWorld;
import nl.gzmn.playerworlds.backend.world.WorldFolders;
import nl.gzmn.playerworlds.backend.world.WorldLoader;
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
import nl.gzmn.playerworlds.core.obs.WorldsMetrics;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
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
 *
 * <h2>The holding deadline (FR-11, section 9)</h2>
 *
 * <p>The branches above all answer. The failure this deadline exists for is the
 * one that does not: a load that never completes leaves the player standing in
 * the holding area indefinitely, which section 9 rules out in as many words —
 * <em>do not spawn them in the holding area indefinitely</em>. So a claimed
 * arrival is armed with a deadline of {@code transfers.holding-timeout-seconds},
 * measured from the join event rather than from the claim, because the
 * {@code pending_transfer} lookup is part of the sequence FR-11 bounds and a
 * saturated database executor can spend the budget before the load is even asked
 * for.
 *
 * <p>The holding timeout is the <em>outer</em> budget of the join path, not one
 * more wait alongside the others: {@code ConfigValidator} keeps both NFR-1's
 * cold-load budget and the commit budget strictly inside it, so a load still
 * within the time NFR-1 allows it can never be ejected by this deadline.
 */
public final class TransferJoinListener implements Listener {

    private static final Logger log = LoggerFactory.getLogger(TransferJoinListener.class);

    private final NodeConfig config;
    private final PendingTransferRepository transfers;
    private final WorldLoader lifecycle;
    private final WorldFolders folders;
    private final PluginExecutors executors;
    private final NodeCommandRepository nodeCommands;
    private final Supplier<NetworkPolicy> policy;
    private final WorldsMetrics metrics;

    /**
     * Arrivals that have claimed a transfer and are waiting for a world, so a
     * disconnect can stand the deadline down instead of ejecting a player who has
     * already left and counting a timeout that never happened.
     */
    private final Map<UUID, Arrival> inFlight = new ConcurrentHashMap<>();

    public TransferJoinListener(
            NodeConfig config,
            PendingTransferRepository transfers,
            WorldLoader lifecycle,
            WorldFolders folders,
            PluginExecutors executors,
            NodeCommandRepository nodeCommands,
            Supplier<NetworkPolicy> policy,
            WorldsMetrics metrics) {
        this.config = Objects.requireNonNull(config, "config");
        this.transfers = Objects.requireNonNull(transfers, "transfers");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.folders = Objects.requireNonNull(folders, "folders");
        this.executors = Objects.requireNonNull(executors, "executors");
        this.nodeCommands = Objects.requireNonNull(nodeCommands, "nodeCommands");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /**
     * Claims the transfer and follows it, all off the tick thread (FR-11).
     *
     * <p>Runs at {@code MONITOR} so the visibility listener has already placed the
     * player in a group before anything moves them.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        processPlayer(event.getPlayer());
    }

    /**
     * Stands down the holding deadline for a player who left before their world
     * arrived (FR-11).
     *
     * <p>Without this the timer still fires: it enqueues an {@code EJECT_PLAYER}
     * for somebody the proxy can no longer find, and moves the timeout counter for
     * a wait that ended for an unrelated reason. Neither breaks anything; both
     * make the metric a worse signal than no metric.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Arrival arrival = inFlight.get(event.getPlayer().getUniqueId());
        if (arrival != null && settle(arrival)) {
            log.debug(
                    "player {} disconnected while waiting for world {}; holding deadline stood down",
                    arrival.playerUuid,
                    arrival.worldId);
        }
    }

    /**
     * Claims and executes any pending transfer for the player.
     */
    public void processPlayer(Player player) {
        NetworkPolicy current = policy.get();
        // FR-11 bounds "the sequence", and the lookup below is its first step, so
        // the clock starts here rather than once the claim has come back.
        long arrivedAtNanos = System.nanoTime();

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

            follow(player, transfer, arrivedAtNanos, current.holdingTimeout());
        });
    }

    /**
     * Waits for the world under FR-11's deadline and acts on whichever of the two
     * arrives first.
     */
    private void follow(Player player, PendingTransfer transfer, long arrivedAtNanos, Duration holdingTimeout) {
        Arrival arrival = arm(player, transfer.worldId(), arrivedAtNanos, holdingTimeout);
        if (arrival.isSettled()) {
            // The budget was gone before the load could be asked for; arm() has
            // already sent them to lobby.
            return;
        }

        var _ = lifecycle
                .load(transfer.worldId())
                .whenCompleteAsync(
                        (outcome, failure) -> {
                            if (!settle(arrival)) {
                                // The deadline got there first and the player is on
                                // their way to lobby. Whatever the load decided, it
                                // is no longer theirs to act on.
                                log.debug(
                                        "load for {} completed after the holding deadline for player {}",
                                        transfer.worldId(),
                                        player.getUniqueId());
                                return;
                            }
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
                                            "this server is holding " + full.loaded() + " worlds and cannot take more",
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
    }

    /**
     * Starts FR-11's holding deadline for an arrival, or expires it on the spot if
     * the budget is already spent.
     *
     * <p>The returned arrival is settled if and only if it expired here; callers
     * check that rather than being handed a null.
     */
    private Arrival arm(Player player, WorldId worldId, long arrivedAtNanos, Duration holdingTimeout) {
        Arrival arrival = new Arrival(player, worldId, holdingTimeout);
        Arrival superseded = inFlight.put(player.getUniqueId(), arrival);
        if (superseded != null) {
            // A second transfer claimed while the first was still waiting. The old
            // deadline must not eject the player out from under the new arrival.
            var _ = superseded.settled.compareAndSet(false, true);
            superseded.cancelDeadline();
        }

        long spentMillis = Duration.ofNanos(System.nanoTime() - arrivedAtNanos).toMillis();
        long remainingMillis = holdingTimeout.toMillis() - spentMillis;
        if (remainingMillis <= 0L) {
            expire(arrival);
            return arrival;
        }

        arrival.deadline = executors.sched().schedule(() -> expire(arrival), remainingMillis, TimeUnit.MILLISECONDS);
        if (arrival.isSettled()) {
            // Settled between the schedule and the assignment. Harmless — expire()
            // re-checks — but there is no reason to leave the task queued.
            arrival.cancelDeadline();
        }
        return arrival;
    }

    /**
     * FR-11's deadline: the sequence did not complete, so the player goes to lobby
     * rather than staying in the holding area.
     *
     * <p>Runs on the scheduler and does no blocking work there: it hops to the main
     * thread to read the player's connection state, and from there the message goes
     * out and the {@code EJECT_PLAYER} row to the db executor, exactly as every
     * other refusal does.
     *
     * <p>Nothing is ejected or counted for a player who has already left. {@link
     * #onQuit} normally stands the deadline down before it ever fires, but it can
     * only do that for an arrival that has been armed, and a player can disconnect
     * inside the window between the join event and the claim coming back. The check
     * here is what makes the counter mean "this wait ran out of time" in both
     * orderings rather than only the common one.
     */
    private void expire(Arrival arrival) {
        if (!settle(arrival)) {
            return;
        }
        executors.main().execute(() -> {
            if (!arrival.player.isOnline()) {
                log.debug(
                        "holding deadline for world {} expired after player {} had already disconnected",
                        arrival.worldId,
                        arrival.playerUuid);
                return;
            }
            log.warn(
                    "holding-area timeout after {}s for player {} joining world {}; sending to lobby ({}, FR-11)",
                    arrival.budget.toSeconds(),
                    arrival.playerUuid,
                    arrival.worldId,
                    NetworkPolicy.KEY_HOLDING_TIMEOUT_SECONDS);
            metrics.holdingTimeout();
            refuse(
                    arrival.player,
                    arrival.worldId,
                    "that world took too long to load; returning you to the lobby",
                    "Holding area timeout after " + arrival.budget.toSeconds() + "s");
        });
    }

    /**
     * Claims the right to finish an arrival. Exactly one of the load completing,
     * the deadline expiring and the player disconnecting wins; the losers do
     * nothing, so a player is never both teleported and ejected.
     */
    private boolean settle(Arrival arrival) {
        if (!arrival.settled.compareAndSet(false, true)) {
            return false;
        }
        var _ = inFlight.remove(arrival.playerUuid, arrival);
        arrival.cancelDeadline();
        return true;
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

    /** One routed arrival, and the single-shot latch that decides who finishes it. */
    private static final class Arrival {

        private final Player player;
        private final UUID playerUuid;
        private final WorldId worldId;
        private final Duration budget;
        private final AtomicBoolean settled = new AtomicBoolean();
        private volatile @Nullable ScheduledFuture<?> deadline;

        Arrival(Player player, WorldId worldId, Duration budget) {
            this.player = player;
            this.playerUuid = player.getUniqueId();
            this.worldId = worldId;
            this.budget = budget;
        }

        boolean isSettled() {
            return settled.get();
        }

        void cancelDeadline() {
            ScheduledFuture<?> handle = deadline;
            if (handle != null) {
                var _ = handle.cancel(false);
            }
        }
    }
}
