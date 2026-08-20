package nl.gzmn.playerworlds.core.menu;

/**
 * Notification from backend/lobby to proxy indicating that a player closed their menu.
 *
 * @param correlationId unique correlation id
 */
public record MenuClosedNotice(long correlationId) {}
