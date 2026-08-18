package nl.gzmn.playerworlds.proxy.command;

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
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.config.StorageQuotaResolver;
import nl.gzmn.playerworlds.core.control.ArchivePayload;
import nl.gzmn.playerworlds.core.control.CommandKind;
import nl.gzmn.playerworlds.core.control.ControlChannels;
import nl.gzmn.playerworlds.core.control.EjectPayload;
import nl.gzmn.playerworlds.core.control.NodeCommand;
import nl.gzmn.playerworlds.core.db.MembershipRepository;
import nl.gzmn.playerworlds.core.db.NodeCommandRepository;
import nl.gzmn.playerworlds.core.db.PendingTransferRepository;
import nl.gzmn.playerworlds.core.db.PlayerNameRepository;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository;
import nl.gzmn.playerworlds.core.db.TransferRequestRepository;
import nl.gzmn.playerworlds.core.db.WorldBanRepository;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.Role;
import nl.gzmn.playerworlds.core.model.StorageQuota;
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
import nl.gzmn.playerworlds.proxy.permission.StorageTiers;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Domain actions and business logic for player-facing world operations.
 *
 * <p>Extracted from {@link WorldCommand} so that both Brigadier commands and
 * GUI menu channel listeners can invoke mutations, validations, cap checks,
 * and routing consistently without duplicating rules.
 */
public final class WorldActions {

    private static final Logger log = LoggerFactory.getLogger(WorldActions.class);

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
    private final StorageTiers storageTiers;

