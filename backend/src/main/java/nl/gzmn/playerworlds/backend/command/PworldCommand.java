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
import nl.gzmn.playerworlds.backend.platform.DimensionKind;
import nl.gzmn.playerworlds.backend.world.CreateOutcome;
import nl.gzmn.playerworlds.backend.world.LoadOutcome;
import nl.gzmn.playerworlds.backend.world.LoadedWorld;
import nl.gzmn.playerworlds.backend.world.WorldFolders;
import nl.gzmn.playerworlds.backend.world.WorldLifecycleService;
import nl.gzmn.playerworlds.backend.world.WorldRegistry;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.control.CommandKind;
import nl.gzmn.playerworlds.core.control.ControlChannels;
import nl.gzmn.playerworlds.core.control.EjectPayload;
import nl.gzmn.playerworlds.core.db.MembershipRepository;
import nl.gzmn.playerworlds.core.db.NodeCommandRepository;
import nl.gzmn.playerworlds.core.db.PlayerNameRepository;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code /pworld} — the backend-side developer and operator surface for
 * milestone 1.
 *
 * <p>Deliberately <em>not</em> {@code /world}. Specification section 6 registers
 * the player-facing commands on the proxy, and a Velocity plugin claiming
 * {@code /world} takes the whole namespace (OQ-15). Registering {@code /world}
 * here would have to be torn back to two subcommands when milestone 5 lands,
 * which is exactly the ambiguity OQ-15 exists to avoid. This root never
 * collides, and it stays afterwards as the operator's way to drive a node
 * directly.
 *
 * <p>Gated behind {@code gzmn.worlds.dev}, which is not granted by default.
 *
 * <p>Every path here is asynchronous. Commands run on the tick thread and NFR-2
 * forbids database access there, so each subcommand hands off immediately and
 * replies from the completion.
 */
public final class PworldCommand implements CommandExecutor, TabCompleter {

    private static final Logger log = LoggerFactory.getLogger(PworldCommand.class);

    private static final List<String> SUBCOMMANDS = List.of("create", "list", "tp", "unload", "info", "leave");

    private final WorldLifecycleService lifecycle;
    private final WorldRegistry registry;
    private final WorldFolders folders;
    private final PlayerWorldRepository worlds;
    private final MembershipRepository membership;
    private final PlayerNameRepository names;
    private final PluginExecutors executors;
    private final NodeCommandRepository nodeCommands;
    private final Supplier<NetworkPolicy> policy;

