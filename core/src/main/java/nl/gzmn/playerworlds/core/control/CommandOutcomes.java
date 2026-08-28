package nl.gzmn.playerworlds.core.control;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import nl.gzmn.playerworlds.core.db.NodeCommandRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lets a producer read back what happened to the command it enqueued (CP-5,
 * CP-6).
 *
 * <p>{@code node_command.result} is written by every handler and, until this
 * existed, read by nothing. That made the three outcomes CP-6 designed
 * specifically to be visible invisible: a {@code /world delete} discarded as
 * {@code STALE_GENERATION} left the world READY, the owner's slot consumed, and
 * the owner told it was archiving.
 *
 * <h2>Waiting for the claim, not for the work</h2>
 *
 * <p>CP-6's failure outcomes are all decided when the row is <em>claimed</em>: a
 * stale generation (CP-4), an unknown kind and a missing handler are settled
 * before any handler runs. The work itself can take minutes — that is what an
 * archive is — so waiting for completion would make every <em>successful</em>
 * archive sit silent for the whole budget and then say what it could have said
 * at once.
 *
 * <p>So the wait ends as soon as the row is claimed, with a short grace for the
 * completion that a refusal writes a database round trip later. A command that
 * is claimed and still running reports that it is running, which is what the
 * player was told before; what changes is that a refusal now says so.
 *
 * <p>A node whose LISTEN connection is down claims on its next
 * {@code control.poll-seconds} instead of on the NOTIFY, which can be after this
 * gives up. The command still runs and still records its result for the operator
 * (ADR 0002); only the immediate report is missed, which is the behaviour every
 * one of these commands had before.
 */
public final class CommandOutcomes {

    private static final Logger log = LoggerFactory.getLogger(CommandOutcomes.class);

    /** How often the row is re-read while waiting. */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    /**
     * How long to wait for some node to claim the row.
     *
     * <p>A NOTIFY reaches a listening node in milliseconds, so this is slack for
     * a busy pool rather than a budget anything is expected to use.
     */
    public static final Duration CLAIM_WAIT = Duration.ofMillis(1500);

    /**
     * How long to keep watching after the claim for the completion a refusal
     * writes.
     *
     * <p>One database round trip separates "claimed" from "completed with
     * STALE_GENERATION"; this is that, with room to spare.
     */
    public static final Duration COMPLETION_GRACE = Duration.ofMillis(500);

    private CommandOutcomes() {}

    /** What a producer learned by waiting. */
    public sealed interface Outcome {

        /** The command was completed, with this in {@code node_command.result}. */
        record Completed(String result) implements Outcome {

            public Completed {
                Objects.requireNonNull(result, "result");
            }

            public boolean isOk() {
                return CommandResult.OK.equals(result);
            }

            /** Player-facing text, without the wire prefixes. */
            public String detail() {
                if (result.startsWith(CommandResult.ERROR_PREFIX)) {
                    return result.substring(CommandResult.ERROR_PREFIX.length());
                }
                if (result.startsWith(CommandResult.UNKNOWN_COMMAND_PREFIX)) {
                    return "this network does not understand that command yet ("
                            + result.substring(CommandResult.UNKNOWN_COMMAND_PREFIX.length()) + ")";
                }
                if (CommandResult.STALE_GENERATION.equals(result)) {
                    return "the world moved on before the command could run; try again";
                }
                if (CommandResult.EXPIRED.equals(result)) {
                    return "no node picked it up in time";
                }
                return result;
            }
        }

        /** Claimed and still working, or not claimed inside {@link #CLAIM_WAIT}. */
        record Running() implements Outcome {}

        /** The row is gone: swept, or its world was deleted underneath it. */
        record Gone() implements Outcome {}
    }

    /**
     * Waits for command {@code id} to be claimed, and for the outcome if one
     * lands with it.
     *
     * <p>Blocking, and expected to run on the database executor — never on a
     * platform main thread (NFR-2). Between polls no connection is held: each
     * read opens and returns one.
     */
    public static Outcome await(NodeCommandRepository commands, long id) {
        Objects.requireNonNull(commands, "commands");

        long claimDeadline = System.nanoTime() + CLAIM_WAIT.toNanos();
        long deadline = claimDeadline;
        boolean claimSeen = false;

        while (true) {
            Optional<NodeCommand> row;
            try {
                row = commands.findById(id);
            } catch (Exception e) {
                // Exception, not SQLException: naming it would put java.sql in
                // core.control, which the ArchUnit rule for CONTRIBUTING rule 3
                // forbids -- database access belongs behind a repository. The
                // command may well still run; this node simply cannot see it.
                log.warn("could not read the outcome of command {}", id, e);
                return new Outcome.Running();
            }
            if (row.isEmpty()) {
                return new Outcome.Gone();
            }
            NodeCommand command = row.get();
            if (command.isCompleted()) {
                String result = command.result();
                return new Outcome.Completed(result == null ? CommandResult.OK : result);
            }
            if (!claimSeen && command.claimedAt() != null) {
                // Somebody has it. Give the refusal its round trip, then let go.
                claimSeen = true;
                deadline = System.nanoTime() + COMPLETION_GRACE.toNanos();
            }
            if (System.nanoTime() >= deadline) {
                return new Outcome.Running();
            }
            try {
                Thread.sleep(POLL_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Outcome.Running();
            }
        }
    }
}
