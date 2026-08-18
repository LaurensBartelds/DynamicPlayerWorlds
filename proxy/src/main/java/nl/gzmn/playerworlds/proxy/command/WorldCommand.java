package nl.gzmn.playerworlds.proxy.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.control.CommandKind;
import nl.gzmn.playerworlds.core.control.CommandResult;
import nl.gzmn.playerworlds.core.control.ControlChannels;
import nl.gzmn.playerworlds.core.control.EjectPayload;
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
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.Role;
import nl.gzmn.playerworlds.core.model.TransferRequest;
import nl.gzmn.playerworlds.core.model.Visibility;
import nl.gzmn.playerworlds.core.model.WorldBan;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.model.WorldMember;
import nl.gzmn.playerworlds.core.model.WorldSettings;
import nl.gzmn.playerworlds.core.model.WorldState;
import nl.gzmn.playerworlds.core.placement.PlacementDecision;
import nl.gzmn.playerworlds.proxy.node.NodeRegistry;
import nl.gzmn.playerworlds.proxy.node.Placement;
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
 * <p>Milestone 2 implemented membership (invite, accept, kick, members, promote).
 * Milestone 5 added {@code create} and {@code join} over the transfer handoff,
 * milestone 8 added placement and {@code admin}, and milestone 9 adds public
 * worlds, browsing, world listing, per-world settings, and bans.
 *
 * <p>Every handler returns immediately and does its database work on the pool.
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
            "admin");

    /** Permissions per specification section 6. */
    public static final String CREATE_PERMISSION = "gzmn.worlds.create";

    public static final String JOIN_PERMISSION = "gzmn.worlds.join";
    public static final String PUBLIC_PERMISSION = "gzmn.worlds.public";
    public static final String ADMIN_PERMISSION = "gzmn.worlds.admin";

    /** Subcommands of {@code /world admin}, for the usage line and for tests. */
    public static final List<String> ADMIN_SUBCOMMANDS = List.of("list", "unload", "migrate", "drain", "transfer");

    /**
     * Subcommands that belong to the backend and must be forwarded (OQ-15).
     *
     * <p>{@code /world leave} is milestone 5 and {@code /world report} is
     * milestone 9; naming the list is what stops either from being silently
     * unreachable when it arrives.
     */
    public static final List<String> BACKEND_SUBCOMMANDS = List.of("leave", "report");

    private final ProxyServer proxy;
    private final PluginExecutors executors;
    private final PlayerWorldRepository worlds;
    private final MembershipRepository membership;
    private final TransferRequestRepository transferRequests;
    private final WorldBanRepository bans;
    private final PlayerNameRepository names;
    private final PendingTransferRepository transfers;
    private final NodeRegistry registry;
    private final Placement placement;
    private final NodeCommandRepository nodeCommands;
    private final Supplier<NetworkPolicy> policy;

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
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.executors = Objects.requireNonNull(executors, "executors");
        this.worlds = Objects.requireNonNull(worlds, "worlds");
        this.membership = Objects.requireNonNull(membership, "membership");
        this.transferRequests = Objects.requireNonNull(transferRequests, "transferRequests");
        this.bans = Objects.requireNonNull(bans, "bans");
        this.names = Objects.requireNonNull(names, "names");
        this.transfers = Objects.requireNonNull(transfers, "transfers");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.placement = Objects.requireNonNull(placement, "placement");
        this.nodeCommands = Objects.requireNonNull(nodeCommands, "nodeCommands");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /** Builds the Brigadier tree. */
    public BrigadierCommand build() {
        LiteralArgumentBuilder<CommandSource> root = BrigadierCommand.literalArgumentBuilder("world")
                .executes(context -> {
                    usage(context.getSource());
                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                })
                .then(BrigadierCommand.literalArgumentBuilder("invite")
                        .then(BrigadierCommand.requiredArgumentBuilder("player", StringArgumentType.word())
                                .suggests(this::suggestOnlinePlayers)
                                .executes(context -> {
                                    invite(context, StringArgumentType.getString(context, "player"));
                                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                })))
                .then(BrigadierCommand.literalArgumentBuilder("accept")
                        .requires(source -> source.hasPermission(JOIN_PERMISSION))
                        .then(BrigadierCommand.requiredArgumentBuilder("owner", StringArgumentType.word())
                                .executes(context -> {
                                    accept(context, StringArgumentType.getString(context, "owner"));
                                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                })))
                .then(BrigadierCommand.literalArgumentBuilder("kick")
                        .then(BrigadierCommand.requiredArgumentBuilder("player", StringArgumentType.word())
                                .executes(context -> {
                                    kick(context, StringArgumentType.getString(context, "player"));
                                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                })))
                .then(BrigadierCommand.literalArgumentBuilder("promote")
                        .then(BrigadierCommand.requiredArgumentBuilder("player", StringArgumentType.word())
                                .executes(context -> {
                                    promote(context, StringArgumentType.getString(context, "player"));
                                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                })))
                .then(BrigadierCommand.literalArgumentBuilder("transfer")
                        .then(BrigadierCommand.literalArgumentBuilder("accept")
                                .then(BrigadierCommand.requiredArgumentBuilder("owner", StringArgumentType.word())
                                        .executes(context -> {
                                            transferAccept(context, StringArgumentType.getString(context, "owner"));
                                            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                        })))
                        .then(BrigadierCommand.literalArgumentBuilder("decline")
                                .then(BrigadierCommand.requiredArgumentBuilder("owner", StringArgumentType.word())
                                        .executes(context -> {
                                            transferDecline(context, StringArgumentType.getString(context, "owner"));
                                            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                        })))
                        .then(BrigadierCommand.requiredArgumentBuilder("player", StringArgumentType.word())
                                .suggests(this::suggestOnlinePlayers)
                                .executes(context -> {
                                    transfer(context, StringArgumentType.getString(context, "player"), false);
                                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                })
                                .then(BrigadierCommand.literalArgumentBuilder("confirm")
                                        .executes(context -> {
                                            transfer(context, StringArgumentType.getString(context, "player"), true);
                                            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                        }))))
                .then(BrigadierCommand.literalArgumentBuilder("create")
                        .requires(source -> source.hasPermission(CREATE_PERMISSION))
                        .then(BrigadierCommand.requiredArgumentBuilder("name", StringArgumentType.word())
                                .executes(context -> {
                                    create(context, StringArgumentType.getString(context, "name"), null);
                                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                })
                                .then(BrigadierCommand.requiredArgumentBuilder("seed", StringArgumentType.word())
                                        .executes(context -> {
                                            create(
                                                    context,
                                                    StringArgumentType.getString(context, "name"),
                                                    StringArgumentType.getString(context, "seed"));
                                            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                        }))))
                .then(BrigadierCommand.literalArgumentBuilder("delete")
                        .then(BrigadierCommand.requiredArgumentBuilder("name", StringArgumentType.word())
                                .executes(context -> {
                                    delete(context, StringArgumentType.getString(context, "name"), false);
                                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                })
                                .then(BrigadierCommand.literalArgumentBuilder("confirm")
                                        .executes(context -> {
                                            delete(context, StringArgumentType.getString(context, "name"), true);
                                            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                        }))))
                .then(BrigadierCommand.literalArgumentBuilder("restore")
                        .then(BrigadierCommand.requiredArgumentBuilder("name", StringArgumentType.word())
                                .executes(context -> {
                                    restore(context, StringArgumentType.getString(context, "name"));
                                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                })))
                .then(BrigadierCommand.literalArgumentBuilder("join")
                        .requires(source -> source.hasPermission(JOIN_PERMISSION))
                        .then(BrigadierCommand.requiredArgumentBuilder("owner", StringArgumentType.word())
                                .executes(context -> {
                                    join(context, StringArgumentType.getString(context, "owner"), null);
                                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                })
                                .then(BrigadierCommand.requiredArgumentBuilder("name", StringArgumentType.word())
                                        .executes(context -> {
                                            join(
                                                    context,
                                                    StringArgumentType.getString(context, "owner"),
                                                    StringArgumentType.getString(context, "name"));
                                            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                        }))))
                .then(BrigadierCommand.literalArgumentBuilder("members").executes(context -> {
                    members(context);
                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                }))
                .then(BrigadierCommand.literalArgumentBuilder("list").executes(context -> {
                    list(context);
                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                }))
                .then(BrigadierCommand.literalArgumentBuilder("browse")
                        .requires(source -> source.hasPermission(JOIN_PERMISSION))
                        .executes(context -> {
                            browse(context);
                            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                        }))
                .then(BrigadierCommand.literalArgumentBuilder("public")
                        .requires(source -> source.hasPermission(PUBLIC_PERMISSION))
                        .then(BrigadierCommand.literalArgumentBuilder("on")
                                .executes(context -> {
                                    setPublic(context, true, null);
                                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                })
                                .then(BrigadierCommand.requiredArgumentBuilder(
                                                "description", StringArgumentType.greedyString())
                                        .executes(context -> {
                                            setPublic(
                                                    context,
                                                    true,
                                                    StringArgumentType.getString(context, "description"));
                                            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                        })))
                        .then(BrigadierCommand.literalArgumentBuilder("off").executes(context -> {
                            setPublic(context, false, null);
                            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                        })))
                .then(BrigadierCommand.literalArgumentBuilder("set")
                        .then(BrigadierCommand.requiredArgumentBuilder("setting", StringArgumentType.word())
                                .then(BrigadierCommand.requiredArgumentBuilder("value", StringArgumentType.word())
                                        .executes(context -> {
                                            setSetting(
                                                    context,
                                                    StringArgumentType.getString(context, "setting"),
                                                    StringArgumentType.getString(context, "value"));
                                            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                        }))))
                .then(BrigadierCommand.literalArgumentBuilder("settings").executes(context -> {
                    showSettings(context);
                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                }))
                .then(BrigadierCommand.literalArgumentBuilder("ban")
                        .then(BrigadierCommand.requiredArgumentBuilder("player", StringArgumentType.word())
                                .suggests(this::suggestOnlinePlayers)
                                .executes(context -> {
                                    ban(context, StringArgumentType.getString(context, "player"), null);
                                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                })
                                .then(BrigadierCommand.requiredArgumentBuilder(
                                                "reason", StringArgumentType.greedyString())
                                        .executes(context -> {
                                            ban(
                                                    context,
                                                    StringArgumentType.getString(context, "player"),
                                                    StringArgumentType.getString(context, "reason"));
                                            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                        }))))
                .then(BrigadierCommand.literalArgumentBuilder("unban")
                        .then(BrigadierCommand.requiredArgumentBuilder("player", StringArgumentType.word())
                                .executes(context -> {
                                    unban(context, StringArgumentType.getString(context, "player"));
                                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                })))
                .then(BrigadierCommand.literalArgumentBuilder("bans").executes(context -> {
                    listBans(context);
                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                }))
                .then(adminTree());
        return new BrigadierCommand(root);
    }

    /**
     * {@code /world admin} — section 6's operator entries, milestone 8's half.
     *
     * <p>{@code list}, {@code unload} and {@code migrate} are section 6's;
     * {@code drain} is not in that table, because section 6 predates section 12
     * and MN-22 gives draining no command name. It is here rather than absent
     * because MN-22 is an operational requirement with no other way to invoke it,
     * and it is flagged as an addition rather than folded in silently.
     *
     * <p>Every entry is gated on {@code gzmn.worlds.admin}. The gate is
     * {@code requires}, so Brigadier hides the whole subtree from a caller who
     * cannot use it rather than offering a completion that then refuses.
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
                                        }))));
    }

    // -----------------------------------------------------------------------
    // Subcommands
    // -----------------------------------------------------------------------

    /**
     * {@code /world create <name> [seed]} - FR-1, FR-1a.
     *
     * <p>Creation is a network operation, not a backend-local one (FR-1a): the
     * proxy resolves a node, and only then does generation begin. What the proxy
     * writes is the row; the node it routes to sees a {@code CREATING} world with
     * no folders on arrival and generates the overworld, because only a node can
     * run {@code createWorld} and FR-4's main-thread stall is its to pay.
     *
     * <p>FR-1a also requires the lease be acquired before any folder is created,
     * and it is: the insert writes {@code assigned_node} and {@code lease_expires}
     * in the same statement that creates the row, at generation 1, so the node the
     * player is then sent to finds a world it already holds.
     */
    private void create(
            CommandContext<CommandSource> context, String name, @org.jspecify.annotations.Nullable String seedText) {
        Player caller = playerOrNull(context);
        if (caller == null) {
            return;
        }
        NetworkPolicy current = policy.get();
        run(caller, () -> {
            UUID owner = caller.getUniqueId();
            int owned = worlds.countOwnedBy(owner);
            if (owned >= current.maxWorldsPerPlayer()) {
                error(caller, "you already own " + owned + " worlds (limit " + current.maxWorldsPerPlayer() + ")");
                return;
            }
            if (worlds.findByOwnerAndName(owner, name).isPresent()) {
                error(caller, "you already own a world called '" + name + "'");
                return;
            }

            // A world with no committed snapshot has no data version, so MN-28
            // excludes nothing and the decision is MN-15's scoring alone. The
            // visibility passed is the one the world is about to be created with,
            // so MN-15a's public/private separation applies from the first
            // placement rather than from the first migration.
            //
            // The id is generated once and used for both the placement and the
            // row: a second WorldId.random() would place one world and create a
            // different one, which is only invisible because placement does not
            // key on the id today.
            WorldId newId = WorldId.random();
            Visibility visibility = Visibility.valueOf(current.defaultVisibility());
            PlacementDecision decision = placement.forNewWorld(newId, visibility, current);
            String nodeId = routableNodeOrExplain(caller, decision, newId);
            if (nodeId == null) {
                return;
            }
            var targetServer = registry.server(nodeId);
            if (targetServer.isEmpty()) {
                error(caller, "that server is not routable right now");
                return;
            }

            long seed = seedText == null ? new java.security.SecureRandom().nextLong() : parseSeed(seedText);
            PlayerWorld world = worlds.create(
                    newId,
                    owner,
                    name,
                    seed,
                    current.defaultBorderRadius(),
                    visibility,
                    nodeId,
                    current.leaseDuration());

            transfers.route(owner, world.id(), nodeId, world.generation());
            info(caller, "creating '" + name + "' on " + nodeId + "; this may take a few seconds...");
            caller.createConnectionRequest(targetServer.get()).fireAndForget();
        });
    }

    /** Vanilla treats a non-numeric seed as text and hashes it; matching that is least surprising. */
    private static long parseSeed(String seedText) {
        try {
            return Long.parseLong(seedText);
        } catch (NumberFormatException e) {
            return seedText.hashCode();
        }
    }

    /**
     * {@code /world delete <name> [confirm]} - FR-27, in part.
     *
     * <p>FR-27 performs FR-35's archival: pack all three dimensions to object
     * storage, verify the checksum, then remove the live folders. Object storage
     * is milestone 6 and archival is milestone 11, so what this does today is the
     * half that can be done safely - the state transition and the cap release.
     * The world folders and every profile stay exactly where they are.
     *
     * <p>That ordering is deliberate and is CONTRIBUTING rule 8: a destructive
     * path verifies before it destroys. Nothing here has anything to verify
     * against yet, so nothing here destroys. {@code /world restore} brings the
     * world straight back.
     */
    private void delete(CommandContext<CommandSource> context, String name, boolean confirmed) {
        Player caller = playerOrNull(context);
        if (caller == null) {
            return;
        }
        NetworkPolicy current = policy.get();
        run(caller, () -> {
            Optional<PlayerWorld> found = worlds.findByOwnerAndName(caller.getUniqueId(), name);
            if (found.isEmpty()) {
                error(caller, "you own no world called '" + name + "'");
                return;
            }
            PlayerWorld world = found.get();
            if (world.state() == WorldState.ARCHIVED) {
                info(caller, "'" + name + "' is already archived; use /world restore " + name + " to bring it back");
                return;
            }
            if (world.state() != WorldState.READY && world.state() != WorldState.CREATING) {
                error(caller, "'" + name + "' is " + world.state() + " and cannot be deleted right now");
                return;
            }
            if (!confirmed) {
                if (world.state() == WorldState.CREATING) {
                    error(
                            caller,
                            "'" + name
                                    + "' was never completed. This removes the incomplete world and frees your slot.");
                } else {
                    // FR-27 requires typed confirmation, and this is the whole of it:
                    // the player has to type the world's name a second time.
                    error(caller, "this archives '" + name + "' and frees a world slot.");
                }
                info(caller, "type /world delete " + name + " confirm to go ahead");
                return;
            }

            if (world.state() == WorldState.CREATING) {
                if (!worlds.deleteIfCreating(world.id())) {
                    error(caller, "'" + name + "' changed while you were confirming; try again");
                    return;
                }
                enqueueToWorldOrAliveNodes(world, CommandKind.UNLOAD_WORLD, NodeCommand.EMPTY_PAYLOAD, current);
                success(caller, "removed incomplete world '" + name + "'; you have a world slot free");
                log.info("world {} removed while in CREATING state by its owner", world.id());
                return;
            }

            if (!worlds.transitionState(world.id(), WorldState.READY, WorldState.ARCHIVED)) {
                error(caller, "'" + name + "' changed while you were confirming; try again");
                return;
            }
            enqueueToWorldOrAliveNodes(world, CommandKind.UNLOAD_WORLD, NodeCommand.EMPTY_PAYLOAD, current);
            success(caller, "archived '" + name + "'; you have a world slot free");
            info(
                    caller,
                    "nothing was erased - the world data is still on its server and /world restore " + name
                            + " brings it back");
            log.info(
                    "world {} archived by its owner (FR-27, state transition only; FR-35's pack to object storage "
                            + "arrives with milestone 11, so the folders are retained)",
                    world.id());
        });
    }

    /**
     * {@code /world restore <name>} - FR-36, in part.
     *
     * <p>FR-36 unpacks the archive and verifies its checksum. Until milestone 11
     * writes one there is nothing to unpack: the folders were never removed, so a
     * restore is the state transition back.
     */
    private void restore(CommandContext<CommandSource> context, String name) {
        Player caller = playerOrNull(context);
        if (caller == null) {
            return;
        }
        NetworkPolicy current = policy.get();
        run(caller, () -> {
            Optional<PlayerWorld> found = worlds.findByOwnerAndName(caller.getUniqueId(), name);
            if (found.isEmpty()) {
                error(caller, "you own no world called '" + name + "'");
                return;
            }
            PlayerWorld world = found.get();
            if (world.state() != WorldState.ARCHIVED) {
                error(caller, "'" + name + "' is " + world.state() + " and does not need restoring");
                return;
            }
            // FR-32's pattern: the cap is re-checked at the moment the world
            // re-enters the count, not only when it left it.
            int owned = worlds.countOwnedBy(caller.getUniqueId());
            if (owned >= current.maxWorldsPerPlayer()) {
                error(
                        caller,
                        "you already own " + owned + " worlds (limit " + current.maxWorldsPerPlayer()
                                + "); archive one before restoring this");
                return;
            }
            if (!worlds.transitionState(world.id(), WorldState.ARCHIVED, WorldState.READY)) {
                error(caller, "'" + name + "' changed while you were restoring; try again");
                return;
            }
            success(caller, "restored '" + name + "'");
        });
    }

    /**
     * {@code /world join <owner> [name]} — FR-10.
     *
     * <p>Membership is validated here, on the proxy, because the proxy is the one
     * component that can answer the question without the world being loaded — and
     * by FR-25 a world is unloaded most of the time.
     *
     * <p>A player who is not a member gets the same answer as one asking about a
     * world that does not exist. Confirming a private world exists to somebody who
     * was never invited is the leak section 5.5 exists to prevent.
     */
    private void join(
            CommandContext<CommandSource> context,
            String ownerName,
            @org.jspecify.annotations.Nullable String worldName) {
        Player caller = playerOrNull(context);
        if (caller == null) {
            return;
        }
        NetworkPolicy current = policy.get();
        run(caller, () -> {
            Optional<UUID> owner = resolvePlayer(ownerName);
            if (owner.isEmpty()) {
                error(caller, "no world you can join matches that");
                return;
            }
            List<PlayerWorld> owned = worlds.listOwnedBy(owner.get());
            Optional<PlayerWorld> target = owned.stream()
                    .filter(world -> worldName == null || world.name().equalsIgnoreCase(worldName))
                    .filter(world -> world.state() == nl.gzmn.playerworlds.core.model.WorldState.READY
                            || world.state() == nl.gzmn.playerworlds.core.model.WorldState.CREATING)
                    .findFirst();
            if (target.isEmpty()) {
                error(caller, "no world you can join matches that");
                return;
            }

            PlayerWorld world = target.get();

            if (bans.isBanned(world.id(), caller.getUniqueId())) {
                Optional<WorldBan> ban = bans.findBan(world.id(), caller.getUniqueId());
                String reason = ban.flatMap(b -> Optional.ofNullable(b.reason()))
                        .map(r -> ": " + r)
                        .orElse("");
                error(caller, "you are banned from '" + world.name() + "'" + reason);
                return;
            }

            if (membership.findMember(world.id(), caller.getUniqueId()).isEmpty()) {
                if (world.visibility() == Visibility.PUBLIC) {
                    membership.addVisitorIfAbsent(world.id(), caller.getUniqueId());
                } else {
                    error(caller, "no world you can join matches that");
                    return;
                }
            }

            // MN-14, in its order: if the world holds a live lease, route to that
            // node; only if it does not is a node selected and the lease acquired.
            // Selecting first and then checking would send the second member of a
            // loaded world to whichever node is emptiest, where the acquisition
            // then fails against the holder's live lease — and MN-16 requires that
            // every member of a world resolve to the same node.
            PlacementDecision decision = placement.forExistingWorld(world.id(), current);
            String nodeId = routableNodeOrExplain(caller, decision, world.id());
            if (nodeId == null) {
                return;
            }

            long routingGeneration = world.generation();
            if (decision instanceof PlacementDecision.Selected selected) {
                Optional<PlayerWorldRepository.LeaseGrant> grant =
                        worlds.acquireLease(world.id(), nodeId, selected.node().dataVersion(), current.leaseDuration());
                if (grant.isEmpty()) {
                    // Another proxy or another join won the race between the read
                    // and the acquire. Harmless and expected: MN-8 is what makes it
                    // safe, and the next attempt sees the lease and routes to it.
                    error(caller, "that world is being opened elsewhere right now; try again in a moment");
                    return;
                }
                routingGeneration = grant.get().generation();
            } else {
                // Held: route on the holder's current generation, not on whatever
                // the row said when the owner's world list was read a moment ago.
                Optional<PlayerWorld> fresh = worlds.findById(world.id());
                if (fresh.isPresent()) {
                    routingGeneration = fresh.get().generation();
                }
            }

            transfers.route(caller.getUniqueId(), world.id(), nodeId, routingGeneration);
            var targetServer = registry.server(nodeId);
            if (targetServer.isEmpty()) {
                error(caller, "that server is not routable right now");
                return;
            }
            info(caller, "sending you to '" + world.name() + "'...");
            // fireAndForget: the node decides what happens on arrival by reading
            // the pending_transfer written above, so there is nothing to await here.
            caller.createConnectionRequest(targetServer.get()).fireAndForget();
        });
    }

    /** {@code /world invite <player>} — FR-6. */
    private void invite(CommandContext<CommandSource> context, String targetName) {
        Player caller = playerOrNull(context);
        if (caller == null) {
            return;
        }
        NetworkPolicy current = policy.get();
        run(caller, () -> {
            Optional<PlayerWorld> world = soleOwnedWorld(caller);
            if (world.isEmpty()) {
                return;
            }
            Optional<UUID> target = resolvePlayer(targetName);
            if (target.isEmpty()) {
                error(caller, "no player called '" + targetName + "' has been seen on this network");
                return;
            }
            if (target.get().equals(caller.getUniqueId())) {
                error(caller, "you are already the owner of that world");
                return;
            }
            if (membership.findMember(world.get().id(), target.get()).isPresent()) {
                error(
                        caller,
                        targetName + " is already a member of '" + world.get().name() + "'");
                return;
            }

            membership.invite(world.get().id(), target.get(), caller.getUniqueId(), current.inviteExpiry());
            success(
                    caller,
                    "invited " + targetName + " to '" + world.get().name() + "'; the invite expires in "
                            + current.inviteExpiry().toMinutes() + " minutes");

            // FR-6's reason for this command living on the proxy: the target may
            // be on any server, and only the proxy can reach them.
            proxy.getPlayer(target.get())
                    .ifPresent(online -> online.sendMessage(Component.text(
                            caller.getUsername() + " invited you to their world '"
                                    + world.get().name() + "'. Use /world accept " + caller.getUsername(),
                            NamedTextColor.GREEN)));
        });
    }

    /** {@code /world accept <owner>} — FR-7. */
    private void accept(CommandContext<CommandSource> context, String ownerName) {
        Player caller = playerOrNull(context);
        if (caller == null) {
            return;
        }
        run(caller, () -> {
            Optional<UUID> owner = resolvePlayer(ownerName);
            if (owner.isEmpty()) {
                error(caller, "no player called '" + ownerName + "' has been seen on this network");
                return;
            }
            List<PlayerWorld> owned = worlds.listOwnedBy(owner.get());
            for (PlayerWorld world : owned) {
                switch (membership.acceptInvite(world.id(), caller.getUniqueId())) {
                    case MembershipRepository.AcceptOutcome.Accepted accepted -> {
                        success(caller, "you are now a " + accepted.member().role() + " of '" + world.name() + "'");
                        // FR-7 also says this sends them to the world. That is the
                        // pending_transfer handoff and arrives with milestone 5.
                        info(
                                caller,
                                "joining a world from here arrives with the transfer path; "
                                        + "use /pworld on the node until then");
                        return;
                    }
                    case MembershipRepository.AcceptOutcome.AlreadyMember already -> {
                        info(caller, "you were already a " + already.role() + " of '" + world.name() + "'");
                        return;
                    }
                    case MembershipRepository.AcceptOutcome.NoLiveInvite ignored -> {
                        // Try the owner's next world.
                    }
                }
            }
            error(caller, "you have no live invite from " + ownerName);
        });
    }

    /** {@code /world kick <player>} — FR-8. */
    private void kick(CommandContext<CommandSource> context, String targetName) {
        Player caller = playerOrNull(context);
        if (caller == null) {
            return;
        }
        NetworkPolicy current = policy.get();
        run(caller, () -> {
            Optional<PlayerWorld> world = soleOwnedWorld(caller);
            if (world.isEmpty()) {
                return;
            }
            Optional<UUID> target = resolvePlayer(targetName);
            if (target.isEmpty()) {
                error(caller, "no player called '" + targetName + "' has been seen on this network");
                return;
            }
            if (target.get().equals(world.get().ownerUuid())) {
                error(caller, "you cannot kick yourself from your own world; use /world transfer or /world delete");
                return;
            }
            // Withdraw any outstanding invite too, or a kicked player can walk
            // straight back in by accepting it.
            membership.revokeInvite(world.get().id(), target.get());
            if (!membership.removeMember(world.get().id(), target.get())) {
                error(caller, targetName + " is not a member of '" + world.get().name() + "'");
                return;
            }
            enqueueToWorldOrAliveNodes(world.get(), CommandKind.INVALIDATE_CACHE, NodeCommand.EMPTY_PAYLOAD, current);
            enqueueToWorldOrAliveNodes(
                    world.get(),
                    CommandKind.KICK_MEMBER,
                    EjectPayload.format(target.get(), "You were removed from this world"),
                    current);
            success(caller, "removed " + targetName + " from '" + world.get().name() + "'");
            // FR-8 also requires an online member be ejected to lobby immediately.
            info(caller, "if they are inside the world right now they will be removed on their next join");
        });
    }

    /** {@code /world promote <player>} — FR-9c. */
    private void promote(CommandContext<CommandSource> context, String targetName) {
        Player caller = playerOrNull(context);
        if (caller == null) {
            return;
        }
        NetworkPolicy current = policy.get();
        run(caller, () -> {
            Optional<PlayerWorld> world = soleOwnedWorld(caller);
            if (world.isEmpty()) {
                return;
            }
            Optional<UUID> target = resolvePlayer(targetName);
            if (target.isEmpty()) {
                error(caller, "no player called '" + targetName + "' has been seen on this network");
                return;
            }
            if (membership.setRole(world.get().id(), target.get(), Role.BUILDER)) {
                enqueueToWorldOrAliveNodes(
                        world.get(), CommandKind.INVALIDATE_CACHE, NodeCommand.EMPTY_PAYLOAD, current);
                success(
                        caller,
                        targetName + " is now a BUILDER of '" + world.get().name() + "'");
            } else {
                error(caller, targetName + " is not a member of '" + world.get().name() + "', or is its owner");
            }
        });
    }

    /**
     * {@code /world transfer <player> [confirm]} — FR-29, FR-30, FR-31, FR-32.
     */
    private void transfer(CommandContext<CommandSource> context, String targetName, boolean confirmed) {
        Player caller = playerOrNull(context);
        if (caller == null) {
            return;
        }
        NetworkPolicy current = policy.get();
        run(caller, () -> {
            Optional<PlayerWorld> world = soleOwnedWorld(caller);
            if (world.isEmpty()) {
                return;
            }
            Optional<UUID> target = resolvePlayer(targetName);
            if (target.isEmpty()) {
                error(caller, "no player called '" + targetName + "' has been seen on this network");
                return;
            }
            if (target.get().equals(caller.getUniqueId())) {
                error(caller, "you already own this world");
                return;
            }
            if (membership.findMember(world.get().id(), target.get()).isEmpty()) {
                error(caller, targetName + " is not a member of '" + world.get().name() + "'");
                return;
            }
            int ownedCount = worlds.countOwnedBy(target.get());
            if (ownedCount >= current.maxWorldsPerPlayer()) {
                error(caller, targetName + " has reached their world limit (" + current.maxWorldsPerPlayer() + ")");
                return;
            }

            if (!confirmed) {
                info(
                        caller,
                        "Are you sure you want to transfer ownership of '"
                                + world.get().name()
                                + "' to " + targetName + "? You will become a BUILDER. Type /world transfer "
                                + targetName + " confirm to proceed.");
                return;
            }

            Optional<Player> online = proxy.getPlayer(target.get());
            if (online.isPresent()) {
                // Online -> immediate transfer (FR-31)
                if (!worlds.transferOwnership(world.get().id(), caller.getUniqueId(), target.get(), "MANUAL")) {
                    error(
                            caller,
                            "could not transfer ownership of '" + world.get().name() + "'");
                    return;
                }
                enqueueToWorldOrAliveNodes(
                        world.get(), CommandKind.INVALIDATE_CACHE, NodeCommand.EMPTY_PAYLOAD, current);
                success(
                        caller,
                        "transferred ownership of '" + world.get().name() + "' to " + targetName
                                + "; you are now a BUILDER");
                online.get()
                        .sendMessage(Component.text(
                                "You are now the owner of '" + world.get().name() + "'!", NamedTextColor.GREEN));
            } else {
                // Offline -> create pending transfer request (FR-32)
                transferRequests.requestTransfer(
                        world.get().id(), target.get(), caller.getUniqueId(), current.transferPendingExpiry());
                success(
                        caller,
                        "created transfer request for " + targetName + "; they can accept it next time they log in");
            }
        });
    }

    /**
     * {@code /world transfer accept <owner>} — FR-32.
     */
    private void transferAccept(CommandContext<CommandSource> context, String ownerName) {
        Player caller = playerOrNull(context);
        if (caller == null) {
            return;
        }
        NetworkPolicy current = policy.get();
        run(caller, () -> {
            Optional<UUID> owner = resolvePlayer(ownerName);
            if (owner.isEmpty()) {
                error(caller, "no player called '" + ownerName + "' has been seen on this network");
                return;
            }
            List<TransferRequest> pending = transferRequests.findLiveRequestsFor(caller.getUniqueId());
            Optional<TransferRequest> matching = pending.stream()
                    .filter(r -> r.fromUuid().equals(owner.get()))
                    .findFirst();
            if (matching.isEmpty()) {
                error(caller, "you have no pending transfer requests from " + ownerName);
                return;
            }
            int ownedCount = worlds.countOwnedBy(caller.getUniqueId());
            if (ownedCount >= current.maxWorldsPerPlayer()) {
                error(caller, "you have reached your world limit (" + current.maxWorldsPerPlayer() + ")");
                return;
            }
            Optional<PlayerWorld> worldOpt = worlds.findById(matching.get().worldId());
            if (worldOpt.isEmpty()) {
                error(caller, "that world no longer exists");
                return;
            }
            PlayerWorld world = worldOpt.get();
            if (!world.ownerUuid().equals(owner.get())) {
                transferRequests.deleteRequest(world.id(), caller.getUniqueId());
                error(caller, ownerName + " is no longer the owner of '" + world.name() + "'");
                return;
            }
            if (!worlds.transferOwnership(world.id(), owner.get(), caller.getUniqueId(), "MANUAL")) {
                error(caller, "could not accept transfer of '" + world.name() + "'");
                return;
            }
            enqueueToWorldOrAliveNodes(world, CommandKind.INVALIDATE_CACHE, NodeCommand.EMPTY_PAYLOAD, current);
            success(caller, "you are now the owner of '" + world.name() + "'!");
            proxy.getPlayer(owner.get())
                    .ifPresent(online -> online.sendMessage(Component.text(
                            caller.getUsername() + " accepted ownership transfer of '" + world.name() + "'!",
                            NamedTextColor.GREEN)));
        });
    }

    /**
     * {@code /world transfer decline <owner>} — FR-32.
     */
    private void transferDecline(CommandContext<CommandSource> context, String ownerName) {
        Player caller = playerOrNull(context);
        if (caller == null) {
            return;
        }
        run(caller, () -> {
            Optional<UUID> owner = resolvePlayer(ownerName);
            if (owner.isEmpty()) {
                error(caller, "no player called '" + ownerName + "' has been seen on this network");
                return;
            }
            List<TransferRequest> pending = transferRequests.findLiveRequestsFor(caller.getUniqueId());
            Optional<TransferRequest> matching = pending.stream()
                    .filter(r -> r.fromUuid().equals(owner.get()))
                    .findFirst();
            if (matching.isEmpty()) {
                error(caller, "you have no pending transfer requests from " + ownerName);
                return;
            }
            transferRequests.deleteRequest(matching.get().worldId(), caller.getUniqueId());
            success(caller, "declined transfer request from " + ownerName);
            proxy.getPlayer(owner.get())
                    .ifPresent(online -> online.sendMessage(Component.text(
                            caller.getUsername() + " declined ownership transfer of your world",
                            NamedTextColor.YELLOW)));
        });
    }

    /** {@code /world members} — FR-8. */
    private void members(CommandContext<CommandSource> context) {
        Player caller = playerOrNull(context);
        if (caller == null) {
            return;
        }
        run(caller, () -> {
            Optional<PlayerWorld> world = soleOwnedWorld(caller);
            if (world.isEmpty()) {
                return;
            }
            List<WorldMember> list = membership.listMembers(world.get().id());
            Map<UUID, String> resolved =
                    names.namesOf(list.stream().map(WorldMember::uuid).toList());
            info(caller, "members of '" + world.get().name() + "':");
            for (WorldMember member : list) {
                // A cache miss renders the UUID rather than failing the command.
                String display =
                        resolved.getOrDefault(member.uuid(), member.uuid().toString());
                info(caller, "  " + display + "  " + member.role());
            }
        });
    }

    /** {@code /world list} — lists owned and member worlds. */
    private void list(CommandContext<CommandSource> context) {
        Player caller = playerOrNull(context);
        if (caller == null) {
            return;
        }
        run(caller, () -> {
            UUID callerUuid = caller.getUniqueId();
            List<PlayerWorld> owned = worlds.listOwnedBy(callerUuid);
            List<WorldMember> memberships = membership.membershipsOf(callerUuid).stream()
                    .filter(m -> m.role() != Role.OWNER)
                    .toList();

            if (owned.isEmpty() && memberships.isEmpty()) {
                info(caller, "You do not own or belong to any worlds yet. Use /world create <name> to create one.");
                return;
            }

            info(caller, "Your worlds:");
            if (owned.isEmpty()) {
                info(caller, "  (none)");
            } else {
                for (PlayerWorld world : owned) {
                    info(
                            caller,
                            "  • " + world.name() + " [" + world.state() + "] (visibility: " + world.visibility()
                                    + ")");
                }
            }

            info(caller, "");
            info(caller, "Shared worlds (member):");
            if (memberships.isEmpty()) {
                info(caller, "  (none)");
            } else {
                List<UUID> ownerUuids = new ArrayList<>();
                List<PlayerWorld> sharedWorlds = new ArrayList<>();
                for (WorldMember m : memberships) {
                    Optional<PlayerWorld> pw = worlds.findById(m.worldId());
                    if (pw.isPresent()) {
                        sharedWorlds.add(pw.get());
                        ownerUuids.add(pw.get().ownerUuid());
                    }
                }
                Map<UUID, String> ownerNames = names.namesOf(ownerUuids);
                for (int i = 0; i < sharedWorlds.size(); i++) {
                    PlayerWorld sw = sharedWorlds.get(i);
                    WorldMember m = memberships.get(i);
                    String ownerDisplayName = ownerNames.getOrDefault(
                            sw.ownerUuid(), sw.ownerUuid().toString());
                    info(caller, "  • " + sw.name() + " (Owner: " + ownerDisplayName + ") - " + m.role());
                }
            }
        });
    }

    /** {@code /world browse} — FR-9b. */
    private void browse(CommandContext<CommandSource> context) {
        CommandSource source = context.getSource();
        runAsAdmin(source, () -> {
            List<PlayerWorld> publicWorlds = worlds.listPublicWorlds();
            if (publicWorlds.isEmpty()) {
                info(source, "There are no public worlds available right now.");
                return;
            }
            List<UUID> owners =
                    publicWorlds.stream().map(PlayerWorld::ownerUuid).toList();
            Map<UUID, String> ownerNames = names.namesOf(owners);

            info(source, "Public worlds:");
            for (PlayerWorld w : publicWorlds) {
                String ownerName =
                        ownerNames.getOrDefault(w.ownerUuid(), w.ownerUuid().toString());
                String desc = w.description() != null ? " - \"" + w.description() + "\"" : "";
                String status = (w.assignedNode() != null && w.leaseExpires() != null)
                        ? "[LOADED on " + w.assignedNode() + "]"
                        : "[UNLOADED]";
                info(source, "  • " + w.name() + " (Owner: " + ownerName + ") " + status + desc);
            }
        });
    }

    /** {@code /world public on|off [description]} — FR-9a, FR-9f, FR-9h. */
    private void setPublic(
            CommandContext<CommandSource> context,
            boolean isPublic,
            @org.jspecify.annotations.Nullable String description) {
        Player caller = playerOrNull(context);
        if (caller == null) {
            return;
        }
        NetworkPolicy current = policy.get();
        run(caller, () -> {
            Optional<PlayerWorld> world = soleOwnedWorld(caller);
            if (world.isEmpty()) {
                return;
            }
            Visibility visibility = isPublic ? Visibility.PUBLIC : Visibility.PRIVATE;
            String desc =
                    isPublic ? (description != null ? description : world.get().description()) : null;

            if (!worlds.updateVisibility(world.get().id(), visibility, desc)) {
                error(caller, "could not update world visibility; try again");
                return;
            }
            enqueueToWorldOrAliveNodes(world.get(), CommandKind.INVALIDATE_CACHE, NodeCommand.EMPTY_PAYLOAD, current);

            if (isPublic) {
                success(
                        caller,
                        "'" + world.get().name() + "' is now PUBLIC"
                                + (desc != null ? " (\"" + desc + "\")" : "")
                                + "; strangers can now browse and join as visitors");
            } else {
                success(caller, "'" + world.get().name() + "' is now PRIVATE; existing members are still members");
            }
        });
    }

    /** {@code /world set <setting> <value>} — FR-9e. */
    private void setSetting(CommandContext<CommandSource> context, String settingName, String valueStr) {
        Player caller = playerOrNull(context);
        if (caller == null) {
            return;
        }
        NetworkPolicy current = policy.get();
        run(caller, () -> {
            Optional<PlayerWorld> world = soleOwnedWorld(caller);
            if (world.isEmpty()) {
                return;
            }
            WorldSettings settings = WorldSettings.fromJson(world.get().settingsJson());
            WorldSettings updated;
            String normKey = settingName.toLowerCase(Locale.ROOT);
            String normVal = valueStr.toLowerCase(Locale.ROOT);
            boolean boolVal = normVal.equals("on")
                    || normVal.equals("true")
                    || normVal.equals("allow")
                    || normVal.equals("yes")
                    || normVal.equals("enable");

            switch (normKey) {
                case "pvp" -> updated = settings.withPvp(boolVal);
                case "containers" -> updated = settings.withVisitorsMayOpenContainers(boolVal);
                case "interact", "redstone", "doors" -> updated = settings.withVisitorsMayInteract(boolVal);
                case "mob-griefing", "mobgriefing" -> updated = settings.withMobGriefing(boolVal);
                default -> {
                    error(
                            caller,
                            "unknown setting '" + settingName
                                    + "'; valid settings: pvp, containers, interact, mob-griefing");
                    return;
                }
            }

            if (!worlds.updateSettings(world.get().id(), updated.toJson())) {
                error(caller, "could not update world settings; try again");
                return;
            }
            enqueueToWorldOrAliveNodes(world.get(), CommandKind.INVALIDATE_CACHE, NodeCommand.EMPTY_PAYLOAD, current);
            success(
                    caller,
                    "set " + normKey + " = " + boolVal + " for '" + world.get().name() + "'");
        });
    }

    /** {@code /world settings} — displays current settings (FR-9e). */
    private void showSettings(CommandContext<CommandSource> context) {
        Player caller = playerOrNull(context);
        if (caller == null) {
            return;
        }
        run(caller, () -> {
            Optional<PlayerWorld> world = soleOwnedWorld(caller);
            if (world.isEmpty()) {
                return;
            }
            WorldSettings settings = WorldSettings.fromJson(world.get().settingsJson());
            info(caller, "Settings for '" + world.get().name() + "':");
            info(caller, "  PVP: " + (settings.pvp() ? "on" : "off"));
            info(caller, "  Visitors may open containers: " + (settings.visitorsMayOpenContainers() ? "on" : "off"));
            info(
                    caller,
                    "  Visitors may interact (doors/buttons/redstone): "
                            + (settings.visitorsMayInteract() ? "on" : "off"));
            info(caller, "  Mob griefing: " + (settings.mobGriefing() ? "on" : "off"));
        });
    }

    /** {@code /world ban <player> [reason]} — FR-9d. */
    private void ban(
            CommandContext<CommandSource> context,
            String targetName,
            @org.jspecify.annotations.Nullable String reason) {
        Player caller = playerOrNull(context);
        if (caller == null) {
            return;
        }
        NetworkPolicy current = policy.get();
        run(caller, () -> {
            Optional<PlayerWorld> world = soleOwnedWorld(caller);
            if (world.isEmpty()) {
                return;
            }
            Optional<UUID> target = resolvePlayer(targetName);
            if (target.isEmpty()) {
                error(caller, "no player called '" + targetName + "' has been seen on this network");
                return;
            }
            if (target.get().equals(world.get().ownerUuid())) {
                error(caller, "you cannot ban yourself from your own world");
                return;
            }

            bans.ban(world.get().id(), target.get(), caller.getUniqueId(), reason);
            membership.revokeInvite(world.get().id(), target.get());
            membership.removeMember(world.get().id(), target.get());

            enqueueToWorldOrAliveNodes(world.get(), CommandKind.INVALIDATE_CACHE, NodeCommand.EMPTY_PAYLOAD, current);
            String ejectReason = "Banned from world" + (reason != null ? ": " + reason : "");
            enqueueToWorldOrAliveNodes(
                    world.get(), CommandKind.KICK_MEMBER, EjectPayload.format(target.get(), ejectReason), current);

            success(caller, "banned " + targetName + " from '" + world.get().name() + "'");
        });
    }

    /** {@code /world unban <player>} — FR-9d. */
    private void unban(CommandContext<CommandSource> context, String targetName) {
        Player caller = playerOrNull(context);
        if (caller == null) {
            return;
        }
        run(caller, () -> {
            Optional<PlayerWorld> world = soleOwnedWorld(caller);
            if (world.isEmpty()) {
                return;
            }
            Optional<UUID> target = resolvePlayer(targetName);
            if (target.isEmpty()) {
                error(caller, "no player called '" + targetName + "' has been seen on this network");
                return;
            }
            if (bans.unban(world.get().id(), target.get())) {
                success(
                        caller,
                        "unbanned " + targetName + " from '" + world.get().name() + "'");
            } else {
                error(
                        caller,
                        targetName + " was not banned from '" + world.get().name() + "'");
            }
        });
    }

    /** {@code /world bans} — lists bans (FR-9d). */
    private void listBans(CommandContext<CommandSource> context) {
        Player caller = playerOrNull(context);
        if (caller == null) {
            return;
        }
        run(caller, () -> {
            Optional<PlayerWorld> world = soleOwnedWorld(caller);
            if (world.isEmpty()) {
                return;
            }
            List<WorldBan> list = bans.listBans(world.get().id());
            if (list.isEmpty()) {
                info(
                        caller,
                        "No players are currently banned from '" + world.get().name() + "'.");
                return;
            }
            List<UUID> targets = list.stream().map(WorldBan::uuid).toList();
            Map<UUID, String> resolved = names.namesOf(targets);

            info(caller, "Bans for '" + world.get().name() + "':");
            for (WorldBan b : list) {
                String name = resolved.getOrDefault(b.uuid(), b.uuid().toString());
                String r = b.reason() != null ? " (" + b.reason() + ")" : "";
                info(caller, "  • " + name + r);
            }
        });
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * The caller's world for commands that act on "their" world.
     *
     * <p>Milestone 2 has no world-name argument on these commands, because
     * section 6 gives none either — {@code /world invite <player>} names a player,
     * not a world. With {@code worlds.max-per-player} defaulting to 2 that is
     * ambiguous the moment somebody owns two, so this refuses rather than
     * guessing, and says how to disambiguate once milestone 5 gives it a way to.
     */
    private Optional<PlayerWorld> soleOwnedWorld(Player caller) throws SQLException {
        List<PlayerWorld> owned = worlds.listOwnedBy(caller.getUniqueId());
        if (owned.isEmpty()) {
            error(caller, "you do not own a world yet");
            return Optional.empty();
        }
        if (owned.size() > 1) {
            error(
                    caller,
                    "you own " + owned.size()
                            + " worlds and these commands do not take a world name yet; that arrives with the "
                            + "transfer path in milestone 5");
            return Optional.empty();
        }
        return Optional.of(owned.getFirst());
    }

    /**
     * A player name to a UUID.
     *
     * <p>Online players resolve through the proxy, which is authoritative and
     * free. Everyone else comes from the {@code player_name} cache, which the
     * proxy fills on login — so an offline player is resolvable exactly when they
     * have logged in at least once since the cache was introduced.
     */
    private Optional<UUID> resolvePlayer(String name) throws SQLException {
        Optional<Player> online = proxy.getPlayer(name);
        if (online.isPresent()) {
            return Optional.of(online.get().getUniqueId());
        }
        return names.uuidOf(name);
    }

    /** Runs database work off the command thread and reports a failure once. */
    private void run(Player caller, SqlTask task) {
        executors.db().execute(() -> {
            try {
                task.run();
            } catch (SQLException e) {
                log.error("/world command failed for {}", caller.getUsername(), e);
                error(caller, "that did not work; the failure is in the proxy log");
            }
        });
    }

    private static com.velocitypowered.api.proxy.@org.jspecify.annotations.Nullable Player playerOrNull(
            CommandContext<CommandSource> context) {
        if (context.getSource() instanceof Player player) {
            return player;
        }
        context.getSource()
                .sendMessage(Component.text(
                        "/world acts on the caller's own worlds and must be run by a player", NamedTextColor.RED));
        return null;
    }

    private java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestOnlinePlayers(
            CommandContext<CommandSource> context, com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        // Online players only. FR-24c is explicit that a database query per
        // keystroke is the wrong shape, and the proxy already holds this list.
        for (Player player : proxy.getAllPlayers()) {
            if (player.getUsername()
                    .toLowerCase(java.util.Locale.ROOT)
                    .startsWith(builder.getRemaining().toLowerCase(java.util.Locale.ROOT))) {
                builder.suggest(player.getUsername());
            }
        }
        return builder.buildFuture();
    }

    private static void usage(CommandSource source) {
        source.sendMessage(Component.text("/world <" + String.join("|", SUBCOMMANDS) + ">", NamedTextColor.YELLOW));
    }

    private static void info(CommandSource source, String message) {
        source.sendMessage(Component.text(message, NamedTextColor.GRAY));
    }

    private static void success(CommandSource source, String message) {
        source.sendMessage(Component.text(message, NamedTextColor.GREEN));
    }

    private static void error(CommandSource source, String message) {
        source.sendMessage(Component.text(message, NamedTextColor.RED));
    }

    // -----------------------------------------------------------------------
    // /world admin (section 6; MN-21, MN-22)
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
                                + " tps=" + (tps == null ? "?" : String.format(java.util.Locale.ROOT, "%.1f", tps))
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
                // By FR-25 a world is unloaded most of the time, and an unload of
                // an unloaded world is not an error to report as one.
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
     *
     * <p>The proxy drives the two halves in MN-8's only safe order. It asks the
     * holding node to give the world up — countdown, eject, commit, unload,
     * release — waits for that command to complete, and only then acquires the
     * lease on the target. There is no moment at which two nodes hold it, and no
     * moment at which the target could load a world whose final snapshot has not
     * been committed.
     *
     * <p>The players are not carried across by this command. They were sent to the
     * lobby by MN-19's ejection, which is where MN-19 puts them, and their next
     * {@code /world join} resolves to the target: it now holds the lease, and
     * MN-14 routes a live lease without scoring.
     */
    private void adminMigrate(CommandSource source, String rawId, String targetNode) {
        Optional<WorldId> parsed = parseWorldId(source, rawId);
        if (parsed.isEmpty()) {
            return;
        }
        WorldId worldId = parsed.get();
        NetworkPolicy current = policy.get();
        // On the io pool, not the database one: this waits for a node to finish a
        // countdown and a commit, and holding a database-pool thread for that long
        // would stall every other /world command on the proxy.
        runOnIo(source, () -> {
            Placement.MigrationCheck check = placement.canTake(targetNode, worldId, current);
            switch (check) {
                case Placement.MigrationCheck.UnknownNode ignored -> {
                    error(source, "no node called '" + targetNode + "' has ever registered");
                    return;
                }
                case Placement.MigrationCheck.NotAvailable unavailable -> {
                    error(
                            source,
                            unavailable.draining()
                                    ? "'" + targetNode + "' is draining and takes no worlds (MN-22)"
                                    : "'" + targetNode + "' has not published a heartbeat recently (MN-18)");
                    return;
                }
                case Placement.MigrationCheck.TooOld tooOld -> {
                    // MN-26 would refuse the lease anyway; refusing here means the
                    // world is not first taken away from the node that can open it.
                    error(
                            source,
                            "'" + targetNode + "' runs data version " + tooOld.nodeDataVersion()
                                    + " and that world was last saved at " + tooOld.worldDataVersion()
                                    + "; it cannot open it (MN-26)");
                    return;
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
            // Nothing to move: the world is not loaded anywhere. Acquiring the
            // lease on the target is the whole migration, and the next join goes
            // there because MN-14 routes a live lease without scoring.
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
        // A TTL long enough for the node to run the whole sequence. Expiring
        // mid-countdown would leave the world ejected but not committed.
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

        // Waiting rather than firing and forgetting: MN-19 is an ordered sequence
        // and the second half is ours. Acquiring the target's lease before the
        // source has released its own is the one thing MN-8 forbids.
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
            // The source released and somebody else got there first. The world is
            // safe and loadable; it is simply not where the operator asked.
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

    /**
     * Polls the command row until it completes.
     *
     * <p>Polling rather than {@code LISTEN}: this is one operator command, and the
     * durable row is the contract the control plane is built on (CP-2). The budget
     * is the node's own — the countdown plus a commit — with a little slack.
     */
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
     *
     * <p>Not in section 6's table; see {@link #adminTree()}. The command goes to
     * the node rather than writing {@code worlds_node.draining} directly, because
     * the node overwrites that column on every heartbeat.
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
                    nl.gzmn.playerworlds.core.db.NodeCommandRepository.DEFAULT_TTL,
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
            Optional<UUID> target = resolvePlayer(targetName);
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
            enqueueToWorldOrAliveNodes(world, CommandKind.INVALIDATE_CACHE, NodeCommand.EMPTY_PAYLOAD, current);
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

    // -----------------------------------------------------------------------
    // Shared
    // -----------------------------------------------------------------------

    /**
     * Turns a placement decision into a node id, or tells the caller why not.
     *
     * <p>The three ways of having no node are three different messages, which is
     * why {@link PlacementDecision} is a sealed result rather than an empty
     * optional. "This world needs a newer server" is section 12.7's rolled-back
     * pool and is a wait; "every server is full" is a capacity problem; "no server
     * is up" is an outage.
     *
     * @return the node id, or {@code null} when the caller has already been told
     */
    private @org.jspecify.annotations.Nullable String routableNodeOrExplain(
            CommandSource caller, PlacementDecision decision, WorldId worldId) {
        return switch (decision) {
            case PlacementDecision.Held held -> held.nodeId();
            case PlacementDecision.Selected selected -> selected.node().nodeId();
            case PlacementDecision.NoNodeNewEnough tooOld -> {
                log.warn(
                        "world {} was last saved at data version {} and the newest live node is at {}; "
                                + "it is unreachable until a newer node returns (section 12.7)",
                        worldId,
                        tooOld.worldDataVersion(),
                        tooOld.newestNodeDataVersion());
                error(
                        caller,
                        "that world was saved by a newer Minecraft version than any server currently running. "
                                + "It is safe, and it will be reachable again when one is back.");
                yield null;
            }
            case PlacementDecision.NoCapacity full -> {
                log.warn(
                        "no capacity for world {}: all {} version-capable nodes are over a threshold (MN-15)",
                        worldId,
                        full.candidates());
                error(caller, "every server is full right now; please try again in a few minutes");
                yield null;
            }
            case PlacementDecision.NoNodesAlive ignored -> {
                error(caller, "no server is available right now");
                yield null;
            }
        };
    }

    /** Optional rather than nullable: the value is read inside a lambda, where a null check does not travel. */
    private Optional<WorldId> parseWorldId(CommandSource source, String raw) {
        try {
            return Optional.of(new WorldId(UUID.fromString(raw.strip())));
        } catch (IllegalArgumentException e) {
            error(source, "'" + raw + "' is not a world id (section 6 takes the uuid, not the name)");
            return Optional.empty();
        }
    }

    /** {@link #run} for a source that may not be a player. */
    private void runAsAdmin(CommandSource source, SqlTask task) {
        submitAdmin(executors.db(), source, task);
    }

    /** {@link #runAsAdmin} for work that waits on another component. */
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

    private java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestNodes(
            CommandContext<CommandSource> context, com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        // Registered names, not alive ones: an operator lifting a drain is naming
        // a node the alive set deliberately excludes.
        try {
            String prefix = builder.getRemaining().toLowerCase(java.util.Locale.ROOT);
            for (NodeStatus node : placement.allNodes()) {
                if (node.nodeId().toLowerCase(java.util.Locale.ROOT).startsWith(prefix)) {
                    builder.suggest(node.nodeId());
                }
            }
        } catch (SQLException e) {
            log.debug("could not suggest node names: {}", e.getMessage());
        }
        return builder.buildFuture();
    }

    private void enqueueToWorldOrAliveNodes(
            PlayerWorld world, CommandKind kind, String payloadJson, NetworkPolicy current) throws SQLException {
        if (world.assignedNode() != null) {
            nodeCommands.enqueue(
                    world.assignedNode(),
                    world.id(),
                    world.generation(),
                    kind.name(),
                    payloadJson,
                    current.holdingTimeout(),
                    ControlChannels.forNode(world.assignedNode()));
        } else {
            for (var alive : registry.aliveNodes(current.deadAfter())) {
                nodeCommands.enqueue(
                        alive.nodeId(),
                        world.id(),
                        world.generation(),
                        kind.name(),
                        payloadJson,
                        current.holdingTimeout(),
                        ControlChannels.forNode(alive.nodeId()));
            }
        }
    }

    /** A body that may throw {@link SQLException}, which {@code Runnable} cannot. */
    @FunctionalInterface
    private interface SqlTask {
        void run() throws SQLException;
    }
}
