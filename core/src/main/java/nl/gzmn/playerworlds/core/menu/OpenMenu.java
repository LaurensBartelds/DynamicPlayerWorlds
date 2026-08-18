package nl.gzmn.playerworlds.core.menu;

/**
 * Instruction from proxy to backend indicating that a player requested to open the main menu.
 *
 * @param correlationId unique correlation id for matching replies and detecting timeouts
 */
public record OpenMenu(long correlationId) {}
