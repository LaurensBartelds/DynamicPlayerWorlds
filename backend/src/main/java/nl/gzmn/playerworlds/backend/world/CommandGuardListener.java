package nl.gzmn.playerworlds.backend.world;

import com.destroystokyo.paper.event.server.AsyncTabCompleteEvent;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;

/**
 * Command access inside a player world (FR-21, FR-22).
 *
 * <p>An allow-list, not a deny-list, and FR-22 is unusually specific about why:
 * a list of known offenders drifts the moment a plugin is added, and it misses
 * the surfaces that ship with the server — vanilla {@code /list} and
 * {@code /tell}, and target selectors for anyone holding the permission. So
 * everything is denied and {@code worlds.allowed-commands} names the exceptions.
 *
 * <p>Only applies inside player worlds. The lobby and every other world on the
 * node behave as they would without this plugin.
 */
public final class CommandGuardListener implements Listener {

    /**
     * Roots this plugin owns, always permitted (plan 03, D11).
     *
     * <p>{@code worlds.allowed-commands} defaults to empty, and taken literally
     * that denies the command a player would use to leave — so entering a world
     * would trap them in it until they disconnected. The allow-list governs
     * vanilla and third-party commands; these two are the exit, and they leak
     * nothing, because every subcommand is already scoped to the caller.
     */
    private static final Set<String> ALWAYS_ALLOWED = Set.of("world", "pworld");

    /** FR-22's exemption. */
    private static final String ADMIN_PERMISSION = "gzmn.worlds.admin";

    private final VisibilityGroups groups;
    private final Supplier<NetworkPolicy> policy;

    public CommandGuardListener(VisibilityGroups groups, Supplier<NetworkPolicy> policy) {
        this.groups = Objects.requireNonNull(groups, "groups");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /** FR-22: deny anything not on the allow-list, inside a player world. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (isPermitted(player, rootOf(event.getMessage()))) {
            return;
        }
        event.setCancelled(true);
        player.sendMessage(Component.text("That command is not available inside a player world.", NamedTextColor.RED));
    }

    /**
     * FR-21: hide commands the player may not use, so tab completion never offers
     * them.
     *
     * <p>Hiding matters beyond tidiness. An offered command that then refuses is
     * itself a signal, and the commands being hidden are the presence-revealing
     * ones — a player learning that {@code /list} exists here has learned there is
     * something to list.
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onCommandSend(PlayerCommandSendEvent event) {
        Player player = event.getPlayer();
        if (groups.groupOf(player).isEmpty() || player.hasPermission(ADMIN_PERMISSION)) {
            return;
        }
        List<String> allowed = policy.get().allowedCommands();
        event.getCommands().removeIf(command -> !isAllowedName(command, allowed));
    }

    /**
     * FR-21: filter name completion inside a player world.
     *
     * <p>Runs off the main thread by design — that is what the async event is for
     * — and touches nothing but the completion list.
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onTabComplete(AsyncTabCompleteEvent event) {
        if (!(event.getSender() instanceof Player player)) {
            return;
        }
        if (groups.groupOf(player).isEmpty() || player.hasPermission(ADMIN_PERMISSION)) {
            return;
        }
        // Anything that completes to the name of a player this one cannot see is
        // a leak whether or not the command it belongs to is allowed.
        Set<String> visible = java.util.HashSet.newHashSet(8);
        for (Player other : groups.visibleTo(player, player.getServer().getOnlinePlayers())) {
            visible.add(other.getName().toLowerCase(Locale.ROOT));
        }
        visible.add(player.getName().toLowerCase(Locale.ROOT));

        event.completions().removeIf(completion -> {
            String suggestion = completion.suggestion().toLowerCase(Locale.ROOT);
            for (Player online : player.getServer().getOnlinePlayers()) {
                if (suggestion.equals(online.getName().toLowerCase(Locale.ROOT)) && !visible.contains(suggestion)) {
                    return true;
                }
            }
            return false;
        });
    }

    /** Whether this player may run this command root where they are standing. */
    boolean isPermitted(Player player, String root) {
        if (groups.groupOf(player).isEmpty()) {
            // Not in a player world: none of this plugin's business.
            return true;
        }
        if (player.hasPermission(ADMIN_PERMISSION)) {
            return true;
        }
        return isAllowedName(root, policy.get().allowedCommands());
    }

    /**
     * The command root of a typed line, lowercased and stripped of any
     * {@code plugin:} prefix.
     *
     * <p>The prefix matters: {@code /minecraft:list} and {@code /essentials:list}
     * reach the same command as {@code /list}, and an allow-list that only knew
     * the bare name would be walked straight around.
     */
    static String rootOf(String message) {
        Objects.requireNonNull(message, "message");
        String line = message.startsWith("/") ? message.substring(1) : message;
        int space = line.indexOf(' ');
        String root = space < 0 ? line : line.substring(0, space);
        int colon = root.indexOf(':');
        if (colon >= 0) {
            root = root.substring(colon + 1);
        }
        return root.toLowerCase(Locale.ROOT);
    }

    private static boolean isAllowedName(String command, List<String> allowed) {
        String root = rootOf(command);
        if (ALWAYS_ALLOWED.contains(root)) {
            return true;
        }
        for (String permitted : allowed) {
            if (rootOf(permitted).equals(root)) {
                return true;
            }
        }
        return false;
    }
}
