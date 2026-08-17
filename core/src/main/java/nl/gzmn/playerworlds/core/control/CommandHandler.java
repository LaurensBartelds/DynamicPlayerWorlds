package nl.gzmn.playerworlds.core.control;

/**
 * Handles one claimed control-plane command.
 *
 * <p>Handlers <strong>must be idempotent</strong> (CP-5, CONTRIBUTING.md rule
 * 7). A claimed-but-uncompleted row is retried after
 * {@code control.claim-timeout-seconds}, so the same instruction can land twice.
 * Completing the row is the control plane's job; the handler only acts and
 * returns a {@link CommandResult}.
 */
@FunctionalInterface
public interface CommandHandler {

    /**
     * Acts on state already committed in the tables of specification section 4.
     * Must not carry new authoritative state in the command payload (section 13
     * closing rule).
     *
     * @throws Exception turned into {@link CommandResult#error(String)} and the
     *     row is still completed, so an unknown failure does not stall the queue
     */
    CommandResult handle(NodeCommand command) throws Exception;
}
