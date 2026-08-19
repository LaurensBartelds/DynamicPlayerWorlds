package nl.gzmn.playerworlds.backend.control;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import nl.gzmn.playerworlds.backend.world.LoadedWorld;
import nl.gzmn.playerworlds.backend.world.WorldRegistry;
import nl.gzmn.playerworlds.core.concurrent.DrainableMainScheduler;
import nl.gzmn.playerworlds.core.concurrent.MainThread;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FR-28: gives up every world this node holds, through the same {@link
 * WorldHandoff} the control-plane commands use.
 *
 * <h2>Why this is not its own sequence</h2>
 *
 * <p>FR-25 orders a give-up <em>commit, unload, release</em>, and {@code
 * WorldHandoff.unload} carries the reason: release comes last so no other node
 * can acquire the world before its final snapshot is the current one. The
 * shutdown path used to commit, then release every lease, then unload — the same
 * three steps with the release moved to the middle, which opens exactly the
 * window that comment rules out. Two paths doing the same thing in opposite
 * orders, with the reasoning written on only one of them.
 *
 * <p>So there is one implementation of the order and this drives it. That also
 * picks up what the old shutdown skipped by not going through {@code
 * afterUnload}: {@code last_played} is written, so MN-15a's placement scoring
 * still knows where the world was warm, and the membership cache is invalidated.
 *
 * <p>A world that will not unload, or whose final commit fails, keeps its lease.
 * That is deliberate and is what the handoff already does for a migrate: the
 * newest state of such a world exists only in this node's scratch directory, and
 * a released lease would invite another node to serve the snapshot before it.
 * The lease expires on its own after {@code nodes.lease-seconds} (MN-12).
 */
public final class NodeShutdown {

    private static final Logger log = LoggerFactory.getLogger(NodeShutdown.class);

    /** Shown to anyone still inside, and carried on the proxy eject. */
    static final String REASON = "This server is shutting down";

    private final WorldRegistry registry;
    private final WorldHandoff handoff;
    private final DrainableMainScheduler main;

    public NodeShutdown(WorldRegistry registry, WorldHandoff handoff, DrainableMainScheduler main) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.handoff = Objects.requireNonNull(handoff, "handoff");
        this.main = Objects.requireNonNull(main, "main");
    }

    /**
     * Runs the handoff for every loaded world and waits for all of them.
     *
     * <p>Started together rather than one after another: the commits are
     * independent, so the whole shutdown fits in one budget instead of one per
     * world. Main thread — the unloads need it, and it is the thread that drains
     * the main-thread work the commits queue.
     *
     * @param budget how long to wait for all of them, which should leave room for
     *     the slowest commit ({@code storage.commit-timeout-seconds})
     */
    public void releaseAll(Duration budget) {
        MainThread.assertOn();
        Objects.requireNonNull(budget, "budget");

        List<LoadedWorld> loaded = registry.loadedWorlds();
        if (loaded.isEmpty()) {
            return;
        }
        log.info("shutting down: handing off {} loaded world(s) within {} (FR-28)", loaded.size(), budget);

        Map<WorldId, CompletableFuture<WorldHandoff.Outcome>> started = new LinkedHashMap<>();
        for (LoadedWorld world : loaded) {
            try {
                started.put(world.id(), handoff.release(world.id(), 0, REASON));
            } catch (RuntimeException e) {
                log.error("could not start the shutdown handoff for world {}", world.id(), e);
            }
        }

        CompletableFuture<Void> all = CompletableFuture.allOf(started.values().toArray(CompletableFuture<?>[]::new));
        try {
            main.awaitDraining(all, budget);
        } catch (TimeoutException e) {
            log.warn(
                    "shutdown handoff did not finish within {}; unfinished worlds keep their leases until they "
                            + "expire (MN-12)",
                    budget);
        } catch (ExecutionException e) {
            // Reported per world below, where the world it belongs to is known.
            log.debug("a shutdown handoff completed exceptionally", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("interrupted while handing off worlds for shutdown");
        }

        for (Map.Entry<WorldId, CompletableFuture<WorldHandoff.Outcome>> entry : started.entrySet()) {
            report(entry.getKey(), entry.getValue());
        }
    }

    private void report(WorldId worldId, CompletableFuture<WorldHandoff.Outcome> future) {
        if (!future.isDone()) {
            log.warn(
                    "world {} did not finish its shutdown handoff; it stays leased until the lease expires (MN-12)",
                    worldId);
            return;
        }
        final WorldHandoff.Outcome outcome;
        try {
            outcome = future.join();
        } catch (RuntimeException e) {
            log.error("shutdown handoff failed for world {}; its lease is left in place", worldId, e);
            return;
        }
        switch (outcome) {
            case WorldHandoff.Outcome.Released released ->
                log.info(
                        "world {} committed, unloaded and released for shutdown, {} player(s) moved out (FR-28)",
                        worldId,
                        released.playersMoved());
            case WorldHandoff.Outcome.NotHeld ignored ->
                log.debug("world {} was already gone when shutdown reached it", worldId);
            case WorldHandoff.Outcome.Blocked blocked ->
                log.warn(
                        "world {} would not unload at dimension {} during shutdown: {}. Its lease is left in "
                                + "place so no other node loads it over this node's scratch copy (MN-12)",
                        worldId,
                        blocked.dimension(),
                        blocked.blockers().isEmpty() ? "no determinable cause" : String.join("; ", blocked.blockers()));
            case WorldHandoff.Outcome.CommitFailed failed ->
                log.error(
                        "final snapshot commit failed for world {} during shutdown: {}. Its lease is left in "
                                + "place: the newest state of this world is only in this node's scratch "
                                + "directory, and another node loading it now would serve the snapshot before "
                                + "it (MN-2, MN-3)",
                        worldId,
                        failed.detail());
        }
    }
}
