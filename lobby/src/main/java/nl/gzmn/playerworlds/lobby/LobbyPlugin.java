package nl.gzmn.playerworlds.lobby;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/**
 * Main entry point for the standalone zero-database lobby menu renderer plugin.
 *
 * <p>Not {@code final}: MockBukkit's plugin loader subclasses the main class.
 */
public class LobbyPlugin extends JavaPlugin implements CommandExecutor {

    private @Nullable LobbyMenuChannel menuChannel;

    @Override
    public void onEnable() {
        this.menuChannel = new LobbyMenuChannel(this);
        this.menuChannel.register();

        getServer().getPluginManager().registerEvents(new LobbyMenuListener(menuChannel), this);

        PluginCommand worldCmd = getCommand("world");
        if (worldCmd != null) {
            worldCmd.setExecutor(this);
        }
        PluginCommand worldsCmd = getCommand("worlds");
        if (worldsCmd != null) {
            worldsCmd.setExecutor(this);
        }

        getLogger().info("gzmn-worlds-lobby enabled (zero-database menu renderer)");
    }

    @Override
    public void onDisable() {
        if (menuChannel != null) {
            menuChannel.unregister();
        }
        getLogger().info("gzmn-worlds-lobby disabled");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player player) {
            menuChannel().sendOpenMenu(player);
        } else {
            sender.sendMessage("This command can only be executed by players.");
        }
        return true;
    }

    /**
     * Returns the active {@link LobbyMenuChannel}.
     *
     * @return the lobby menu channel
     */
    public LobbyMenuChannel menuChannel() {
        if (menuChannel == null) {
            throw new IllegalStateException("LobbyMenuChannel has not been initialized");
        }
        return menuChannel;
    }
}
