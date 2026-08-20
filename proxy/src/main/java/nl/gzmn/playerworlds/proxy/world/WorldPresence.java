package nl.gzmn.playerworlds.proxy.world;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.jspecify.annotations.Nullable;

/**
 * Which player world each online player is standing in, as reported by the node
 * they are on.
 *
 * <p>The proxy routes every entry into a world (FR-10) and could have recorded
 * it there, but that record goes stale the moment a player moves between worlds
 * on the node — which a portal (FR-3a) and the backend's own commands both do
 * without the proxy hearing about it. The node reports instead, and this holds
 * what it said.
 *
 * <p>Nothing here is authoritative and nothing may be trusted for access
 * control: it says where a player is, not what they may do. Every caller
 * re-reads the world and re-checks ownership against {@code player_world}
 * (FR-31a).
 */
public final class WorldPresence {

    /** The world, and the node that vouched for it. */
    private record Entry(String node, WorldId world) {}

    private final ConcurrentMap<UUID, Entry> current = new ConcurrentHashMap<>();

    /**
     * Records where a node says one of its players is.
     *
     * @param player the player, taken from the connection the report arrived on
     * @param node the reporting node, so a later switch to the lobby cannot be
     *     answered with a world the player has left
     * @param world the world they are in, or {@code null} for anywhere that is
     *     not a player world
     */
    public void entered(UUID player, String node, @Nullable WorldId world) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(node, "node");
        if (world == null) {
            current.remove(player);
            return;
        }
        current.put(player, new Entry(node, world));
    }

    /** Drops a player's entry, on disconnect. */
    public void forget(UUID player) {
        Objects.requireNonNull(player, "player");
        current.remove(player);
    }

    /**
     * The world this player is standing in, if a node has said so and they are
     * still on that node.
     *
     * <p>The node check is what makes a missed report safe. The lobby runs no
     * worlds and reports nothing, so without it a player who walked out of a
     * world would keep answering with the world they left.
     */
    public Optional<WorldId> worldOf(Player player) {
        Objects.requireNonNull(player, "player");
        Entry entry = current.get(player.getUniqueId());
        if (entry == null) {
            return Optional.empty();
        }
        Optional<ServerConnection> connection = player.getCurrentServer();
        if (connection.isEmpty() || !connection.get().getServerInfo().getName().equals(entry.node())) {
            return Optional.empty();
        }
        return Optional.of(entry.world());
    }

    /** How many players are recorded, for the enable log and for tests. */
    public int size() {
        return current.size();
    }
}