    public PworldCommand(
            WorldLifecycleService lifecycle,
            WorldRegistry registry,
            WorldFolders folders,
            PlayerWorldRepository worlds,
            MembershipRepository membership,
            PlayerNameRepository names,
            PluginExecutors executors,
            NodeCommandRepository nodeCommands,
            Supplier<NetworkPolicy> policy) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.folders = Objects.requireNonNull(folders, "folders");
        this.worlds = Objects.requireNonNull(worlds, "worlds");
        this.membership = Objects.requireNonNull(membership, "membership");
        this.names = Objects.requireNonNull(names, "names");
        this.executors = Objects.requireNonNull(executors, "executors");
        this.nodeCommands = Objects.requireNonNull(nodeCommands, "nodeCommands");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            usage(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create" -> create(sender, args);
            case "list" -> list(sender);
            case "tp" -> teleport(sender, args);
            case "unload" -> unload(sender, args);
            case "info" -> info(sender);
            case "leave" -> leave(sender);
            default -> usage(sender);
        }
        return true;
    }

    // -----------------------------------------------------------------------
    // Subcommands
    // -----------------------------------------------------------------------

    /** {@code /pworld create <name> [seed]} — FR-1, FR-2, FR-4. */
    private void create(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        if (args.length < 2) {
            error(sender, "usage: /pworld create <name> [seed]");
            return;
        }
        String name = args[1];
        Long seed = null;
        if (args.length >= 3) {
            try {
                seed = Long.parseLong(args[2]);
            } catch (NumberFormatException e) {
                // Vanilla treats a non-numeric seed as text and hashes it; matching
                // that means "/pworld create home mySeed" does what a player expects.
                seed = (long) args[2].hashCode();
            }
        }

        UUID owner = player.getUniqueId();
        info(sender, "creating world '" + name + "'...");
        // The future is consumed by the callback; nothing waits on it, because a
        // command handler must return to the tick thread immediately (NFR-2).
        var _ = lifecycle
                .create(owner, name, seed)
                .whenCompleteAsync(
                        (outcome, failure) -> {
                            if (failure != null) {
                                log.error("/pworld create failed", failure);
                                error(sender, "world creation failed; see the server log");
                                return;
                            }
                            switch (outcome) {
                                case CreateOutcome.Created created -> {
                                    success(
                                            sender,
                                            "created '" + created.row().name() + "' ("
                                                    + created.row().id() + ")");
                                    // FR-5, teleport half. The "fresh profile" half needs the
                                    // per-world profile store from milestone 4; clearing an
                                    // inventory before there is somewhere to have saved the
                                    // old one is unrecoverable item loss (plan 01 §5.2).
                                    teleportToSpawn(player, created.world());
                                }
                                case CreateOutcome.CapReached cap ->
                                    error(
                                            sender,
                                            "you already own " + cap.owned() + " worlds (limit " + cap.cap()
                                                    + "); delete one first");
                                case CreateOutcome.NameTaken taken ->
                                    error(sender, "you already own a world called '" + taken.name() + "'");
                                case CreateOutcome.NodeFull full ->
                                    error(
                                            sender,
                                            "this node is holding " + full.loaded() + " worlds (limit " + full.cap()
                                                    + ")");
                                case CreateOutcome.Failed reason -> error(sender, reason.reason());
                            }
                        },
                        executors.main());
    }

    /** {@code /pworld list} — every world this player owns, and whether it is loaded here. */
    private void list(CommandSender sender) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        UUID owner = player.getUniqueId();
        executors.db().execute(() -> {
            final List<PlayerWorld> owned;
            try {
                owned = worlds.listOwnedBy(owner);
            } catch (SQLException e) {
                log.error("/pworld list failed", e);
                onMain(() -> error(sender, "could not read your worlds; see the server log"));
                return;
            }
            onMain(() -> {
                if (owned.isEmpty()) {
                    info(sender, "you own no worlds; /pworld create <name>");
                    return;
                }
                info(sender, "worlds you own:");
                for (PlayerWorld world : owned) {
                    String loaded = registry.isLoaded(world.id()) ? "loaded" : "unloaded";
                    Optional<LoadedWorld> live = registry.find(world.id());
                    String dimensions =
                            live.map(l -> l.materialised().toString()).orElse("[]");
                    info(
                            sender,
                            "  " + world.name() + "  " + world.state() + "  " + loaded + "  " + dimensions + "  seed "
                                    + world.seed());
                }
            });
        });
    }

    /** {@code /pworld tp <name>} — load if needed, then teleport to the overworld spawn. */
    private void teleport(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        if (args.length < 2) {
            error(sender, "usage: /pworld tp <name>  |  /pworld tp <owner> <name>");
            return;
        }
        // Two arguments means somebody else's world, which is what makes FR-9's
        // roles testable on a single server: without a way in, a VISITOR never
        // exists anywhere but in the database.
        if (args.length >= 3) {
            teleportToOthersWorld(sender, player, args[1], args[2]);
            return;
        }
        String name = args[1];
        UUID owner = player.getUniqueId();

        executors.db().execute(() -> {
            final Optional<PlayerWorld> found;
            try {
                found = worlds.findByOwnerAndName(owner, name);
            } catch (SQLException e) {
                log.error("/pworld tp lookup failed", e);
                onMain(() -> error(sender, "could not read your worlds; see the server log"));
                return;
            }
            if (found.isEmpty()) {
                onMain(() -> error(sender, "you own no world called '" + name + "'"));
                return;
            }
            var _ = lifecycle
                    .load(found.get().id())
                    .whenCompleteAsync(
                            (outcome, failure) -> {
                                if (failure != null) {
                                    log.error("/pworld tp load failed", failure);
                                    error(sender, "could not load that world; see the server log");
                                    return;
                                }
                                switch (outcome) {
                                    case LoadOutcome.Loaded loaded -> teleportToSpawn(player, loaded.world());
                                    case LoadOutcome.NotFound ignored -> error(sender, "that world no longer exists");
                                    case LoadOutcome.WrongState state ->
                                        error(
                                                sender,
                                                "that world is " + state.state() + " and cannot be loaded right now");
                                    case LoadOutcome.TooNew tooNew ->
                                        error(
                                                sender,
                                                "that world needs a newer server version (world data version "
                                                        + tooNew.worldDataVersion() + ", this node "
                                                        + tooNew.nodeDataVersion()
                                                        + ")");
                                    case LoadOutcome.NodeFull full ->
                                        error(
                                                sender,
                                                "this node is holding " + full.loaded() + " worlds (limit " + full.cap()
                                                        + ")");
                                    case LoadOutcome.Failed reason -> error(sender, reason.reason());
                                }
                            },
                            executors.main());
        });
    }

    /**
     * {@code /pworld tp <owner> <name>} — enter a world somebody else owns.
     *
     * <p>Membership is checked here rather than assumed, because this is the only
     * route into another player's world until the proxy's {@code /world join}
     * lands with the transfer path (milestone 5). A non-member is refused with
     * the same message as a world that does not exist: telling a stranger that a
     * private world exists is the leak specification section 5.5 exists to
     * prevent.
     */
    private void teleportToOthersWorld(CommandSender sender, Player player, String ownerName, String worldName) {
        executors.db().execute(() -> {
            final Optional<PlayerWorld> found;
            try {
                Optional<UUID> owner = names.uuidOf(ownerName);
                if (owner.isEmpty()) {
                    onMain(() -> error(sender, "no world called '" + worldName + "' that you can enter"));
                    return;
                }
                found = worlds.findByOwnerAndName(owner.get(), worldName);
                if (found.isEmpty()
                        || membership
                                .findMember(found.get().id(), player.getUniqueId())
                                .isEmpty()) {
                    onMain(() -> error(sender, "no world called '" + worldName + "' that you can enter"));
                    return;
                }
                membership.markJoined(found.get().id(), player.getUniqueId());
            } catch (SQLException e) {
                log.error("/pworld tp lookup failed", e);
                onMain(() -> error(sender, "could not read that world; see the server log"));
                return;
            }
            var _ = lifecycle
                    .load(found.get().id())
                    .whenCompleteAsync(
                            (outcome, failure) -> {
                                if (failure != null) {
                                    log.error("/pworld tp load failed", failure);
                                    error(sender, "could not load that world; see the server log");
                                    return;
                                }
                                if (outcome instanceof LoadOutcome.Loaded loaded) {
                                    teleportToSpawn(player, loaded.world());
                                } else {
                                    error(sender, "that world could not be loaded right now");
                                }
                            },
                            executors.main());
        });
    }

    /**
     * {@code /pworld unload <name>} — unload now, ignoring the FR-25 grace period.
     *
     * <p>Follows the same FR-25a ordering and the same all-or-nothing rule as the
     * idle sweep: a world that will not come down completely stays up, rather than
     * being left with a split visibility group.
     */
    private void unload(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        if (args.length < 2) {
            error(sender, "usage: /pworld unload <name>");
            return;
        }
        String name = args[1];
        UUID owner = player.getUniqueId();

        executors.db().execute(() -> {
            final Optional<PlayerWorld> found;
            try {
                found = worlds.findByOwnerAndName(owner, name);
            } catch (SQLException e) {
                log.error("/pworld unload lookup failed", e);
                onMain(() -> error(sender, "could not read your worlds; see the server log"));
                return;
            }
            if (found.isEmpty()) {
                onMain(() -> error(sender, "you own no world called '" + name + "'"));
                return;
            }
            Optional<LoadedWorld> live = registry.find(found.get().id());
            if (live.isEmpty()) {
                onMain(() -> info(sender, "'" + name + "' is not loaded on this node"));
                return;
            }
            onMain(() -> {
                switch (lifecycle.unloadOnMain(live.get())) {
                    case nl.gzmn.playerworlds.backend.world.UnloadOutcome.Complete complete -> {
                        lifecycle.afterUnload(live.get());
                        success(sender, "unloaded '" + name + "' " + complete.unloaded());
                    }
                    case nl.gzmn.playerworlds.backend.world.UnloadOutcome.Blocked blocked ->
                        error(
                                sender,
                                "'" + name + "' would not unload at " + blocked.dimension() + ": "
                                        + (blocked.blockers().isEmpty()
                                                ? "no determinable cause"
                                                : String.join("; ", blocked.blockers())));
                }
            });
        });
    }

    /** {@code /pworld info} — what the sender is standing in. */
    private void info(CommandSender sender) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        String bukkitName = player.getWorld().getName();
        Optional<WorldFolders.PlayerWorldDimension> resolved = folders.resolve(bukkitName);
        if (resolved.isEmpty()) {
            info(sender, bukkitName + " is not a player world");
            return;
        }
        WorldFolders.PlayerWorldDimension here = resolved.get();
        Optional<LoadedWorld> live = registry.find(here.worldId());
        if (live.isEmpty()) {
            info(sender, "world " + here.worldId() + " (" + here.dimension() + ") is not registered on this node");
            return;
        }
        LoadedWorld world = live.get();
        info(sender, "world '" + world.name() + "' (" + world.id() + ")");
        info(sender, "  dimension " + here.dimension() + ", materialised " + world.materialised());
        info(sender, "  seed " + world.seed() + ", border radius " + world.borderRadius());
        info(sender, "  idle sweeps " + world.idleSweeps() + ", retry wait " + world.retryWaitSweeps());
    }

    /**
     * {@code /pworld leave} — return leg to lobby (FR-11, FR-12).
     */
    private void leave(CommandSender sender) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }

        World currentWorld = player.getWorld();
        Optional<WorldFolders.PlayerWorldDimension> resolved = folders.resolve(currentWorld.getName());
        WorldId worldId =
                resolved.map(WorldFolders.PlayerWorldDimension::worldId).orElse(null);

        info(sender, "Returning to lobby...");

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

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** FR-5's teleport half: the world spawn of the overworld. */
    private void teleportToSpawn(Player player, LoadedWorld world) {
        String bukkitName = folders.bukkitWorldName(world.id(), DimensionKind.OVERWORLD);
        World overworld = Bukkit.getWorld(bukkitName);
        if (overworld == null) {
            error(player, "the world loaded but its overworld is not available");
            return;
        }
        player.teleport(overworld.getSpawnLocation());
    }

    private @Nullable Player asPlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        error(sender, "/pworld must be run by a player: it acts on the caller's own worlds");
        return null;
    }

    private void onMain(Runnable work) {
        executors.main().execute(work);
    }

    private static void usage(CommandSender sender) {
        sender.sendMessage(Component.text("/pworld <create|list|tp|unload|info|leave>", NamedTextColor.YELLOW));
    }

    private static void info(CommandSender sender, String message) {
        sender.sendMessage(Component.text(message, NamedTextColor.GRAY));
    }

    private static void success(CommandSender sender, String message) {
        sender.sendMessage(Component.text(message, NamedTextColor.GREEN));
    }

    private static void error(CommandSender sender, String message) {
        sender.sendMessage(Component.text(message, NamedTextColor.RED));
    }

    @Override
    public @Nullable List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return prefixed(SUBCOMMANDS, args[0]);
        }
        // World names would need a database read, and FR-24c is explicit that a
        // query per keystroke is the wrong shape. Milestone 5 gives the proxy a
        // membership cache for exactly this; until then the operator types it.
        return List.of();
    }

    private static List<String> prefixed(List<String> candidates, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>(candidates.size());
        for (String candidate : candidates) {
            if (candidate.startsWith(lower)) {
                matches.add(candidate);
            }
        }
        return matches;
    }
}
