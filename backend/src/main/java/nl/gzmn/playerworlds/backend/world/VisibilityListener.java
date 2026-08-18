package nl.gzmn.playerworlds.backend.world;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * Isolation between player worlds on one node (FR-18, FR-19, FR-20).
 *
 * <p>The rule FR-19 states, and the one every handler here is an instance of:
 * <em>a broadcast that reaches every player on the server is a defect unless it
 * has been routed through the group filter.</em> The specification is explicit
 * that its own list is "the known cases rather than the complete one", so a new
 * broadcast added anywhere else in this plugin — or a Paper release that adds
 * one — is a new handler here, not an exception.
 *
 * <p>Every message is suppressed globally first and then re-emitted to the
 * group. Suppressing and re-emitting rather than filtering in place is what
 * makes the default safe: a message nobody remembered to route reaches nobody
 * instead of everybody.
 */
public final class VisibilityListener implements Listener {

    private final Plugin plugin;
    private final VisibilityGroups groups;
    private final @Nullable GroupChatBuffer chatBuffer;

    public VisibilityListener(Plugin plugin, VisibilityGroups groups) {
        this(plugin, groups, null);
    }

    public VisibilityListener(Plugin plugin, VisibilityGroups groups, @Nullable GroupChatBuffer chatBuffer) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.groups = Objects.requireNonNull(groups, "groups");
        this.chatBuffer = chatBuffer;
    }

    // -----------------------------------------------------------------------
    // FR-18 — entity and tab-list visibility
    // -----------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        // Suppressed globally and re-emitted to the group (FR-19). A join
        // message is presence and a name, which is exactly what must not cross.
        Component message = event.joinMessage();
        event.joinMessage(null);
        recomputeVisibility(event.getPlayer());
        broadcastToGroup(event.getPlayer(), message);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Component message = event.quitMessage();
        event.quitMessage(null);
        broadcastToGroup(event.getPlayer(), message);
        // No need to un-hide: the player is leaving, and the server drops the
        // per-player visibility state with them.
    }

    /**
     * FR-18: recompute in both directions on every world change.
     *
     * <p>Both directions matter and are not the same call. {@code hidePlayer} is
     * one-way — it controls whether A sees B — so a group change needs the pair
     * updated from each side or the two disagree about whether they can see each
     * other.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        recomputeVisibility(event.getPlayer());
    }

    /**
     * Applies FR-18 between {@code player} and everyone else online.
     *
     * <p>Called on join and on every world change. Cheap by construction: a node
     * holds at most {@code nodes.max-worlds} worlds and a few dozen players, so
     * this is a few dozen comparisons on a transition that already involves a
     * world change.
     */
    public void recomputeVisibility(Player player) {
        for (Player other : plugin.getServer().getOnlinePlayers()) {
            if (other.getUniqueId().equals(player.getUniqueId())) {
                continue;
            }
            if (groups.mutuallyVisible(player, other)) {
                player.showPlayer(plugin, other);
                other.showPlayer(plugin, player);
            } else {
                player.hidePlayer(plugin, other);
                other.hidePlayer(plugin, player);
            }
        }
    }

    // -----------------------------------------------------------------------
    // FR-19 — server-wide broadcasts
    // -----------------------------------------------------------------------

    /**
     * Death messages reach every player on the server by default, which is a
     * name and a location hint crossing groups.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Component message = event.deathMessage();
        event.deathMessage(null);
        broadcastToGroup(event.getEntity(), message);
    }

    /**
     * The {@code announceAdvancements} gamerule broadcasts to every player on the
     * server rather than per world, which FR-19 calls out by name.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        Component message = event.message();
        event.message(null);
        broadcastToGroup(event.getPlayer(), message);
    }

    // -----------------------------------------------------------------------
    // FR-20 — chat
    // -----------------------------------------------------------------------

    /**
     * Scopes chat by mutating the viewer set rather than cancelling and
     * rebroadcasting, exactly as FR-20 requires.
     *
     * <p>The difference matters: cancelling loses the formatting every other chat
     * plugin on the server contributed, and re-sending it ourselves makes this
     * plugin the chat formatter for the whole node, which it has no business
     * being.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onChat(AsyncChatEvent event) {
        Player sender = event.getPlayer();
        if (chatBuffer != null) {
            groups.groupOf(sender).ifPresent(worldId -> {
                String text = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(event.message());
                chatBuffer.record(worldId, sender.getUniqueId(), sender.getName(), text);
            });
        }
        event.viewers().removeIf(viewer -> {
            if (!(viewer instanceof Player recipient)) {
                // The console is not in a group and is not a leak: it is already
                // trusted with everything, and staff need chat to be greppable.
                return false;
            }
            return !recipient.getUniqueId().equals(sender.getUniqueId()) && !groups.mutuallyVisible(sender, recipient);
        });
    }

    /** Sends a suppressed broadcast to the originator's group, and nobody else. */
    private void broadcastToGroup(Player origin, @Nullable Component message) {
        if (message == null) {
            return;
        }
        origin.sendMessage(message);
        for (Player recipient : groups.visibleTo(origin, plugin.getServer().getOnlinePlayers())) {
            recipient.sendMessage(message);
        }
    }
}
