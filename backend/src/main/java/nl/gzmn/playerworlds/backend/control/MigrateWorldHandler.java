package nl.gzmn.playerworlds.backend.control;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import nl.gzmn.playerworlds.core.config.HandoffBudget;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.control.CommandHandler;
import nl.gzmn.playerworlds.core.control.CommandKind;
import nl.gzmn.playerworlds.core.control.CommandResult;
import nl.gzmn.playerworlds.core.control.MigratePayload;
import nl.gzmn.playerworlds.core.control.NodeCommand;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link CommandKind#MIGRATE_WORLD} on the node currently holding the world
 * (MN-19, MN-21).
 *
 * <p>This node does the giving-up half only: warn, eject, commit, unload,
 * release. It never contacts the destination. MN-8 permits exactly one holder,
 * so the target's lease can only be acquired after this node's is released, and
 * the proxy — which issued the command and is watching for its completion — is
 * the one place that can see both halves happen in order.
 *
 * <p>Idempotent (CP-5). A world this node is not holding completes {@code OK},
 * which is also the answer for a retry of a migration that already ran.
 */
public final class MigrateWorldHandler implements CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(MigrateWorldHandler.class);

    private final WorldHandoff handoff;
    private final Supplier<NetworkPolicy> policy;

    public MigrateWorldHandler(WorldHandoff handoff, Supplier<NetworkPolicy> policy) {
        this.handoff = Objects.requireNonNull(handoff, "handoff");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Override
    public CommandResult handle(NodeCommand command) throws Exception {
        WorldId worldId = command.worldId();
        if (worldId == null) {
            return CommandResult.error("missing world_id");
        }
        Optional<MigratePayload> parsed = MigratePayload.parse(command.payloadJson());
        if (parsed.isEmpty()) {
            // Refused rather than defaulted: a migration run against an unreadable
            // payload would move the world somewhere nobody asked for.
            return CommandResult.error("unreadable migrate payload");
        }
        MigratePayload payload = parsed.get();
        String destination = payload.targetNode();

        String reason = destination == null
                ? "This world is being moved to another server"
                : "This world is moving to " + destination;

        log.info(
                "migrating world {} to {} with a {}s countdown (MN-19, MN-21)",
                worldId,
                destination == null ? "wherever it is next placed" : destination,
                payload.countdownSeconds());

        Duration budget = HandoffBudget.forCountdown(policy.get(), payload.countdownSeconds());
        if (HandoffBudget.isClamped(policy.get(), payload.countdownSeconds())) {
            log.warn(
                    "migrate countdown of {}s does not fit inside control.claim-timeout-seconds; "
                            + "waiting only {} before reporting a failure",
                    payload.countdownSeconds(),
                    budget);
        }
        WorldHandoff.Outcome outcome = handoff.release(worldId, payload.countdownSeconds(), reason)
                .get(budget.toMillis(), TimeUnit.MILLISECONDS);

        return switch (outcome) {
            case WorldHandoff.Outcome.NotHeld ignored -> CommandResult.ok();
            case WorldHandoff.Outcome.Released released -> {
                log.info("world {} released after moving {} players out (MN-19)", worldId, released.playersMoved());
                yield CommandResult.ok();
            }
            case WorldHandoff.Outcome.Blocked blocked ->
                CommandResult.error(
                        "unload blocked on " + blocked.dimension() + ": " + String.join(", ", blocked.blockers()));
            case WorldHandoff.Outcome.CommitFailed failed ->
                CommandResult.error("final snapshot commit failed: " + failed.detail());
        };
    }
}
