package nl.gzmn.playerworlds.backend.control;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import nl.gzmn.playerworlds.backend.platform.DimensionKind;
import nl.gzmn.playerworlds.backend.profile.WorldCommitService;
import nl.gzmn.playerworlds.backend.world.LoadedWorld;
import nl.gzmn.playerworlds.backend.world.UnloadOutcome;
import nl.gzmn.playerworlds.backend.world.WorldFolders;
import nl.gzmn.playerworlds.backend.world.WorldLifecycleService;
import nl.gzmn.playerworlds.backend.world.WorldRegistry;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.control.CommandKind;
import nl.gzmn.playerworlds.core.control.ControlChannels;
import nl.gzmn.playerworlds.core.control.EjectPayload;
import nl.gzmn.playerworlds.core.db.NodeCommandRepository;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gives a world up, in MN-19's order: warn, eject, commit, unload, release.
 *
 * <p>One implementation for the three commands that need it, because the order
 * is the correctness argument and three copies of it would be three chances to
 * get it wrong:
 *
 * <ul>
 *   <li>{@link CommandKind#MIGRATE_WORLD} — MN-19 and MN-21, with a destination
 *       and a countdown.
 *   <li>{@link CommandKind#DRAIN_NODE} — MN-22, which "unloads its worlds in
 *       place (players to lobby, each with a snapshot commit) rather than
 *       live-migrating them". The same sequence with no destination.
 *   <li>{@link CommandKind#UNLOAD_WORLD} — an administrative unload, which owes
 *       the same commit: FR-25 and MN-5 both order it commit, unload, release.
 * </ul>
 *
 * <h2>Why the ejection comes before the commit</h2>
 *
 * <p>MN-19 says so, and gives the reason: the players' profiles have to be
 * captured by the final commit so they come back with the world on the target
 * node. Their capture is the {@code PlayerChangedWorldEvent} the teleport out
 * raises (FR-15), which folds each departing profile into the next commit. Eject
 * after the commit and every player in the world arrives on the target with the
 * inventory they had one sync interval ago, while the world has the one they
 * left behind — FR-15a's duplication bug, with the two halves swapped.
 *
 * <h2>Why a failed commit abandons the handoff</h2>
 *
 * <p>The alternative is unloading a world whose last few minutes exist only in a
 * scratch directory that the next node will never read (MN-2, MN-3). The world
 * stays loaded and leased, the command completes with an error an operator can
 * see, and the FR-25 idle sweep tries again.
 */
public final class WorldHandoff {

    private static final Logger log = LoggerFactory.getLogger(WorldHandoff.class);

    /** What the handoff did, for the control-plane result and for the caller's log line. */
    public sealed interface Outcome {

        /** Not loaded here. Idempotent success (CP-5): a retry of a handoff that already ran. */
        record NotHeld() implements Outcome {}

        /** Committed, unloaded and released. */
        record Released(int playersMoved) implements Outcome {}

        /** A dimension would not unload (FR-25a). The world is still loaded and still leased. */
        record Blocked(DimensionKind dimension, List<String> blockers) implements Outcome {}

        /** The final snapshot commit failed, so nothing was unloaded. */
        record CommitFailed(String detail) implements Outcome {}
    }

    private final WorldRegistry registry;
    private final WorldLifecycleService lifecycle;
    private final WorldFolders folders;
    private final PluginExecutors executors;
    private final @Nullable WorldCommitService commits;
    private final NodeCommandRepository nodeCommands;
    private final Supplier<NetworkPolicy> policy;

    public WorldHandoff(
            WorldRegistry registry,
            WorldLifecycleService lifecycle,
            WorldFolders folders,
            PluginExecutors executors,
            @Nullable WorldCommitService commits,
            NodeCommandRepository nodeCommands,
            Supplier<NetworkPolicy> policy) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.folders = Objects.requireNonNull(folders, "folders");
        this.executors = Objects.requireNonNull(executors, "executors");
        this.commits = commits;
        this.nodeCommands = Objects.requireNonNull(nodeCommands, "nodeCommands");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /**
     * Runs the sequence for one world.
     *
     * @param countdownSeconds MN-21's visible warning; zero moves at once
     * @param reason shown to players and carried on the proxy eject
     */
    public CompletableFuture<Outcome> release(WorldId worldId, int countdownSeconds, String reason) {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(reason, "reason");

        Optional<LoadedWorld> found = registry.find(worldId);
        if (found.isEmpty()) {
            return CompletableFuture.completedFuture(new Outcome.NotHeld());
        }
        LoadedWorld loaded = found.get();

        return countdown(worldId, countdownSeconds, reason)
                .thenComposeAsync(ignored -> ejectPlayers(worldId, reason), executors.main())
                .thenCompose(moved -> commitThenUnload(loaded, moved, reason));
    }

    /**
     * MN-21's countdown, shown to anybody inside.
     *
     * <p>One message a second is deliberately noisy: MN-19 calls this "several
     * seconds of visible interruption", and a player who is mining should not
     * discover it by being teleported.
     */
    private CompletableFuture<Void> countdown(WorldId worldId, int seconds, String reason) {
        if (seconds <= 0) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> done = new CompletableFuture<>();
        for (int remaining = seconds; remaining > 0; remaining--) {
            int at = remaining;
            var _ = executors
                    .sched()
                    .schedule(
                            () -> executors.main().execute(() -> tellInside(worldId, reason + " in " + at + "s")),
                            (long) (seconds - at),
                            TimeUnit.SECONDS);
        }
        var _ = executors.sched().schedule(() -> done.complete(null), seconds, TimeUnit.SECONDS);
        return done;
    }

    /** Main thread. Moves everyone out of the world's three dimensions, then tells the proxy. */
    private CompletableFuture<Integer> ejectPlayers(WorldId worldId, String reason) {
        World holding = holdingWorld(worldId);
        List<Player> moved = new ArrayList<>();

        for (DimensionKind dimension : DimensionKind.values()) {
            World bukkit = Bukkit.getWorld(folders.bukkitWorldName(worldId, dimension));
            if (bukkit == null) {
                continue;
            }
            for (Player player : List.copyOf(bukkit.getPlayers())) {
                player.sendMessage(Component.text(reason, NamedTextColor.YELLOW));
                if (holding != null) {
                    // The teleport is what raises PlayerChangedWorldEvent and so
                    // what captures the profile into the commit below (FR-15).
                    player.teleport(holding.getSpawnLocation());
                }
                moved.add(player);
            }
        }

        if (!moved.isEmpty()) {
            List<Player> toRoute = List.copyOf(moved);
            executors.db().execute(() -> {
                for (Player player : toRoute) {
                    try {
                        nodeCommands.enqueue(
                                "proxy",
                                worldId,
                                null,
                                CommandKind.EJECT_PLAYER.name(),
                                EjectPayload.format(player.getUniqueId(), reason),
                                policy.get().holdingTimeout(),
                                ControlChannels.PROXY);
                    } catch (Exception e) {
                        log.warn(
                                "could not enqueue EJECT_PLAYER for {} during handoff of world {}",
                                player.getUniqueId(),
                                worldId,
                                e);
                    }
                }
            });
        }
        return CompletableFuture.completedFuture(moved.size());
    }

    private CompletableFuture<Outcome> commitThenUnload(LoadedWorld loaded, int playersMoved, String reason) {
        WorldCommitService commitService = commits;
        if (commitService == null) {
            // No object storage configured. There is nothing durable to commit to,
            // so the unload is the whole of the handoff.
            return unload(loaded, playersMoved);
        }
        CompletableFuture<Outcome> done = new CompletableFuture<>();
        var _ = commitService.requestCommit(loaded.id()).whenComplete((ignored, failure) -> {
            if (failure != null) {
                log.error(
                        "final snapshot commit failed for world {} during '{}'; leaving it loaded and leased",
                        loaded.id(),
                        reason,
                        failure);
                done.complete(new Outcome.CommitFailed(describe(failure)));
                return;
            }
            var _ = unload(loaded, playersMoved).whenComplete((outcome, error) -> {
                if (error != null) {
                    done.completeExceptionally(error);
                } else {
                    done.complete(outcome);
                }
            });
        });
        return done;
    }

    /** One line an operator can act on, never a stack trace (ADR 0002). */
    private static String describe(Throwable failure) {
        Throwable unwrapped = failure.getCause();
        Throwable cause = failure instanceof CompletionException && unwrapped != null ? unwrapped : failure;
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    private CompletableFuture<Outcome> unload(LoadedWorld loaded, int playersMoved) {
        CompletableFuture<Outcome> done = new CompletableFuture<>();
        executors.main().execute(() -> {
            try {
                if (registry.find(loaded.id()).isEmpty()) {
                    // Fenced while the commit ran (MN-10a), which has already
                    // unloaded and quarantined it. Nothing left to hand off.
                    done.complete(new Outcome.NotHeld());
                    return;
                }
                UnloadOutcome outcome = lifecycle.unloadOnMain(loaded);
                switch (outcome) {
                    case UnloadOutcome.Complete ignored -> {
                        // Deregisters, records last_played and releases the lease
                        // (MN-12). Release comes last, so no other node can acquire
                        // the world before its final snapshot is the current one.
                        //
                        // Waited on rather than fired and forgotten: `Released` is
                        // read by callers that act on the lease straight afterwards
                        // (FR-35's archival re-acquires it), so completing this
                        // future before the release lands would make the outcome's
                        // own name untrue.
                        var _ = lifecycle.afterUnload(loaded).whenComplete((released, failure) -> {
                            if (failure != null) {
                                // The world is down either way; the lease will
                                // expire on its own (MN-12). Report it rather
                                // than claiming a clean release.
                                log.warn(
                                        "world {} unloaded but its lease release did not complete",
                                        loaded.id(),
                                        failure);
                            }
                            done.complete(new Outcome.Released(playersMoved));
                        });
                    }
                    case UnloadOutcome.Blocked blocked ->
                        done.complete(new Outcome.Blocked(blocked.dimension(), blocked.blockers()));
                }
            } catch (RuntimeException e) {
                done.completeExceptionally(e);
            }
        });
        return done;
    }

    /** Main thread. */
    private void tellInside(WorldId worldId, String message) {
        for (DimensionKind dimension : DimensionKind.values()) {
            World bukkit = Bukkit.getWorld(folders.bukkitWorldName(worldId, dimension));
            if (bukkit == null) {
                continue;
            }
            for (Player player : bukkit.getPlayers()) {
                player.sendMessage(Component.text(message, NamedTextColor.YELLOW));
            }
        }
    }

    /**
     * Somewhere on this node that is not the world being given up.
     *
     * <p>FR-11's holding area. Players sit here for the moment between leaving
     * the world and the proxy's transfer arriving; the teleport also has to
     * happen for the unload below to be able to succeed at all, since Bukkit
     * refuses to unload a world that still holds a player.
     */
    private @Nullable World holdingWorld(WorldId worldId) {
        for (World candidate : Bukkit.getWorlds()) {
            if (!folders.isPlayerWorld(candidate.getName())) {
                return candidate;
            }
        }
        for (World candidate : Bukkit.getWorlds()) {
            if (!candidate.getName().startsWith(worldId.folder())) {
                return candidate;
            }
        }
        return null;
    }
}
