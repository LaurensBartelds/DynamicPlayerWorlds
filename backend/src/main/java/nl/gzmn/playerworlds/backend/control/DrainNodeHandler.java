package nl.gzmn.playerworlds.backend.control;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import nl.gzmn.playerworlds.backend.node.NodeHeartbeat;
import nl.gzmn.playerworlds.backend.world.LoadedWorld;
import nl.gzmn.playerworlds.backend.world.WorldRegistry;
import nl.gzmn.playerworlds.core.config.HandoffBudget;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.control.CommandHandler;
import nl.gzmn.playerworlds.core.control.CommandKind;
import nl.gzmn.playerworlds.core.control.CommandResult;
import nl.gzmn.playerworlds.core.control.MigratePayload;
import nl.gzmn.playerworlds.core.control.NodeCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link CommandKind#DRAIN_NODE} — taking a node out of service (MN-22).
 *
 * <p>MN-22 is explicit that a drain is <em>not</em> a bulk migration: it "unloads
 * its worlds in place (players to lobby, each with a snapshot commit) rather than
 * live-migrating them", and MN-20 then places each world fresh on the next join.
 * So each world runs the same MN-19 sequence with no destination, and nothing
 * tries to choose one — a drain usually means the pool is about to change shape,
 * and a destination chosen now would be chosen against the wrong pool.
 *
 * <p>The draining flag is set before any world is released, so placement stops
 * choosing this node while the drain is still running. It is set here rather than
 * written to {@code worlds_node} by the proxy because the node's own heartbeat
 * overwrites that column every {@code node.heartbeat-seconds}: the node is
 * authoritative for its own drain state, and a proxy-side write would be undone
 * within one beat.
 *
 * <p>Deregistering from Velocity (MN-22's last step) needs no separate command.
 * The proxy's sweep registers exactly the nodes {@code aliveNodes} returns, and
 * that query already excludes draining ones, so the next sweep removes it.
 *
 * <p>Idempotent (CP-5): a node with nothing loaded completes {@code OK}, which is
 * also the answer for a retry of a drain that already finished.
 */
public final class DrainNodeHandler implements CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(DrainNodeHandler.class);

    private final WorldRegistry registry;
    private final WorldHandoff handoff;
    private final NodeHeartbeat heartbeat;
    private final Supplier<NetworkPolicy> policy;

    public DrainNodeHandler(
            WorldRegistry registry, WorldHandoff handoff, NodeHeartbeat heartbeat, Supplier<NetworkPolicy> policy) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.handoff = Objects.requireNonNull(handoff, "handoff");
        this.heartbeat = Objects.requireNonNull(heartbeat, "heartbeat");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Override
    public CommandResult handle(NodeCommand command) throws Exception {
        Optional<MigratePayload> parsed = MigratePayload.parse(command.payloadJson());
        if (parsed.isEmpty()) {
            return CommandResult.error("unreadable drain payload");
        }
        MigratePayload payload = parsed.get();

        if (payload.resume()) {
            heartbeat.setDraining(false);
            log.info("drain lifted; this node takes new placements again (MN-20)");
            // Published at once rather than on the next beat, so the operator who
            // typed the command sees the node come back in the same breath.
            heartbeat.run();
            return CommandResult.ok();
        }

        heartbeat.setDraining(true);
        heartbeat.run();
        log.info("draining: taking no new placements and releasing {} loaded worlds (MN-22)", registry.size());

        List<LoadedWorld> loaded = List.copyOf(registry.loadedWorlds());
        List<String> failures = new ArrayList<>();
        Duration deadline = HandoffBudget.forCountdown(policy.get(), payload.countdownSeconds());
        if (HandoffBudget.isClamped(policy.get(), payload.countdownSeconds())) {
            // The comment below argues the drain has to fit inside one claim
            // window; until R15 nothing made it, so a long countdown simply ran
            // past the window and a second poller drained the node again.
            log.warn(
                    "drain countdown of {}s does not fit inside control.claim-timeout-seconds; "
                            + "waiting only {} before reporting a failure",
                    payload.countdownSeconds(),
                    deadline);
        }

        // Started together rather than one after another. Every player on the node
        // is being warned about the same maintenance, so they get one countdown
        // rather than nodes.max-worlds of them in series — and the whole drain
        // then fits inside one control.claim-timeout-seconds instead of being
        // reclaimed halfway through by a second poller.
        List<Map.Entry<LoadedWorld, CompletableFuture<WorldHandoff.Outcome>>> running = new ArrayList<>(loaded.size());
        for (LoadedWorld world : loaded) {
            running.add(Map.entry(
                    world,
                    handoff.release(
                            world.id(), payload.countdownSeconds(), "This server is going down for maintenance")));
        }

        long deadlineNanos = System.nanoTime() + deadline.toNanos();
        for (Map.Entry<LoadedWorld, CompletableFuture<WorldHandoff.Outcome>> entry : running) {
            LoadedWorld world = entry.getKey();
            try {
                long remaining = Math.max(1L, deadlineNanos - System.nanoTime());
                WorldHandoff.Outcome outcome = entry.getValue().get(remaining, TimeUnit.NANOSECONDS);
                switch (outcome) {
                    case WorldHandoff.Outcome.NotHeld ignored -> {
                        // Unloaded by the idle sweep or fenced while we worked.
                    }
                    case WorldHandoff.Outcome.Released released ->
                        log.info("drained world {} ({} players moved)", world.id(), released.playersMoved());
                    case WorldHandoff.Outcome.Blocked blocked ->
                        failures.add(world.id() + " blocked on " + blocked.dimension());
                    case WorldHandoff.Outcome.CommitFailed failed ->
                        failures.add(world.id() + " commit failed: " + failed.detail());
                }
            } catch (Exception e) {
                // One world that will not come down must not leave the rest loaded
                // on a node the operator is about to stop.
                log.error("could not drain world {}", world.id(), e);
                failures.add(world.id() + " " + e.getClass().getSimpleName());
            }
        }

        if (failures.isEmpty()) {
            return CommandResult.ok();
        }
        // The node stays draining: it has worlds it could not release, and letting
        // placement send it more would make the operator's problem larger.
        return CommandResult.error(failures.size() + " worlds would not drain: " + String.join("; ", failures));
    }
}
