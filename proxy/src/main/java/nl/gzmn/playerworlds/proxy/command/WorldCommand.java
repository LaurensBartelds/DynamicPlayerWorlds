package nl.gzmn.playerworlds.proxy.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.control.ArchivePayload;
import nl.gzmn.playerworlds.core.control.CommandKind;
import nl.gzmn.playerworlds.core.control.CommandResult;
import nl.gzmn.playerworlds.core.control.ControlChannels;
import nl.gzmn.playerworlds.core.control.MigratePayload;
import nl.gzmn.playerworlds.core.control.NodeCommand;
import nl.gzmn.playerworlds.core.db.MembershipRepository;
import nl.gzmn.playerworlds.core.db.NodeCommandRepository;
import nl.gzmn.playerworlds.core.db.NodeRepository.NodeStatus;
import nl.gzmn.playerworlds.core.db.PendingTransferRepository;
import nl.gzmn.playerworlds.core.db.PlayerNameRepository;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository;
import nl.gzmn.playerworlds.core.db.TransferRequestRepository;
import nl.gzmn.playerworlds.core.db.WorldBanRepository;
import nl.gzmn.playerworlds.core.menu.MenuCodec;
import nl.gzmn.playerworlds.core.menu.OpenMenu;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.StorageQuota;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.model.WorldState;
import nl.gzmn.playerworlds.proxy.menu.MenuChannelListener;
import nl.gzmn.playerworlds.proxy.node.NodeRegistry;
import nl.gzmn.playerworlds.proxy.node.Placement;
import nl.gzmn.playerworlds.proxy.permission.StorageTiers;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code /world} root (specification section 6).
 *
 * <p>Registering this claims the whole namespace, which is why
 * {@link #BACKEND_SUBCOMMANDS} exists: section 6 keeps {@code /world leave} and
 * {@code /world report} on the backend, and they are only reachable if this
 * handler forwards them. Resolves OQ-15.
 *
 * <p>Player-facing subcommands delegate to {@link WorldActions}.
 * Admin operations and Brigadier bindings are managed here.
 */
public final class WorldCommand {

    private static final Logger log = LoggerFactory.getLogger(WorldCommand.class);

    /** Implemented here, for the enable log line and for tests. */
    public static final List<String> SUBCOMMANDS = List.of(
            "create",
            "join",
            "delete",
            "restore",
            "invite",
            "accept",
            "kick",
            "members",
            "promote",
            "transfer",
            "list",
            "browse",
            "public",
            "set",
            "settings",
            "ban",
            "unban",
            "bans",
            "storage",
            "admin");

    /** Permissions per specification section 6. */
    public static final String CREATE_PERMISSION = "gzmn.worlds.create";

    public static final String JOIN_PERMISSION = "gzmn.worlds.join";
    public static final String PUBLIC_PERMISSION = "gzmn.worlds.public";
    public static final String ADMIN_PERMISSION = "gzmn.worlds.admin";

    /** Subcommands of {@code /world admin}, for the usage line and for tests. */
    public static final List<String> ADMIN_SUBCOMMANDS =
            List.of("list", "unload", "migrate", "drain", "transfer", "storage", "archive", "restore", "delete");

    /**
     * Subcommands that belong to the backend and must be forwarded (OQ-15).
     *
     * <p>{@code /world leave} is milestone 5 and {@code /world report} is
     * milestone 9; naming the list is what stops either from being silently
     * unreachable when it arrives.
     */
    public static final List<String> BACKEND_SUBCOMMANDS = List.of("leave", "report");

    private final WorldActions actions;
    private final ProxyServer proxy;
    private final PluginExecutors executors;
    private final PlayerWorldRepository worlds;
    private final Placement placement;
    private final NodeCommandRepository nodeCommands;
    private final Supplier<NetworkPolicy> policy;
    private final StorageTiers storageTiers = new StorageTiers();

    public WorldCommand(
            ProxyServer proxy,
            PluginExecutors executors,
            PlayerWorldRepository worlds,
            MembershipRepository membership,
            TransferRequestRepository transferRequests,
            WorldBanRepository bans,
            PlayerNameRepository names,
            PendingTransferRepository transfers,
            NodeRegistry registry,
            Placement placement,
            NodeCommandRepository nodeCommands,
            Supplier<NetworkPolicy> policy) {
        this(
                new WorldActions(
                        proxy,
                        executors,
                        worlds,
                        membership,
                        transferRequests,
                        bans,
                        names,
                        transfers,
                        registry,
                        placement,
                        nodeCommands,
                        policy),
                proxy,
                executors,
                worlds,
                placement,
                nodeCommands,
                policy);
    }

    public WorldCommand(
            WorldActions actions,
            ProxyServer proxy,
            PluginExecutors executors,
            PlayerWorldRepository worlds,
            Placement placement,
            NodeCommandRepository nodeCommands,
            Supplier<NetworkPolicy> policy) {
        this.actions = Objects.requireNonNull(actions, "actions");
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.executors = Objects.requireNonNull(executors, "executors");
        this.worlds = Objects.requireNonNull(worlds, "worlds");
        this.placement = Objects.requireNonNull(placement, "placement");
        this.nodeCommands = Objects.requireNonNull(nodeCommands, "nodeCommands");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    private static boolean hasPermissionOrDefault(CommandSource source, String permission) {
        return source.getPermissionValue(permission) != com.velocitypowered.api.permission.Tristate.FALSE;
    }

    private static final AtomicLong CORRELATION_SEQ = new AtomicLong(1);

    /** Builds the Brigadier tree. */
    public BrigadierCommand build() {
        LiteralArgumentBuilder<CommandSource> root = BrigadierCommand.literalArgumentBuilder("world")
                .executes(context -> {
                    openMenuOrUsage(context.getSource());
                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                })
                .then(BrigadierCommand.literalArgumentBuilder("invite")
                        .then(BrigadierCommand.requiredArgumentBuilder("player", StringArgumentType.word())
                                .suggests(this::suggestOnlinePlayers)
                                .executes(context -> {
                                    Player caller = playerOrNull(context);
                                    if (caller != null) {
                                        var _ = actions.invite(caller, StringArgumentType.getString(context, "player"));
                                    }
                                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                })))
                .then(BrigadierCommand.literalArgumentBuilder("accept")
                        .requires(source -> hasPermissionOrDefault(source, JOIN_PERMISSION))
                        .then(BrigadierCommand.requiredArgumentBuilder("owner", StringArgumentType.word())
                                .executes(context -> {
                                    Player caller = playerOrNull(context);
                                    if (caller != null) {
                                        var _ = actions.accept(caller, StringArgumentType.getString(context, "owner"));
                                    }
                                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                })))
                .then(BrigadierCommand.literalArgumentBuilder("kick")
                        .then(BrigadierCommand.requiredArgumentBuilder("player", StringArgumentType.word())
                                .executes(context -> {
                                    Player caller = playerOrNull(context);
                                    if (caller != null) {
                                        var _ = actions.kick(caller, StringArgumentType.getString(context, "player"));
                                    }
                                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                })))
                .then(BrigadierCommand.literalArgumentBuilder("promote")
                        .then(BrigadierCommand.requiredArgumentBuilder("player", StringArgumentType.word())
                                .executes(context -> {
                                    Player caller = playerOrNull(context);
                                    if (caller != null) {
                                        var _ = actions.promote(
                                                caller, StringArgumentType.getString(context, "player"));
                                    }
                                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                })))
                .then(BrigadierCommand.literalArgumentBuilder("transfer")
                        .then(BrigadierCommand.literalArgumentBuilder("accept")
                                .then(BrigadierCommand.requiredArgumentBuilder("owner", StringArgumentType.word())
                                        .executes(context -> {
                                            Player caller = playerOrNull(context);
                                            if (caller != null) {
                                                var _ = actions.transferAccept(
                                                        caller, StringArgumentType.getString(context, "owner"));
                                            }
                                            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                        })))
                        .then(BrigadierCommand.literalArgumentBuilder("decline")
                                .then(BrigadierCommand.requiredArgumentBuilder("owner", StringArgumentType.word())
                                        .executes(context -> {
                                            Player caller = playerOrNull(context);
                                            if (caller != null) {
                                                var _ = actions.transferDecline(
                                                        caller, StringArgumentType.getString(context, "owner"));
                                            }
                                            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                        })))
                        .then(BrigadierCommand.requiredArgumentBuilder("player", StringArgumentType.word())
                                .suggests(this::suggestOnlinePlayers)
                                .executes(context -> {
                                    Player caller = playerOrNull(context);
                                    if (caller != null) {
                                        var _ = actions.transfer(
                                                caller, StringArgumentType.getString(context, "player"), false);
                                    }
                                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                })
                                .then(BrigadierCommand.literalArgumentBuilder("confirm")
                                        .executes(context -> {
                                            Player caller = playerOrNull(context);
                                            if (caller != null) {
                                                var _ = actions.transfer(
                                                        caller, StringArgumentType.getString(context, "player"), true);
                                            }
                                            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                        }))))
                .then(BrigadierCommand.literalArgumentBuilder("create")
                        .requires(source -> hasPermissionOrDefault(source, CREATE_PERMISSION))
                        .then(BrigadierCommand.requiredArgumentBuilder("name", StringArgumentType.word())
                                .executes(context -> {
                                    Player caller = playerOrNull(context);
                                    if (caller != null) {
                                        var _ = actions.create(
                                                caller, StringArgumentType.getString(context, "name"), null);
                                    }
                                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                })
                                .then(BrigadierCommand.requiredArgumentBuilder("seed", StringArgumentType.word())
                                        .executes(context -> {
                                            Player caller = playerOrNull(context);
                                            if (caller != null) {
                                                var _ = actions.create(
                                                        caller,
                                                        StringArgumentType.getString(context, "name"),
                                                        StringArgumentType.getString(context, "seed"));
                                            }
                                            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                        }))))
                .then(BrigadierCommand.literalArgumentBuilder("delete")
                        .then(BrigadierCommand.requiredArgumentBuilder("name", StringArgumentType.word())
                                .executes(context -> {
                                    Player caller = playerOrNull(context);
                                    if (caller != null) {
                                        var _ = actions.delete(
                                                caller, StringArgumentType.getString(context, "name"), false);
                                    }
                                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                })
                                .then(BrigadierCommand.literalArgumentBuilder("confirm")
                                        .executes(context -> {
                                            Player caller = playerOrNull(context);
                                            if (caller != null) {
                                                var _ = actions.delete(
                                                        caller, StringArgumentType.getString(context, "name"), true);
                                            }
                                            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                        }))
                                .then(BrigadierCommand.literalArgumentBuilder("hard")
                                        .executes(context -> {
                                            Player caller = playerOrNull(context);
                                            if (caller != null) {
                                                var _ = actions.deleteHard(
                                                        caller, StringArgumentType.getString(context, "name"), false);
                                            }
                                            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                        })
                                        .then(BrigadierCommand.literalArgumentBuilder("confirm")
                                                .executes(context -> {
                                                    Player caller = playerOrNull(context);
                                                    if (caller != null) {
                                                        var _ = actions.deleteHard(
                                                                caller,
                                                                StringArgumentType.getString(context, "name"),
                                                                true);
                                                    }
                                                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                                })))))
                .then(BrigadierCommand.literalArgumentBuilder("restore")
                        .then(BrigadierCommand.requiredArgumentBuilder("name", StringArgumentType.word())
                                .executes(context -> {
                                    Player caller = playerOrNull(context);
                                    if (caller != null) {
                                        var _ = actions.restore(caller, StringArgumentType.getString(context, "name"));
                                    }
                                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                })))
                .then(BrigadierCommand.literalArgumentBuilder("join")
                        .requires(source -> hasPermissionOrDefault(source, JOIN_PERMISSION))
                        .then(BrigadierCommand.requiredArgumentBuilder("owner", StringArgumentType.word())
                                .executes(context -> {
                                    Player caller = playerOrNull(context);
                                    if (caller != null) {
                                        var _ = actions.join(
                                                caller, StringArgumentType.getString(context, "owner"), null);
                                    }
                                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                })
                                .then(BrigadierCommand.requiredArgumentBuilder("name", StringArgumentType.word())
                                        .executes(context -> {
                                            Player caller = playerOrNull(context);
                                            if (caller != null) {
                                                var _ = actions.join(
                                                        caller,
                                                        StringArgumentType.getString(context, "owner"),
                                                        StringArgumentType.getString(context, "name"));
                                            }
                                            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                        }))))
                .then(BrigadierCommand.literalArgumentBuilder("members").executes(context -> {
                    Player caller = playerOrNull(context);
                    if (caller != null) {
                        var _ = actions.members(caller);
                    }
                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                }))
                .then(BrigadierCommand.literalArgumentBuilder("list").executes(context -> {
                    Player caller = playerOrNull(context);
                    if (caller != null) {
                        var _ = actions.list(caller);
                    }
                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                }))
                .then(BrigadierCommand.literalArgumentBuilder("browse")
                        .requires(source -> hasPermissionOrDefault(source, JOIN_PERMISSION))
                        .executes(context -> {
                            var _ = actions.browse(context.getSource());
                            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                        }))
                .then(BrigadierCommand.literalArgumentBuilder("public")
                        .requires(source -> source.hasPermission(PUBLIC_PERMISSION))
                        .then(BrigadierCommand.literalArgumentBuilder("on")
                                .executes(context -> {
                                    Player caller = playerOrNull(context);
                                    if (caller != null) {
                                        var _ = actions.setPublic(caller, true, null);
                                    }
                                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                })
                                .then(BrigadierCommand.requiredArgumentBuilder(
                                                "description", StringArgumentType.greedyString())
                                        .executes(context -> {
                                            Player caller = playerOrNull(context);
                                            if (caller != null) {
                                                var _ = actions.setPublic(
                                                        caller,
                                                        true,
                                                        StringArgumentType.getString(context, "description"));
                                            }
                                            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                        })))
                        .then(BrigadierCommand.literalArgumentBuilder("off").executes(context -> {
                            Player caller = playerOrNull(context);
                            if (caller != null) {
                                var _ = actions.setPublic(caller, false, null);
                            }
                            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                        })))
                .then(BrigadierCommand.literalArgumentBuilder("set")
                        .then(BrigadierCommand.requiredArgumentBuilder("setting", StringArgumentType.word())
                                .then(BrigadierCommand.requiredArgumentBuilder("value", StringArgumentType.word())
                                        .executes(context -> {
                                            Player caller = playerOrNull(context);
                                            if (caller != null) {
                                                var _ = actions.setSetting(
                                                        caller,
                                                        StringArgumentType.getString(context, "setting"),
                                                        StringArgumentType.getString(context, "value"));
                                            }
                                            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                        }))))
                .then(BrigadierCommand.literalArgumentBuilder("settings").executes(context -> {
                    Player caller = playerOrNull(context);
                    if (caller != null) {
                        var _ = actions.showSettings(caller);
                    }
                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                }))
                .then(BrigadierCommand.literalArgumentBuilder("ban")
                        .then(BrigadierCommand.requiredArgumentBuilder("player", StringArgumentType.word())
                                .suggests(this::suggestOnlinePlayers)
                                .executes(context -> {
                                    Player caller = playerOrNull(context);
                                    if (caller != null) {
                                        var _ = actions.ban(
                                                caller, StringArgumentType.getString(context, "player"), null);
                                    }
                                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                })
                                .then(BrigadierCommand.requiredArgumentBuilder(
                                                "reason", StringArgumentType.greedyString())
                                        .executes(context -> {
                                            Player caller = playerOrNull(context);
                                            if (caller != null) {
                                                var _ = actions.ban(
                                                        caller,
                                                        StringArgumentType.getString(context, "player"),
                                                        StringArgumentType.getString(context, "reason"));
                                            }
                                            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                        }))))
                .then(BrigadierCommand.literalArgumentBuilder("unban")
                        .then(BrigadierCommand.requiredArgumentBuilder("player", StringArgumentType.word())
                                .executes(context -> {
                                    Player caller = playerOrNull(context);
                                    if (caller != null) {
                                        var _ = actions.unban(caller, StringArgumentType.getString(context, "player"));
                                    }
                                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                })))
                .then(BrigadierCommand.literalArgumentBuilder("storage").executes(context -> {
                    Player caller = playerOrNull(context);
                    if (caller != null) {
                        var _ = actions.storage(caller);
                    }
                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                }))
                .then(BrigadierCommand.literalArgumentBuilder("bans").executes(context -> {
                    Player caller = playerOrNull(context);
                    if (caller != null) {
                        var _ = actions.listBans(caller);
                    }
                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                }))
                .then(adminTree());
        return new BrigadierCommand(root);
    }

    /**
     * {@code /world admin} — section 6's operator entries, milestone 8's half.
     */
    private LiteralArgumentBuilder<CommandSource> adminTree() {
        return BrigadierCommand.literalArgumentBuilder("admin")
                .requires(source -> source.hasPermission(ADMIN_PERMISSION))
                .executes(context -> {
                    info(context.getSource(), "/world admin <" + String.join("|", ADMIN_SUBCOMMANDS) + ">");
                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                })
                .then(BrigadierCommand.literalArgumentBuilder("list").executes(context -> {
                    adminList(context.getSource());
                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                }))
                .then(BrigadierCommand.literalArgumentBuilder("unload")
                        .then(BrigadierCommand.requiredArgumentBuilder("id", StringArgumentType.word())
                                .executes(context -> {
                                    adminUnload(context.getSource(), StringArgumentType.getString(context, "id"));
                                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                })))
                .then(BrigadierCommand.literalArgumentBuilder("migrate")
                        .then(BrigadierCommand.requiredArgumentBuilder("id", StringArgumentType.word())
                                .then(BrigadierCommand.requiredArgumentBuilder("node", StringArgumentType.word())
                                        .suggests(this::suggestNodes)
                                        .executes(context -> {
                                            adminMigrate(
                                                    context.getSource(),
                                                    StringArgumentType.getString(context, "id"),
                                                    StringArgumentType.getString(context, "node"));
                                            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                        }))))
                .then(BrigadierCommand.literalArgumentBuilder("drain")
                        .then(BrigadierCommand.requiredArgumentBuilder("node", StringArgumentType.word())
                                .suggests(this::suggestNodes)
                                .executes(context -> {
                                    adminDrain(
                                            context.getSource(), StringArgumentType.getString(context, "node"), true);
                                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                })
                                .then(BrigadierCommand.literalArgumentBuilder("off")
                                        .executes(context -> {
                                            adminDrain(
                                                    context.getSource(),
                                                    StringArgumentType.getString(context, "node"),
                                                    false);
                                            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                        }))
                                .then(BrigadierCommand.literalArgumentBuilder("on")
                                        .executes(context -> {
                                            adminDrain(
                                                    context.getSource(),
                                                    StringArgumentType.getString(context, "node"),
                                                    true);
                                            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                        }))))
                .then(BrigadierCommand.literalArgumentBuilder("transfer")
                        .then(BrigadierCommand.requiredArgumentBuilder("id", StringArgumentType.word())
                                .then(BrigadierCommand.requiredArgumentBuilder("player", StringArgumentType.word())
                                        .suggests(this::suggestOnlinePlayers)
                                        .executes(context -> {
                                            adminTransfer(
                                                    context.getSource(),
                                                    StringArgumentType.getString(context, "id"),
                                                    StringArgumentType.getString(context, "player"));
                                            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                        }))))
                .then(BrigadierCommand.literalArgumentBuilder("storage")
                        .then(BrigadierCommand.requiredArgumentBuilder("player", StringArgumentType.word())
                                .suggests(this::suggestOnlinePlayers)
                                .executes(context -> {
                                    adminStorage(context.getSource(), StringArgumentType.getString(context, "player"));
                                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                })))
                .then(BrigadierCommand.literalArgumentBuilder("archive")
                        .then(BrigadierCommand.requiredArgumentBuilder("id", StringArgumentType.word())
                                .executes(context -> {
                                    adminArchive(context.getSource(), StringArgumentType.getString(context, "id"));
                                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                })))
                .then(BrigadierCommand.literalArgumentBuilder("restore")
                        .then(BrigadierCommand.requiredArgumentBuilder("id", StringArgumentType.word())
                                .executes(context -> {
                                    adminRestore(
                                            context.getSource(), StringArgumentType.getString(context, "id"), null);
                                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                })
                                .then(BrigadierCommand.requiredArgumentBuilder("player", StringArgumentType.word())
                                        .suggests(this::suggestOnlinePlayers)
                                        .executes(context -> {
                                            adminRestore(
                                                    context.getSource(),
                                                    StringArgumentType.getString(context, "id"),
                                                    StringArgumentType.getString(context, "player"));
                                            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                        }))))
                .then(BrigadierCommand.literalArgumentBuilder("delete")
                        .then(BrigadierCommand.requiredArgumentBuilder("id", StringArgumentType.word())
                                .executes(context -> {
                                    adminDelete(
                                            context.getSource(), StringArgumentType.getString(context, "id"), false);
                                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                })
                                .then(BrigadierCommand.literalArgumentBuilder("confirm")
                                        .executes(context -> {
                                            adminDelete(
                                                    context.getSource(),
                                                    StringArgumentType.getString(context, "id"),
                                                    true);
                                            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                        }))));
    }

    // -----------------------------------------------------------------------
    // Admin Subcommands (section 6; MN-21, MN-22)
    // -----------------------------------------------------------------------

    /** {@code /world admin list} — the pool as placement sees it. */
    private void adminList(CommandSource source) {
        NetworkPolicy current = policy.get();
        runAsAdmin(source, () -> {
            List<NodeStatus> all = placement.allNodes();
            if (all.isEmpty()) {
                info(source, "no node has ever published a heartbeat");
                return;
            }
            java.util.Set<String> alive = new java.util.HashSet<>();
            for (NodeStatus node : placement.aliveNodes(current.deadAfter())) {
                alive.add(node.nodeId());
            }
            info(
                    source,
                    all.size() + " node(s); alive means a heartbeat within "
                            + current.deadAfter().toSeconds() + "s and not draining (MN-18)");
            for (NodeStatus node : all) {
                boolean isAlive = alive.contains(node.nodeId());
                String state = node.draining() ? "DRAINING" : isAlive ? "alive" : "DEAD";
                Integer heap = node.heapPercent();
                Double tps = node.tps();
                NamedTextColor colour =
                        node.draining() ? NamedTextColor.YELLOW : isAlive ? NamedTextColor.GREEN : NamedTextColor.RED;
                source.sendMessage(Component.text(
                        node.nodeId() + " [" + state + "] " + node.address()
                                + " worlds=" + node.loadedWorlds() + "/" + current.maxWorldsPerNode()
                                + " players=" + node.onlinePlayers()
                                + " heap=" + (heap == null ? "?" : heap + "%")
                                + " tps=" + (tps == null ? "?" : String.format(Locale.ROOT, "%.1f", tps))
                                + " mc=" + node.mcVersion() + " dataVersion=" + node.dataVersion(),
                        colour));
            }
        });
    }

    /** {@code /world admin unload <id>} — routed to the holding node (section 6, CP-1). */
    private void adminUnload(CommandSource source, String rawId) {
        Optional<WorldId> parsed = parseWorldId(source, rawId);
        if (parsed.isEmpty()) {
            return;
        }
        WorldId worldId = parsed.get();
        NetworkPolicy current = policy.get();
        runAsAdmin(source, () -> {
            Optional<PlayerWorld> found = worlds.findById(worldId);
            if (found.isEmpty()) {
                error(source, "no world with that id");
                return;
            }
            Optional<String> holder = worlds.leaseHolder(worldId);
            if (holder.isEmpty()) {
                info(source, "that world holds no live lease; nothing to unload");
                return;
            }
            nodeCommands.enqueue(
                    holder.get(),
                    worldId,
                    found.get().generation(),
                    CommandKind.UNLOAD_WORLD.name(),
                    NodeCommand.EMPTY_PAYLOAD,
                    current.holdingTimeout(),
                    ControlChannels.forNode(holder.get()));
            success(source, "asked " + holder.get() + " to commit and unload " + worldId);
        });
    }

    /**
     * {@code /world admin migrate <id> <node>} — MN-19 and MN-21.
     */
    private void adminMigrate(CommandSource source, String rawId, String targetNode) {
        Optional<WorldId> parsed = parseWorldId(source, rawId);
        if (parsed.isEmpty()) {
            return;
        }
        WorldId worldId = parsed.get();
        NetworkPolicy current = policy.get();
        runOnIo(source, () -> {
            Placement.MigrationCheck check = placement.canTake(targetNode, worldId, current);
            switch (check) {
                case Placement.MigrationCheck.UnknownNode ignored -> {
                    error(source, "no node called '" + targetNode + "' has ever registered");
                }
                case Placement.MigrationCheck.NotAvailable unavailable -> {
                    error(
                            source,
                            unavailable.draining()
                                    ? "'" + targetNode + "' is draining and takes no worlds (MN-22)"
                                    : "'" + targetNode + "' has not published a heartbeat recently (MN-18)");
                }
                case Placement.MigrationCheck.TooOld tooOld -> {
                    error(
                            source,
                            "'" + targetNode + "' runs data version " + tooOld.nodeDataVersion()
                                    + " and that world was last saved at " + tooOld.worldDataVersion()
                                    + "; it cannot open it (MN-26)");
                }
                case Placement.MigrationCheck.Ready ready -> {
                    migrateReadyWorld(source, worldId, ready, current);
                }
            }
        });
    }

    private void migrateReadyWorld(
            CommandSource source, WorldId worldId, Placement.MigrationCheck.Ready ready, NetworkPolicy current)
            throws SQLException {
        String targetNode = ready.node().nodeId();
        String holder = ready.world().leaseHolder();

        if (holder == null) {
            Optional<PlayerWorldRepository.LeaseGrant> grant =
                    worlds.acquireLease(worldId, targetNode, ready.node().dataVersion(), current.leaseDuration());
            if (grant.isEmpty()) {
                error(source, "a node took that world while you were typing; try again");
                return;
            }
            success(source, worldId + " was not loaded; its lease is now on " + targetNode);
            return;
        }
        if (holder.equals(targetNode)) {
            info(source, "that world is already on " + targetNode);
            return;
        }

        Optional<PlayerWorld> row = worlds.findById(worldId);
        if (row.isEmpty()) {
            error(source, "no world with that id");
            return;
        }

        int countdown = MigratePayload.DEFAULT_COUNTDOWN_SECONDS;
        java.time.Duration ttl = java.time.Duration.ofSeconds(countdown)
                .plus(current.commitTimeout())
                .plusSeconds(60);
        long commandId = nodeCommands.enqueue(
                holder,
                worldId,
                row.get().generation(),
                CommandKind.MIGRATE_WORLD.name(),
                MigratePayload.to(targetNode, countdown).format(),
                ttl,
                ControlChannels.forNode(holder));
        info(
                source,
                "asked " + holder + " to hand " + worldId + " over to " + targetNode + "; players inside get a "
                        + countdown + "s countdown (MN-21)");

        Optional<NodeCommand> completed = awaitCompletion(commandId, countdown, current);
        if (completed.isEmpty()) {
            error(
                    source,
                    "no answer from " + holder + " yet; the world stays where it is. Check /world admin list and the "
                            + "node's log, then try again");
            return;
        }
        String result = completed.get().result();
        if (!CommandResult.OK.equals(result)) {
            error(source, holder + " refused to give the world up: " + result);
            return;
        }

        Optional<PlayerWorldRepository.LeaseGrant> grant =
                worlds.acquireLease(worldId, targetNode, ready.node().dataVersion(), current.leaseDuration());
        if (grant.isEmpty()) {
            Optional<String> nowOn = worlds.leaseHolder(worldId);
            error(
                    source,
                    "the world was released but "
                            + nowOn.orElse("another node")
                            + " took the lease before " + targetNode + " could");
            return;
        }
        success(source, worldId + " moved from " + holder + " to " + targetNode + " (MN-19)");
    }

    private Optional<NodeCommand> awaitCompletion(long commandId, int countdownSeconds, NetworkPolicy current)
            throws SQLException {
        long deadline = System.nanoTime()
                + java.time.Duration.ofSeconds(countdownSeconds)
                        .plus(current.commitTimeout())
                        .plusSeconds(10)
                        .toNanos();
        while (System.nanoTime() < deadline) {
            Optional<NodeCommand> row = nodeCommands.findById(commandId);
            if (row.isPresent() && row.get().isCompleted()) {
                return row;
            }
            try {
                Thread.sleep(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * {@code /world admin drain <node> [on|off]} — MN-22.
     */
    private void adminDrain(CommandSource source, String targetNode, boolean draining) {
        runAsAdmin(source, () -> {
            if (placement.allNodes().stream().noneMatch(node -> node.nodeId().equals(targetNode))) {
                error(source, "no node called '" + targetNode + "' has ever registered");
                return;
            }
            List<WorldId> holding = worlds.worldsLeasedTo(targetNode);
            MigratePayload payload = draining
                    ? MigratePayload.drain(MigratePayload.DEFAULT_COUNTDOWN_SECONDS)
                    : MigratePayload.resumeDrain();

            var _ = nodeCommands.enqueue(
                    targetNode,
                    null,
                    null,
                    CommandKind.DRAIN_NODE.name(),
                    payload.format(),
                    NodeCommandRepository.DEFAULT_TTL,
                    ControlChannels.forNode(targetNode));

            if (draining) {
                success(
                        source,
                        "draining " + targetNode + ": it takes no new placements and releases its " + holding.size()
                                + " world(s) in place (MN-22)");
                info(
                        source,
                        "its worlds are placed fresh on the next join (MN-20); it leaves the proxy's server "
                                + "list on the next sweep");
            } else {
                success(source, targetNode + " will take new placements again");
            }
        });
    }

    /**
     * {@code /world admin transfer <id> <player>} — FR-33.
     */
    private void adminTransfer(CommandSource source, String rawId, String targetName) {
        Optional<WorldId> parsed = parseWorldId(source, rawId);
        if (parsed.isEmpty()) {
            return;
        }
        WorldId worldId = parsed.get();
        NetworkPolicy current = policy.get();
        runAsAdmin(source, () -> {
            Optional<PlayerWorld> found = worlds.findById(worldId);
            if (found.isEmpty()) {
                error(source, "no world with that id");
                return;
            }
            PlayerWorld world = found.get();
            Optional<UUID> target = actions.resolvePlayer(targetName);
            if (target.isEmpty()) {
                error(source, "no player called '" + targetName + "' has been seen on this network");
                return;
            }
            if (target.get().equals(world.ownerUuid())) {
                error(source, targetName + " is already the owner of that world");
                return;
            }
            int ownedCount = worlds.countOwnedBy(target.get());
            if (ownedCount >= current.maxWorldsPerPlayer()) {
                error(source, targetName + " has reached their world limit (" + current.maxWorldsPerPlayer() + ")");
                return;
            }
            if (!worlds.transferOwnership(worldId, world.ownerUuid(), target.get(), "ADMIN")) {
                error(source, "could not transfer world " + worldId);
                return;
            }
            actions.enqueueToWorldOrAliveNodes(world, CommandKind.INVALIDATE_CACHE, NodeCommand.EMPTY_PAYLOAD, current);
            success(
                    source,
                    "transferred ownership of '" + world.name() + "' (" + worldId + ") to " + targetName
                            + " with reason ADMIN");
            proxy.getPlayer(target.get())
                    .ifPresent(online -> online.sendMessage(Component.text(
                            "You were granted ownership of '" + world.name() + "' by an administrator.",
                            NamedTextColor.GREEN)));
            proxy.getPlayer(world.ownerUuid())
                    .ifPresent(online -> online.sendMessage(Component.text(
                            "Ownership of '" + world.name() + "' was transferred to " + targetName
                                    + " by an administrator.",
                            NamedTextColor.YELLOW)));
        });
    }

    /** {@code /world admin storage <player>} — another player's footprint, for support. */
    private void adminStorage(CommandSource source, String targetName) {
        NetworkPolicy current = policy.get();
        runAsAdmin(source, () -> {
            Optional<UUID> target = actions.resolvePlayer(targetName);
            if (target.isEmpty()) {
                error(source, "no player called '" + targetName + "' has been seen on this network");
                return;
            }
            UUID uuid = target.get();
            long used = worlds.totalStorageUsedBy(uuid);
            Optional<Player> online = proxy.getPlayer(uuid);
            StorageQuota quota = online.isPresent()
                    ? storageTiers.evaluate(online.get(), used, current).quota()
                    : new StorageQuota(uuid, used, current.defaultStorageLimitBytes(), false);
            WorldActions.renderStorage(source, targetName + "'s", quota, worlds.listOwnedBy(uuid));
            if (online.isEmpty()) {
                info(source, "  (offline: allowance shown is the network default, not their permission tier)");
            }
        });
    }

    /** {@code /world admin archive <id>} — FR-35 on demand, whoever owns the world. */
    private void adminArchive(CommandSource source, String rawId) {
        Optional<WorldId> parsed = parseWorldId(source, rawId);
        if (parsed.isEmpty()) {
            return;
        }
        WorldId worldId = parsed.get();
        NetworkPolicy current = policy.get();
        runAsAdmin(source, () -> {
            Optional<PlayerWorld> found = worlds.findById(worldId);
            if (found.isEmpty()) {
                error(source, "no world with that id");
                return;
            }
            PlayerWorld world = found.get();
            if (world.state() == WorldState.ARCHIVED) {
                info(source, "'" + world.name() + "' is already archived");
                return;
            }
            String node = adminNodeOrExplain(source, world, current);
            if (node == null) {
                return;
            }
            actions.enqueueTo(node, world, CommandKind.ARCHIVE_WORLD, ArchivePayload.format(null), current);
            success(source, "queued archival of '" + world.name() + "' on " + node);
            log.info("world {} queued for archival on {} by an administrator (FR-35)", worldId, node);
        });
    }

    /** {@code /world admin restore <id> [player]} — FR-36, optionally handing the world on. */
    private void adminRestore(CommandSource source, String rawId, @Nullable String targetName) {
        Optional<WorldId> parsed = parseWorldId(source, rawId);
        if (parsed.isEmpty()) {
            return;
        }
        WorldId worldId = parsed.get();
        NetworkPolicy current = policy.get();
        runAsAdmin(source, () -> {
            Optional<PlayerWorld> found = worlds.findById(worldId);
            if (found.isEmpty()) {
                error(source, "no world with that id");
                return;
            }
            PlayerWorld world = found.get();
            if (world.state() != WorldState.ARCHIVED) {
                error(source, "'" + world.name() + "' is " + world.state() + " and does not need restoring");
                return;
            }
            UUID newOwner = null;
            if (targetName != null) {
                Optional<UUID> target = actions.resolvePlayer(targetName);
                if (target.isEmpty()) {
                    error(source, "no player called '" + targetName + "' has been seen on this network");
                    return;
                }
                newOwner = target.get();
            }
            String node = adminNodeOrExplain(source, world, current);
            if (node == null) {
                return;
            }
            actions.enqueueTo(node, world, CommandKind.RESTORE_WORLD, ArchivePayload.format(newOwner), current);
            success(
                    source,
                    "queued restore of '" + world.name() + "' on " + node
                            + (targetName == null ? "" : " for " + targetName));
            log.info("world {} queued for restore on {} by an administrator (FR-36)", worldId, node);
        });
    }

    /**
     * {@code /world admin delete <id> confirm} — FR-37's hard deletion.
     */
    private void adminDelete(CommandSource source, String rawId, boolean confirmed) {
        Optional<WorldId> parsed = parseWorldId(source, rawId);
        if (parsed.isEmpty()) {
            return;
        }
        WorldId worldId = parsed.get();
        NetworkPolicy current = policy.get();
        runAsAdmin(source, () -> {
            Optional<PlayerWorld> found = worlds.findById(worldId);
            if (found.isEmpty()) {
                error(source, "no world with that id");
                return;
            }
            PlayerWorld world = found.get();
            if (!confirmed) {
                error(
                        source,
                        "this permanently destroys '" + world.name() + "' and every archive of it. "
                                + "There is no undo and no other command undoes it.");
                info(source, "type /world admin delete " + worldId.value() + " confirm to go ahead");
                return;
            }
            if (!worlds.deleteHard(worldId)) {
                error(source, "'" + world.name() + "' changed while you were confirming; try again");
                return;
            }
            actions.enqueueToWorldOrAliveNodes(world, CommandKind.UNLOAD_WORLD, NodeCommand.EMPTY_PAYLOAD, current);
            success(source, "permanently deleted '" + world.name() + "' (" + worldId.value() + ")");
            log.warn(
                    "world {} ('{}') hard deleted by an administrator (FR-37); archives are gone",
                    worldId,
                    world.name());
        });
    }

    private @Nullable String adminNodeOrExplain(CommandSource source, PlayerWorld world, NetworkPolicy current)
            throws SQLException {
        if (world.assignedNode() != null) {
            return world.assignedNode();
        }
        return actions.routableNodeOrExplain(source, placement.forExistingWorld(world.id(), current), world.id());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static @Nullable Player playerOrNull(CommandContext<CommandSource> context) {
        if (context.getSource() instanceof Player player) {
            return player;
        }
        context.getSource()
                .sendMessage(Component.text(
                        "/world acts on the caller's own worlds and must be run by a player", NamedTextColor.RED));
        return null;
    }

    private CompletableFuture<Suggestions> suggestOnlinePlayers(
            CommandContext<CommandSource> context, com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        for (Player player : proxy.getAllPlayers()) {
            if (player.getUsername()
                    .toLowerCase(Locale.ROOT)
                    .startsWith(builder.getRemaining().toLowerCase(Locale.ROOT))) {
                builder.suggest(player.getUsername());
            }
        }
        return builder.buildFuture();
    }

    private CompletableFuture<Suggestions> suggestNodes(
            CommandContext<CommandSource> context, com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        try {
            String prefix = builder.getRemaining().toLowerCase(Locale.ROOT);
            for (NodeStatus node : placement.allNodes()) {
                if (node.nodeId().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    builder.suggest(node.nodeId());
                }
            }
        } catch (SQLException e) {
            log.debug("could not suggest node names: {}", e.getMessage());
        }
        return builder.buildFuture();
    }

    private Optional<WorldId> parseWorldId(CommandSource source, String raw) {
        try {
            return Optional.of(new WorldId(UUID.fromString(raw.strip())));
        } catch (IllegalArgumentException e) {
            error(source, "'" + raw + "' is not a world id (section 6 takes the uuid, not the name)");
            return Optional.empty();
        }
    }

    private void runAsAdmin(CommandSource source, SqlTask task) {
        submitAdmin(executors.db(), source, task);
    }

    private void runOnIo(CommandSource source, SqlTask task) {
        submitAdmin(executors.io(), source, task);
    }

    private void submitAdmin(java.util.concurrent.Executor pool, CommandSource source, SqlTask task) {
        pool.execute(() -> {
            try {
                task.run();
            } catch (SQLException e) {
                log.error("/world admin failed", e);
                error(source, "that did not work; the failure is in the proxy log");
            }
        });
    }

    /**
     * Builds the Brigadier command for {@code /worlds}.
     */
    public BrigadierCommand buildWorlds() {
        LiteralArgumentBuilder<CommandSource> root = BrigadierCommand.literalArgumentBuilder("worlds")
                .executes(context -> {
                    openMenuOrUsage(context.getSource());
                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                });
        return new BrigadierCommand(root);
    }

    private static void openMenuOrUsage(CommandSource source) {
        if (source instanceof Player player) {
            Optional<ServerConnection> connection = player.getCurrentServer();
            if (connection.isPresent()) {
                byte[] data = MenuCodec.encodeOpenMenu(new OpenMenu(CORRELATION_SEQ.getAndIncrement()));
                connection.get().sendPluginMessage(MenuChannelListener.CHANNEL_IDENTIFIER, data);
                return;
            }
        }
        usage(source);
    }

    private static void usage(CommandSource source) {
        source.sendMessage(Component.text("/world <" + String.join("|", SUBCOMMANDS) + ">", NamedTextColor.YELLOW));
    }

    private static Component info(CommandSource source, String message) {
        return WorldActions.info(source, message);
    }

    private static Component success(CommandSource source, String message) {
        return WorldActions.success(source, message);
    }

    private static Component error(CommandSource source, String message) {
        return WorldActions.error(source, message);
    }

    @FunctionalInterface
    private interface SqlTask {
        void run() throws SQLException;
    }
}
