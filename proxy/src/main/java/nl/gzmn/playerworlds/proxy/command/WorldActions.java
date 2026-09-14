package nl.gzmn.playerworlds.proxy.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
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
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.config.MessageCatalog;
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
import nl.gzmn.playerworlds.core.db.WorldUpgradeRepository;
import nl.gzmn.playerworlds.core.menu.FailureCode;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.Role;
import nl.gzmn.playerworlds.core.model.StorageQuota;
import nl.gzmn.playerworlds.core.model.TransferRequest;
import nl.gzmn.playerworlds.core.model.UpgradeKind;
import nl.gzmn.playerworlds.core.model.Visibility;
import nl.gzmn.playerworlds.core.model.WorldBan;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.model.WorldMember;
import nl.gzmn.playerworlds.core.model.WorldSettings;
import nl.gzmn.playerworlds.core.model.WorldState;
import nl.gzmn.playerworlds.core.model.WorldUpgrade;
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

    /**
     * One-time purchased upgrades (FR-44). Built here rather than passed in: it is derived
     * from the {@link Database} this class already holds, and threading it through every
     * constructor overload would say it were a separate collaborator when it is not.
     */
    private final WorldUpgradeRepository upgrades;

    private final Supplier<NetworkPolicy> policy;
    private final StorageTiers storageTiers;
    private final Messages messages;

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
                storageTiers,
                null);
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
            StorageTiers storageTiers,
            @Nullable Supplier<MessageCatalog> messageCatalog) {
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
        this.upgrades = new WorldUpgradeRepository(database);
        this.policy = Objects.requireNonNull(policy, "policy");
        this.storageTiers = Objects.requireNonNull(storageTiers, "storageTiers");
        this.messages = new Messages(messageCatalog);
    }

    /**
     * Creates a new world (FR-1, FR-1a).
     *
     * <p>Permission is checked here rather than only in Brigadier so the GUI path
     * cannot bypass {@code gzmn.worlds.create} (D14 / R5).
     */
    public CompletableFuture<ActionResult> create(Player caller, String name, @Nullable String seedText) {
        return create(caller, name, seedText, false);
    }

    /**
     * Creates a new world, optionally hardcore (FR-1, FR-1a, FR-1b).
     *
     * <p>{@code hardcore} is only ever read here. There is no command, menu
     * intent or control-plane message that changes it afterwards, which is what
     * FR-1b means by fixed at creation.
     */
    public CompletableFuture<ActionResult> create(
            Player caller, String name, @Nullable String seedText, boolean hardcore) {
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
                        int slots = slotsFor(caller, current);
                        if (owned >= slots) {
                            return ActionResult.failure(
                                    FailureCode.CAP_REACHED,
                                    error(
                                            "messages.command.create.cap-reached",
                                            Placeholders.count("owned", owned),
                                            Placeholders.count("max", slots)));
                        }
                        if (worlds.findByOwnerAndName(owner, name).isPresent()) {
                            return ActionResult.failure(
                                    FailureCode.ALREADY_EXISTS,
                                    error("messages.command.create.already-exists", Placeholders.text("world", name)));
                        }
                        StorageQuota quota = quotaFor(caller, current);
                        if (quota.isExceeded()) {
                            return ActionResult.failure(
                                    FailureCode.QUOTA_EXCEEDED,
                                    refuseForQuota(caller, quota, "messages.command.generic.quota-attempted-create"));
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
                                    FailureCode.SERVER_UNROUTABLE, error("messages.command.generic.unroutable"));
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
                                hardcore,
                                nodeId,
                                current.leaseDuration());

                        transfers.route(owner, world.id(), nodeId, world.generation());
                        Component msg = info(
                                hardcore
                                        ? "messages.command.create.started-hardcore"
                                        : "messages.command.create.started",
                                Placeholders.text("world", name),
                                Placeholders.raw("node", nodeId));
                        caller.createConnectionRequest(targetServer.get()).fireAndForget();
                        return ActionResult.success(msg);
                    } catch (SQLException e) {
                        log.error("/world create failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("messages.command.generic-failure"));
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
                                    FailureCode.WORLD_NOT_FOUND,
                                    error(
                                            "messages.command.generic.owns-no-world-named",
                                            Placeholders.text("world", name)));
                        }
                        PlayerWorld world = found.get();
                        if (world.state() == WorldState.ARCHIVED) {
                            return ActionResult.failure(
                                    FailureCode.STATE_CONFLICT,
                                    info("messages.command.delete.already-archived", Placeholders.text("world", name)));
                        }
                        if (world.state() != WorldState.READY && world.state() != WorldState.CREATING) {
                            return ActionResult.failure(
                                    FailureCode.STATE_CONFLICT,
                                    error(
                                            "messages.command.delete.wrong-state",
                                            Placeholders.text("world", name),
                                            Placeholders.raw(
                                                    "state", world.state().name())));
                        }
                        if (!confirmed) {
                            if (world.state() == WorldState.CREATING) {
                                tell(
                                        caller,
                                        error(
                                                "messages.command.delete.confirm-creating",
                                                Placeholders.text("world", name)));
                            } else {
                                tell(
                                        caller,
                                        error(
                                                "messages.command.delete.confirm-archive",
                                                Placeholders.text("world", name)));
                            }
                            Component infoMsg =
                                    info("messages.command.delete.confirm-hint", Placeholders.text("world", name));
                            return ActionResult.failure(FailureCode.STATE_CONFLICT, infoMsg);
                        }

                        if (world.state() == WorldState.CREATING) {
                            if (!removeIncompleteWorld(world, current)) {
                                return ActionResult.failure(
                                        FailureCode.STATE_CONFLICT,
                                        error(
                                                "messages.command.delete.changed-while-confirming",
                                                Placeholders.text("world", name)));
                            }
                            Component msg = success(
                                    "messages.command.delete.incomplete-removed", Placeholders.text("world", name));
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
                        ActionResult refused = outcomeOrRunning(
                                caller,
                                commandId,
                                info("messages.command.delete.archiving-what", Placeholders.text("world", name)));
                        if (refused != null) {
                            return refused;
                        }
                        Component msg = info(
                                "messages.command.delete.archiving",
                                Placeholders.text("world", name),
                                Placeholders.raw("node", node));
                        tell(caller, info("messages.command.delete.archiving-hint", Placeholders.text("world", name)));
                        log.info("world {} queued for archival on {} by its owner (FR-27, FR-35)", world.id(), node);
                        return ActionResult.success(msg);
                    } catch (SQLException e) {
                        log.error("/world delete failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("messages.command.generic-failure"));
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
                                    FailureCode.WORLD_NOT_FOUND,
                                    error(
                                            "messages.command.generic.owns-no-world-named",
                                            Placeholders.text("world", name)));
                        }
                        return executeDeleteHard(caller, worldOpt.get(), confirmed);
                    } catch (SQLException e) {
                        log.error("deleteHard failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("messages.command.generic-failure"));
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
                            return ActionResult.failure(
                                    FailureCode.WORLD_NOT_FOUND, error("messages.command.delete-hard.not-found"));
                        }
                        PlayerWorld world = worldOpt.get();
                        if (!world.ownerUuid().equals(caller.getUniqueId())) {
                            return ActionResult.failure(
                                    FailureCode.PERMISSION_DENIED, error("messages.command.delete-hard.not-owner"));
                        }
                        // ConfirmMenu on the backend is FR-37's confirmation substitute.
                        return executeDeleteHard(caller, world, true);
                    } catch (SQLException e) {
                        log.error("deleteHard by id failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("messages.command.generic-failure"));
                    }
                },
                executors.db());
    }

    private ActionResult executeDeleteHard(Player caller, PlayerWorld world, boolean confirmed) throws SQLException {
        if (!WorldPermissions.allows(caller, WorldPermissions.HARD_DELETE)) {
            return ActionResult.failure(
                    FailureCode.PERMISSION_DENIED, error("messages.command.delete-hard.no-permission"));
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
                    error(
                            "messages.command.delete-hard.wrong-state",
                            Placeholders.text("world", world.name()),
                            Placeholders.raw("state", world.state().name())));
        }
        if (!confirmed) {
            if (neverArchived) {
                // Deliberately not the ARCHIVED wording. There is no archive behind this one, so
                // "and all backup archives" would imply a copy survives when none does.
                tell(
                        caller,
                        error(
                                "messages.command.delete-hard.confirm-never-archived",
                                Placeholders.text("world", world.name())));
            } else {
                tell(
                        caller,
                        info(
                                "messages.command.delete-hard.confirm-archived",
                                Placeholders.text("world", world.name())));
            }
            return ActionResult.failure(
                    FailureCode.STATE_CONFLICT,
                    info("messages.command.delete-hard.confirm-hint", Placeholders.text("world", world.name())));
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
        ActionResult refused = outcomeOrRunning(
                caller,
                commandId,
                info("messages.command.delete-hard.deleting-what", Placeholders.text("world", world.name())));
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
                "messages.command.delete-hard.queued",
                Placeholders.text("world", world.name()),
                Placeholders.raw("suffix", neverArchived ? "on" : "and its archives on"),
                Placeholders.raw("node", node)));
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
                                    FailureCode.WORLD_NOT_FOUND,
                                    error(
                                            "messages.command.generic.owns-no-world-named",
                                            Placeholders.text("world", name)));
                        }
                        PlayerWorld world = found.get();
                        if (world.state() != WorldState.ARCHIVED) {
                            return ActionResult.failure(
                                    FailureCode.STATE_CONFLICT,
                                    error(
                                            "messages.command.restore.not-archived",
                                            Placeholders.text("world", name),
                                            Placeholders.raw(
                                                    "state", world.state().name())));
                        }
                        int owned = worlds.countOwnedBy(caller.getUniqueId());
                        int slots = slotsFor(caller, current);
                        if (owned >= slots) {
                            return ActionResult.failure(
                                    FailureCode.CAP_REACHED,
                                    error(
                                            "messages.command.restore.cap-reached",
                                            Placeholders.count("owned", owned),
                                            Placeholders.count("max", slots)));
                        }
                        StorageQuota quota = quotaFor(caller, current);
                        if (quota.isExceeded()) {
                            return ActionResult.failure(
                                    FailureCode.QUOTA_EXCEEDED,
                                    refuseForQuota(
                                            caller,
                                            quota,
                                            "messages.command.generic.quota-attempted-restore",
                                            Placeholders.text("world", name)));
                        }
                        Routing routing = routeForExistingWorld(world, current);
                        if (routing instanceof Routing.Refused refused) {
                            return ActionResult.failure(FailureCode.SERVER_UNROUTABLE, refused.explanation());
                        }
                        String node = ((Routing.To) routing).nodeId();
                        long commandId =
                                enqueueTo(node, world, CommandKind.RESTORE_WORLD, ArchivePayload.format(null), current);
                        ActionResult refused = outcomeOrRunning(
                                caller,
                                commandId,
                                info("messages.command.restore.restoring-what", Placeholders.text("world", name)));
                        if (refused != null) {
                            return refused;
                        }
                        Component msg = info(
                                "messages.command.restore.started",
                                Placeholders.text("world", name),
                                Placeholders.raw("node", node));
                        log.info("world {} queued for restore on {} by its owner (FR-36)", world.id(), node);
                        return ActionResult.success(msg);
                    } catch (SQLException e) {
                        log.error("/world restore failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("messages.command.generic-failure"));
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
                                    FailureCode.WORLD_NOT_FOUND, error("messages.command.join.not-found"));
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
                                    FailureCode.WORLD_NOT_FOUND, error("messages.command.join.not-found"));
                        }
                        return doJoin(caller, target.get(), current);
                    } catch (SQLException e) {
                        log.error("/world join failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("messages.command.generic-failure"));
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
                                    FailureCode.WORLD_NOT_FOUND, error("messages.command.join.not-found"));
                        }
                        return doJoin(caller, target.get(), current);
                    } catch (SQLException e) {
                        log.error("/world join failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("messages.command.generic-failure"));
                    }
                },
                executors.db());
    }

    private ActionResult doJoin(Player caller, PlayerWorld world, NetworkPolicy current) throws SQLException {
        // Joining the world you are standing in must not run the sequence below.
        // It would write a pending_transfer the node's poll answers by teleporting
        // you to spawn and leaving you there: FR-14 and FR-15b's restore rides
        // PlayerChangedWorldEvent, and FR-11 notes that event does not fire for
        // somebody who is already there. Presence answers before anything is
        // written; TransferJoinListener holds the authoritative check for when
        // this entry is stale. worldOf already insists the caller is still
        // connected to the node that reported it, so an entry outliving a dead
        // node cannot refuse a join asked from the lobby.
        Optional<WorldId> standingIn = presence.worldOf(caller);
        if (standingIn.isPresent() && standingIn.get().equals(world.id())) {
            return ActionResult.success(
                    info("messages.command.join.already-here", Placeholders.text("world", world.name())));
        }
        // FR-5b: a hardcore death is permanent, and this is the check that makes
        // it so. It sits beside the ban check because entry is the only place it
        // can be enforced from -- the world is unloaded most of the time (FR-25),
        // so there is no node to ask, and the answer must be the same whether or
        // not one happens to hold it.
        if (world.hardcore()
                && membership
                        .findMember(world.id(), caller.getUniqueId())
                        .map(WorldMember::isDead)
                        .orElse(false)) {
            return ActionResult.failure(
                    FailureCode.PERMISSION_DENIED,
                    error("messages.command.join.hardcore-dead", Placeholders.text("world", world.name())));
        }

        if (bans.isBanned(world.id(), caller.getUniqueId())) {
            Optional<WorldBan> ban = bans.findBan(world.id(), caller.getUniqueId());
            String reason = ban.flatMap(b -> Optional.ofNullable(b.reason()))
                    .map(r -> ": " + r)
                    .orElse("");
            return ActionResult.failure(
                    FailureCode.BANNED,
                    error(
                            "messages.command.join.banned",
                            Placeholders.text("world", world.name()),
                            Placeholders.text("reason", reason)));
        }

        if (membership.findMember(world.id(), caller.getUniqueId()).isEmpty()) {
            if (world.visibility() == Visibility.PUBLIC) {
                membership.addVisitorIfAbsent(world.id(), caller.getUniqueId());
            } else {
                return ActionResult.failure(FailureCode.PERMISSION_DENIED, error("messages.command.join.not-found"));
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
                return ActionResult.failure(FailureCode.STATE_CONFLICT, error("messages.command.join.lease-conflict"));
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
                        FailureCode.SERVER_UNROUTABLE, error("messages.command.generic.unroutable"));
            }
            Component msg = info("messages.command.join.started", Placeholders.text("world", world.name()));
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
                                    error(
                                            "messages.command.generic.player-not-found",
                                            Placeholders.text("player", targetName)));
                        }
                        if (target.get().equals(caller.getUniqueId())) {
                            return ActionResult.failure(
                                    FailureCode.INVALID_NAME, error("messages.command.invite.already-owner"));
                        }
                        if (membership.findMember(world.id(), target.get()).isPresent()) {
                            return ActionResult.failure(
                                    FailureCode.ALREADY_EXISTS,
                                    error(
                                            "messages.command.invite.already-member",
                                            Placeholders.text("target", targetName),
                                            Placeholders.text("world", world.name())));
                        }

                        membership.invite(world.id(), target.get(), caller.getUniqueId(), current.inviteExpiry());
                        Component msg = success(
                                "messages.command.invite.sent",
                                Placeholders.text("target", targetName),
                                Placeholders.text("world", world.name()),
                                Placeholders.count(
                                        "minutes", current.inviteExpiry().toMinutes()));

                        proxy.getPlayer(target.get())
                                .ifPresent(
                                        online -> online.sendMessage(inviteNotice(caller.getUsername(), world.name())));
                        return ActionResult.success(msg);
                    } catch (SQLException e) {
                        log.error("/world invite failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("messages.command.generic-failure"));
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
                                    error(
                                            "messages.command.generic.player-not-found",
                                            Placeholders.text("player", ownerName)));
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
                                            "messages.command.accept.success",
                                            Placeholders.raw(
                                                    "role",
                                                    accepted.member().role().name()),
                                            Placeholders.text("world", world.name()));
                                    tell(
                                            caller,
                                            info(
                                                    "messages.command.accept.go-hint",
                                                    Placeholders.text("world", world.name())));
                                    return ActionResult.success(msg);
                                }
                                case MembershipRepository.AcceptOutcome.AlreadyMember already -> {
                                    Component msg = info(
                                            "messages.command.accept.already-member",
                                            Placeholders.raw(
                                                    "role", already.role().name()),
                                            Placeholders.text("world", world.name()));
                                    return ActionResult.success(msg);
                                }
                                case MembershipRepository.AcceptOutcome.NoLiveInvite ignored -> {
                                    // Try the owner's next world.
                                }
                            }
                        }
                        return ActionResult.failure(
                                FailureCode.STATE_CONFLICT,
                                error("messages.command.accept.no-invite", Placeholders.text("owner", ownerName)));
                    } catch (SQLException e) {
                        log.error("/world accept failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("messages.command.generic-failure"));
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
                                    error(
                                            "messages.command.generic.player-not-found",
                                            Placeholders.text("player", targetName)));
                        }
                        if (target.get().equals(world.ownerUuid())) {
                            return ActionResult.failure(
                                    FailureCode.PERMISSION_DENIED, error("messages.command.kick.self"));
                        }
                        membership.revokeInvite(world.id(), target.get());
                        if (!membership.removeMember(world.id(), target.get())) {
                            return ActionResult.failure(
                                    FailureCode.STATE_CONFLICT,
                                    error(
                                            "messages.command.generic.not-member",
                                            Placeholders.text("target", targetName),
                                            Placeholders.text("world", world.name())));
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
                        Component msg = success(
                                "messages.command.kick.success",
                                Placeholders.text("target", targetName),
                                Placeholders.text("world", world.name()));
                        return ActionResult.success(msg);
                    } catch (SQLException e) {
                        log.error("/world kick failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("messages.command.generic-failure"));
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
                                    error(
                                            "messages.command.generic.player-not-found",
                                            Placeholders.text("player", targetName)));
                        }
                        if (membership.setRole(world.id(), target.get(), Role.BUILDER)) {
                            enqueueToWorldOrAliveNodes(
                                    world, CommandKind.INVALIDATE_CACHE, NodeCommand.EMPTY_PAYLOAD, current);
                            Component msg = success(
                                    "messages.command.promote.success",
                                    Placeholders.text("target", targetName),
                                    Placeholders.text("world", world.name()));
                            return ActionResult.success(msg);
                        } else {
                            return ActionResult.failure(
                                    FailureCode.STATE_CONFLICT,
                                    error(
                                            "messages.command.promote.not-member",
                                            Placeholders.text("target", targetName),
                                            Placeholders.text("world", world.name())));
                        }
                    } catch (SQLException e) {
                        log.error("/world promote failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("messages.command.generic-failure"));
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
                                    error(
                                            "messages.command.generic.player-not-found",
                                            Placeholders.text("player", targetName)));
                        }
                        if (target.get().equals(caller.getUniqueId())) {
                            return ActionResult.failure(
                                    FailureCode.INVALID_NAME, error("messages.command.transfer.self"));
                        }
                        if (membership.findMember(world.id(), target.get()).isEmpty()) {
                            return ActionResult.failure(
                                    FailureCode.STATE_CONFLICT,
                                    error(
                                            "messages.command.generic.not-member",
                                            Placeholders.text("target", targetName),
                                            Placeholders.text("world", world.name())));
                        }
                        int ownedCount = worlds.countOwnedBy(target.get());
                        int targetSlots = slotsFor(target.get(), current);
                        if (ownedCount >= targetSlots) {
                            return ActionResult.failure(
                                    FailureCode.CAP_REACHED,
                                    error(
                                            "messages.command.transfer.cap-reached",
                                            Placeholders.text("target", targetName),
                                            Placeholders.count("max", targetSlots)));
                        }

                        if (!confirmed) {
                            Component msg = info(
                                    "messages.command.transfer.confirm",
                                    Placeholders.text("world", world.name()),
                                    Placeholders.text("target", targetName));
                            return ActionResult.failure(FailureCode.STATE_CONFLICT, msg);
                        }

                        Optional<Player> online = proxy.getPlayer(target.get());
                        if (online.isPresent()) {
                            // Online -> immediate transfer (FR-31)
                            if (!worlds.transferOwnership(world.id(), caller.getUniqueId(), target.get(), "MANUAL")) {
                                return ActionResult.failure(
                                        FailureCode.GENERIC_ERROR,
                                        error(
                                                "messages.command.transfer.failed",
                                                Placeholders.text("world", world.name())));
                            }
                            enqueueToWorldOrAliveNodes(
                                    world, CommandKind.INVALIDATE_CACHE, NodeCommand.EMPTY_PAYLOAD, current);
                            Component msg = success(
                                    "messages.command.transfer.success-online",
                                    Placeholders.text("world", world.name()),
                                    Placeholders.text("target", targetName));
                            online.get()
                                    .sendMessage(success(
                                            "messages.command.transfer.new-owner-notice",
                                            Placeholders.text("world", world.name())));
                            return ActionResult.success(msg);
                        } else {
                            // Offline -> create pending transfer request (FR-32)
                            transferRequests.requestTransfer(
                                    world.id(), target.get(), caller.getUniqueId(), current.transferPendingExpiry());
                            Component msg = success(
                                    "messages.command.transfer.request-created",
                                    Placeholders.text("target", targetName));
                            return ActionResult.success(msg);
                        }
                    } catch (SQLException e) {
                        log.error("/world transfer failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("messages.command.generic-failure"));
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
                                    error(
                                            "messages.command.generic.player-not-found",
                                            Placeholders.text("player", ownerName)));
                        }
                        List<TransferRequest> pending = transferRequests.findLiveRequestsFor(caller.getUniqueId());
                        Optional<TransferRequest> matching = pending.stream()
                                .filter(r -> r.fromUuid().equals(owner.get()))
                                .findFirst();
                        if (matching.isEmpty()) {
                            return ActionResult.failure(
                                    FailureCode.STATE_CONFLICT,
                                    error(
                                            "messages.command.generic.no-pending-transfer",
                                            Placeholders.text("owner", ownerName)));
                        }
                        int ownedCount = worlds.countOwnedBy(caller.getUniqueId());
                        int slots = slotsFor(caller, current);
                        if (ownedCount >= slots) {
                            return ActionResult.failure(
                                    FailureCode.CAP_REACHED,
                                    error(
                                            "messages.command.transfer-accept.cap-reached",
                                            Placeholders.count("max", slots)));
                        }
                        Optional<PlayerWorld> worldOpt =
                                worlds.findById(matching.get().worldId());
                        if (worldOpt.isEmpty()) {
                            return ActionResult.failure(
                                    FailureCode.WORLD_NOT_FOUND, error("messages.command.generic.world-gone"));
                        }
                        PlayerWorld world = worldOpt.get();
                        StorageQuota quota = quotaFor(caller, current);
                        if (!quota.unlimited()
                                && quota.usedBytes() + world.storageBytes() > quota.effectiveLimitBytes()) {
                            return ActionResult.failure(
                                    FailureCode.QUOTA_EXCEEDED,
                                    refuseForQuota(
                                            caller,
                                            new StorageQuota(
                                                    quota.playerUuid(),
                                                    quota.usedBytes() + world.storageBytes(),
                                                    quota.limitBytes(),
                                                    quota.bonusBytes(),
                                                    false),
                                            "messages.command.generic.quota-attempted-accept",
                                            Placeholders.text("world", world.name())));
                        }
                        if (!world.ownerUuid().equals(owner.get())) {
                            transferRequests.deleteRequest(world.id(), caller.getUniqueId());
                            return ActionResult.failure(
                                    FailureCode.STATE_CONFLICT,
                                    error(
                                            "messages.command.transfer-accept.owner-changed",
                                            Placeholders.text("owner", ownerName),
                                            Placeholders.text("world", world.name())));
                        }
                        if (!worlds.transferOwnership(world.id(), owner.get(), caller.getUniqueId(), "MANUAL")) {
                            return ActionResult.failure(
                                    FailureCode.GENERIC_ERROR,
                                    error(
                                            "messages.command.transfer-accept.failed",
                                            Placeholders.text("world", world.name())));
                        }
                        enqueueToWorldOrAliveNodes(
                                world, CommandKind.INVALIDATE_CACHE, NodeCommand.EMPTY_PAYLOAD, current);
                        Component msg = success(
                                "messages.command.transfer-accept.success", Placeholders.text("world", world.name()));
                        proxy.getPlayer(owner.get())
                                .ifPresent(online -> online.sendMessage(success(
                                        "messages.command.transfer-accept.old-owner-notice",
                                        Placeholders.text("accepter", caller.getUsername()),
                                        Placeholders.text("world", world.name()))));
                        return ActionResult.success(msg);
                    } catch (SQLException e) {
                        log.error("/world transfer accept failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("messages.command.generic-failure"));
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
                                    error(
                                            "messages.command.generic.player-not-found",
                                            Placeholders.text("player", ownerName)));
                        }
                        List<TransferRequest> pending = transferRequests.findLiveRequestsFor(caller.getUniqueId());
                        Optional<TransferRequest> matching = pending.stream()
                                .filter(r -> r.fromUuid().equals(owner.get()))
                                .findFirst();
                        if (matching.isEmpty()) {
                            return ActionResult.failure(
                                    FailureCode.STATE_CONFLICT,
                                    error(
                                            "messages.command.generic.no-pending-transfer",
                                            Placeholders.text("owner", ownerName)));
                        }
                        transferRequests.deleteRequest(matching.get().worldId(), caller.getUniqueId());
                        Component msg = success(
                                "messages.command.transfer-decline.success", Placeholders.text("owner", ownerName));
                        proxy.getPlayer(owner.get())
                                .ifPresent(online -> online.sendMessage(info(
                                        "messages.command.transfer-decline.notice",
                                        Placeholders.text("decliner", caller.getUsername()))));
                        return ActionResult.success(msg);
                    } catch (SQLException e) {
                        log.error("/world transfer decline failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("messages.command.generic-failure"));
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
                                    FailureCode.GENERIC_ERROR, error("messages.command.set-public.failed"));
                        }
                        enqueueToWorldOrAliveNodes(
                                world, CommandKind.INVALIDATE_CACHE, NodeCommand.EMPTY_PAYLOAD, current);

                        if (isPublic) {
                            Component msg = desc != null
                                    ? success(
                                            "messages.command.set-public.now-public-with-description",
                                            Placeholders.text("world", world.name()),
                                            Placeholders.text("description", desc))
                                    : success(
                                            "messages.command.set-public.now-public",
                                            Placeholders.text("world", world.name()));
                            return ActionResult.success(msg);
                        } else {
                            Component msg = success(
                                    "messages.command.set-public.now-private",
                                    Placeholders.text("world", world.name()));
                            return ActionResult.success(msg);
                        }
                    } catch (SQLException e) {
                        log.error("/world public failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("messages.command.generic-failure"));
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
                                        error(
                                                "messages.command.set-setting.unknown",
                                                Placeholders.text("setting", settingName)));
                            }
                        }

                        if (!worlds.updateSettings(world.id(), updated.toJson())) {
                            return ActionResult.failure(
                                    FailureCode.GENERIC_ERROR, error("messages.command.set-setting.failed"));
                        }
                        // R9 / FR-9e / FR-9i: APPLY_SETTINGS refreshes the settings cache
                        // and re-asserts every gamerule on loaded dimensions. INVALIDATE_CACHE
                        // alone left those gamerules stuck at load time.
                        enqueueToWorldOrAliveNodes(
                                world, CommandKind.APPLY_SETTINGS, NodeCommand.EMPTY_PAYLOAD, current);
                        Component msg = success(
                                "messages.command.set-setting.success",
                                Placeholders.raw("setting", normKey),
                                Placeholders.raw("value", displayValue),
                                Placeholders.text("world", world.name()));
                        return ActionResult.success(msg);
                    } catch (SQLException e) {
                        log.error("/world set failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("messages.command.generic-failure"));
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

    private ActionResult invalidIntResult(String settingName, int min, int max) {
        String range = max == Integer.MAX_VALUE ? (min + " or greater") : (min + "-" + max);
        return ActionResult.failure(
                FailureCode.INVALID_NAME,
                error(
                        "messages.command.set-setting.invalid-int",
                        Placeholders.text("setting", settingName),
                        Placeholders.raw("range", range)));
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
                        tell(
                                caller,
                                info("messages.command.settings.header", Placeholders.text("world", world.name())));
                        tell(caller, settingsRow("PVP", settings.pvp()));
                        tell(caller, settingsRow("Visitors may open containers", settings.visitorsMayOpenContainers()));
                        tell(
                                caller,
                                settingsRow(
                                        "Visitors may interact (doors/buttons/redstone)",
                                        settings.visitorsMayInteract()));
                        tell(caller, settingsRow("Mob griefing", settings.mobGriefing()));
                        tell(caller, settingsRow("Keep inventory", settings.keepInventory()));
                        tell(caller, settingsRow("Fall damage", settings.fallDamage()));
                        tell(caller, settingsRow("Fire damage", settings.fireDamage()));
                        tell(caller, settingsRow("Freeze damage", settings.freezeDamage()));
                        tell(caller, settingsRow("Drowning damage", settings.drowningDamage()));
                        tell(caller, settingsRow("Daylight cycle", settings.advanceTime()));
                        tell(caller, settingsRow("Weather cycle", settings.advanceWeather()));
                        tell(caller, settingsRow("Insomnia (phantoms)", settings.spawnPhantoms()));
                        tell(caller, settingsRow("Immediate respawn", settings.immediateRespawn()));
                        tell(caller, settingsRow("Natural regeneration", settings.naturalHealthRegeneration()));
                        tell(
                                caller,
                                info(
                                        "messages.command.settings.row",
                                        Placeholders.raw("label", "Players sleeping percentage"),
                                        Placeholders.raw("value", settings.playersSleepingPercentage() + "%")));
                        tell(
                                caller,
                                info(
                                        "messages.command.settings.row",
                                        Placeholders.raw("label", "Max entity cramming"),
                                        Placeholders.count("value", settings.maxEntityCramming())));
                        tell(
                                caller,
                                info(
                                        "messages.command.settings.row",
                                        Placeholders.raw("label", "Respawn radius"),
                                        Placeholders.count("value", settings.respawnRadius())));
                        tell(
                                caller,
                                info(
                                        "messages.command.settings.row",
                                        Placeholders.raw("label", "Max snow accumulation height"),
                                        Placeholders.count("value", settings.maxSnowAccumulationHeight())));
                        Component msg = info("messages.command.settings.footer");
                        return ActionResult.success(msg);
                    } catch (SQLException e) {
                        log.error("/world settings failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("messages.command.generic-failure"));
                    }
                },
                executors.db());
    }

    /** One boxed row of {@code /world settings}'s on/off gamerules. */
    private Component settingsRow(String label, boolean enabled) {
        return info(
                "messages.command.settings.row",
                Placeholders.raw("label", label),
                Placeholders.raw("value", enabled ? "on" : "off"));
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
                                    error(
                                            "messages.command.generic.player-not-found",
                                            Placeholders.text("player", targetName)));
                        }
                        if (target.get().equals(world.ownerUuid())) {
                            return ActionResult.failure(
                                    FailureCode.PERMISSION_DENIED, error("messages.command.ban.self"));
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

                        Component msg = success(
                                "messages.command.ban.success",
                                Placeholders.text("target", targetName),
                                Placeholders.text("world", world.name()));
                        return ActionResult.success(msg);
                    } catch (SQLException e) {
                        log.error("/world ban failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("messages.command.generic-failure"));
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
                                    error(
                                            "messages.command.generic.player-not-found",
                                            Placeholders.text("player", targetName)));
                        }
                        if (bans.unban(world.id(), target.get())) {
                            Component msg = success(
                                    "messages.command.unban.success",
                                    Placeholders.text("target", targetName),
                                    Placeholders.text("world", world.name()));
                            return ActionResult.success(msg);
                        } else {
                            return ActionResult.failure(
                                    FailureCode.STATE_CONFLICT,
                                    error(
                                            "messages.command.unban.not-banned",
                                            Placeholders.text("target", targetName),
                                            Placeholders.text("world", world.name())));
                        }
                    } catch (SQLException e) {
                        log.error("/world unban failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("messages.command.generic-failure"));
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
                            Component msg =
                                    info("messages.command.bans.empty", Placeholders.text("world", world.name()));
                            return ActionResult.success(msg);
                        }
                        List<UUID> targets = list.stream().map(WorldBan::uuid).toList();
                        Map<UUID, String> resolved = names.namesOf(targets);

                        tell(caller, info("messages.command.bans.header", Placeholders.text("world", world.name())));
                        for (WorldBan b : list) {
                            String name =
                                    resolved.getOrDefault(b.uuid(), b.uuid().toString());
                            String r = b.reason() != null ? " (" + b.reason() + ")" : "";
                            tell(
                                    caller,
                                    info(
                                            "messages.command.bans.entry",
                                            Placeholders.text("player", name),
                                            Placeholders.text("reason", r)));
                        }
                        Component msg = info("messages.command.bans.footer");
                        return ActionResult.success(msg);
                    } catch (SQLException e) {
                        log.error("/world bans failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("messages.command.generic-failure"));
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
                        tell(caller, info("messages.command.members.header", Placeholders.text("world", world.name())));
                        for (WorldMember member : list) {
                            String display = resolved.getOrDefault(
                                    member.uuid(), member.uuid().toString());
                            tell(
                                    caller,
                                    info(
                                            "messages.command.members.entry",
                                            Placeholders.text("player", display),
                                            Placeholders.raw(
                                                    "role", member.role().name())));
                        }
                        Component msg = info("messages.command.members.footer");
                        return ActionResult.success(msg);
                    } catch (SQLException e) {
                        log.error("/world members failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("messages.command.generic-failure"));
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
                            Component msg = info("messages.command.list.no-worlds");
                            return ActionResult.success(msg);
                        }

                        tell(caller, info("messages.command.list.header"));
                        if (owned.isEmpty()) {
                            tell(caller, info("messages.command.list.owned-empty"));
                        } else {
                            for (PlayerWorld world : owned) {
                                tell(
                                        caller,
                                        info(
                                                "messages.command.list.owned-entry",
                                                Placeholders.text("world", world.name()),
                                                Placeholders.raw(
                                                        "state", world.state().name()),
                                                Placeholders.raw(
                                                        "visibility",
                                                        world.visibility().name())));
                            }
                        }

                        tell(caller, info("messages.command.list.section-shared"));
                        if (memberships.isEmpty()) {
                            tell(caller, info("messages.command.list.shared-empty"));
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
                                        info(
                                                "messages.command.list.shared-entry",
                                                Placeholders.text("world", sw.name()),
                                                Placeholders.text("owner", ownerDisplayName),
                                                Placeholders.raw(
                                                        "role", m.role().name())));
                            }
                        }
                        tell(caller, info("messages.command.list.footer"));
                        return ActionResult.success(info("messages.command.list.summary"));
                    } catch (SQLException e) {
                        log.error("/world list failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("messages.command.generic-failure"));
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
                            Component msg = info("messages.command.browse.empty");
                            return ActionResult.success(msg);
                        }
                        // FR-9b / worlds.public.browse-page-size: bounded so a network with
                        // hundreds of public worlds does not flood the caller's chat with one
                        // command. Spec section 8.2 names this key as enforced here.
                        // ConfigValidator does not check this key's sign (it never consulted it
                        // before now), so a misconfigured zero or negative value degrades to
                        // "show nothing, then say how many are hidden" instead of throwing.
                        int pageSize = Math.max(policy.get().browsePageSize(), 0);
                        int shown = Math.min(publicWorlds.size(), pageSize);
                        List<PlayerWorld> page = publicWorlds.subList(0, shown);
                        List<UUID> owners =
                                page.stream().map(PlayerWorld::ownerUuid).toList();
                        Map<UUID, String> ownerNames = names.namesOf(owners);

                        tell(source, info("messages.command.browse.header"));
                        for (PlayerWorld w : page) {
                            String ownerName = ownerNames.getOrDefault(
                                    w.ownerUuid(), w.ownerUuid().toString());
                            String desc = w.description() != null ? " - \"" + w.description() + "\"" : "";
                            String status = (w.assignedNode() != null && w.leaseExpires() != null)
                                    ? "[LOADED on " + w.assignedNode() + "]"
                                    : "[UNLOADED]";
                            tell(
                                    source,
                                    info(
                                            "messages.command.browse.entry",
                                            Placeholders.text("world", w.name()),
                                            Placeholders.text("owner", ownerName),
                                            Placeholders.raw("status", status),
                                            Placeholders.text("description", desc)));
                        }
                        if (publicWorlds.size() > shown) {
                            tell(
                                    source,
                                    info(
                                            "messages.command.browse.more",
                                            Placeholders.count("count", publicWorlds.size() - shown)));
                        }
                        tell(source, info("messages.command.browse.footer"));
                        return ActionResult.success(info("messages.command.browse.summary"));
                    } catch (SQLException e) {
                        log.error("/world browse failed", e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("messages.command.generic-failure"));
                    }
                },
                executors.db());
    }

    /**
     * {@code /world border <radius> [world]} — raises a world's border (FR-3c).
     *
     * <p>Three ceilings apply and they are not the same thing. The owner's subscription tier
     * says how far <em>they</em> may go; a redeemed {@code BORDER} upgrade adds to that for
     * <em>this world only</em>; and {@code worlds.max-border-radius} caps everyone, because
     * NFR-3 bounds disk usage by the border and nothing else does.
     *
     * @param caller the owner
     * @param radius the radius to raise to, in blocks
     * @param worldId the world, or null to resolve it as §6.1 says
     * @return the outcome
     */
    public CompletableFuture<ActionResult> border(Player caller, int radius, @Nullable WorldId worldId) {
        Objects.requireNonNull(caller, "caller");
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        NetworkPolicy current = policy.get();
                        Target scope = targetWorld(caller, worldId, "/world border <radius> <world>");
                        if (scope instanceof Target.None none) {
                            return none.refusal();
                        }
                        PlayerWorld world = ((Target.Found) scope).world();

                        if (radius < 1) {
                            return ActionResult.failure(
                                    FailureCode.INVALID_NAME,
                                    error("messages.command.border.invalid", Placeholders.count("radius", radius)));
                        }
                        // FR-3c forbids lowering, and says why: the ring a smaller border
                        // removes may have anything built in it, on the wrong side of a
                        // barrier the player cannot cross.
                        if (radius <= world.borderRadius()) {
                            return ActionResult.failure(
                                    FailureCode.STATE_CONFLICT,
                                    error(
                                            "messages.command.border.not-larger",
                                            Placeholders.text("world", world.name()),
                                            Placeholders.count("current", world.borderRadius()),
                                            Placeholders.count("radius", radius)));
                        }

                        long bought = upgrades.borderBonusBlocks(world.id());
                        int subscription = storageTiers.borderAllowance(caller, current);
                        long allowedLong = subscription + bought;
                        int allowed = (int) Math.min(allowedLong, current.maxBorderRadius());
                        if (radius > allowed) {
                            return ActionResult.failure(
                                    FailureCode.PERMISSION_DENIED,
                                    error(
                                            "messages.command.border.beyond-allowance",
                                            Placeholders.count("radius", radius),
                                            Placeholders.count("allowed", allowed),
                                            Placeholders.count("ceiling", current.maxBorderRadius())));
                        }

                        if (!worlds.raiseBorderRadius(world.id(), radius)) {
                            // The statement is conditional on the border still being smaller,
                            // so losing that race means somebody else already raised it.
                            return ActionResult.failure(
                                    FailureCode.STATE_CONFLICT, error("messages.command.border.not-raised"));
                        }
                        // FR-3 re-asserts the border from the database on every load, so an
                        // unloaded world needs nothing; a loaded one needs telling.
                        enqueueToWorldOrAliveNodes(
                                world, CommandKind.APPLY_SETTINGS, NodeCommand.EMPTY_PAYLOAD, current);

                        return ActionResult.success(success(
                                "messages.command.border.success",
                                Placeholders.text("world", world.name()),
                                Placeholders.count("radius", radius),
                                Placeholders.count("nether", radius / current.netherBorderDivisor())));
                    } catch (SQLException e) {
                        log.error("/world border failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("messages.command.generic-failure"));
                    }
                },
                executors.db());
    }

    /**
     * {@code /world upgrades} — what the caller has bought and what is still unspent (FR-45).
     *
     * @param caller the player
     * @return the outcome, whose message is the listing
     */
    public CompletableFuture<ActionResult> listUpgrades(Player caller) {
        Objects.requireNonNull(caller, "caller");
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        List<WorldUpgrade> held = upgrades.listOwnedBy(caller.getUniqueId());
                        if (held.isEmpty()) {
                            return ActionResult.success(info("messages.command.upgrades.none"));
                        }
                        Map<WorldId, String> worldNames = new HashMap<>();
                        for (PlayerWorld owned : worlds.listOwnedBy(caller.getUniqueId())) {
                            worldNames.put(owned.id(), owned.name());
                        }
                        Component out = info("messages.command.upgrades.header");
                        for (WorldUpgrade upgrade : held) {
                            out = out.append(Component.newline()).append(renderUpgradeLine(upgrade, worldNames));
                        }
                        return ActionResult.success(out);
                    } catch (SQLException e) {
                        log.error("/world upgrades failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("messages.command.generic-failure"));
                    }
                },
                executors.db());
    }

    /**
     * {@code /world upgrades redeem <id> [world]} — spends one upgrade on a world (FR-45).
     *
     * <p>A {@code STORAGE} upgrade adds to the owner's pool; a {@code BORDER} one raises what
     * they may set that world's border to, and is not the raise itself — {@code /world border}
     * is, so that a player chooses the radius rather than having it moved under them.
     *
     * @param caller the player
     * @param upgradeId the upgrade to spend
     * @param worldId the world to spend it on, or null to resolve it as §6.1 says
     * @return the outcome
     */
    public CompletableFuture<ActionResult> redeemUpgrade(Player caller, UUID upgradeId, @Nullable WorldId worldId) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(upgradeId, "upgradeId");
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        Target scope = targetWorld(caller, worldId, "/world upgrades redeem <id> <world>");
                        if (scope instanceof Target.None none) {
                            return none.refusal();
                        }
                        PlayerWorld world = ((Target.Found) scope).world();

                        Optional<WorldUpgrade> redeemed = upgrades.redeem(upgradeId, caller.getUniqueId(), world.id());
                        if (redeemed.isEmpty()) {
                            // The conditional UPDATE covers three refusals at once: no such
                            // upgrade, not theirs, or already spent. Re-reading says which.
                            Optional<WorldUpgrade> existing = upgrades.findById(upgradeId);
                            if (existing.isPresent()
                                    && existing.get().ownerUuid().equals(caller.getUniqueId())
                                    && !existing.get().unredeemed()) {
                                return ActionResult.failure(
                                        FailureCode.STATE_CONFLICT, error("messages.command.upgrades.already-spent"));
                            }
                            return ActionResult.failure(
                                    FailureCode.WORLD_NOT_FOUND, error("messages.command.upgrades.not-yours"));
                        }

                        WorldUpgrade upgrade = redeemed.get();
                        String key = upgrade.kind() == UpgradeKind.STORAGE
                                ? "messages.command.upgrades.redeemed-storage"
                                : "messages.command.upgrades.redeemed-border";
                        return ActionResult.success(success(
                                key,
                                Placeholders.text("world", world.name()),
                                Placeholders.bytes("size", upgrade.amount()),
                                Placeholders.count("blocks", (int) Math.min(upgrade.amount(), Integer.MAX_VALUE))));
                    } catch (SQLException e) {
                        log.error("/world upgrades redeem failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("messages.command.generic-failure"));
                    }
                },
                executors.db());
    }

    /**
     * Spends the caller's oldest unredeemed upgrade of {@code kind} on a world (FR-45).
     *
     * <p>The GUI's route to redemption. A menu button cannot carry an upgrade id, and it does
     * not need one: upgrades of a kind are interchangeable, so the oldest is spent and the
     * player keeps the rest. {@code /world upgrades redeem <id>} remains for picking one.
     *
     * @param caller the owner
     * @param kind which dial to spend on
     * @param worldId the world to spend it on, or null to resolve it as §6.1 says
     * @return the outcome
     */
    public CompletableFuture<ActionResult> redeemOldestUpgrade(
            Player caller, UpgradeKind kind, @Nullable WorldId worldId) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(kind, "kind");
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        Optional<WorldUpgrade> oldest = upgrades.listUnredeemed(caller.getUniqueId()).stream()
                                .filter(upgrade -> upgrade.kind() == kind)
                                .findFirst();
                        if (oldest.isEmpty()) {
                            return ActionResult.failure(
                                    FailureCode.WORLD_NOT_FOUND, error("messages.command.upgrades.none-of-kind"));
                        }
                        return redeemUpgrade(caller, oldest.get().id(), worldId).join();
                    } catch (SQLException e) {
                        log.error("menu upgrade redeem failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("messages.command.generic-failure"));
                    }
                },
                executors.db());
    }

    /**
     * {@code /world admin upgrade grant} — records a purchase (FR-44).
     *
     * <p>This is the whole integration surface a webstore needs, and it is idempotent on
     * {@code reference}: a retried delivery grants one upgrade and says the second was
     * already there. Nothing here sees a price or a currency.
     *
     * @param targetUuid the player who bought it
     * @param kind what it raises
     * @param amount bytes for STORAGE, blocks of radius for BORDER
     * @param reference the grantor's transaction id
     * @return the outcome, which distinguishes a new grant from a repeat
     */
    public CompletableFuture<ActionResult> grantUpgrade(
            UUID targetUuid, UpgradeKind kind, long amount, String reference) {
        Objects.requireNonNull(targetUuid, "targetUuid");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(reference, "reference");
        return CompletableFuture.supplyAsync(
                () -> {
                    if (amount <= 0) {
                        return ActionResult.failure(
                                FailureCode.INVALID_NAME, error("messages.command.admin.upgrade.invalid-amount"));
                    }
                    try {
                        WorldUpgradeRepository.Grant grant = upgrades.grant(targetUuid, kind, amount, reference);
                        String key = grant.created()
                                ? "messages.command.admin.upgrade.granted"
                                : "messages.command.admin.upgrade.already-granted";
                        return ActionResult.success(success(
                                key,
                                Placeholders.raw("kind", kind.name()),
                                Placeholders.raw("amount", describeAmount(grant.upgrade())),
                                Placeholders.raw("reference", reference),
                                Placeholders.raw("id", grant.upgrade().id().toString())));
                    } catch (SQLException e) {
                        log.error("/world admin upgrade grant failed for {}", targetUuid, e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("messages.command.generic-failure"));
                    }
                },
                executors.db());
    }

    /**
     * {@code /world admin upgrade revoke <ref>} — a refund or a mis-delivery.
     *
     * <p>Refuses one that has been spent. Clawing back a redeemed border upgrade would leave
     * that world enlarged past its owner's allowance, and FR-3c forbids the only correction
     * that would fix it.
     *
     * @param reference the grantor's transaction id
     * @return the outcome
     */
    public CompletableFuture<ActionResult> revokeUpgrade(String reference) {
        Objects.requireNonNull(reference, "reference");
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        if (upgrades.revoke(reference)) {
                            return ActionResult.success(success(
                                    "messages.command.admin.upgrade.revoked",
                                    Placeholders.raw("reference", reference)));
                        }
                        return ActionResult.failure(
                                FailureCode.STATE_CONFLICT,
                                error(
                                        "messages.command.admin.upgrade.not-revocable",
                                        Placeholders.raw("reference", reference)));
                    } catch (SQLException e) {
                        log.error("/world admin upgrade revoke failed for {}", reference, e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("messages.command.generic-failure"));
                    }
                },
                executors.db());
    }

    /**
     * {@code /world admin upgrade list <player>} — what a player holds, online or not.
     *
     * @param targetUuid the player
     * @return the outcome, whose message is the listing
     */
    public CompletableFuture<ActionResult> listUpgradesOf(UUID targetUuid) {
        Objects.requireNonNull(targetUuid, "targetUuid");
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        List<WorldUpgrade> held = upgrades.listOwnedBy(targetUuid);
                        if (held.isEmpty()) {
                            return ActionResult.success(info("messages.command.upgrades.none"));
                        }
                        Map<WorldId, String> worldNames = new HashMap<>();
                        for (PlayerWorld owned : worlds.listOwnedBy(targetUuid)) {
                            worldNames.put(owned.id(), owned.name());
                        }
                        Component out = info("messages.command.upgrades.header");
                        for (WorldUpgrade upgrade : held) {
                            out = out.append(Component.newline()).append(renderUpgradeLine(upgrade, worldNames));
                        }
                        return ActionResult.success(out);
                    } catch (SQLException e) {
                        log.error("/world admin upgrade list failed for {}", targetUuid, e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("messages.command.generic-failure"));
                    }
                },
                executors.db());
    }

    private Component renderUpgradeLine(WorldUpgrade upgrade, Map<WorldId, String> worldNames) {
        if (upgrade.unredeemed()) {
            return info(
                    "messages.command.upgrades.entry-unspent",
                    Placeholders.raw("id", upgrade.id().toString()),
                    Placeholders.raw("kind", upgrade.kind().name()),
                    Placeholders.raw("amount", describeAmount(upgrade)));
        }
        WorldId on = upgrade.worldId();
        return info(
                "messages.command.upgrades.entry-spent",
                Placeholders.raw("kind", upgrade.kind().name()),
                Placeholders.raw("amount", describeAmount(upgrade)),
                Placeholders.text(
                        "world",
                        on == null
                                ? "?"
                                : worldNames.getOrDefault(on, on.value().toString())));
    }

    private static String describeAmount(WorldUpgrade upgrade) {
        return upgrade.kind() == UpgradeKind.STORAGE
                ? StorageQuotaResolver.formatBytes(upgrade.amount())
                : upgrade.amount() + " blocks";
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
                        long bought = upgrades.bonusStorageBytes(caller.getUniqueId());
                        StorageTiers.Resolution resolved = storageTiers.evaluate(caller, used, current, bought);
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
                                    info(
                                            "messages.command.storage.probed-note",
                                            Placeholders.count(
                                                    "count",
                                                    current.storageQuotaTiers().size())));
                        }
                        return ActionResult.success(
                                info("messages.command.storage.summary", Placeholders.bytes("size", used)));
                    } catch (SQLException e) {
                        log.error("/world storage failed for {}", caller.getUsername(), e);
                        return ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("messages.command.generic-failure"));
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
    private Optional<ActionResult> requirePermission(CommandSource source, String permission) {
        if (WorldPermissions.allows(source, permission)) {
            return Optional.empty();
        }
        return Optional.of(ActionResult.failure(
                FailureCode.PERMISSION_DENIED,
                error("messages.command.generic.permission-denied", Placeholders.raw("permission", permission))));
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
                return new Target.None(ActionResult.failure(
                        FailureCode.WORLD_NOT_FOUND, error("messages.command.generic.world-gone")));
            }
            PlayerWorld named = found.get();
            if (!named.ownerUuid().equals(caller.getUniqueId())) {
                return new Target.None(ActionResult.failure(
                        FailureCode.PERMISSION_DENIED,
                        error("messages.command.generic.not-owner", Placeholders.text("world", named.name()))));
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
            return new Target.None(ActionResult.failure(
                    FailureCode.WORLD_NOT_FOUND, error("messages.command.generic.no-world-owned")));
        }
        if (owned.size() > 1) {
            String yours =
                    String.join(", ", owned.stream().map(PlayerWorld::name).toList());
            Component refusal = usage != null
                    ? error(
                            "messages.command.generic.ambiguous-world-with-usage",
                            Placeholders.count("count", owned.size()),
                            Placeholders.text("names", yours),
                            Placeholders.text("usage", usage))
                    : error(
                            "messages.command.generic.ambiguous-world-free-text",
                            Placeholders.count("count", owned.size()),
                            Placeholders.text("names", yours));
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
                                    FailureCode.WORLD_NOT_FOUND,
                                    error(
                                            "messages.command.generic.owns-no-world-named",
                                            Placeholders.text("world", worldName))));
                        }
                        return new Target.Found(found.get());
                    } catch (SQLException e) {
                        log.error("resolving world '{}' for {} failed", worldName, caller.getUsername(), e);
                        return new Target.None(ActionResult.failure(
                                FailureCode.GENERIC_ERROR, error("messages.command.generic-failure")));
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
        long bought = upgrades.bonusStorageBytes(caller.getUniqueId());
        return storageTiers.evaluate(caller, used, current, bought).quota();
    }

    /**
     * How many worlds this player may own: the network cap, raised by their subscription
     * tier if they hold one (FR-1, FR-42, FR-43).
     *
     * @param caller the player
     * @param current the policy in force
     * @return the effective cap
     */
    public int slotsFor(Player caller, NetworkPolicy current) {
        return storageTiers.slots(caller, current);
    }

    /**
     * How many worlds a player may own when they may not be online (FR-43).
     *
     * <p>A subscription is permissions, and Velocity can only answer about a player it has
     * a connection to. For an offline target the network default is the honest answer: it is
     * what every player is entitled to, so the check refuses only what it is certain about
     * and lets a subscriber's own {@code /world create} — where they are online by
     * definition — be the place their tier is read.
     *
     * @param uuid the player, online or not
     * @param current the policy in force
     * @return their effective cap, or the network default when nothing can be read
     */
    public int slotsFor(UUID uuid, NetworkPolicy current) {
        return proxy.getPlayer(uuid)
                .map(online -> storageTiers.slots(online, current))
                .orElseGet(current::maxWorldsPerPlayer);
    }

    /** One-time purchased upgrades (FR-44, FR-45). */
    public WorldUpgradeRepository upgrades() {
        return upgrades;
    }

    /**
     * The subscription tier resolver (FR-43), shared so the menus answer as the commands do.
     *
     * <p>One instance rather than one per surface: {@link StorageTiers} decides once whether
     * LuckPerms can enumerate permissions and remembers it, so a second instance would repeat
     * that detection and log it twice — and, worse, could reach a different answer if it were
     * constructed before the permission plugin had loaded.
     */
    public StorageTiers storageTiers() {
        return storageTiers;
    }

    public Component refuseForQuota(
            CommandSource caller, StorageQuota quota, String attemptedKey, TagResolver... attemptedPlaceholders) {
        Component attempted = messages.render(attemptedKey, attemptedPlaceholders);
        Component err = error(
                "messages.command.generic.quota-exceeded",
                Placeholders.component("attempted", attempted),
                Placeholders.bytes("used", quota.usedBytes()),
                Placeholders.bytes("limit", quota.effectiveLimitBytes()));
        tell(caller, info("messages.command.generic.quota-hint"));
        return err;
    }

    public void renderStorage(CommandSource target, String who, StorageQuota quota, List<PlayerWorld> owned) {
        if (quota.unlimited()) {
            tell(
                    target,
                    info(
                            "messages.command.storage.summary-unlimited",
                            Placeholders.text("who", who),
                            Placeholders.bytes("used", quota.usedBytes())));
        } else {
            tell(
                    target,
                    info(
                            "messages.command.storage.summary-limited",
                            Placeholders.text("who", who),
                            Placeholders.bytes("used", quota.usedBytes()),
                            Placeholders.bytes("limit", quota.effectiveLimitBytes()),
                            Placeholders.raw("bar", progressBar(quota.percentage())),
                            Placeholders.raw("percent", String.format(Locale.ROOT, "%.0f", quota.percentage()))));
        }
        if (owned.isEmpty()) {
            tell(target, info("messages.command.storage.no-worlds"));
            return;
        }
        for (PlayerWorld world : owned) {
            tell(
                    target,
                    info(
                            "messages.command.storage.world-entry",
                            Placeholders.text("world", world.name()),
                            Placeholders.bytes("size", world.storageBytes()),
                            Placeholders.raw("state", world.state() == WorldState.ARCHIVED ? "archived" : "live")));
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
                yield new Routing.Refused(error("messages.command.generic.version-too-new"));
            }
            case PlacementDecision.NoCapacity full -> {
                log.warn(
                        "no capacity for world {}: all {} version-capable nodes are over a threshold (MN-15)",
                        worldId,
                        full.candidates());
                yield new Routing.Refused(error("messages.command.generic.no-capacity"));
            }
            case PlacementDecision.NoNodesAlive ignored ->
                new Routing.Refused(error("messages.command.generic.no-nodes-alive"));
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
    public @Nullable ActionResult outcomeOrRunning(CommandSource caller, long commandId, Component what) {
        CommandOutcomes.Outcome outcome = CommandOutcomes.await(nodeCommands, commandId);
        String whatText = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(what);
        return switch (outcome) {
            case CommandOutcomes.Outcome.Running ignored -> null;
            case CommandOutcomes.Outcome.Completed completed -> {
                if (completed.isOk()) {
                    yield null;
                }
                log.warn("{} (command {}) was refused: {}", whatText, commandId, completed.result());
                yield ActionResult.failure(
                        FailureCode.STATE_CONFLICT,
                        error(
                                "messages.command.generic.command-refused",
                                Placeholders.component("what", what),
                                Placeholders.text("detail", completed.detail())));
            }
            case CommandOutcomes.Outcome.Gone ignored -> {
                // Swept, or the world went with it. Either way nobody ran it.
                log.warn("{} (command {}) vanished before it was completed", whatText, commandId);
                yield ActionResult.failure(
                        FailureCode.STATE_CONFLICT,
                        error("messages.command.generic.command-lost", Placeholders.component("what", what)));
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
    Component inviteNotice(String ownerName, String worldName) {
        Objects.requireNonNull(ownerName, "ownerName");
        Objects.requireNonNull(worldName, "worldName");
        // The click target is built here, not in the template: MiniMessage's
        // <click:run_command:'...'> argument is a plain string that is never
        // re-parsed for tags, so a placeholder embedded inside it would reach the
        // client unresolved (literally "/world accept <owner>").
        String command = "/world accept " + ownerName;
        Component button = Component.text("[Click here to accept]", NamedTextColor.GOLD, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text("Runs " + command, NamedTextColor.GRAY)));
        return messages.render(
                "messages.notice.invite",
                Placeholders.text("owner", ownerName),
                Placeholders.text("world", worldName),
                Placeholders.component("button", button));
    }

    /**
     * Builds an informational line. Does not send it.
     *
     * <p>These three used to both build <em>and</em> send, and the built
     * {@link Component} then went into an {@link ActionResult} that the menu
     * channel serialised and sent again — so every GUI-driven action delivered
     * its message twice. One place decides delivery now: the command tree sends
     * {@code result.message()}, the menu channel serialises it (NFR-5).
     *
     * <p>{@code key} looks up a {@code messages.command.*} template (NFR-5);
     * {@code colorIfAbsent} is a fallback only — a template with its own explicit
     * color tag keeps it, one without gets the surface's default color.
     */
    public Component info(String key, TagResolver... placeholders) {
        return messages.render(key, placeholders).colorIfAbsent(NamedTextColor.GRAY);
    }

    /** Builds a success line. Does not send it. */
    public Component success(String key, TagResolver... placeholders) {
        return messages.render(key, placeholders).colorIfAbsent(NamedTextColor.GREEN);
    }

    /** Builds an error line. Does not send it. */
    public Component error(String key, TagResolver... placeholders) {
        return messages.render(key, placeholders).colorIfAbsent(NamedTextColor.RED);
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
