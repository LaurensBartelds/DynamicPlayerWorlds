package nl.gzmn.playerworlds.backend.command;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import nl.gzmn.playerworlds.backend.world.GroupChatBuffer;
import nl.gzmn.playerworlds.backend.world.WorldFolders;
import nl.gzmn.playerworlds.backend.world.WorldFolders.PlayerWorldDimension;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.control.CommandKind;
import nl.gzmn.playerworlds.core.control.ControlChannels;
import nl.gzmn.playerworlds.core.control.EjectPayload;
import nl.gzmn.playerworlds.core.db.NodeCommandRepository;
import nl.gzmn.playerworlds.core.db.PlayerNameRepository;
import nl.gzmn.playerworlds.core.db.ReportRepository;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Backend executor for {@code /world} subcommands forwarded by the proxy (OQ-15).
 *
 * <p>Handles {@code /world leave} and {@code /world report <player> <reason>}.
 */
public final class BackendWorldCommand implements CommandExecutor, TabCompleter {

    private static final Logger log = LoggerFactory.getLogger(BackendWorldCommand.class);

    private final WorldFolders folders;
    private final ReportRepository reports;
    private final PlayerNameRepository names;
    private final GroupChatBuffer chatBuffer;
    private final NodeCommandRepository nodeCommands;
    private final PluginExecutors executors;
    private final Supplier<NetworkPolicy> policy;

    public BackendWorldCommand(
            WorldFolders folders,
            ReportRepository reports,
            PlayerNameRepository names,
            GroupChatBuffer chatBuffer,
            NodeCommandRepository nodeCommands,
            PluginExecutors executors,
            Supplier<NetworkPolicy> policy) {
        this.folders = Objects.requireNonNull(folders, "folders");
        this.reports = Objects.requireNonNull(reports, "reports");
        this.names = Objects.requireNonNull(names, "names");
        this.chatBuffer = Objects.requireNonNull(chatBuffer, "chatBuffer");
        this.nodeCommands = Objects.requireNonNull(nodeCommands, "nodeCommands");
        this.executors = Objects.requireNonNull(executors, "executors");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can execute /world on the backend.", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) {
            player.sendMessage(Component.text("Usage: /world <leave|report <player> <reason>>", NamedTextColor.RED));
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "leave" -> handleLeave(player);
            case "report" -> handleReport(player, args);
            default ->
                player.sendMessage(
                        Component.text("Unknown backend subcommand. Usage: /world <leave|report>", NamedTextColor.RED));
        }
        return true;
    }

    private void handleLeave(Player player) {
        World currentWorld = player.getWorld();
        Optional<PlayerWorldDimension> resolved = folders.resolve(currentWorld.getName());
        WorldId worldId = resolved.map(PlayerWorldDimension::worldId).orElse(null);

        player.sendMessage(Component.text("Returning to lobby...", NamedTextColor.YELLOW));

        World fallbackWorld = null;
        for (World w : Bukkit.getWorlds()) {
            if (!folders.isPlayerWorld(w.getName())) {
                fallbackWorld = w;
                break;
            }
        }
        if (currentWorld != null && folders.isPlayerWorld(currentWorld.getName()) && fallbackWorld != null) {
            player.teleport(fallbackWorld.getSpawnLocation());
        }

        executors.db().execute(() -> {
            try {
                nodeCommands.enqueue(
                        "proxy",
                        worldId,
                        null,
                        CommandKind.EJECT_PLAYER.name(),
                        EjectPayload.format(player.getUniqueId(), "Left world"),
                        policy.get().holdingTimeout(),
                        ControlChannels.PROXY);
            } catch (SQLException e) {
                log.warn("could not enqueue EJECT_PLAYER for {}", player.getUniqueId(), e);
            }
        });
    }

    private void handleReport(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(Component.text("Usage: /world report <player> <reason>", NamedTextColor.RED));
            return;
        }

        World currentWorld = player.getWorld();
        Optional<PlayerWorldDimension> resolved = folders.resolve(currentWorld.getName());
        if (resolved.isEmpty()) {
            player.sendMessage(
                    Component.text("You must be inside a player world to file a report.", NamedTextColor.RED));
            return;
        }

        WorldId worldId = resolved.get().worldId();
        String targetName = args[1];
        StringBuilder reasonBuilder = new StringBuilder();
        for (int i = 2; i < args.length; i++) {
            if (i > 2) {
                reasonBuilder.append(" ");
            }
            reasonBuilder.append(args[i]);
        }
        String reason = reasonBuilder.toString();

        String chatLog = chatBuffer.snapshotJson(worldId);

        executors.db().execute(() -> {
            try {
                Optional<UUID> targetUuid = names.uuidOf(targetName);
                if (targetUuid.isEmpty()) {
                    Player online = Bukkit.getPlayer(targetName);
                    if (online != null) {
                        targetUuid = Optional.of(online.getUniqueId());
                    }
                }

                if (targetUuid.isEmpty()) {
                    player.sendMessage(
                            Component.text("No player named '" + targetName + "' was found.", NamedTextColor.RED));
                    return;
                }

                reports.createReport(worldId, player.getUniqueId(), targetUuid.get(), reason, chatLog);
                player.sendMessage(Component.text(
                        "Your report has been submitted to staff for review. Thank you.", NamedTextColor.GREEN));
            } catch (Exception e) {
                log.warn("could not save player report", e);
                player.sendMessage(Component.text(
                        "An error occurred while filing your report. Please try again later.", NamedTextColor.RED));
            }
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> matches = new ArrayList<>();
            for (String sub : List.of("leave", "report")) {
                if (sub.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    matches.add(sub);
                }
            }
            return List.copyOf(matches);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("report")) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    names.add(p.getName());
                }
            }
            return List.copyOf(names);
        }
        return List.of();
    }
}