    public WorldActions(
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
                policy,
                new StorageTiers());
    }

    public WorldActions(
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
            Supplier<NetworkPolicy> policy,
            StorageTiers storageTiers) {
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
        this.storageTiers = Objects.requireNonNull(storageTiers, "storageTiers");
    }

    /**
     * Creates a new world (FR-1, FR-1a).
     */
    public CompletableFuture<ActionResult> create(Player caller, String name, @Nullable String seedText) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(name, "name");
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        NetworkPolicy current = policy.get();
                        UUID owner = caller.getUniqueId();
                        int owned = worlds.countOwnedBy(owner);
                        if (owned >= current.maxWorldsPerPlayer()) {
                            return ActionResult.failure(
                                    "LIMIT_REACHED",
                                    error(
                                            caller,
                                            "you already own " + owned + " worlds (limit "
                                                    + current.maxWorldsPerPlayer() + ")"));
                        }
                        if (worlds.findByOwnerAndName(owner, name).isPresent()) {
                            return ActionResult.failure(
                                    "NAME_TAKEN", error(caller, "you already own a world called '" + name + "'"));
                        }
                        StorageQuota quota = quotaFor(caller, current);
                        if (quota.isExceeded()) {
                            return ActionResult.failure(
                                    "QUOTA_EXCEEDED", refuseForQuota(caller, quota, "create another world"));
                        }

                        WorldId newId = WorldId.random();
                        Visibility visibility = Visibility.valueOf(current.defaultVisibility());
                        PlacementDecision decision = placement.forNewWorld(newId, visibility, current);
                        String nodeId = routableNodeOrExplain(caller, decision, newId);
                        if (nodeId == null) {
                            return ActionResult.failure(
                                    "ROUTING_FAILED", Component.text("cannot route to node", NamedTextColor.RED));
                        }
                        var targetServer = registry.server(nodeId);
                        if (targetServer.isEmpty()) {
                            return ActionResult.failure(
                                    "NOT_ROUTABLE", error(caller, "that server is not routable right now"));
                        }

                        long seed =
                                seedText == null ? new java.security.SecureRandom().nextLong() : parseSeed(seedText);
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
                        Component msg = info(
                                caller, "creating '" + name + "' on " + nodeId + "; this may take a few seconds...");
                        caller.createConnectionRequest(targetServer.get()).fireAndForget();
                        return ActionResult.success(msg);
                    } catch (SQLException e) {
                        log.error("/world create failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                "DATABASE_ERROR", error(caller, "that did not work; the failure is in the proxy log"));
                    }
                },
                executors.db());
    }

    /**
     * Deletes or archives a world (FR-27, FR-35).
     */
    public CompletableFuture<ActionResult> delete(Player caller, String name, boolean confirmed) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(name, "name");
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        NetworkPolicy current = policy.get();
                        Optional<PlayerWorld> found = worlds.findByOwnerAndName(caller.getUniqueId(), name);
                        if (found.isEmpty()) {
                            return ActionResult.failure(
                                    "WORLD_NOT_FOUND", error(caller, "you own no world called '" + name + "'"));
                        }
                        PlayerWorld world = found.get();
                        if (world.state() == WorldState.ARCHIVED) {
                            return ActionResult.failure(
                                    "ALREADY_ARCHIVED",
                                    info(
                                            caller,
                                            "'" + name + "' is already archived; use /world restore " + name
                                                    + " to bring it back"));
                        }
                        if (world.state() != WorldState.READY && world.state() != WorldState.CREATING) {
                            return ActionResult.failure(
                                    "ILLEGAL_STATE",
                                    error(
                                            caller,
                                            "'" + name + "' is " + world.state() + " and cannot be deleted right now"));
                        }
                        if (!confirmed) {
                            if (world.state() == WorldState.CREATING) {
                                error(
                                        caller,
                                        "'" + name
                                                + "' was never completed. This removes the incomplete world and frees your slot.");
                            } else {
                                error(caller, "this archives '" + name + "' and frees a world slot.");
                            }
                            Component infoMsg = info(caller, "type /world delete " + name + " confirm to go ahead");
                            return ActionResult.failure("CONFIRMATION_REQUIRED", infoMsg);
                        }

                        if (world.state() == WorldState.CREATING) {
                            if (!worlds.deleteIfCreating(world.id())) {
                                return ActionResult.failure(
                                        "STATE_CHANGED",
                                        error(caller, "'" + name + "' changed while you were confirming; try again"));
                            }
                            enqueueToWorldOrAliveNodes(
                                    world, CommandKind.UNLOAD_WORLD, NodeCommand.EMPTY_PAYLOAD, current);
                            Component msg = success(
                                    caller, "removed incomplete world '" + name + "'; you have a world slot free");
                            log.info("world {} removed while in CREATING state by its owner", world.id());
                            return ActionResult.success(msg);
                        }

                        String node = archivalNodeOrExplain(caller, world, current);
                        if (node == null) {
                            return ActionResult.failure(
                                    "ROUTING_FAILED", Component.text("cannot route to node", NamedTextColor.RED));
                        }
                        enqueueTo(
                                node,
                                world,
                                CommandKind.ARCHIVE_WORLD,
                                ArchivePayload.format(caller.getUniqueId()),
                                current);
                        Component msg = info(
                                caller,
                                "archiving '" + name + "' on " + node
                                        + "; this may take a few minutes for a large world");
                        info(
                                caller,
                                "nothing is erased - the world is packed to cold storage and /world restore " + name
                                        + " brings it back");
                        log.info("world {} queued for archival on {} by its owner (FR-27, FR-35)", world.id(), node);
                        return ActionResult.success(msg);
                    } catch (SQLException e) {
                        log.error("/world delete failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                "DATABASE_ERROR", error(caller, "that did not work; the failure is in the proxy log"));
                    }
                },
                executors.db());
    }

    public static final String HARD_DELETE_PERMISSION = "gzmn.worlds.delete.hard";

    /**
     * Permanently destroys an archived world by name.
     */
    public CompletableFuture<ActionResult> deleteHard(Player caller, String name, boolean confirmed) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(name, "name");
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        Optional<PlayerWorld> worldOpt = worlds.findByOwnerAndName(caller.getUniqueId(), name);
                        if (worldOpt.isEmpty()) {
                            return ActionResult.failure(
                                    "WORLD_NOT_FOUND", error(caller, "you own no world called '" + name + "'"));
                        }
                        return executeDeleteHard(caller, worldOpt.get(), confirmed);
                    } catch (SQLException e) {
                        log.error("deleteHard failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                "DATABASE_ERROR", error(caller, "that did not work; the failure is in the proxy log"));
                    }
                },
                executors.db());
    }

    /**
     * Permanently destroys an archived world by WorldId (already confirmed via GUI modal).
     */
    public CompletableFuture<ActionResult> deleteHard(Player caller, WorldId worldId) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(worldId, "worldId");
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        Optional<PlayerWorld> worldOpt = worlds.findById(worldId);
                        if (worldOpt.isEmpty()) {
                            return ActionResult.failure("WORLD_NOT_FOUND", error(caller, "world not found"));
                        }
                        PlayerWorld world = worldOpt.get();
                        if (!world.ownerUuid().equals(caller.getUniqueId())) {
                            return ActionResult.failure(
                                    "PERMISSION_DENIED", error(caller, "you are not the owner of this world"));
                        }
                        return executeDeleteHard(caller, world, true);
                    } catch (SQLException e) {
                        log.error("deleteHard by id failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                "DATABASE_ERROR", error(caller, "that did not work; the failure is in the proxy log"));
                    }
                },
                executors.db());
    }

    private ActionResult executeDeleteHard(Player caller, PlayerWorld world, boolean confirmed) throws SQLException {
        if (!caller.hasPermission(HARD_DELETE_PERMISSION)) {
            return ActionResult.failure(
                    "PERMISSION_DENIED", error(caller, "you do not have permission to permanently delete worlds"));
        }
        if (world.state() != WorldState.ARCHIVED) {
            return ActionResult.failure(
                    "STATE_CONFLICT",
                    error(caller, "'" + world.name() + "' must be archived before it can be permanently deleted"));
        }
        if (!confirmed) {
            info(caller, "this permanently destroys '" + world.name() + "' and all backup archives. This cannot be undone.");
            return ActionResult.failure(
                    "UNCONFIRMED", info(caller, "type /world delete " + world.name() + " hard confirm to permanently delete"));
        }
        if (!worlds.deleteHard(world.id())) {
            return ActionResult.failure(
                    "STATE_CHANGED", error(caller, "'" + world.name() + "' changed while you were confirming; try again"));
        }
        log.info("world {} ('{}') permanently deleted by owner {}", world.id(), world.name(), caller.getUsername());
        return ActionResult.success(
                Component.text("Permanently deleted world '" + world.name() + "'.", NamedTextColor.GREEN));
    }

    /**
     * Restores an archived world (FR-36).
     */
    public CompletableFuture<ActionResult> restore(Player caller, String name) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(name, "name");
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        NetworkPolicy current = policy.get();
                        Optional<PlayerWorld> found = worlds.findByOwnerAndName(caller.getUniqueId(), name);
                        if (found.isEmpty()) {
                            return ActionResult.failure(
                                    "WORLD_NOT_FOUND", error(caller, "you own no world called '" + name + "'"));
                        }
                        PlayerWorld world = found.get();
                        if (world.state() != WorldState.ARCHIVED) {
                            return ActionResult.failure(
                                    "NOT_ARCHIVED",
                                    error(
                                            caller,
                                            "'" + name + "' is " + world.state() + " and does not need restoring"));
                        }
                        int owned = worlds.countOwnedBy(caller.getUniqueId());
                        if (owned >= current.maxWorldsPerPlayer()) {
                            return ActionResult.failure(
                                    "LIMIT_REACHED",
                                    error(
                                            caller,
                                            "you already own " + owned + " worlds (limit "
                                                    + current.maxWorldsPerPlayer()
                                                    + "); archive one before restoring this"));
                        }
                        StorageQuota quota = quotaFor(caller, current);
                        if (quota.isExceeded()) {
                            return ActionResult.failure(
                                    "QUOTA_EXCEEDED", refuseForQuota(caller, quota, "restore '" + name + "'"));
                        }
                        String node = archivalNodeOrExplain(caller, world, current);
                        if (node == null) {
                            return ActionResult.failure(
                                    "ROUTING_FAILED", Component.text("cannot route to node", NamedTextColor.RED));
                        }
                        enqueueTo(node, world, CommandKind.RESTORE_WORLD, ArchivePayload.format(null), current);
                        Component msg =
                                info(caller, "restoring '" + name + "' on " + node + "; this may take a few minutes");
                        log.info("world {} queued for restore on {} by its owner (FR-36)", world.id(), node);
                        return ActionResult.success(msg);
                    } catch (SQLException e) {
                        log.error("/world restore failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                "DATABASE_ERROR", error(caller, "that did not work; the failure is in the proxy log"));
                    }
                },
                executors.db());
    }

    /**
     * Joins a world by owner name and optional world name (FR-10).
     */
    public CompletableFuture<ActionResult> join(Player caller, String ownerName, @Nullable String worldName) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(ownerName, "ownerName");
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        NetworkPolicy current = policy.get();
                        Optional<UUID> owner = resolvePlayer(ownerName);
                        if (owner.isEmpty()) {
                            return ActionResult.failure(
                                    "WORLD_NOT_FOUND", error(caller, "no world you can join matches that"));
                        }
                        List<PlayerWorld> owned = worlds.listOwnedBy(owner.get());
                        Optional<PlayerWorld> target = owned.stream()
                                .filter(world ->
                                        worldName == null || world.name().equalsIgnoreCase(worldName))
                                .filter(world ->
                                        world.state() == WorldState.READY || world.state() == WorldState.CREATING)
                                .findFirst();
                        if (target.isEmpty()) {
                            return ActionResult.failure(
                                    "WORLD_NOT_FOUND", error(caller, "no world you can join matches that"));
                        }
                        return doJoin(caller, target.get(), current);
                    } catch (SQLException e) {
                        log.error("/world join failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                "DATABASE_ERROR", error(caller, "that did not work; the failure is in the proxy log"));
                    }
                },
                executors.db());
    }

    /**
     * Joins a world by its {@link WorldId}.
     */
    public CompletableFuture<ActionResult> join(Player caller, WorldId worldId) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(worldId, "worldId");
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        NetworkPolicy current = policy.get();
                        Optional<PlayerWorld> target = worlds.findById(worldId);
                        if (target.isEmpty()
                                || (target.get().state() != WorldState.READY
                                        && target.get().state() != WorldState.CREATING)) {
                            return ActionResult.failure(
                                    "WORLD_NOT_FOUND", error(caller, "no world you can join matches that"));
                        }
                        return doJoin(caller, target.get(), current);
                    } catch (SQLException e) {
                        log.error("/world join failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                "DATABASE_ERROR", error(caller, "that did not work; the failure is in the proxy log"));
                    }
                },
                executors.db());
    }

    private ActionResult doJoin(Player caller, PlayerWorld world, NetworkPolicy current) throws SQLException {
        if (bans.isBanned(world.id(), caller.getUniqueId())) {
            Optional<WorldBan> ban = bans.findBan(world.id(), caller.getUniqueId());
            String reason = ban.flatMap(b -> Optional.ofNullable(b.reason()))
                    .map(r -> ": " + r)
                    .orElse("");
            return ActionResult.failure("BANNED", error(caller, "you are banned from '" + world.name() + "'" + reason));
        }

        if (membership.findMember(world.id(), caller.getUniqueId()).isEmpty()) {
            if (world.visibility() == Visibility.PUBLIC) {
                membership.addVisitorIfAbsent(world.id(), caller.getUniqueId());
            } else {
                return ActionResult.failure("NOT_MEMBER", error(caller, "no world you can join matches that"));
            }
        }

        PlacementDecision decision = placement.forExistingWorld(world.id(), current);
        String nodeId = routableNodeOrExplain(caller, decision, world.id());
        if (nodeId == null) {
            return ActionResult.failure("ROUTING_FAILED", Component.text("cannot route to node", NamedTextColor.RED));
        }

        long routingGeneration = world.generation();
        if (decision instanceof PlacementDecision.Selected selected) {
            Optional<PlayerWorldRepository.LeaseGrant> grant =
                    worlds.acquireLease(world.id(), nodeId, selected.node().dataVersion(), current.leaseDuration());
            if (grant.isEmpty()) {
                return ActionResult.failure(
                        "LEASE_RACE",
                        error(caller, "that world is being opened elsewhere right now; try again in a moment"));
            }
            routingGeneration = grant.get().generation();
        } else {
            Optional<PlayerWorld> fresh = worlds.findById(world.id());
            if (fresh.isPresent()) {
                routingGeneration = fresh.get().generation();
            }
        }

        transfers.route(caller.getUniqueId(), world.id(), nodeId, routingGeneration);
        var targetServer = registry.server(nodeId);
        if (targetServer.isEmpty()) {
            return ActionResult.failure("NOT_ROUTABLE", error(caller, "that server is not routable right now"));
        }
        Component msg = info(caller, "sending you to '" + world.name() + "'...");
        caller.createConnectionRequest(targetServer.get()).fireAndForget();
        return ActionResult.success(msg);
    }

    public CompletableFuture<ActionResult> invite(Player caller, String targetName) {
        return invite(caller, targetName, null);
    }

    /**
     * Invites a player to a world (FR-6).
     */
    public CompletableFuture<ActionResult> invite(Player caller, String targetName, @Nullable WorldId worldId) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(targetName, "targetName");
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        NetworkPolicy current = policy.get();
                        Optional<PlayerWorld> world = targetWorld(caller, worldId);
                        if (world.isEmpty()) {
                            return ActionResult.failure(
                                    "NO_TARGET_WORLD", Component.text("target world not found", NamedTextColor.RED));
                        }
                        Optional<UUID> target = resolvePlayer(targetName);
                        if (target.isEmpty()) {
                            return ActionResult.failure(
                                    "PLAYER_NOT_FOUND",
                                    error(
                                            caller,
                                            "no player called '" + targetName + "' has been seen on this network"));
                        }
                        if (target.get().equals(caller.getUniqueId())) {
                            return ActionResult.failure(
                                    "CANNOT_TARGET_SELF", error(caller, "you are already the owner of that world"));
                        }
                        if (membership
                                .findMember(world.get().id(), target.get())
                                .isPresent()) {
                            return ActionResult.failure(
                                    "ALREADY_MEMBER",
                                    error(
                                            caller,
                                            targetName + " is already a member of '"
                                                    + world.get().name() + "'"));
                        }

                        membership.invite(world.get().id(), target.get(), caller.getUniqueId(), current.inviteExpiry());
                        Component msg = success(
                                caller,
                                "invited " + targetName + " to '" + world.get().name() + "'; the invite expires in "
                                        + current.inviteExpiry().toMinutes() + " minutes");

                        proxy.getPlayer(target.get())
                                .ifPresent(online -> online.sendMessage(Component.text(
                                        caller.getUsername() + " invited you to their world '"
                                                + world.get().name() + "'. Use /world accept " + caller.getUsername(),
                                        NamedTextColor.GREEN)));
                        return ActionResult.success(msg);
                    } catch (SQLException e) {
                        log.error("/world invite failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                "DATABASE_ERROR", error(caller, "that did not work; the failure is in the proxy log"));
                    }
                },
                executors.db());
    }

    /**
     * Accepts an invitation (FR-7).
     */
    public CompletableFuture<ActionResult> accept(Player caller, String ownerName) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(ownerName, "ownerName");
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        Optional<UUID> owner = resolvePlayer(ownerName);
                        if (owner.isEmpty()) {
                            return ActionResult.failure(
                                    "PLAYER_NOT_FOUND",
                                    error(
                                            caller,
                                            "no player called '" + ownerName + "' has been seen on this network"));
                        }
                        List<PlayerWorld> owned = worlds.listOwnedBy(owner.get());
                        for (PlayerWorld world : owned) {
                            switch (membership.acceptInvite(world.id(), caller.getUniqueId())) {
                                case MembershipRepository.AcceptOutcome.Accepted accepted -> {
                                    Component msg = success(
                                            caller,
                                            "you are now a " + accepted.member().role() + " of '" + world.name() + "'");
                                    info(
                                            caller,
                                            "joining a world from here arrives with the transfer path; "
                                                    + "use /pworld on the node until then");
                                    return ActionResult.success(msg);
                                }
                                case MembershipRepository.AcceptOutcome.AlreadyMember already -> {
                                    Component msg = info(
                                            caller,
                                            "you were already a " + already.role() + " of '" + world.name() + "'");
                                    return ActionResult.success(msg);
                                }
                                case MembershipRepository.AcceptOutcome.NoLiveInvite ignored -> {
                                    // Try the owner's next world.
                                }
                            }
                        }
                        return ActionResult.failure(
                                "NO_LIVE_INVITE", error(caller, "you have no live invite from " + ownerName));
                    } catch (SQLException e) {
                        log.error("/world accept failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                "DATABASE_ERROR", error(caller, "that did not work; the failure is in the proxy log"));
                    }
                },
                executors.db());
    }

    public CompletableFuture<ActionResult> kick(Player caller, String targetName) {
        return kick(caller, targetName, null);
    }

    /**
     * Kicks a member from a world (FR-8).
     */
    public CompletableFuture<ActionResult> kick(Player caller, String targetName, @Nullable WorldId worldId) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(targetName, "targetName");
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        NetworkPolicy current = policy.get();
                        Optional<PlayerWorld> world = targetWorld(caller, worldId);
                        if (world.isEmpty()) {
                            return ActionResult.failure(
                                    "NO_TARGET_WORLD", Component.text("target world not found", NamedTextColor.RED));
                        }
                        Optional<UUID> target = resolvePlayer(targetName);
                        if (target.isEmpty()) {
                            return ActionResult.failure(
                                    "PLAYER_NOT_FOUND",
                                    error(
                                            caller,
                                            "no player called '" + targetName + "' has been seen on this network"));
                        }
                        if (target.get().equals(world.get().ownerUuid())) {
                            return ActionResult.failure(
                                    "CANNOT_KICK_OWNER",
                                    error(
                                            caller,
                                            "you cannot kick yourself from your own world; use /world transfer or /world delete"));
                        }
                        membership.revokeInvite(world.get().id(), target.get());
                        if (!membership.removeMember(world.get().id(), target.get())) {
                            return ActionResult.failure(
                                    "NOT_A_MEMBER",
                                    error(
                                            caller,
                                            targetName + " is not a member of '"
                                                    + world.get().name() + "'"));
                        }
                        enqueueToWorldOrAliveNodes(
                                world.get(), CommandKind.INVALIDATE_CACHE, NodeCommand.EMPTY_PAYLOAD, current);
                        enqueueToWorldOrAliveNodes(
                                world.get(),
                                CommandKind.KICK_MEMBER,
                                EjectPayload.format(target.get(), "You were removed from this world"),
                                current);
                        Component msg = success(
                                caller,
                                "removed " + targetName + " from '"
                                        + world.get().name() + "'");
                        info(caller, "if they are inside the world right now they will be removed on their next join");
                        return ActionResult.success(msg);
                    } catch (SQLException e) {
                        log.error("/world kick failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                "DATABASE_ERROR", error(caller, "that did not work; the failure is in the proxy log"));
                    }
                },
                executors.db());
    }

    public CompletableFuture<ActionResult> promote(Player caller, String targetName) {
        return promote(caller, targetName, null);
    }

    /**
     * Promotes a member to builder (FR-9c).
     */
    public CompletableFuture<ActionResult> promote(Player caller, String targetName, @Nullable WorldId worldId) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(targetName, "targetName");
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        NetworkPolicy current = policy.get();
                        Optional<PlayerWorld> world = targetWorld(caller, worldId);
                        if (world.isEmpty()) {
                            return ActionResult.failure(
                                    "NO_TARGET_WORLD", Component.text("target world not found", NamedTextColor.RED));
                        }
                        Optional<UUID> target = resolvePlayer(targetName);
                        if (target.isEmpty()) {
                            return ActionResult.failure(
                                    "PLAYER_NOT_FOUND",
                                    error(
                                            caller,
                                            "no player called '" + targetName + "' has been seen on this network"));
                        }
                        if (membership.setRole(world.get().id(), target.get(), Role.BUILDER)) {
                            enqueueToWorldOrAliveNodes(
                                    world.get(), CommandKind.INVALIDATE_CACHE, NodeCommand.EMPTY_PAYLOAD, current);
                            Component msg = success(
                                    caller,
                                    targetName + " is now a BUILDER of '"
                                            + world.get().name() + "'");
                            return ActionResult.success(msg);
                        } else {
                            return ActionResult.failure(
                                    "NOT_A_MEMBER",
                                    error(
                                            caller,
                                            targetName + " is not a member of '"
                                                    + world.get().name() + "', or is its owner"));
                        }
                    } catch (SQLException e) {
                        log.error("/world promote failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                "DATABASE_ERROR", error(caller, "that did not work; the failure is in the proxy log"));
                    }
                },
                executors.db());
    }

    public CompletableFuture<ActionResult> transfer(Player caller, String targetName, boolean confirmed) {
        return transfer(caller, targetName, confirmed, null);
    }

    /**
     * Transfers world ownership (FR-29, FR-30, FR-31, FR-32).
     */
    public CompletableFuture<ActionResult> transfer(
            Player caller, String targetName, boolean confirmed, @Nullable WorldId worldId) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(targetName, "targetName");
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        NetworkPolicy current = policy.get();
                        Optional<PlayerWorld> world = targetWorld(caller, worldId);
                        if (world.isEmpty()) {
                            return ActionResult.failure(
                                    "NO_TARGET_WORLD", Component.text("target world not found", NamedTextColor.RED));
                        }
                        Optional<UUID> target = resolvePlayer(targetName);
                        if (target.isEmpty()) {
                            return ActionResult.failure(
                                    "PLAYER_NOT_FOUND",
                                    error(
                                            caller,
                                            "no player called '" + targetName + "' has been seen on this network"));
                        }
                        if (target.get().equals(caller.getUniqueId())) {
                            return ActionResult.failure(
                                    "CANNOT_TARGET_SELF", error(caller, "you already own this world"));
                        }
                        if (membership
                                .findMember(world.get().id(), target.get())
                                .isEmpty()) {
                            return ActionResult.failure(
                                    "NOT_A_MEMBER",
                                    error(
                                            caller,
                                            targetName + " is not a member of '"
                                                    + world.get().name() + "'"));
                        }
                        int ownedCount = worlds.countOwnedBy(target.get());
                        if (ownedCount >= current.maxWorldsPerPlayer()) {
                            return ActionResult.failure(
                                    "LIMIT_REACHED",
                                    error(
                                            caller,
                                            targetName + " has reached their world limit ("
                                                    + current.maxWorldsPerPlayer() + ")"));
                        }

                        if (!confirmed) {
                            Component msg = info(
                                    caller,
                                    "Are you sure you want to transfer ownership of '"
                                            + world.get().name()
                                            + "' to " + targetName
                                            + "? You will become a BUILDER. Type /world transfer "
                                            + targetName + " confirm to proceed.");
                            return ActionResult.failure("CONFIRMATION_REQUIRED", msg);
                        }

                        Optional<Player> online = proxy.getPlayer(target.get());
                        if (online.isPresent()) {
                            // Online -> immediate transfer (FR-31)
                            if (!worlds.transferOwnership(
                                    world.get().id(), caller.getUniqueId(), target.get(), "MANUAL")) {
                                return ActionResult.failure(
                                        "TRANSFER_FAILED",
                                        error(
                                                caller,
                                                "could not transfer ownership of '"
                                                        + world.get().name() + "'"));
                            }
                            enqueueToWorldOrAliveNodes(
                                    world.get(), CommandKind.INVALIDATE_CACHE, NodeCommand.EMPTY_PAYLOAD, current);
                            Component msg = success(
                                    caller,
                                    "transferred ownership of '" + world.get().name() + "' to " + targetName
                                            + "; you are now a BUILDER");
                            online.get()
                                    .sendMessage(Component.text(
                                            "You are now the owner of '"
                                                    + world.get().name() + "'!",
                                            NamedTextColor.GREEN));
                            return ActionResult.success(msg);
                        } else {
                            // Offline -> create pending transfer request (FR-32)
                            transferRequests.requestTransfer(
                                    world.get().id(),
                                    target.get(),
                                    caller.getUniqueId(),
                                    current.transferPendingExpiry());
                            Component msg = success(
                                    caller,
                                    "created transfer request for " + targetName
                                            + "; they can accept it next time they log in");
                            return ActionResult.success(msg);
                        }
                    } catch (SQLException e) {
                        log.error("/world transfer failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                "DATABASE_ERROR", error(caller, "that did not work; the failure is in the proxy log"));
                    }
                },
                executors.db());
    }

    /**
     * Accepts a pending transfer request (FR-32).
     */
    public CompletableFuture<ActionResult> transferAccept(Player caller, String ownerName) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(ownerName, "ownerName");
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        NetworkPolicy current = policy.get();
                        Optional<UUID> owner = resolvePlayer(ownerName);
                        if (owner.isEmpty()) {
                            return ActionResult.failure(
                                    "PLAYER_NOT_FOUND",
                                    error(
                                            caller,
                                            "no player called '" + ownerName + "' has been seen on this network"));
                        }
                        List<TransferRequest> pending = transferRequests.findLiveRequestsFor(caller.getUniqueId());
                        Optional<TransferRequest> matching = pending.stream()
                                .filter(r -> r.fromUuid().equals(owner.get()))
                                .findFirst();
                        if (matching.isEmpty()) {
                            return ActionResult.failure(
                                    "NO_REQUEST",
                                    error(caller, "you have no pending transfer requests from " + ownerName));
                        }
                        int ownedCount = worlds.countOwnedBy(caller.getUniqueId());
                        if (ownedCount >= current.maxWorldsPerPlayer()) {
                            return ActionResult.failure(
                                    "LIMIT_REACHED",
                                    error(
                                            caller,
                                            "you have reached your world limit (" + current.maxWorldsPerPlayer()
                                                    + ")"));
                        }
                        Optional<PlayerWorld> worldOpt =
                                worlds.findById(matching.get().worldId());
                        if (worldOpt.isEmpty()) {
                            return ActionResult.failure(
                                    "WORLD_NOT_FOUND", error(caller, "that world no longer exists"));
                        }
                        PlayerWorld world = worldOpt.get();
                        StorageQuota quota = quotaFor(caller, current);
                        if (!quota.unlimited() && quota.usedBytes() + world.storageBytes() > quota.limitBytes()) {
                            return ActionResult.failure(
                                    "QUOTA_EXCEEDED",
                                    refuseForQuota(
                                            caller,
                                            new StorageQuota(
                                                    quota.playerUuid(),
                                                    quota.usedBytes() + world.storageBytes(),
                                                    quota.limitBytes(),
                                                    false),
                                            "accept '" + world.name() + "'"));
                        }
                        if (!world.ownerUuid().equals(owner.get())) {
                            transferRequests.deleteRequest(world.id(), caller.getUniqueId());
                            return ActionResult.failure(
                                    "OWNER_CHANGED",
                                    error(caller, ownerName + " is no longer the owner of '" + world.name() + "'"));
                        }
                        if (!worlds.transferOwnership(world.id(), owner.get(), caller.getUniqueId(), "MANUAL")) {
                            return ActionResult.failure(
                                    "TRANSFER_FAILED",
                                    error(caller, "could not accept transfer of '" + world.name() + "'"));
                        }
                        enqueueToWorldOrAliveNodes(
                                world, CommandKind.INVALIDATE_CACHE, NodeCommand.EMPTY_PAYLOAD, current);
                        Component msg = success(caller, "you are now the owner of '" + world.name() + "'!");
                        proxy.getPlayer(owner.get())
                                .ifPresent(online -> online.sendMessage(Component.text(
                                        caller.getUsername() + " accepted ownership transfer of '" + world.name()
                                                + "'!",
                                        NamedTextColor.GREEN)));
                        return ActionResult.success(msg);
                    } catch (SQLException e) {
                        log.error("/world transfer accept failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                "DATABASE_ERROR", error(caller, "that did not work; the failure is in the proxy log"));
                    }
                },
                executors.db());
    }

    /**
     * Declines a pending transfer request (FR-32).
     */
    public CompletableFuture<ActionResult> transferDecline(Player caller, String ownerName) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(ownerName, "ownerName");
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        Optional<UUID> owner = resolvePlayer(ownerName);
                        if (owner.isEmpty()) {
                            return ActionResult.failure(
                                    "PLAYER_NOT_FOUND",
                                    error(
                                            caller,
                                            "no player called '" + ownerName + "' has been seen on this network"));
                        }
                        List<TransferRequest> pending = transferRequests.findLiveRequestsFor(caller.getUniqueId());
                        Optional<TransferRequest> matching = pending.stream()
                                .filter(r -> r.fromUuid().equals(owner.get()))
                                .findFirst();
                        if (matching.isEmpty()) {
                            return ActionResult.failure(
                                    "NO_REQUEST",
                                    error(caller, "you have no pending transfer requests from " + ownerName));
                        }
                        transferRequests.deleteRequest(matching.get().worldId(), caller.getUniqueId());
                        Component msg = success(caller, "declined transfer request from " + ownerName);
                        proxy.getPlayer(owner.get())
                                .ifPresent(online -> online.sendMessage(Component.text(
                                        caller.getUsername() + " declined ownership transfer of your world",
                                        NamedTextColor.YELLOW)));
                        return ActionResult.success(msg);
                    } catch (SQLException e) {
                        log.error("/world transfer decline failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                "DATABASE_ERROR", error(caller, "that did not work; the failure is in the proxy log"));
                    }
                },
                executors.db());
    }

    public CompletableFuture<ActionResult> setPublic(Player caller, boolean isPublic, @Nullable String description) {
        return setPublic(caller, isPublic, description, null);
    }

    /**
     * Toggles visibility between PUBLIC and PRIVATE (FR-9a, FR-9f, FR-9h).
     */
    public CompletableFuture<ActionResult> setPublic(
            Player caller, boolean isPublic, @Nullable String description, @Nullable WorldId worldId) {
        Objects.requireNonNull(caller, "caller");
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        NetworkPolicy current = policy.get();
                        Optional<PlayerWorld> world = targetWorld(caller, worldId);
                        if (world.isEmpty()) {
                            return ActionResult.failure(
                                    "NO_TARGET_WORLD", Component.text("target world not found", NamedTextColor.RED));
                        }
                        Visibility visibility = isPublic ? Visibility.PUBLIC : Visibility.PRIVATE;
                        String desc = isPublic
                                ? (description != null
                                        ? description
                                        : world.get().description())
                                : null;

                        if (!worlds.updateVisibility(world.get().id(), visibility, desc)) {
                            return ActionResult.failure(
                                    "UPDATE_FAILED", error(caller, "could not update world visibility; try again"));
                        }
                        enqueueToWorldOrAliveNodes(
                                world.get(), CommandKind.INVALIDATE_CACHE, NodeCommand.EMPTY_PAYLOAD, current);

                        if (isPublic) {
                            Component msg = success(
                                    caller,
                                    "'" + world.get().name() + "' is now PUBLIC"
                                            + (desc != null ? " (\"" + desc + "\")" : "")
                                            + "; strangers can now browse and join as visitors");
                            return ActionResult.success(msg);
                        } else {
                            Component msg = success(
                                    caller,
                                    "'" + world.get().name() + "' is now PRIVATE; existing members are still members");
                            return ActionResult.success(msg);
                        }
                    } catch (SQLException e) {
                        log.error("/world public failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                "DATABASE_ERROR", error(caller, "that did not work; the failure is in the proxy log"));
                    }
                },
                executors.db());
    }

    public CompletableFuture<ActionResult> setSetting(Player caller, String settingName, String valueStr) {
        return setSetting(caller, settingName, valueStr, null);
    }

    /**
     * Updates world settings (FR-9e).
     */
    public CompletableFuture<ActionResult> setSetting(
            Player caller, String settingName, String valueStr, @Nullable WorldId worldId) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(settingName, "settingName");
        Objects.requireNonNull(valueStr, "valueStr");
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        NetworkPolicy current = policy.get();
                        Optional<PlayerWorld> world = targetWorld(caller, worldId);
                        if (world.isEmpty()) {
                            return ActionResult.failure(
                                    "NO_TARGET_WORLD", Component.text("target world not found", NamedTextColor.RED));
                        }
                        WorldSettings settings =
                                WorldSettings.fromJson(world.get().settingsJson());
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
                                return ActionResult.failure(
                                        "UNKNOWN_SETTING",
                                        error(
                                                caller,
                                                "unknown setting '" + settingName
                                                        + "'; valid settings: pvp, containers, interact, mob-griefing"));
                            }
                        }

                        if (!worlds.updateSettings(world.get().id(), updated.toJson())) {
                            return ActionResult.failure(
                                    "UPDATE_FAILED", error(caller, "could not update world settings; try again"));
                        }
                        enqueueToWorldOrAliveNodes(
                                world.get(), CommandKind.INVALIDATE_CACHE, NodeCommand.EMPTY_PAYLOAD, current);
                        Component msg = success(
                                caller,
                                "set " + normKey + " = " + boolVal + " for '"
                                        + world.get().name() + "'");
                        return ActionResult.success(msg);
                    } catch (SQLException e) {
                        log.error("/world set failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                "DATABASE_ERROR", error(caller, "that did not work; the failure is in the proxy log"));
                    }
                },
                executors.db());
    }

    public CompletableFuture<ActionResult> showSettings(Player caller) {
        return showSettings(caller, null);
    }

    /**
     * Displays settings for a world (FR-9e).
     */
    public CompletableFuture<ActionResult> showSettings(Player caller, @Nullable WorldId worldId) {
        Objects.requireNonNull(caller, "caller");
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        Optional<PlayerWorld> world = targetWorld(caller, worldId);
                        if (world.isEmpty()) {
                            return ActionResult.failure(
                                    "NO_TARGET_WORLD", Component.text("target world not found", NamedTextColor.RED));
                        }
                        WorldSettings settings =
                                WorldSettings.fromJson(world.get().settingsJson());
                        info(caller, "Settings for '" + world.get().name() + "':");
                        info(caller, "  PVP: " + (settings.pvp() ? "on" : "off"));
                        info(
                                caller,
                                "  Visitors may open containers: "
                                        + (settings.visitorsMayOpenContainers() ? "on" : "off"));
                        info(
                                caller,
                                "  Visitors may interact (doors/buttons/redstone): "
                                        + (settings.visitorsMayInteract() ? "on" : "off"));
                        Component msg = info(caller, "  Mob griefing: " + (settings.mobGriefing() ? "on" : "off"));
                        return ActionResult.success(msg);
                    } catch (SQLException e) {
                        log.error("/world settings failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                "DATABASE_ERROR", error(caller, "that did not work; the failure is in the proxy log"));
                    }
                },
                executors.db());
    }

    public CompletableFuture<ActionResult> ban(Player caller, String targetName, @Nullable String reason) {
        return ban(caller, targetName, reason, null);
    }

    /**
     * Bans a player from a world (FR-9d).
     */
    public CompletableFuture<ActionResult> ban(
            Player caller, String targetName, @Nullable String reason, @Nullable WorldId worldId) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(targetName, "targetName");
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        NetworkPolicy current = policy.get();
                        Optional<PlayerWorld> world = targetWorld(caller, worldId);
                        if (world.isEmpty()) {
                            return ActionResult.failure(
                                    "NO_TARGET_WORLD", Component.text("target world not found", NamedTextColor.RED));
                        }
                        Optional<UUID> target = resolvePlayer(targetName);
                        if (target.isEmpty()) {
                            return ActionResult.failure(
                                    "PLAYER_NOT_FOUND",
                                    error(
                                            caller,
                                            "no player called '" + targetName + "' has been seen on this network"));
                        }
                        if (target.get().equals(world.get().ownerUuid())) {
                            return ActionResult.failure(
                                    "CANNOT_BAN_OWNER", error(caller, "you cannot ban yourself from your own world"));
                        }

                        bans.ban(world.get().id(), target.get(), caller.getUniqueId(), reason);
                        membership.revokeInvite(world.get().id(), target.get());
                        membership.removeMember(world.get().id(), target.get());

                        enqueueToWorldOrAliveNodes(
                                world.get(), CommandKind.INVALIDATE_CACHE, NodeCommand.EMPTY_PAYLOAD, current);
                        String ejectReason = "Banned from world" + (reason != null ? ": " + reason : "");
                        enqueueToWorldOrAliveNodes(
                                world.get(),
                                CommandKind.KICK_MEMBER,
                                EjectPayload.format(target.get(), ejectReason),
                                current);

                        Component msg = success(
                                caller,
                                "banned " + targetName + " from '" + world.get().name() + "'");
                        return ActionResult.success(msg);
                    } catch (SQLException e) {
                        log.error("/world ban failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                "DATABASE_ERROR", error(caller, "that did not work; the failure is in the proxy log"));
                    }
                },
                executors.db());
    }

    public CompletableFuture<ActionResult> unban(Player caller, String targetName) {
        return unban(caller, targetName, null);
    }

    /**
     * Unbans a player from a world (FR-9d).
     */
    public CompletableFuture<ActionResult> unban(Player caller, String targetName, @Nullable WorldId worldId) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(targetName, "targetName");
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        Optional<PlayerWorld> world = targetWorld(caller, worldId);
                        if (world.isEmpty()) {
                            return ActionResult.failure(
                                    "NO_TARGET_WORLD", Component.text("target world not found", NamedTextColor.RED));
                        }
                        Optional<UUID> target = resolvePlayer(targetName);
                        if (target.isEmpty()) {
                            return ActionResult.failure(
                                    "PLAYER_NOT_FOUND",
                                    error(
                                            caller,
                                            "no player called '" + targetName + "' has been seen on this network"));
                        }
                        if (bans.unban(world.get().id(), target.get())) {
                            Component msg = success(
                                    caller,
                                    "unbanned " + targetName + " from '"
                                            + world.get().name() + "'");
                            return ActionResult.success(msg);
                        } else {
                            return ActionResult.failure(
                                    "NOT_BANNED",
                                    error(
                                            caller,
                                            targetName + " was not banned from '"
                                                    + world.get().name() + "'"));
                        }
                    } catch (SQLException e) {
                        log.error("/world unban failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                "DATABASE_ERROR", error(caller, "that did not work; the failure is in the proxy log"));
                    }
                },
                executors.db());
    }

    public CompletableFuture<ActionResult> listBans(Player caller) {
        return listBans(caller, null);
    }

    /**
     * Lists banned players for a world (FR-9d).
     */
    public CompletableFuture<ActionResult> listBans(Player caller, @Nullable WorldId worldId) {
        Objects.requireNonNull(caller, "caller");
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        Optional<PlayerWorld> world = targetWorld(caller, worldId);
                        if (world.isEmpty()) {
                            return ActionResult.failure(
                                    "NO_TARGET_WORLD", Component.text("target world not found", NamedTextColor.RED));
                        }
                        List<WorldBan> list = bans.listBans(world.get().id());
                        if (list.isEmpty()) {
                            Component msg = info(
                                    caller,
                                    "No players are currently banned from '"
                                            + world.get().name() + "'.");
                            return ActionResult.success(msg);
                        }
                        List<UUID> targets = list.stream().map(WorldBan::uuid).toList();
                        Map<UUID, String> resolved = names.namesOf(targets);

                        info(caller, "Bans for '" + world.get().name() + "':");
                        Component last = null;
                        for (WorldBan b : list) {
                            String name =
                                    resolved.getOrDefault(b.uuid(), b.uuid().toString());
                            String r = b.reason() != null ? " (" + b.reason() + ")" : "";
                            last = info(caller, "  • " + name + r);
                        }
                        return ActionResult.success(last != null ? last : Component.empty());
                    } catch (SQLException e) {
                        log.error("/world bans failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                "DATABASE_ERROR", error(caller, "that did not work; the failure is in the proxy log"));
                    }
                },
                executors.db());
    }

    public CompletableFuture<ActionResult> members(Player caller) {
        return members(caller, null);
    }

    /**
     * Lists members of a world (FR-8).
     */
    public CompletableFuture<ActionResult> members(Player caller, @Nullable WorldId worldId) {
        Objects.requireNonNull(caller, "caller");
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        Optional<PlayerWorld> world = targetWorld(caller, worldId);
                        if (world.isEmpty()) {
                            return ActionResult.failure(
                                    "NO_TARGET_WORLD", Component.text("target world not found", NamedTextColor.RED));
                        }
                        List<WorldMember> list =
                                membership.listMembers(world.get().id());
                        Map<UUID, String> resolved = names.namesOf(
                                list.stream().map(WorldMember::uuid).toList());
                        info(caller, "members of '" + world.get().name() + "':");
                        Component last = null;
                        for (WorldMember member : list) {
                            String display = resolved.getOrDefault(
                                    member.uuid(), member.uuid().toString());
                            last = info(caller, "  " + display + "  " + member.role());
                        }
                        return ActionResult.success(last != null ? last : Component.empty());
                    } catch (SQLException e) {
                        log.error("/world members failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                "DATABASE_ERROR", error(caller, "that did not work; the failure is in the proxy log"));
                    }
                },
                executors.db());
    }

    /**
     * Lists owned and shared worlds.
     */
    public CompletableFuture<ActionResult> list(Player caller) {
        Objects.requireNonNull(caller, "caller");
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        UUID callerUuid = caller.getUniqueId();
                        List<PlayerWorld> owned = worlds.listOwnedBy(callerUuid);
                        List<WorldMember> memberships = membership.membershipsOf(callerUuid).stream()
                                .filter(m -> m.role() != Role.OWNER)
                                .toList();

                        if (owned.isEmpty() && memberships.isEmpty()) {
                            Component msg = info(
                                    caller,
                                    "You do not own or belong to any worlds yet. Use /world create <name> to create one.");
                            return ActionResult.success(msg);
                        }

                        info(caller, "Your worlds:");
                        if (owned.isEmpty()) {
                            info(caller, "  (none)");
                        } else {
                            for (PlayerWorld world : owned) {
                                info(
                                        caller,
                                        "  • " + world.name() + " [" + world.state() + "] (visibility: "
                                                + world.visibility() + ")");
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
                        return ActionResult.success(Component.text("Your worlds", NamedTextColor.GRAY));
                    } catch (SQLException e) {
                        log.error("/world list failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                "DATABASE_ERROR", error(caller, "that did not work; the failure is in the proxy log"));
                    }
                },
                executors.db());
    }

    /**
     * Lists public worlds across the network (FR-9b).
     */
    public CompletableFuture<ActionResult> browse(Player caller) {
        return browse((CommandSource) caller);
    }

    /**
     * Lists public worlds across the network (FR-9b) for any CommandSource.
     */
    public CompletableFuture<ActionResult> browse(CommandSource source) {
        Objects.requireNonNull(source, "source");
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        List<PlayerWorld> publicWorlds = worlds.listPublicWorlds();
                        if (publicWorlds.isEmpty()) {
                            Component msg = info(source, "There are no public worlds available right now.");
                            return ActionResult.success(msg);
                        }
                        List<UUID> owners = publicWorlds.stream()
                                .map(PlayerWorld::ownerUuid)
                                .toList();
                        Map<UUID, String> ownerNames = names.namesOf(owners);

                        info(source, "Public worlds:");
                        for (PlayerWorld w : publicWorlds) {
                            String ownerName = ownerNames.getOrDefault(
                                    w.ownerUuid(), w.ownerUuid().toString());
                            String desc = w.description() != null ? " - \"" + w.description() + "\"" : "";
                            String status = (w.assignedNode() != null && w.leaseExpires() != null)
                                    ? "[LOADED on " + w.assignedNode() + "]"
                                    : "[UNLOADED]";
                            info(source, "  • " + w.name() + " (Owner: " + ownerName + ") " + status + desc);
                        }
                        return ActionResult.success(Component.text("Public worlds:", NamedTextColor.GRAY));
                    } catch (SQLException e) {
                        log.error("/world browse failed", e);
                        return ActionResult.failure(
                                "DATABASE_ERROR", error(source, "that did not work; the failure is in the proxy log"));
                    }
                },
                executors.db());
    }

    /**
     * Shows storage allowance and usage (FR-30a).
     */
    public CompletableFuture<ActionResult> storage(Player caller) {
        Objects.requireNonNull(caller, "caller");
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        NetworkPolicy current = policy.get();
                        long used = worlds.totalStorageUsedBy(caller.getUniqueId());
                        StorageTiers.Resolution resolved = storageTiers.evaluate(caller, used, current);
                        renderStorage(
                                caller,
                                caller.getUsername(),
                                resolved.quota(),
                                worlds.listOwnedBy(caller.getUniqueId()));
                        if (resolved.source() == StorageTiers.Source.PROBED
                                && caller.hasPermission(WorldCommand.ADMIN_PERMISSION)
                                && !current.storageQuotaTiers().isEmpty()) {
                            info(
                                    caller,
                                    "  (no enumerable permission plugin: only the "
                                            + current.storageQuotaTiers().size()
                                            + " tiers in storage.quota-tiers are recognised)");
                        }
                        return ActionResult.success(Component.text(
                                "Storage: " + StorageQuotaResolver.formatBytes(used), NamedTextColor.GRAY));
                    } catch (SQLException e) {
                        log.error("/world storage failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                "DATABASE_ERROR", error(caller, "that did not work; the failure is in the proxy log"));
                    }
                },
                executors.db());
    }

    // -----------------------------------------------------------------------
    // Internal & Helper methods
    // -----------------------------------------------------------------------

    private Optional<PlayerWorld> targetWorld(Player caller, @Nullable WorldId worldId) throws SQLException {
        if (worldId != null) {
            Optional<PlayerWorld> found = worlds.findById(worldId);
            if (found.isEmpty()) {
                error(caller, "that world no longer exists");
                return Optional.empty();
            }
            PlayerWorld world = found.get();
            if (!world.ownerUuid().equals(caller.getUniqueId())) {
                error(caller, "you do not own that world");
                return Optional.empty();
            }
            return Optional.of(world);
        }
        return soleOwnedWorld(caller);
    }

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

    public StorageQuota quotaFor(Player caller, NetworkPolicy current) throws SQLException {
        long used = worlds.totalStorageUsedBy(caller.getUniqueId());
        return storageTiers.evaluate(caller, used, current).quota();
    }

    public static Component refuseForQuota(CommandSource caller, StorageQuota quota, String attempted) {
        Component err = error(
                caller,
                "you cannot " + attempted + ": that would use "
                        + StorageQuotaResolver.formatBytes(quota.usedBytes()) + " of your "
                        + StorageQuotaResolver.formatBytes(quota.limitBytes()) + " storage allowance");
        info(caller, "/world storage shows where it has gone; archiving a world does not free it, deleting does");
        return err;
    }

    public static void renderStorage(CommandSource target, String who, StorageQuota quota, List<PlayerWorld> owned) {
        if (quota.unlimited()) {
            info(target, who + " storage: " + StorageQuotaResolver.formatBytes(quota.usedBytes()) + " (unlimited)");
        } else {
            info(
                    target,
                    who + " storage: " + StorageQuotaResolver.formatBytes(quota.usedBytes()) + " / "
                            + StorageQuotaResolver.formatBytes(quota.limitBytes()) + " "
                            + progressBar(quota.percentage()) + " "
                            + String.format(Locale.ROOT, "%.0f%%", quota.percentage()));
        }
        if (owned.isEmpty()) {
            info(target, "  no worlds owned");
            return;
        }
        for (PlayerWorld world : owned) {
            info(
                    target,
                    "  " + world.name() + " - " + StorageQuotaResolver.formatBytes(world.storageBytes()) + " - "
                            + (world.state() == WorldState.ARCHIVED ? "archived" : "live"));
        }
    }

    public static String progressBar(double percentage) {
        int filled = (int) Math.round(percentage / 10.0);
        StringBuilder bar = new StringBuilder(12).append('[');
        for (int i = 0; i < 10; i++) {
            bar.append(i < filled ? '|' : '.');
        }
        return bar.append(']').toString();
    }

    public Optional<UUID> resolvePlayer(String name) throws SQLException {
        Optional<Player> online = proxy.getPlayer(name);
        if (online.isPresent()) {
            return Optional.of(online.get().getUniqueId());
        }
        return names.uuidOf(name);
    }

    public @Nullable String routableNodeOrExplain(CommandSource caller, PlacementDecision decision, WorldId worldId) {
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

    public @Nullable String archivalNodeOrExplain(CommandSource caller, PlayerWorld world, NetworkPolicy current)
            throws SQLException {
        if (world.assignedNode() != null) {
            return world.assignedNode();
        }
        PlacementDecision decision = placement.forExistingWorld(world.id(), current);
        return routableNodeOrExplain(caller, decision, world.id());
    }

    public void enqueueTo(String nodeId, PlayerWorld world, CommandKind kind, String payloadJson, NetworkPolicy current)
            throws SQLException {
        nodeCommands.enqueue(
                nodeId,
                world.id(),
                world.generation(),
                kind.name(),
                payloadJson,
                current.holdingTimeout(),
                ControlChannels.forNode(nodeId));
    }

    public void enqueueToWorldOrAliveNodes(
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

    private static long parseSeed(String seedText) {
        try {
            return Long.parseLong(seedText);
        } catch (NumberFormatException e) {
            return seedText.hashCode();
        }
    }

    public static Component info(CommandSource source, String message) {
        Component comp = Component.text(message, NamedTextColor.GRAY);
        source.sendMessage(comp);
        return comp;
    }

    public static Component success(CommandSource source, String message) {
        Component comp = Component.text(message, NamedTextColor.GREEN);
        source.sendMessage(comp);
        return comp;
    }

    public static Component error(CommandSource source, String message) {
        Component comp = Component.text(message, NamedTextColor.RED);
        source.sendMessage(comp);
        return comp;
    }
}
