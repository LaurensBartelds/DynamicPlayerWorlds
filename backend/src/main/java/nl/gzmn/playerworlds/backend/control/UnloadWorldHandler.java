package nl.gzmn.playerworlds.backend.control;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.control.CommandHandler;
import nl.gzmn.playerworlds.core.control.CommandKind;
import nl.gzmn.playerworlds.core.control.CommandResult;
import nl.gzmn.playerworlds.core.control.NodeCommand;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link CommandKind#UNLOAD_WORLD} on a node — {@code /world admin unload}, and
 * the unload {@code /world delete} sends to whichever node is holding the world.
 *
 * <p>Runs MN-19's sequence with no destination, exactly as the idle sweep and the
 * drain do: warn nobody, move the players out, commit a final snapshot, unload,
 * release. The commit is not optional. MN-5 and FR-25 both order it before the
 * unload, and an administrative unload has more reason to want it than the idle
 * one does — it is the operator taking a world away from players who were in it,
 * with whatever they were carrying (FR-15).
 *
 * <p>Idempotent (CP-5): if the world is not loaded here, completes {@code OK}.
 */
public final class UnloadWorldHandler implements CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(UnloadWorldHandler.class);

    private final @Nullable WorldHandoff handoff;
    private final Supplier<NetworkPolicy> policy;

    /**
     * @param handoff {@code null} on a node wired without a lifecycle service, in
     *     which case the command completes as a no-op rather than pretending
     */
    public UnloadWorldHandler(@Nullable WorldHandoff handoff, Supplier<NetworkPolicy> policy) {
        this.handoff = handoff;
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Override
    public CommandResult handle(NodeCommand command) throws Exception {
        WorldId worldId = command.worldId();
        if (worldId == null) {
            return CommandResult.error("missing world_id");
        }
        WorldHandoff sequence = handoff;
        if (sequence == null) {
            return CommandResult.ok();
        }

        NetworkPolicy current = policy.get();
        WorldHandoff.Outcome outcome = sequence.release(worldId, 0, "This world is being unloaded")
                .get(current.commitTimeout().plus(Duration.ofSeconds(5)).toMillis(), TimeUnit.MILLISECONDS);

        return switch (outcome) {
            case WorldHandoff.Outcome.NotHeld ignored -> CommandResult.ok();
            case WorldHandoff.Outcome.Released released -> {
                log.info("unloaded world {} on request ({} players moved)", worldId, released.playersMoved());
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
