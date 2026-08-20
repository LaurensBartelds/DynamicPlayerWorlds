package nl.gzmn.playerworlds.core.menu;

import nl.gzmn.playerworlds.core.model.WorldId;
import org.jspecify.annotations.Nullable;

/**
 * Which player world the sender is standing in, told by the node they are on.
 *
 * <p>The proxy routes every entry into a world (FR-10) but cannot see a player
 * move <em>between</em> worlds on a node, and section 6's owner commands are all
 * registered proxy-side. Without this the proxy knows only which node a player
 * is on, which with more than one world per node is not enough to answer "the
 * world I am standing in" — the question every one of those commands asks when
 * the caller names no world.
 *
 * <p>It carries no player identity on purpose. The proxy takes that from the
 * {@code ServerConnection} the message arrived on, because a uuid in a payload
 * is a uuid the sender chose.
 *
 * @param worldId the world the player is in, or {@code null} for anywhere that
 *     is not a player world — the lobby, a node's own level, or a world that has
 *     just been left
 */
public record WorldPresenceNotice(@Nullable WorldId worldId) {}
