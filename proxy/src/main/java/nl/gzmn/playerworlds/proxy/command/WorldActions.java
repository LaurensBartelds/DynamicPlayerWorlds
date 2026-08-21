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
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.config.StorageQuotaResolver;
import nl.gzmn.playerworlds.core.control.ArchivePayload;
import nl.gzmn.playerworlds.core.control.CommandKind;
import nl.gzmn.playerworlds.core.control.CommandOutcomes;
import nl.gzmn.playerworlds.core.control.ControlChannels;
import nl.gzmn.playerworlds.core.control.DeletePayload;
import nl.gzmn.playerworlds.core.control.EjectPayload;
import nl.gzmn.playerworlds.core.control.NodeCommand;
import nl.gzmn.playerworlds.core.control.WorldPayload;
import nl.gzmn.playerworlds.core.db.Database;
import nl.gzmn.playerworlds.core.db.MembershipRepository;
import nl.gzmn.playerworlds.core.db.NodeCommandRepository;
import nl.gzmn.playerworlds.core.db.PendingTransferRepository;
import nl.gzmn.playerworlds.core.db.PlayerNameRepository;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository;
import nl.gzmn.playerworlds.core.db.TransferRequestRepository;
import nl.gzmn.playerworlds.core.db.WorldBanRepository;
import nl.gzmn.playerworlds.core.menu.FailureCode;
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
import nl.gzmn.playerworlds.proxy.permission.WorldPermissions;
import nl.gzmn.playerworlds.proxy.world.WorldPresence;
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

    /** For the one place that has to compose a delete and an enqueue into one transaction (R25). */
    private final Database database;

    private final Supplier<NetworkPolicy> policy;
    private final StorageTiers storageTiers;

    /**
     * Where each player is, filled by the nodes over the menu channel.
     *
     * <p>Owned here rather than injected because there must be exactly one of
     * it, and every surface that needs it already holds a {@code WorldActions}.
     */
    private final WorldPresence presence = new WorldPresence();

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
            Database database,
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
                database,
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
            Database database,
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
        this.database = Objects.requireNonNull(database, "database");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.storageTiers = Objects.requireNonNull(storageTiers, "storageTiers");
    }

    /**
     * Creates a new world (FR-1, FR-1a).
     *
     * <p>Permission is checked here rather than only in Brigadier so the GUI path
     * cannot bypass {@code gzmn.worlds.create} (D14 / R5).
     */
    public CompletableFuture<ActionResult> create(Player caller, String name, @Nullable String seedText) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(name, "name");
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        Optional<ActionResult> denied = requirePermission(caller, WorldPermissions.CREATE);
                        if (denied.isPresent()) {
                            return denied.get();
                        }
                        NetworkPolicy current = policy.get();
                        UUID owner = caller.getUniqueId();
                        int owned = worlds.countOwnedBy(owner);
                        if (owned >= current.maxWorldsPerPlayer()) {
                            return ActionResult.failure(
                                    FailureCode.CAP_REACHED,
                                    error("you already own " + owned + " worlds (limit " + current.maxWorldsPerPlayer()
                                            + ")"));
                        }
                        if (worlds.findByOwnerAndName(owner, name).isPresent()) {
                            return ActionResult.failure(
                                    FailureCode.ALREADY_EXISTS, error("you already own a world called '" + name + "'"));
                        }
                        StorageQuota quota = quotaFor(caller, current);
                        if (quota.isExceeded()) {
                            return ActionResult.failure(
                                    FailureCode.QUOTA_EXCEEDED, refuseForQuota(caller, quota, "create another world"));
                        }

                        WorldId newId = WorldId.random();
                        Visibility visibility = Visibility.valueOf(current.defaultVisibility());
                        PlacementDecision decision = placement.forNewWorld(newId, visibility, current);
                        Routing routing = routeOrExplain(decision, newId);
                        if (routing instanceof Routing.Refused refused) {
                            return ActionResult.failure(FailureCode.SERVER_UNROUTABLE, refused.explanation());
                        }
                        String nodeId = ((Routing.To) routing).nodeId();
                        var targetServer = registry.server(nodeId);
                        if (targetServer.isEmpty()) {
                            return ActionResult.failure(
                                    FailureCode.SERVER_UNROUTABLE, error("that server is not routable right now"));
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
                        Component msg =
                                info("creating '" + name + "' on " + nodeId + "; this may take a few seconds...");
                        caller.createConnectionRequest(targetServer.get()).fireAndForget();
                        return ActionResult.success(msg);
                    } catch (SQLException e) {
                        log.error("/world create failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("that did not work; the failure is in the proxy log"));
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
                                    FailureCode.WORLD_NOT_FOUND, error("you own no world called '" + name + "'"));
                        }
                        PlayerWorld world = found.get();
                        if (world.state() == WorldState.ARCHIVED) {
                            return ActionResult.failure(
                                    FailureCode.STATE_CONFLICT,
                                    info("'" + name + "' is already archived; use /world restore " + name
                                            + " to bring it back"));
                        }
                        if (world.state() != WorldState.READY && world.state() != WorldState.CREATING) {
                            return ActionResult.failure(
                                    FailureCode.STATE_CONFLICT,
                                    error("'" + name + "' is " + world.state() + " and cannot be deleted right now"));
                        }
                        if (!confirmed) {
                            if (world.state() == WorldState.CREATING) {
                                tell(
                                        caller,
                                        error(
                                                "'" + name
                                                        + "' was never completed. This removes the incomplete world and frees your slot."));
                            } else {
                                tell(caller, error("this archives '" + name + "' and frees a world slot."));
                            }
                            Component infoMsg = info("type /world delete " + name + " confirm to go ahead");
                            return ActionResult.failure(FailureCode.STATE_CONFLICT, infoMsg);
                        }

                        if (world.state() == WorldState.CREATING) {
                            if (!removeIncompleteWorld(world, current)) {
                                return ActionResult.failure(
                                        FailureCode.STATE_CONFLICT,
                                        error("'" + name + "' changed while you were confirming; try again"));
                            }
                            Component msg =
                                    success("removed incomplete world '" + name + "'; you have a world slot free");
                            log.info("world {} removed while in CREATING state by its owner (FR-27)", world.id());
                            return ActionResult.success(msg);
                        }

                        Routing routing = routeForExistingWorld(world, current);
                        if (routing instanceof Routing.Refused refused) {
                            return ActionResult.failure(FailureCode.SERVER_UNROUTABLE, refused.explanation());
                        }
                        String node = ((Routing.To) routing).nodeId();
                        long commandId = enqueueTo(
                                node,
                                world,
                                CommandKind.ARCHIVE_WORLD,
                                ArchivePayload.format(caller.getUniqueId()),
                                current);
                        ActionResult refused = outcomeOrRunning(caller, commandId, "archiving '" + name + "'");
                        if (refused != null) {
                            return refused;
                        }
                        Component msg = info("archiving '" + name + "' on " + node
                                + "; this may take a few minutes for a large world");
                        tell(
                                caller,
                                info("nothing is erased - the world is packed to cold storage and /world restore "
                                        + name + " brings it back"));
                        log.info("world {} queued for archival on {} by its owner (FR-27, FR-35)", world.id(), node);
                        return ActionResult.success(msg);
                    } catch (SQLException e) {
                        log.error("/world delete failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("that did not work; the failure is in the proxy log"));
                    }
                },
                executors.db());
    }

    /** Alias of {@link WorldPermissions#HARD_DELETE} for existing call sites. */
    public static final String HARD_DELETE_PERMISSION = WorldPermissions.HARD_DELETE;

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
                                    FailureCode.WORLD_NOT_FOUND, error("you own no world called '" + name + "'"));
                        }
                        return executeDeleteHard(caller, worldOpt.get(), confirmed);
                    } catch (SQLException e) {
                        log.error("deleteHard failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("that did not work; the failure is in the proxy log"));
                    }
                },
                executors.db());
    }

    /**
     * Permanently destroys an archived world by WorldId.
     *
     * <p>The GUI path reaches this only after {@code ConfirmMenu} has run (FR-37):
     * the modal is the typed-confirmation substitute, so {@code confirmed} is true.
     */
    public CompletableFuture<ActionResult> deleteHard(Player caller, WorldId worldId) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(worldId, "worldId");
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        Optional<PlayerWorld> worldOpt = worlds.findById(worldId);
                        if (worldOpt.isEmpty()) {
                            return ActionResult.failure(FailureCode.WORLD_NOT_FOUND, error("world not found"));
                        }
                        PlayerWorld world = worldOpt.get();
                        if (!world.ownerUuid().equals(caller.getUniqueId())) {
                            return ActionResult.failure(
                                    FailureCode.PERMISSION_DENIED, error("you are not the owner of this world"));
                        }
                        // ConfirmMenu on the backend is FR-37's confirmation substitute.
                        return executeDeleteHard(caller, world, true);
                    } catch (SQLException e) {
                        log.error("deleteHard by id failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("that did not work; the failure is in the proxy log"));
                    }
                },
                executors.db());
    }

    private ActionResult executeDeleteHard(Player caller, PlayerWorld world, boolean confirmed) throws SQLException {
        if (!WorldPermissions.allows(caller, WorldPermissions.HARD_DELETE)) {
            return ActionResult.failure(
                    FailureCode.PERMISSION_DENIED, error("you do not have permission to permanently delete worlds"));
        }
        // FR-37 takes ARCHIVED or READY. READY is the way out of a world that can never reach
        // ARCHIVED: with object storage unreachable, FR-35 has nothing to pack and no retry
        // changes that, so /world delete would refuse for ever and the world would hold one of
        // the owner's FR-30 slots with no way to play it or get rid of it. CREATING is FR-27's
        // removal, and the two transient states resolve themselves under FR-40.
        boolean neverArchived = world.state() == WorldState.READY;
        if (world.state() != WorldState.ARCHIVED && !neverArchived) {
            return ActionResult.failure(
                    FailureCode.STATE_CONFLICT,
                    error("'" + world.name() + "' is " + world.state()
                            + " and cannot be permanently deleted right now"));
        }
        if (!confirmed) {
            if (neverArchived) {
                // Deliberately not the ARCHIVED wording. There is no archive behind this one, so
                // "and all backup archives" would imply a copy survives when none does.
                tell(
                        caller,
                        error("'" + world.name() + "' has never been archived, so there is no backup."
                                + " This destroys the world itself. This cannot be undone."));
            } else {
                tell(
                        caller,
                        info("this permanently destroys '" + world.name()
                                + "' and all backup archives. This cannot be undone."));
            }
            return ActionResult.failure(
                    FailureCode.STATE_CONFLICT,
                    info("type /world delete " + world.name() + " hard confirm to permanently delete"));
        }
        // R23 / FR-37: routed to a node rather than done here. The confirmation
        // promises to destroy the world "and all backup archives", and the proxy
        // has no object-store client (spec section 13) — deleting the row here
        // took the archive rows with it through the cascade and orphaned every
        // object they named, permanently, because MN-2b's collection walks per
        // world and the world was gone.
        NetworkPolicy current = policy.get();
        Routing routing = routeForExistingWorld(world, current);
        if (routing instanceof Routing.Refused refused) {
            return ActionResult.failure(FailureCode.SERVER_UNROUTABLE, refused.explanation());
        }
        String node = ((Routing.To) routing).nodeId();
        long commandId = enqueueTo(node, world, CommandKind.DELETE_WORLD, DeletePayload.format(world.state()), current);
        ActionResult refused = outcomeOrRunning(caller, commandId, "permanently deleting '" + world.name() + "'");
        if (refused != null) {
            return refused;
        }
        log.info(
                "world {} ('{}') queued for permanent deletion on {} by owner {} (FR-37)",
                world.id(),
                world.name(),
                node,
                caller.getUsername());
        return ActionResult.success(info(
                "permanently deleting '" + world.name() + (neverArchived ? "' on " : "' and its archives on ") + node));
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
                                    FailureCode.WORLD_NOT_FOUND, error("you own no world called '" + name + "'"));
                        }
                        PlayerWorld world = found.get();
                        if (world.state() != WorldState.ARCHIVED) {
                            return ActionResult.failure(
                                    FailureCode.STATE_CONFLICT,
                                    error("'" + name + "' is " + world.state() + " and does not need restoring"));
                        }
                        int owned = worlds.countOwnedBy(caller.getUniqueId());
                        if (owned >= current.maxWorldsPerPlayer()) {
                            return ActionResult.failure(
                                    FailureCode.CAP_REACHED,
                                    error("you already own " + owned + " worlds (limit "
                                            + current.maxWorldsPerPlayer()
                                            + "); archive one before restoring this"));
                        }
                        StorageQuota quota = quotaFor(caller, current);
                        if (quota.isExceeded()) {
                            return ActionResult.failure(
                                    FailureCode.QUOTA_EXCEEDED,
                                    refuseForQuota(caller, quota, "restore '" + name + "'"));
                        }
                        Routing routing = routeForExistingWorld(world, current);
                        if (routing instanceof Routing.Refused refused) {
                            return ActionResult.failure(FailureCode.SERVER_UNROUTABLE, refused.explanation());
                        }
                        String node = ((Routing.To) routing).nodeId();
                        long commandId =
                                enqueueTo(node, world, CommandKind.RESTORE_WORLD, ArchivePayload.format(null), current);
                        ActionResult refused = outcomeOrRunning(caller, commandId, "restoring '" + name + "'");
                        if (refused != null) {
                            return refused;
                        }
                        Component msg = info("restoring '" + name + "' on " + node + "; this may take a few minutes");
                        log.info("world {} queued for restore on {} by its owner (FR-36)", world.id(), node);
                        return ActionResult.success(msg);
                    } catch (SQLException e) {
                        log.error("/world restore failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("that did not work; the failure is in the proxy log"));
                    }
                },
                executors.db());
    }

    /**
     * Joins a world by owner name and optional world name (FR-10).
     *
     * <p>Permission checked here so the menu channel cannot bypass {@code gzmn.worlds.join} (D14).
     */
    public CompletableFuture<ActionResult> join(Player caller, String ownerName, @Nullable String worldName) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(ownerName, "ownerName");
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        Optional<ActionResult> denied = requirePermission(caller, WorldPermissions.JOIN);
                        if (denied.isPresent()) {
                            return denied.get();
                        }
                        NetworkPolicy current = policy.get();
                        Optional<UUID> owner = resolvePlayer(ownerName);
                        if (owner.isEmpty()) {
                            return ActionResult.failure(
                                    FailureCode.WORLD_NOT_FOUND, error("no world you can join matches that"));
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
                                    FailureCode.WORLD_NOT_FOUND, error("no world you can join matches that"));
                        }
                        return doJoin(caller, target.get(), current);
                    } catch (SQLException e) {
                        log.error("/world join failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("that did not work; the failure is in the proxy log"));
                    }
                },
                executors.db());
    }

    /**
     * Joins a world by its {@link WorldId}.
     *
     * <p>Permission checked here so the menu channel cannot bypass {@code gzmn.worlds.join} (D14).
     */
    public CompletableFuture<ActionResult> join(Player caller, WorldId worldId) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(worldId, "worldId");
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        Optional<ActionResult> denied = requirePermission(caller, WorldPermissions.JOIN);
                        if (denied.isPresent()) {
                            return denied.get();
                        }
                        NetworkPolicy current = policy.get();
                        Optional<PlayerWorld> target = worlds.findById(worldId);
                        if (target.isEmpty()
                                || (target.get().state() != WorldState.READY
                                        && target.get().state() != WorldState.CREATING)) {
                            return ActionResult.failure(
                                    FailureCode.WORLD_NOT_FOUND, error("no world you can join matches that"));
                        }
                        return doJoin(caller, target.get(), current);
                    } catch (SQLException e) {
                        log.error("/world join failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("that did not work; the failure is in the proxy log"));
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
            return ActionResult.failure(
                    FailureCode.BANNED, error("you are banned from '" + world.name() + "'" + reason));
        }

        if (membership.findMember(world.id(), caller.getUniqueId()).isEmpty()) {
            if (world.visibility() == Visibility.PUBLIC) {
                membership.addVisitorIfAbsent(world.id(), caller.getUniqueId());
            } else {
                return ActionResult.failure(FailureCode.PERMISSION_DENIED, error("no world you can join matches that"));
            }
        }

        PlacementDecision decision = placement.forExistingWorld(world.id(), current);
        Routing routing = routeOrExplain(decision, world.id());
        if (routing instanceof Routing.Refused refused) {
            return ActionResult.failure(FailureCode.SERVER_UNROUTABLE, refused.explanation());
        }
        String nodeId = ((Routing.To) routing).nodeId();

        long routingGeneration = world.generation();
        // R12: track a lease this join acquired so terminal failures before the
        // player is handed off release it (MN-12). releaseLease is conditional on
        // (node, generation), so a racing takeover is a no-op.
        @Nullable String acquiredForNode = null;
        long acquiredGeneration = -1L;
        if (decision instanceof PlacementDecision.Selected selected) {
            Optional<PlayerWorldRepository.LeaseGrant> grant =
                    worlds.acquireLease(world.id(), nodeId, selected.node().dataVersion(), current.leaseDuration());
            if (grant.isEmpty()) {
                return ActionResult.failure(
                        FailureCode.STATE_CONFLICT,
                        error("that world is being opened elsewhere right now; try again in a moment"));
            }
            routingGeneration = grant.get().generation();
            acquiredForNode = nodeId;
            acquiredGeneration = routingGeneration;
        } else {
            Optional<PlayerWorld> fresh = worlds.findById(world.id());
            if (fresh.isPresent()) {
                routingGeneration = fresh.get().generation();
            }
        }

        try {
            transfers.route(caller.getUniqueId(), world.id(), nodeId, routingGeneration);
            var targetServer = registry.server(nodeId);
            if (targetServer.isEmpty()) {
                releaseJoinLease(world.id(), acquiredForNode, acquiredGeneration);
                return ActionResult.failure(
                        FailureCode.SERVER_UNROUTABLE, error("that server is not routable right now"));
            }
            Component msg = info("sending you to '" + world.name() + "'...");
            // One node holds many worlds (MN-15). If the caller is already on this
            // node -- switching from one of its worlds to another -- Velocity's
            // createConnectionRequest refuses with ALREADY_CONNECTED and
            // fireAndForget() answers that by telling the player "You are already
            // connected to this server!", which is wrong: they are not there yet.
            // Skip the reconnect and let the pending_transfer row just written
            // carry them the rest of the way; the node's own poll picks it up and
            // teleports them in place (FR-11).
            boolean alreadyOnNode = caller.getCurrentServer()
                    .map(conn -> conn.getServerInfo().getName().equals(nodeId))
                    .orElse(false);
            if (!alreadyOnNode) {
                caller.createConnectionRequest(targetServer.get()).fireAndForget();
            }
            return ActionResult.success(msg);
        } catch (SQLException e) {
            releaseJoinLease(world.id(), acquiredForNode, acquiredGeneration);
            throw e;
        }
    }

    /** R12: drop a lease acquired for a join that never left the proxy (MN-12). */
    private void releaseJoinLease(WorldId worldId, @Nullable String nodeId, long generation) {
        if (nodeId == null || generation < 0L) {
            return;
        }
        try {
            if (worlds.releaseLease(worldId, nodeId, generation)) {
                log.info(
                        "released lease for {} on {} gen {} after failed join handoff (R12)",
                        worldId,
                        nodeId,
                        generation);
            }
        } catch (SQLException e) {
            log.warn("could not release lease for {} on {} after failed join handoff", worldId, nodeId, e);
        }
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
                        Target scope = targetWorld(caller, worldId, "/world invite <player> <world>");
                        if (scope instanceof Target.None none) {
                            return none.refusal();
                        }
                        PlayerWorld world = ((Target.Found) scope).world();
                        Optional<UUID> target = resolvePlayer(targetName);
                        if (target.isEmpty()) {
                            return ActionResult.failure(
                                    FailureCode.PLAYER_NOT_FOUND,
                                    error("no player called '" + targetName + "' has been seen on this network"));
                        }
                        if (target.get().equals(caller.getUniqueId())) {
                            return ActionResult.failure(
                                    FailureCode.INVALID_NAME, error("you are already the owner of that world"));
                        }
                        if (membership.findMember(world.id(), target.get()).isPresent()) {
                            return ActionResult.failure(
                                    FailureCode.ALREADY_EXISTS,
                                    error(targetName + " is already a member of '" + world.name() + "'"));
                        }

                        membership.invite(world.id(), target.get(), caller.getUniqueId(), current.inviteExpiry());
                        Component msg =
                                success("invited " + targetName + " to '" + world.name() + "'; the invite expires in "
                                        + current.inviteExpiry().toMinutes() + " minutes");

                        proxy.getPlayer(target.get())
                                .ifPresent(
                                        online -> online.sendMessage(inviteNotice(caller.getUsername(), world.name())));
                        return ActionResult.success(msg);
                    } catch (SQLException e) {
                        log.error("/world invite failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("that did not work; the failure is in the proxy log"));
                    }
                },
                executors.db());
    }

    /**
     * Accepts an invitation (FR-7).
     *
     * <p>Requires {@code gzmn.worlds.join} (section 6), checked here for the menu path (D14).
     */
    public CompletableFuture<ActionResult> accept(Player caller, String ownerName) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(ownerName, "ownerName");
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        NetworkPolicy current = policy.get();
                        Optional<ActionResult> denied = requirePermission(caller, WorldPermissions.JOIN);
                        if (denied.isPresent()) {
                            return denied.get();
                        }
                        Optional<UUID> owner = resolvePlayer(ownerName);
                        if (owner.isEmpty()) {
                            return ActionResult.failure(
                                    FailureCode.PLAYER_NOT_FOUND,
                                    error("no player called '" + ownerName + "' has been seen on this network"));
                        }
                        List<PlayerWorld> owned = worlds.listOwnedBy(owner.get());
                        for (PlayerWorld world : owned) {
                            switch (membership.acceptInvite(world.id(), caller.getUniqueId())) {
                                case MembershipRepository.AcceptOutcome.Accepted accepted -> {
                                    // FR-9 / FR-31a: role enforcement on the node answers from
                                    // MembershipCache, which is filled at world load and only
                                    // refreshed when something says membership moved. Without
                                    // this the brand-new BUILDER is a VISITOR in a world that
                                    // is already loaded -- they can walk in and not build --
                                    // until it next unloads. Kick, promote and demote have
                                    // always sent it; accept was the one path that did not.
                                    enqueueToWorldOrAliveNodes(
                                            world, CommandKind.INVALIDATE_CACHE, NodeCommand.EMPTY_PAYLOAD, current);
                                    Component msg = success(
                                            "you are now a " + accepted.member().role() + " of '" + world.name() + "'");
                                    tell(caller, info("open /worlds and pick '" + world.name() + "' to go there"));
                                    return ActionResult.success(msg);
                                }
                                case MembershipRepository.AcceptOutcome.AlreadyMember already -> {
                                    Component msg =
                                            info("you were already a " + already.role() + " of '" + world.name() + "'");
                                    return ActionResult.success(msg);
                                }
                                case MembershipRepository.AcceptOutcome.NoLiveInvite ignored -> {
                                    // Try the owner's next world.
                                }
                            }
                        }
                        return ActionResult.failure(
                                FailureCode.STATE_CONFLICT, error("you have no live invite from " + ownerName));
                    } catch (SQLException e) {
                        log.error("/world accept failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("that did not work; the failure is in the proxy log"));
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
                        Target scope = targetWorld(caller, worldId, "/world kick <player> <world>");
                        if (scope instanceof Target.None none) {
                            return none.refusal();
                        }
                        PlayerWorld world = ((Target.Found) scope).world();
                        Optional<UUID> target = resolvePlayer(targetName);
                        if (target.isEmpty()) {
                            return ActionResult.failure(
                                    FailureCode.PLAYER_NOT_FOUND,
                                    error("no player called '" + targetName + "' has been seen on this network"));
                        }
                        if (target.get().equals(world.ownerUuid())) {
                            return ActionResult.failure(
                                    FailureCode.PERMISSION_DENIED,
                                    error(
                                            "you cannot kick yourself from your own world; use /world transfer or /world delete"));
                        }
                        membership.revokeInvite(world.id(), target.get());
                        if (!membership.removeMember(world.id(), target.get())) {
                            return ActionResult.failure(
                                    FailureCode.STATE_CONFLICT,
                                    error(targetName + " is not a member of '" + world.name() + "'"));
                        }
                        enqueueToWorldOrAliveNodes(
                                world, CommandKind.INVALIDATE_CACHE, NodeCommand.EMPTY_PAYLOAD, current);
                        enqueueToWorldOrAliveNodes(
                                world,
                                CommandKind.KICK_MEMBER,
                                EjectPayload.format(target.get(), "You were removed from this world"),
                                current);
                        // FR-8: "removes them from the world immediately and returns
                        // them to lobby". The line that used to follow this said
                        // they would go "on their next join", which described the
                        // pre-control-plane behaviour -- a KICK_MEMBER now ejects
                        // them where they stand -- and taught operators a model
                        // the system stopped having (R27).
                        Component msg = success("removed " + targetName + " from '" + world.name()
                                + "'; if they are in it now, they are on their way to lobby");
                        return ActionResult.success(msg);
                    } catch (SQLException e) {
                        log.error("/world kick failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("that did not work; the failure is in the proxy log"));
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
                        Target scope = targetWorld(caller, worldId, "/world promote <player> <world>");
                        if (scope instanceof Target.None none) {
                            return none.refusal();
                        }
                        PlayerWorld world = ((Target.Found) scope).world();
                        Optional<UUID> target = resolvePlayer(targetName);
                        if (target.isEmpty()) {
                            return ActionResult.failure(
                                    FailureCode.PLAYER_NOT_FOUND,
                                    error("no player called '" + targetName + "' has been seen on this network"));
                        }
                        if (membership.setRole(world.id(), target.get(), Role.BUILDER)) {
                            enqueueToWorldOrAliveNodes(
                                    world, CommandKind.INVALIDATE_CACHE, NodeCommand.EMPTY_PAYLOAD, current);
                            Component msg = success(targetName + " is now a BUILDER of '" + world.name() + "'");
                            return ActionResult.success(msg);
                        } else {
                            return ActionResult.failure(
                                    FailureCode.STATE_CONFLICT,
                                    error(targetName + " is not a member of '" + world.name() + "', or is its owner"));
                        }
                    } catch (SQLException e) {
                        log.error("/world promote failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("that did not work; the failure is in the proxy log"));
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
                        Target scope = targetWorld(caller, worldId, "/world transfer <player> <world>");
                        if (scope instanceof Target.None none) {
                            return none.refusal();
                        }
                        PlayerWorld world = ((Target.Found) scope).world();
                        Optional<UUID> target = resolvePlayer(targetName);
                        if (target.isEmpty()) {
                            return ActionResult.failure(
                                    FailureCode.PLAYER_NOT_FOUND,
                                    error("no player called '" + targetName + "' has been seen on this network"));
                        }
                        if (target.get().equals(caller.getUniqueId())) {
                            return ActionResult.failure(FailureCode.INVALID_NAME, error("you already own this world"));
                        }
                        if (membership.findMember(world.id(), target.get()).isEmpty()) {
                            return ActionResult.failure(
                                    FailureCode.STATE_CONFLICT,
                                    error(targetName + " is not a member of '" + world.name() + "'"));
                        }
                        int ownedCount = worlds.countOwnedBy(target.get());
                        if (ownedCount >= current.maxWorldsPerPlayer()) {
                            return ActionResult.failure(
                                    FailureCode.CAP_REACHED,
                                    error(targetName + " has reached their world limit (" + current.maxWorldsPerPlayer()
                                            + ")"));
                        }

                        if (!confirmed) {
                            Component msg = info("Are you sure you want to transfer ownership of '"
                                    + world.name()
                                    + "' to " + targetName
                                    + "? You will become a BUILDER. Type /world transfer "
                                    + targetName + " confirm to proceed.");
                            return ActionResult.failure(FailureCode.STATE_CONFLICT, msg);
                        }

                        Optional<Player> online = proxy.getPlayer(target.get());
                        if (online.isPresent()) {
                            // Online -> immediate transfer (FR-31)
                            if (!worlds.transferOwnership(world.id(), caller.getUniqueId(), target.get(), "MANUAL")) {
                                return ActionResult.failure(
                                        FailureCode.GENERIC_ERROR,
                                        error("could not transfer ownership of '" + world.name() + "'"));
                            }
                            enqueueToWorldOrAliveNodes(
                                    world, CommandKind.INVALIDATE_CACHE, NodeCommand.EMPTY_PAYLOAD, current);
                            Component msg = success("transferred ownership of '" + world.name() + "' to " + targetName
                                    + "; you are now a BUILDER");
                            online.get()
                                    .sendMessage(Component.text(
                                            "You are now the owner of '" + world.name() + "'!", NamedTextColor.GREEN));
                            return ActionResult.success(msg);
                        } else {
                            // Offline -> create pending transfer request (FR-32)
                            transferRequests.requestTransfer(
                                    world.id(), target.get(), caller.getUniqueId(), current.transferPendingExpiry());
                            Component msg = success("created transfer request for " + targetName
                                    + "; they can accept it next time they log in");
                            return ActionResult.success(msg);
                        }
                    } catch (SQLException e) {
                        log.error("/world transfer failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("that did not work; the failure is in the proxy log"));
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
                                    FailureCode.PLAYER_NOT_FOUND,
                                    error("no player called '" + ownerName + "' has been seen on this network"));
                        }
                        List<TransferRequest> pending = transferRequests.findLiveRequestsFor(caller.getUniqueId());
                        Optional<TransferRequest> matching = pending.stream()
                                .filter(r -> r.fromUuid().equals(owner.get()))
                                .findFirst();
                        if (matching.isEmpty()) {
                            return ActionResult.failure(
                                    FailureCode.STATE_CONFLICT,
                                    error("you have no pending transfer requests from " + ownerName));
                        }
                        int ownedCount = worlds.countOwnedBy(caller.getUniqueId());
                        if (ownedCount >= current.maxWorldsPerPlayer()) {
                            return ActionResult.failure(
                                    FailureCode.CAP_REACHED,
                                    error("you have reached your world limit (" + current.maxWorldsPerPlayer() + ")"));
                        }
                        Optional<PlayerWorld> worldOpt =
                                worlds.findById(matching.get().worldId());
                        if (worldOpt.isEmpty()) {
                            return ActionResult.failure(
                                    FailureCode.WORLD_NOT_FOUND, error("that world no longer exists"));
                        }
                        PlayerWorld world = worldOpt.get();
                        StorageQuota quota = quotaFor(caller, current);
                        if (!quota.unlimited() && quota.usedBytes() + world.storageBytes() > quota.limitBytes()) {
                            return ActionResult.failure(
                                    FailureCode.QUOTA_EXCEEDED,
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
                                    FailureCode.STATE_CONFLICT,
                                    error(ownerName + " is no longer the owner of '" + world.name() + "'"));
                        }
                        if (!worlds.transferOwnership(world.id(), owner.get(), caller.getUniqueId(), "MANUAL")) {
                            return ActionResult.failure(
                                    FailureCode.GENERIC_ERROR,
                                    error("could not accept transfer of '" + world.name() + "'"));
                        }
                        enqueueToWorldOrAliveNodes(
                                world, CommandKind.INVALIDATE_CACHE, NodeCommand.EMPTY_PAYLOAD, current);
                        Component msg = success("you are now the owner of '" + world.name() + "'!");
                        proxy.getPlayer(owner.get())
                                .ifPresent(online -> online.sendMessage(Component.text(
                                        caller.getUsername() + " accepted ownership transfer of '" + world.name()
                                                + "'!",
                                        NamedTextColor.GREEN)));
                        return ActionResult.success(msg);
                    } catch (SQLException e) {
                        log.error("/world transfer accept failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("that did not work; the failure is in the proxy log"));
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
                                    FailureCode.PLAYER_NOT_FOUND,
                                    error("no player called '" + ownerName + "' has been seen on this network"));
                        }
                        List<TransferRequest> pending = transferRequests.findLiveRequestsFor(caller.getUniqueId());
                        Optional<TransferRequest> matching = pending.stream()
                                .filter(r -> r.fromUuid().equals(owner.get()))
                                .findFirst();
                        if (matching.isEmpty()) {
                            return ActionResult.failure(
                                    FailureCode.STATE_CONFLICT,
                                    error("you have no pending transfer requests from " + ownerName));
                        }
                        transferRequests.deleteRequest(matching.get().worldId(), caller.getUniqueId());
                        Component msg = success("declined transfer request from " + ownerName);
                        proxy.getPlayer(owner.get())
                                .ifPresent(online -> online.sendMessage(Component.text(
                                        caller.getUsername() + " declined ownership transfer of your world",
                                        NamedTextColor.YELLOW)));
                        return ActionResult.success(msg);
                    } catch (SQLException e) {
                        log.error("/world transfer decline failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("that did not work; the failure is in the proxy log"));
                    }
                },
                executors.db());
    }

    public CompletableFuture<ActionResult> setPublic(Player caller, boolean isPublic, @Nullable String description) {
        return setPublic(caller, isPublic, description, null);
    }

    /**
     * Toggles visibility between PUBLIC and PRIVATE (FR-9a, FR-9f, FR-9h).
     *
     * <p>{@code gzmn.worlds.public} is enforced here — not only in Brigadier — so the
     * menu channel cannot open a world to strangers without the node (D14 / FR-9h / OQ-7).
     */
    public CompletableFuture<ActionResult> setPublic(
            Player caller, boolean isPublic, @Nullable String description, @Nullable WorldId worldId) {
        Objects.requireNonNull(caller, "caller");
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        Optional<ActionResult> denied = requirePermission(caller, WorldPermissions.PUBLIC);
                        if (denied.isPresent()) {
                            return denied.get();
                        }
                        NetworkPolicy current = policy.get();
                        Target scope = targetWorld(caller, worldId, null);
                        if (scope instanceof Target.None none) {
                            return none.refusal();
                        }
                        PlayerWorld world = ((Target.Found) scope).world();
                        Visibility visibility = isPublic ? Visibility.PUBLIC : Visibility.PRIVATE;
                        String desc = isPublic ? (description != null ? description : world.description()) : null;

                        if (!worlds.updateVisibility(world.id(), visibility, desc)) {
                            return ActionResult.failure(
                                    FailureCode.GENERIC_ERROR, error("could not update world visibility; try again"));
                        }
                        enqueueToWorldOrAliveNodes(
                                world, CommandKind.INVALIDATE_CACHE, NodeCommand.EMPTY_PAYLOAD, current);

                        if (isPublic) {
                            Component msg = success("'" + world.name() + "' is now PUBLIC"
                                    + (desc != null ? " (\"" + desc + "\")" : "")
                                    + "; strangers can now browse and join as visitors");
                            return ActionResult.success(msg);
                        } else {
                            Component msg = success(
                                    "'" + world.name() + "' is now PRIVATE; existing members are still members");
                            return ActionResult.success(msg);
                        }
                    } catch (SQLException e) {
                        log.error("/world public failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("that did not work; the failure is in the proxy log"));
                    }
                },
                executors.db());
    }

    public CompletableFuture<ActionResult> setSetting(Player caller, String settingName, String valueStr) {
        return setSetting(caller, settingName, valueStr, null);
    }

    /**
     * Updates world settings (FR-9e, FR-9i).
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
                        Target scope = targetWorld(caller, worldId, "/world set <setting> <value> <world>");
                        if (scope instanceof Target.None none) {
                            return none.refusal();
                        }
                        PlayerWorld world = ((Target.Found) scope).world();
                        WorldSettings settings = WorldSettings.fromJson(world.settingsJson());
                        WorldSettings updated;
                        String normKey = settingName.toLowerCase(Locale.ROOT);
                        String normVal = valueStr.toLowerCase(Locale.ROOT);
                        boolean boolVal = normVal.equals("on")
                                || normVal.equals("true")
                                || normVal.equals("allow")
                                || normVal.equals("yes")
                                || normVal.equals("enable");
                        String displayValue = String.valueOf(boolVal);

                        switch (normKey) {
                            case "pvp" -> updated = settings.withPvp(boolVal);
                            case "containers" -> updated = settings.withVisitorsMayOpenContainers(boolVal);
                            case "interact", "redstone", "doors" -> updated = settings.withVisitorsMayInteract(boolVal);
                            case "mob-griefing", "mobgriefing" -> updated = settings.withMobGriefing(boolVal);
                            case "keep-inventory", "keepinventory" -> updated = settings.withKeepInventory(boolVal);
                            case "fall-damage" -> updated = settings.withFallDamage(boolVal);
                            case "fire-damage" -> updated = settings.withFireDamage(boolVal);
                            case "freeze-damage" -> updated = settings.withFreezeDamage(boolVal);
                            case "drowning-damage" -> updated = settings.withDrowningDamage(boolVal);
                            case "daylight-cycle", "advance-time" -> updated = settings.withAdvanceTime(boolVal);
                            case "weather-cycle", "advance-weather" -> updated = settings.withAdvanceWeather(boolVal);
                            case "insomnia", "phantoms" -> updated = settings.withSpawnPhantoms(boolVal);
                            case "immediate-respawn" -> updated = settings.withImmediateRespawn(boolVal);
                            case "natural-regeneration", "regeneration" ->
                                updated = settings.withNaturalHealthRegeneration(boolVal);
                            case "sleep-percentage", "sleeping-percentage" -> {
                                Integer parsed = parseRangedInt(valueStr, 0, 100);
                                if (parsed == null) {
                                    return invalidIntResult(settingName, 0, 100);
                                }
                                updated = settings.withPlayersSleepingPercentage(parsed);
                                displayValue = String.valueOf(parsed);
                            }
                            case "entity-cramming", "max-entity-cramming" -> {
                                Integer parsed = parseRangedInt(valueStr, 0, Integer.MAX_VALUE);
                                if (parsed == null) {
                                    return invalidIntResult(settingName, 0, Integer.MAX_VALUE);
                                }
                                updated = settings.withMaxEntityCramming(parsed);
                                displayValue = String.valueOf(parsed);
                            }
                            case "respawn-radius" -> {
                                Integer parsed = parseRangedInt(valueStr, 0, Integer.MAX_VALUE);
                                if (parsed == null) {
                                    return invalidIntResult(settingName, 0, Integer.MAX_VALUE);
                                }
                                updated = settings.withRespawnRadius(parsed);
                                displayValue = String.valueOf(parsed);
                            }
                            case "snow-height", "max-snow-height" -> {
                                Integer parsed = parseRangedInt(valueStr, 0, Integer.MAX_VALUE);
                                if (parsed == null) {
                                    return invalidIntResult(settingName, 0, Integer.MAX_VALUE);
                                }
                                updated = settings.withMaxSnowAccumulationHeight(parsed);
                                displayValue = String.valueOf(parsed);
                            }
                            default -> {
                                return ActionResult.failure(
                                        FailureCode.INVALID_NAME,
                                        error("unknown setting '" + settingName
                                                + "'; valid settings: pvp, containers, interact, mob-griefing, "
                                                + "keep-inventory, fall-damage, fire-damage, freeze-damage, "
                                                + "drowning-damage, daylight-cycle, weather-cycle, insomnia, "
                                                + "immediate-respawn, natural-regeneration, sleep-percentage, "
                                                + "entity-cramming, respawn-radius, snow-height"));
                            }
                        }

                        if (!worlds.updateSettings(world.id(), updated.toJson())) {
                            return ActionResult.failure(
                                    FailureCode.GENERIC_ERROR, error("could not update world settings; try again"));
                        }
                        // R9 / FR-9e / FR-9i: APPLY_SETTINGS refreshes the settings cache
                        // and re-asserts every gamerule on loaded dimensions. INVALIDATE_CACHE
                        // alone left those gamerules stuck at load time.
                        enqueueToWorldOrAliveNodes(
                                world, CommandKind.APPLY_SETTINGS, NodeCommand.EMPTY_PAYLOAD, current);
                        Component msg =
                                success("set " + normKey + " = " + displayValue + " for '" + world.name() + "'");
                        return ActionResult.success(msg);
                    } catch (SQLException e) {
                        log.error("/world set failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("that did not work; the failure is in the proxy log"));
                    }
                },
                executors.db());
    }

    /** Parses a whole number within {@code [min, max]}, or {@code null} if it does not parse or is out of range. */
    private static @Nullable Integer parseRangedInt(String valueStr, int min, int max) {
        try {
            int parsed = Integer.parseInt(valueStr.trim());
            if (parsed < min || parsed > max) {
                return null;
            }
            return parsed;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static ActionResult invalidIntResult(String settingName, int min, int max) {
        String range = max == Integer.MAX_VALUE ? (min + " or greater") : (min + "-" + max);
        return ActionResult.failure(
                FailureCode.INVALID_NAME, error("'" + settingName + "' must be a whole number " + range));
    }

    public CompletableFuture<ActionResult> showSettings(Player caller) {
        return showSettings(caller, null);
    }

    /**
     * Displays settings for a world (FR-9e, FR-9i).
     */
    public CompletableFuture<ActionResult> showSettings(Player caller, @Nullable WorldId worldId) {
        Objects.requireNonNull(caller, "caller");
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        Target scope = targetWorld(caller, worldId, "/world settings <world>");
                        if (scope instanceof Target.None none) {
                            return none.refusal();
                        }
                        PlayerWorld world = ((Target.Found) scope).world();
                        WorldSettings settings = WorldSettings.fromJson(world.settingsJson());
                        tell(caller, info("Settings for '" + world.name() + "':"));
                        tell(caller, info("  PVP: " + (settings.pvp() ? "on" : "off")));
                        tell(
                                caller,
                                info("  Visitors may open containers: "
                                        + (settings.visitorsMayOpenContainers() ? "on" : "off")));
                        tell(
                                caller,
                                info("  Visitors may interact (doors/buttons/redstone): "
                                        + (settings.visitorsMayInteract() ? "on" : "off")));
                        tell(caller, info("  Mob griefing: " + (settings.mobGriefing() ? "on" : "off")));
                        tell(caller, info("  Keep inventory: " + (settings.keepInventory() ? "on" : "off")));
                        tell(caller, info("  Fall damage: " + (settings.fallDamage() ? "on" : "off")));
                        tell(caller, info("  Fire damage: " + (settings.fireDamage() ? "on" : "off")));
                        tell(caller, info("  Freeze damage: " + (settings.freezeDamage() ? "on" : "off")));
                        tell(caller, info("  Drowning damage: " + (settings.drowningDamage() ? "on" : "off")));
                        tell(caller, info("  Daylight cycle: " + (settings.advanceTime() ? "on" : "off")));
                        tell(caller, info("  Weather cycle: " + (settings.advanceWeather() ? "on" : "off")));
                        tell(caller, info("  Insomnia (phantoms): " + (settings.spawnPhantoms() ? "on" : "off")));
                        tell(caller, info("  Immediate respawn: " + (settings.immediateRespawn() ? "on" : "off")));
                        tell(
                                caller,
                                info("  Natural regeneration: "
                                        + (settings.naturalHealthRegeneration() ? "on" : "off")));
                        tell(
                                caller,
                                info("  Players sleeping percentage: " + settings.playersSleepingPercentage() + "%"));
                        tell(caller, info("  Max entity cramming: " + settings.maxEntityCramming()));
                        tell(caller, info("  Respawn radius: " + settings.respawnRadius()));
                        Component msg = info("  Max snow accumulation height: " + settings.maxSnowAccumulationHeight());
                        return ActionResult.success(msg);
                    } catch (SQLException e) {
                        log.error("/world settings failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("that did not work; the failure is in the proxy log"));
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
                        Target scope = targetWorld(caller, worldId, null);
                        if (scope instanceof Target.None none) {
                            return none.refusal();
                        }
                        PlayerWorld world = ((Target.Found) scope).world();
                        Optional<UUID> target = resolvePlayer(targetName);
                        if (target.isEmpty()) {
                            return ActionResult.failure(
                                    FailureCode.PLAYER_NOT_FOUND,
                                    error("no player called '" + targetName + "' has been seen on this network"));
                        }
                        if (target.get().equals(world.ownerUuid())) {
                            return ActionResult.failure(
                                    FailureCode.PERMISSION_DENIED,
                                    error("you cannot ban yourself from your own world"));
                        }

                        bans.ban(world.id(), target.get(), caller.getUniqueId(), reason);
                        membership.revokeInvite(world.id(), target.get());
                        membership.removeMember(world.id(), target.get());

                        enqueueToWorldOrAliveNodes(
                                world, CommandKind.INVALIDATE_CACHE, NodeCommand.EMPTY_PAYLOAD, current);
                        String ejectReason = "Banned from world" + (reason != null ? ": " + reason : "");
                        enqueueToWorldOrAliveNodes(
                                world,
                                CommandKind.KICK_MEMBER,
                                EjectPayload.format(target.get(), ejectReason),
                                current);

                        Component msg = success("banned " + targetName + " from '" + world.name() + "'");
                        return ActionResult.success(msg);
                    } catch (SQLException e) {
                        log.error("/world ban failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("that did not work; the failure is in the proxy log"));
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
                        Target scope = targetWorld(caller, worldId, "/world unban <player> <world>");
                        if (scope instanceof Target.None none) {
                            return none.refusal();
                        }
                        PlayerWorld world = ((Target.Found) scope).world();
                        Optional<UUID> target = resolvePlayer(targetName);
                        if (target.isEmpty()) {
                            return ActionResult.failure(
                                    FailureCode.PLAYER_NOT_FOUND,
                                    error("no player called '" + targetName + "' has been seen on this network"));
                        }
                        if (bans.unban(world.id(), target.get())) {
                            Component msg = success("unbanned " + targetName + " from '" + world.name() + "'");
                            return ActionResult.success(msg);
                        } else {
                            return ActionResult.failure(
                                    FailureCode.STATE_CONFLICT,
                                    error(targetName + " was not banned from '" + world.name() + "'"));
                        }
                    } catch (SQLException e) {
                        log.error("/world unban failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("that did not work; the failure is in the proxy log"));
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
                        Target scope = targetWorld(caller, worldId, "/world bans <world>");
                        if (scope instanceof Target.None none) {
                            return none.refusal();
                        }
                        PlayerWorld world = ((Target.Found) scope).world();
                        List<WorldBan> list = bans.listBans(world.id());
                        if (list.isEmpty()) {
                            Component msg = info("No players are currently banned from '" + world.name() + "'.");
                            return ActionResult.success(msg);
                        }
                        List<UUID> targets = list.stream().map(WorldBan::uuid).toList();
                        Map<UUID, String> resolved = names.namesOf(targets);

                        tell(caller, info("Bans for '" + world.name() + "':"));
                        Component last = null;
                        for (WorldBan b : list) {
                            String name =
                                    resolved.getOrDefault(b.uuid(), b.uuid().toString());
                            String r = b.reason() != null ? " (" + b.reason() + ")" : "";
                            last = info("  • " + name + r);
                        }
                        return ActionResult.success(last != null ? last : Component.empty());
                    } catch (SQLException e) {
                        log.error("/world bans failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("that did not work; the failure is in the proxy log"));
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
                        Target scope = targetWorld(caller, worldId, "/world members <world>");
                        if (scope instanceof Target.None none) {
                            return none.refusal();
                        }
                        PlayerWorld world = ((Target.Found) scope).world();
                        List<WorldMember> list = membership.listMembers(world.id());
                        Map<UUID, String> resolved = names.namesOf(
                                list.stream().map(WorldMember::uuid).toList());
                        tell(caller, info("members of '" + world.name() + "':"));
                        Component last = null;
                        for (WorldMember member : list) {
                            String display = resolved.getOrDefault(
                                    member.uuid(), member.uuid().toString());
                            last = info("  " + display + "  " + member.role());
                        }
                        return ActionResult.success(last != null ? last : Component.empty());
                    } catch (SQLException e) {
                        log.error("/world members failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("that did not work; the failure is in the proxy log"));
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
                                    "You do not own or belong to any worlds yet. Use /world create <name> to create one.");
                            return ActionResult.success(msg);
                        }

                        tell(caller, info("Your worlds:"));
                        if (owned.isEmpty()) {
                            tell(caller, info("  (none)"));
                        } else {
                            for (PlayerWorld world : owned) {
                                tell(
                                        caller,
                                        info("  • " + world.name() + " [" + world.state() + "] (visibility: "
                                                + world.visibility() + ")"));
                            }
                        }

                        tell(caller, info(""));
                        tell(caller, info("Shared worlds (member):"));
                        if (memberships.isEmpty()) {
                            tell(caller, info("  (none)"));
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
                                tell(
                                        caller,
                                        info("  • " + sw.name() + " (Owner: " + ownerDisplayName + ") - " + m.role()));
                            }
                        }
                        return ActionResult.success(Component.text("Your worlds", NamedTextColor.GRAY));
                    } catch (SQLException e) {
                        log.error("/world list failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("that did not work; the failure is in the proxy log"));
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
     *
     * <p>Requires {@code gzmn.worlds.join} (section 6), checked here for parity with
     * the command tree (D14).
     */
    public CompletableFuture<ActionResult> browse(CommandSource source) {
        Objects.requireNonNull(source, "source");
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        Optional<ActionResult> denied = requirePermission(source, WorldPermissions.JOIN);
                        if (denied.isPresent()) {
                            return denied.get();
                        }
                        List<PlayerWorld> publicWorlds = worlds.listPublicWorlds();
                        if (publicWorlds.isEmpty()) {
                            Component msg = info("There are no public worlds available right now.");
                            return ActionResult.success(msg);
                        }
                        List<UUID> owners = publicWorlds.stream()
                                .map(PlayerWorld::ownerUuid)
                                .toList();
                        Map<UUID, String> ownerNames = names.namesOf(owners);

                        tell(source, info("Public worlds:"));
                        for (PlayerWorld w : publicWorlds) {
                            String ownerName = ownerNames.getOrDefault(
                                    w.ownerUuid(), w.ownerUuid().toString());
                            String desc = w.description() != null ? " - \"" + w.description() + "\"" : "";
                            String status = (w.assignedNode() != null && w.leaseExpires() != null)
                                    ? "[LOADED on " + w.assignedNode() + "]"
                                    : "[UNLOADED]";
                            tell(source, info("  • " + w.name() + " (Owner: " + ownerName + ") " + status + desc));
                        }
                        return ActionResult.success(Component.text("Public worlds:", NamedTextColor.GRAY));
                    } catch (SQLException e) {
                        log.error("/world browse failed", e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("that did not work; the failure is in the proxy log"));
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
                                && WorldPermissions.allows(caller, WorldPermissions.ADMIN)
                                && !current.storageQuotaTiers().isEmpty()) {
                            tell(
                                    caller,
                                    info("  (no enumerable permission plugin: only the "
                                            + current.storageQuotaTiers().size()
                                            + " tiers in storage.quota-tiers are recognised)"));
                        }
                        return ActionResult.success(Component.text(
                                "Storage: " + StorageQuotaResolver.formatBytes(used), NamedTextColor.GRAY));
                    } catch (SQLException e) {
                        log.error("/world storage failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("that did not work; the failure is in the proxy log"));
                    }
                },
                executors.db());
    }

    // -----------------------------------------------------------------------
    // Internal & Helper methods
    // -----------------------------------------------------------------------

    /**
     * Shared permission refusal for every surface that reaches an action (D14).
     *
     * @return empty when allowed; a failed {@link ActionResult} when denied
     */
    private static Optional<ActionResult> requirePermission(CommandSource source, String permission) {
        if (WorldPermissions.allows(source, permission)) {
            return Optional.empty();
        }
        return Optional.of(ActionResult.failure(
                FailureCode.PERMISSION_DENIED, error("you do not have permission to do that (" + permission + ")")));
    }

    /**
     * The world an owner-scoped action applies to (section 6).
     *
     * <p>Three answers, tried in order: the world the caller named -- by id from
     * the menu, by name from chat; the world they are standing in, when they own
     * it; their only world. That last one was for a while the only one, and
     * FR-1's cap of two owned worlds is what made it unusable on its own: an
     * owner of two was refused rather than asked.
     *
     * @param usage how to name a world on this particular command, or {@code
     *     null} when the command ends in free text and so cannot take one
     */
    private Target targetWorld(Player caller, @Nullable WorldId worldId, @Nullable String usage) throws SQLException {
        if (worldId != null) {
            Optional<PlayerWorld> found = worlds.findById(worldId);
            if (found.isEmpty()) {
                return new Target.None(
                        ActionResult.failure(FailureCode.WORLD_NOT_FOUND, error("that world no longer exists")));
            }
            PlayerWorld named = found.get();
            if (!named.ownerUuid().equals(caller.getUniqueId())) {
                return new Target.None(ActionResult.failure(
                        FailureCode.PERMISSION_DENIED, error("you do not own '" + named.name() + "'")));
            }
            return new Target.Found(named);
        }
        // The world they are standing in, which is what a player means when they
        // are standing in one and say nothing. Their own only: a visitor in
        // someone else's world falls through to the world they own.
        Optional<WorldId> standingIn = presence.worldOf(caller);
        if (standingIn.isPresent()) {
            Optional<PlayerWorld> here = worlds.findById(standingIn.get());
            if (here.isPresent() && here.get().ownerUuid().equals(caller.getUniqueId())) {
                return new Target.Found(here.get());
            }
        }
        return soleOwnedWorld(caller, usage);
    }

    private Target soleOwnedWorld(Player caller, @Nullable String usage) throws SQLException {
        List<PlayerWorld> owned = worlds.listOwnedBy(caller.getUniqueId());
        if (owned.isEmpty()) {
            return new Target.None(
                    ActionResult.failure(FailureCode.WORLD_NOT_FOUND, error("you do not own a world yet")));
        }
        if (owned.size() > 1) {
            String yours =
                    String.join(", ", owned.stream().map(PlayerWorld::name).toList());
            Component refusal = usage != null
                    ? error("you own " + owned.size() + " worlds (" + yours
                            + ") and are not standing in one; say which: " + usage)
                    : error("you own " + owned.size() + " worlds (" + yours
                            + ") and are not standing in one; this command ends in free text so it cannot also "
                            + "take a world name -- run it inside the world, or use /world menu");
            return new Target.None(ActionResult.failure(FailureCode.STATE_CONFLICT, refusal));
        }
        return new Target.Found(owned.getFirst());
    }

    /**
     * The caller's world of that name, or the refusal to show for it (section 6).
     *
     * <p>Chat names a world because a player types names; the menu names one by
     * id because it is already holding one. This is the only place that turns
     * the one into the other, and it looks only among the caller's own worlds,
     * so a name cannot reach a world they do not own.
     */
    public CompletableFuture<Target> ownedWorld(Player caller, String worldName) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(worldName, "worldName");
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        Optional<PlayerWorld> found = worlds.findByOwnerAndName(caller.getUniqueId(), worldName);
                        if (found.isEmpty()) {
                            return (Target) new Target.None(ActionResult.failure(
                                    FailureCode.WORLD_NOT_FOUND, error("you own no world called '" + worldName + "'")));
                        }
                        return new Target.Found(found.get());
                    } catch (SQLException e) {
                        log.error("resolving world '{}' for {} failed", worldName, caller.getUsername(), e);
                        return new Target.None(ActionResult.failure(
                                FailureCode.GENERIC_ERROR,
                                error("that did not work; the failure is in the proxy log")));
                    }
                },
                executors.db());
    }

    /** Where each player is, for the surfaces whose caller may say nothing. */
    public WorldPresence presence() {
        return presence;
    }

    public StorageQuota quotaFor(Player caller, NetworkPolicy current) throws SQLException {
        long used = worlds.totalStorageUsedBy(caller.getUniqueId());
        return storageTiers.evaluate(caller, used, current).quota();
    }

    public static Component refuseForQuota(CommandSource caller, StorageQuota quota, String attempted) {
        Component err = error("you cannot " + attempted + ": that would use "
                + StorageQuotaResolver.formatBytes(quota.usedBytes()) + " of your "
                + StorageQuotaResolver.formatBytes(quota.limitBytes()) + " storage allowance");
        tell(caller, info("/world storage shows where it has gone; archiving a world does not free it, deleting does"));
        return err;
    }

    public static void renderStorage(CommandSource target, String who, StorageQuota quota, List<PlayerWorld> owned) {
        if (quota.unlimited()) {
            tell(
                    target,
                    info(who + " storage: " + StorageQuotaResolver.formatBytes(quota.usedBytes()) + " (unlimited)"));
        } else {
            tell(
                    target,
                    info(who + " storage: " + StorageQuotaResolver.formatBytes(quota.usedBytes()) + " / "
                            + StorageQuotaResolver.formatBytes(quota.limitBytes()) + " "
                            + progressBar(quota.percentage()) + " "
                            + String.format(Locale.ROOT, "%.0f%%", quota.percentage())));
        }
        if (owned.isEmpty()) {
            tell(target, info("  no worlds owned"));
            return;
        }
        for (PlayerWorld world : owned) {
            tell(
                    target,
                    info("  " + world.name() + " - " + StorageQuotaResolver.formatBytes(world.storageBytes()) + " - "
                            + (world.state() == WorldState.ARCHIVED ? "archived" : "live")));
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

    /**
     * Either the world an owner-scoped action applies to, or the refusal that
     * explains why there is none.
     *
     * <p>The refusal is <em>returned</em> rather than sent, for the reason in
     * {@link Routing.Refused}. Sent, it reached a chat user and nobody else, and
     * all eleven call sites then returned an {@link ActionResult} carrying the
     * words "target world not found" -- so a menu user got a placeholder while
     * the real reason went to a surface they were not looking at, and a chat
     * user was told both (NFR-5).
     */
    public sealed interface Target {

        /** Act on this one. */
        record Found(PlayerWorld world) implements Target {}

        /** There is no world to act on, and this says why in words the player can act on. */
        record None(ActionResult refusal) implements Target {}
    }

    /** Either the node a world goes to, or why there isn't one. */
    public sealed interface Routing {

        /** Send it here. */
        record To(String nodeId) implements Routing {}

        /**
         * There is nowhere to send it, and this says why in words the player can
         * act on.
         *
         * <p>The explanation is <em>returned</em> rather than sent. Sent, it
         * reached a chat user and nobody else: four call sites then built an
         * {@code ActionResult} carrying the string "cannot route to node", so a
         * GUI user got developer text while the real reason — a world saved by a
         * newer Minecraft version, every server full — went to a surface they
         * were not looking at (NFR-5).
         */
        record Refused(Component explanation) implements Routing {}
    }

    /** Turns a placement decision into a node or an explanation. */
    public Routing routeOrExplain(PlacementDecision decision, WorldId worldId) {
        return switch (decision) {
            case PlacementDecision.Held held -> new Routing.To(held.nodeId());
            case PlacementDecision.Selected selected ->
                new Routing.To(selected.node().nodeId());
            case PlacementDecision.NoNodeNewEnough tooOld -> {
                log.warn(
                        "world {} was last saved at data version {} and the newest live node is at {}; "
                                + "it is unreachable until a newer node returns (section 12.7)",
                        worldId,
                        tooOld.worldDataVersion(),
                        tooOld.newestNodeDataVersion());
                yield new Routing.Refused(
                        error("that world was saved by a newer Minecraft version than any server currently running. "
                                + "It is safe, and it will be reachable again when one is back."));
            }
            case PlacementDecision.NoCapacity full -> {
                log.warn(
                        "no capacity for world {}: all {} version-capable nodes are over a threshold (MN-15)",
                        worldId,
                        full.candidates());
                yield new Routing.Refused(error("every server is full right now; please try again in a few minutes"));
            }
            case PlacementDecision.NoNodesAlive ignored ->
                new Routing.Refused(error("no server is available right now"));
        };
    }

    /** {@link #routeOrExplain} for a world that may already be leased somewhere. */
    public Routing routeForExistingWorld(PlayerWorld world, NetworkPolicy current) throws SQLException {
        if (world.assignedNode() != null) {
            return new Routing.To(world.assignedNode());
        }
        return routeOrExplain(placement.forExistingWorld(world.id(), current), world.id());
    }

    /**
     * The node, or {@code null} after sending the caller the reason.
     *
     * <p>For the command tree, which sends its own replies. Actions use
     * {@link #routeOrExplain} and put the explanation in their result instead.
     */
    public @Nullable String routableNodeOrExplain(CommandSource caller, PlacementDecision decision, WorldId worldId) {
        Routing routing = routeOrExplain(decision, worldId);
        if (routing instanceof Routing.Refused refused) {
            tell(caller, refused.explanation());
            return null;
        }
        return ((Routing.To) routing).nodeId();
    }

    /**
     * FR-27: removes a world that never finished being created, and tells the
     * nodes to drop whatever they materialised of it.
     *
     * <p>Both in one transaction, and the command carries the world in its
     * <em>payload</em> rather than in {@code node_command.world_id}. Filling the
     * column in was the bug: it references {@code player_world(id)} with
     * {@code ON DELETE CASCADE}, so an insert before the delete was removed by the
     * cascade, and an insert after it violated the foreign key — which is what
     * happened. The {@code SQLException} reached the method's outer handler and
     * the owner was told "that did not work", after the world was gone and their
     * cap slot freed. The success message and the log line after it were
     * unreachable.
     *
     * @return false when the world stopped being CREATING while the owner
     *     confirmed, in which case nothing is deleted and nothing is enqueued
     */
    private boolean removeIncompleteWorld(PlayerWorld world, NetworkPolicy current) throws SQLException {
        return database.inTransaction(connection -> {
            if (!worlds.deleteIfCreating(connection, world.id())) {
                return false;
            }
            String payload = WorldPayload.format(world.id());
            if (world.assignedNode() != null) {
                var _ = nodeCommands.enqueue(
                        connection,
                        world.assignedNode(),
                        null,
                        null,
                        CommandKind.UNLOAD_WORLD.name(),
                        payload,
                        current.holdingTimeout(),
                        ControlChannels.forNode(world.assignedNode()));
                return true;
            }
            // Unleased, so any alive node may be holding the folders a failed
            // create left behind. Idempotent: a node that has nothing completes OK.
            for (var alive : registry.aliveNodes(current.deadAfter())) {
                var _ = nodeCommands.enqueue(
                        connection,
                        alive.nodeId(),
                        null,
                        null,
                        CommandKind.UNLOAD_WORLD.name(),
                        payload,
                        current.holdingTimeout(),
                        ControlChannels.forNode(alive.nodeId()));
            }
            return true;
        });
    }

    /**
     * Addresses one command to one node.
     *
     * @return the {@code node_command} row id, so the caller can read the outcome
     *     back (CP-5); see {@link #outcomeOrRunning}
     */
    public long enqueueTo(String nodeId, PlayerWorld world, CommandKind kind, String payloadJson, NetworkPolicy current)
            throws SQLException {
        return nodeCommands.enqueue(
                nodeId,
                world.id(),
                world.generation(),
                kind.name(),
                payloadJson,
                current.holdingTimeout(),
                ControlChannels.forNode(nodeId));
    }

    /**
     * Waits briefly for a command's outcome and turns a refusal into a message
     * the player can act on (CP-5, CP-6).
     *
     * <p>Only for commands addressed to a single node. {@link
     * #enqueueToWorldOrAliveNodes} broadcasts when a world is unleased, so "the
     * result" would be several results — that is fine for the idempotent
     * notifications it carries ({@code INVALIDATE_CACHE}, {@code KICK_MEMBER},
     * {@code APPLY_SETTINGS}), and none of them is something a player is waiting
     * on. Anything a player waits on is placed first and addressed to one node.
     *
     * @return {@code null} when the command succeeded or is still running, or the
     *     failure to report when it did not
     */
    public @Nullable ActionResult outcomeOrRunning(CommandSource caller, long commandId, String what) {
        CommandOutcomes.Outcome outcome = CommandOutcomes.await(nodeCommands, commandId);
        return switch (outcome) {
            case CommandOutcomes.Outcome.Running ignored -> null;
            case CommandOutcomes.Outcome.Completed completed -> {
                if (completed.isOk()) {
                    yield null;
                }
                log.warn("{} (command {}) was refused: {}", what, commandId, completed.result());
                yield ActionResult.failure(
                        FailureCode.STATE_CONFLICT, error(what + " did not happen: " + completed.detail()));
            }
            case CommandOutcomes.Outcome.Gone ignored -> {
                // Swept, or the world went with it. Either way nobody ran it.
                log.warn("{} (command {}) vanished before it was completed", what, commandId);
                yield ActionResult.failure(
                        FailureCode.STATE_CONFLICT, error(what + " did not happen; the instruction was lost"));
            }
        };
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

    /**
     * FR-6's notification, with the accept as a click rather than as something to
     * retype.
     *
     * <p>The command text stays visible next to the button on purpose: a click
     * event is invisible to anyone reading a screenshot or a log, and it does not
     * survive a client that has chat links disabled.
     */
    static Component inviteNotice(String ownerName, String worldName) {
        Objects.requireNonNull(ownerName, "ownerName");
        Objects.requireNonNull(worldName, "worldName");
        String command = "/world accept " + ownerName;
        return Component.text(ownerName + " invited you to their world '" + worldName + "'.", NamedTextColor.GREEN)
                .append(Component.newline())
                .append(Component.text("[Click here to accept]", NamedTextColor.GOLD, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand(command))
                        .hoverEvent(HoverEvent.showText(Component.text("Runs " + command, NamedTextColor.GRAY))))
                .append(Component.text(" or type " + command, NamedTextColor.GRAY));
    }

    /**
     * Builds an informational line. Does not send it.
     *
     * <p>These three used to both build <em>and</em> send, and the built
     * {@link Component} then went into an {@link ActionResult} that the menu
     * channel serialised and sent again — so every GUI-driven action delivered
     * its message twice. One place decides delivery now: the command tree sends
     * {@code result.message()}, the menu channel serialises it (NFR-5).
     */
    public static Component info(String message) {
        return Component.text(message, NamedTextColor.GRAY);
    }

    /** Builds a success line. Does not send it. */
    public static Component success(String message) {
        return Component.text(message, NamedTextColor.GREEN);
    }

    /** Builds an error line. Does not send it. */
    public static Component error(String message) {
        return Component.text(message, NamedTextColor.RED);
    }

    /**
     * Sends one line now, for output that is not the action's result.
     *
     * <p>The long listings — {@code /world list}, {@code bans}, {@code members},
     * {@code settings} — are several lines and one outcome, and the lines are the
     * point. Explicit, so it is visible which sends happen and which are the
     * caller's to make.
     */
    private static void tell(CommandSource source, Component line) {
        source.sendMessage(line);
    }
}
