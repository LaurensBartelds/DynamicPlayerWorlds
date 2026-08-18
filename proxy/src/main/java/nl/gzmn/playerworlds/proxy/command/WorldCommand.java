package nl.gzmn.playerworlds.proxy.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.sql.SQLException;
import java.util.List;
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
import nl.gzmn.playerworlds.core.control.ControlChannels;
import nl.gzmn.playerworlds.core.control.EjectPayload;
import nl.gzmn.playerworlds.core.control.NodeCommand;
import nl.gzmn.playerworlds.core.db.MembershipRepository;
import nl.gzmn.playerworlds.core.db.NodeCommandRepository;
import nl.gzmn.playerworlds.core.db.PendingTransferRepository;
import nl.gzmn.playerworlds.core.db.PlayerNameRepository;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.Role;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.model.WorldMember;
import nl.gzmn.playerworlds.core.model.WorldState;
import nl.gzmn.playerworlds.proxy.node.NodeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code /world} root (specification section 6).
 *
 * <p>Registering this claims the whole namespace, which is why
 * {@link #BACKEND_SUBCOMMANDS} exists: section 6 keeps {@code /world leave} and
 * {@code /world report} on the backend, and they are only reachable if this
 * handler forwards them. The list is empty until those land (milestones 5 and
 * 9), but the mechanism is designed in rather than discovered — which is what
 * OQ-15 asked for.
 *
 * <p>Milestone 2 implements the membership half: invite, accept, kick, members
 * and promote. {@code create}, {@code join} and {@code browse} need placement
 * and the transfer handoff and arrive with milestones 5 and 8; until then
 * {@code /pworld} on a node covers them for a single server.
 *
 * <p>Every handler returns immediately and does its database work on the pool.
 */
public final class WorldCommand {

    private static final Logger log = LoggerFactory.getLogger(WorldCommand.class);

    /** Implemented here, for the enable log line and for tests. */
    public static final List<String> SUBCOMMANDS =
            List.of("create", "join", "delete", "restore", "invite", "accept", "kick", "members", "promote");

    /**
     * Subcommands that belong to the backend and must be forwarded (OQ-15).
     *
     * <p>{@code /world leave} is milestone 5 and {@code /world report} is
     * milestone 9; naming the list is what stops either from being silently
     * unreachable when it arrives.
     */
    public static final List<String> BACKEND_SUBCOMMANDS = List.of("leave");

    private final ProxyServer proxy;
    private final PluginExecutors executors;
    private final PlayerWorldRepository worlds;
    private final MembershipRepository membership;
    private final PlayerNameRepository names;
    private final PendingTransferRepository transfers;
    private final NodeRegistry registry;
    private final NodeCommandRepository nodeCommands;
    private final Supplier<NetworkPolicy> policy;

    public WorldCommand(
            ProxyServer proxy,
            PluginExecutors executors,
            PlayerWorldRepository worlds,
            MembershipRepository membership,
            PlayerNameRepository names,
            PendingTransferRepository transfers,
            NodeRegistry registry,
            NodeCommandRepository nodeCommands,
            Supplier<NetworkPolicy> policy) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.executors = Objects.requireNonNull(executors, "executors");
        this.worlds = Objects.requireNonNull(worlds, "worlds");
        this.membership = Objects.requireNonNull(membership, "membership");
        this.names = Objects.requireNonNull(names, "names");
        this.transfers = Objects.requireNonNull(transfers, "transfers");
        this.registry = Objects.requireNonNull(registry, "registry");
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
                .then(BrigadierCommand.literalArgumentBuilder("create")
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
                }));
        return new BrigadierCommand(root);
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
     * <p>FR-1a also requires the lease be acquired before any folder is created.
     * Leases are milestone 7, so the ordering here is right and the lease itself
     * is absent - recorded in plan 01 section 5.3 rather than papered over.
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

            // A world with no committed snapshot has no data version, so every
            // alive node is a candidate (MN-28).
            Optional<nl.gzmn.playerworlds.core.db.NodeRepository.NodeStatus> node =
                    registry.selectNode(WorldId.random(), null, current.deadAfter());
            if (node.isEmpty()) {
                error(caller, "no server is available to host a new world right now");
                return;
            }
            var targetServer = registry.server(node.get().nodeId());
            if (targetServer.isEmpty()) {
                error(caller, "that server is not routable right now");
                return;
            }

            long seed = seedText == null ? new java.security.SecureRandom().nextLong() : parseSeed(seedText);
            PlayerWorld world = worlds.create(
                    WorldId.random(),
                    owner,
                    name,
                    seed,
                    current.defaultBorderRadius(),
                    nl.gzmn.playerworlds.core.model.Visibility.valueOf(current.defaultVisibility()),
                    node.get().nodeId(),
                    current.leaseDuration());

            transfers.route(owner, world.id(), node.get().nodeId(), world.generation());
            info(caller, "creating '" + name + "' on " + node.get().nodeId() + "; this may take a few seconds...");
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
            if (target.isEmpty()
                    || membership
                            .findMember(target.get().id(), caller.getUniqueId())
                            .isEmpty()) {
                error(caller, "no world you can join matches that");
                return;
            }

            PlayerWorld world = target.get();
            Optional<nl.gzmn.playerworlds.core.db.NodeRepository.NodeStatus> node =
                    registry.selectNode(world.id(), world.dataVersion(), current.deadAfter());
            if (node.isEmpty()) {
                // MN-15 excludes nodes that cannot open the world before any other
                // term, so "no node" and "no node new enough" arrive here together.
                error(caller, "no server is available to host that world right now");
                return;
            }

            // MN-14: If the world does not hold a live lease on the target node, acquire it before routing
            long routingGeneration = world.generation();
            boolean liveLease = world.assignedNode() != null
                    && world.assignedNode().equals(node.get().nodeId())
                    && world.leaseExpires() != null
                    && world.leaseExpires().isAfter(java.time.Instant.now());

            if (!liveLease) {
                int nodeDataVersion = node.get().dataVersion();
                Optional<PlayerWorldRepository.LeaseGrant> grant =
                        worlds.acquireLease(world.id(), node.get().nodeId(), nodeDataVersion, current.leaseDuration());
                if (grant.isEmpty()) {
                    error(caller, "could not acquire a lease for that world; please try again");
                    return;
                }
                routingGeneration = grant.get().generation();
            }

            transfers.route(caller.getUniqueId(), world.id(), node.get().nodeId(), routingGeneration);
            var targetServer = registry.server(node.get().nodeId());
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
