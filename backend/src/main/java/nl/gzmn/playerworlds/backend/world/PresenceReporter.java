package nl.gzmn.playerworlds.backend.world;

import java.util.Objects;
import java.util.Optional;
import nl.gzmn.playerworlds.backend.gui.MenuChannel;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Tells the proxy which player world each player on this node is standing in.
 *
 * <p>Section 6 registers the owner commands on the proxy, and the proxy knows
 * only which <em>node</em> a player is connected to. One node holds many worlds
 * (MN-15), so "the world I am standing in" — the answer {@code /world invite}
 * and its siblings need when the caller names no world — is a question only the
 * node can answer.
 *
 * <p>Reported on join and on every world change, which between them cover every
 * way a player arrives in or leaves a world: the routed join (FR-11), a portal
 * between a world's own dimensions (FR-3a), and the teleport out on leave. A
 * disconnect needs no message, because the proxy drops the entry when the
 * connection goes.
 *
 * <p>Sending is cheap and unconditional rather than filtered to owners: this
 * class cannot see ownership without a database round trip on the main thread,
 * and the proxy is where the check belongs anyway.
 */
public final class PresenceReporter implements Listener {

    private final WorldFolders folders;
    private final MenuChannel channel;

    public PresenceReporter(WorldFolders folders, MenuChannel channel) {
        this.folders = Objects.requireNonNull(folders, "folders");
        this.channel = Objects.requireNonNull(channel, "channel");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        report(event.getPlayer());
    }

    /**
     * The three dimensions of one world are one world (FR-2), so a portal within
     * it re-reports the same id rather than nothing.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        report(event.getPlayer());
    }

    private void report(Player player) {
        Optional<WorldId> here = folders.worldIdOf(player.getWorld().getName());
        channel.sendPresence(player, here.orElse(null));
    }
}
