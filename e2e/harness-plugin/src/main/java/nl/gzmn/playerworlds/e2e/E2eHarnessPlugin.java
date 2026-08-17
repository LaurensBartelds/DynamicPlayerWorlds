package nl.gzmn.playerworlds.e2e;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/**
 * Test-only Paper plugin for the e2e compose harness (plan section 11 / F11).
 *
 * <p>Not shipped in releases. It exists so the harness can drive and observe a
 * real join over RCON without scraping free-form server logs:
 *
 * <ul>
 *   <li>logs a stable {@code e2e player_joined} / {@code e2e player_quit} line;
 *   <li>writes {@code last-join.txt} under the plugin data folder;
 *   <li>answers {@code /e2e ping} and {@code /e2e status} for RCON probes.
 * </ul>
 *
 * <p>No dependency on {@code :core} or gameplay code — the foundation harness
 * has to stay useful before milestone behaviour exists.
 */
public final class E2eHarnessPlugin extends JavaPlugin implements Listener {

    static final String JOIN_MARKER_PREFIX = "e2e player_joined name=";
    static final String QUIT_MARKER_PREFIX = "e2e player_quit name=";
    static final String ENABLED_MARKER = "e2e-harness enabled";
    static final String LAST_JOIN_FILE = "last-join.txt";

    private @Nullable Path lastJoinFile;

    @Override
    public void onEnable() {
        Path data = getDataFolder().toPath();
        try {
            Files.createDirectories(data);
        } catch (IOException e) {
            getLogger().severe(() -> "could not create data folder: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.lastJoinFile = data.resolve(LAST_JOIN_FILE);
        getServer().getPluginManager().registerEvents(this, this);
        var e2e = getCommand("e2e");
        if (e2e != null) {
            e2e.setExecutor(this);
        }
        // Stable enable line the compose smoke waits on (alongside gzmn-worlds).
        getLogger().info(ENABLED_MARKER);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        World world = player.getWorld();
        String line =
                JOIN_MARKER_PREFIX + player.getName() + " uuid=" + player.getUniqueId() + " world=" + world.getName();
        getLogger().info(line);
        Path marker = lastJoinFile;
        if (marker != null) {
            try {
                Files.writeString(marker, player.getName() + "\n", StandardCharsets.UTF_8);
            } catch (IOException e) {
                getLogger().warning(() -> "could not write " + LAST_JOIN_FILE + ": " + e.getMessage());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent event) {
        getLogger().info(QUIT_MARKER_PREFIX + event.getPlayer().getName());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("e2e")) {
            return false;
        }
        if (args.length == 0) {
            sender.sendMessage("e2e usage: ping | status");
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "ping" -> sender.sendMessage("e2e pong");
            case "status" -> sender.sendMessage(statusLine());
            default -> sender.sendMessage("e2e usage: ping | status");
        }
        return true;
    }

    private static String statusLine() {
        List<Player> online = List.copyOf(Bukkit.getOnlinePlayers());
        String names = online.stream().map(Player::getName).sorted().collect(Collectors.joining(","));
        return "e2e status online="
                + online.size()
                + " players="
                + names
                + " worlds="
                + Bukkit.getWorlds().stream().map(World::getName).collect(Collectors.joining(","));
    }
}
