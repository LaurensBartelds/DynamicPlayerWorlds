package nl.gzmn.playerworlds.core.menu;

/**
 * Instruction from proxy to backend/lobby indicating that a player's menu should be closed.
 *
 * @param correlationId unique correlation id
 */
public record CloseMenuMessage(long correlationId) {}
