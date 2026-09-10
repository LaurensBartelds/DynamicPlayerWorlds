package nl.gzmn.playerworlds.proxy.menu;

import com.velocitypowered.api.proxy.Player;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.config.MessageCatalog;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.db.MembershipRepository;
import nl.gzmn.playerworlds.core.db.PlayerNameRepository;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository;
import nl.gzmn.playerworlds.core.db.TransferRequestRepository;
import nl.gzmn.playerworlds.core.db.WorldBanRepository;
import nl.gzmn.playerworlds.core.db.WorldUpgradeRepository;
import nl.gzmn.playerworlds.core.menu.RenderMenuPayload;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.Role;
import nl.gzmn.playerworlds.core.model.StorageQuota;
import nl.gzmn.playerworlds.core.model.TransferRequest;
import nl.gzmn.playerworlds.core.model.UpgradeKind;
import nl.gzmn.playerworlds.core.model.WorldBan;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.model.WorldInvite;
import nl.gzmn.playerworlds.core.model.WorldMember;
import nl.gzmn.playerworlds.core.model.WorldSettings;
import nl.gzmn.playerworlds.core.model.WorldUpgrade;
import nl.gzmn.playerworlds.proxy.command.Messages;
import nl.gzmn.playerworlds.proxy.menu.screens.BansScreenBuilder;
import nl.gzmn.playerworlds.proxy.menu.screens.BrowseScreenBuilder;
import nl.gzmn.playerworlds.proxy.menu.screens.ConfirmScreenBuilder;
import nl.gzmn.playerworlds.proxy.menu.screens.InvitesScreenBuilder;
import nl.gzmn.playerworlds.proxy.menu.screens.MainScreenBuilder;
import nl.gzmn.playerworlds.proxy.menu.screens.MembersScreenBuilder;
import nl.gzmn.playerworlds.proxy.menu.screens.MyWorldsScreenBuilder;
import nl.gzmn.playerworlds.proxy.menu.screens.SettingsScreenBuilder;
import nl.gzmn.playerworlds.proxy.menu.screens.StorageScreenBuilder;
import nl.gzmn.playerworlds.proxy.menu.screens.WorldDetailScreenBuilder;
import nl.gzmn.playerworlds.proxy.permission.StorageTiers;
import org.jspecify.annotations.Nullable;

/**
 * Proxy-side service coordinating asynchronous database queries and view construction
 * for all GUI menu screens (NFR-2).
 */
public final class MenuViewService {

    private final PlayerWorldRepository worldRepository;
    private final MembershipRepository membershipRepository;
    private final TransferRequestRepository transferRepository;
    private final WorldBanRepository banRepository;
    private final PlayerNameRepository nameRepository;
    private final WorldUpgradeRepository upgradeRepository;

    /**
     * Subscription tiers (FR-43), shared with the commands.
     *
     * <p>The screens have to answer the same as {@code /world create} does. Resolved here from
     * a bare permission predicate they could only probe the configured tiers, so a subscriber
     * whose tier LuckPerms knows about but {@code worlds.slot-tiers} does not was shown the
     * network default while the command let them past it.
     */
    private final StorageTiers storageTiers;

    private final Supplier<NetworkPolicy> policySupplier;
    private final Executor dbExecutor;
    private final Messages messages;

    public MenuViewService(
            PlayerWorldRepository worldRepository,
            MembershipRepository membershipRepository,
            TransferRequestRepository transferRepository,
            WorldBanRepository banRepository,
            PlayerNameRepository nameRepository,
            WorldUpgradeRepository upgradeRepository,
            StorageTiers storageTiers,
            Supplier<NetworkPolicy> policySupplier,
            PluginExecutors executors) {
        this(
                worldRepository,
                membershipRepository,
                transferRepository,
                banRepository,
                nameRepository,
                upgradeRepository,
                storageTiers,
                policySupplier,
                executors,
                null);
    }

    public MenuViewService(
            PlayerWorldRepository worldRepository,
            MembershipRepository membershipRepository,
            TransferRequestRepository transferRepository,
            WorldBanRepository banRepository,
            PlayerNameRepository nameRepository,
            WorldUpgradeRepository upgradeRepository,
            StorageTiers storageTiers,
            Supplier<NetworkPolicy> policySupplier,
            PluginExecutors executors,
            @Nullable Supplier<MessageCatalog> messageCatalog) {
        this(
                worldRepository,
                membershipRepository,
                transferRepository,
                banRepository,
                nameRepository,
                upgradeRepository,
                storageTiers,
                policySupplier,
                Objects.requireNonNull(executors, "executors").db(),
                messageCatalog);
    }

    public MenuViewService(
            PlayerWorldRepository worldRepository,
            MembershipRepository membershipRepository,
            TransferRequestRepository transferRepository,
            WorldBanRepository banRepository,
            PlayerNameRepository nameRepository,
            WorldUpgradeRepository upgradeRepository,
            StorageTiers storageTiers,
            Supplier<NetworkPolicy> policySupplier,
            Executor dbExecutor) {
        this(
                worldRepository,
                membershipRepository,
                transferRepository,
                banRepository,
                nameRepository,
                upgradeRepository,
                storageTiers,
                policySupplier,
                dbExecutor,
                null);
    }

    public MenuViewService(
            PlayerWorldRepository worldRepository,
            MembershipRepository membershipRepository,
            TransferRequestRepository transferRepository,
            WorldBanRepository banRepository,
            PlayerNameRepository nameRepository,
            WorldUpgradeRepository upgradeRepository,
            StorageTiers storageTiers,
            Supplier<NetworkPolicy> policySupplier,
            Executor dbExecutor,
            @Nullable Supplier<MessageCatalog> messageCatalog) {
        this.worldRepository = Objects.requireNonNull(worldRepository, "worldRepository");
        this.membershipRepository = Objects.requireNonNull(membershipRepository, "membershipRepository");
        this.transferRepository = Objects.requireNonNull(transferRepository, "transferRepository");
        this.banRepository = Objects.requireNonNull(banRepository, "banRepository");
        this.nameRepository = Objects.requireNonNull(nameRepository, "nameRepository");
        this.upgradeRepository = Objects.requireNonNull(upgradeRepository, "upgradeRepository");
        this.storageTiers = Objects.requireNonNull(storageTiers, "storageTiers");
        this.policySupplier = Objects.requireNonNull(policySupplier, "policySupplier");
        this.dbExecutor = Objects.requireNonNull(dbExecutor, "dbExecutor");
        this.messages = new Messages(messageCatalog);
    }

    /** Renders admin-configurable message/GUI text (NFR-5). */
    public Messages messages() {
        return messages;
    }

    /**
     * Builds the main hub menu screen payload.
     */
    public CompletableFuture<RenderMenuPayload> buildMainMenu(Player player, long correlationId) {
        Objects.requireNonNull(player, "player");
        UUID playerUuid = player.getUniqueId();

        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        int owned = worldRepository.countOwnedBy(playerUuid);
                        long used = worldRepository.totalStorageUsedBy(playerUuid);
                        int invites = membershipRepository
                                .findLiveInvitesFor(playerUuid)
                                .size();
                        NetworkPolicy pol = policySupplier.get();
                        StorageQuota quota = storageTiers
                                .evaluate(player, used, pol, upgradeRepository.bonusStorageBytes(playerUuid))
                                .quota();
                        int slots = storageTiers.slots(player, pol);
                        return MainScreenBuilder.build(messages, correlationId, owned, slots, invites, quota);
                    } catch (SQLException e) {
                        throw new CompletionException(e);
                    }
                },
                dbExecutor);
    }

    /**
     * Builds page 0 of the owned worlds menu screen.
     */
    public CompletableFuture<RenderMenuPayload> buildMyWorldsMenu(Player player, long correlationId) {
        return buildMyWorldsMenu(player, 0, correlationId);
    }

    /**
     * Builds a specific page of the owned worlds menu screen.
     *
     * <p>Takes the permission check because the screen states the world cap, and a
     * subscriber's cap is not the network's (FR-43). Showing them the default would tell a
     * player who has paid for ten slots that they have two.
     */
    public CompletableFuture<RenderMenuPayload> buildMyWorldsMenu(Player player, int page, long correlationId) {
        Objects.requireNonNull(player, "player");
        UUID playerUuid = player.getUniqueId();

        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        List<PlayerWorld> owned = worldRepository.listOwnedBy(playerUuid);
                        List<PlayerWorld> shared = worldRepository.listSharedWith(playerUuid);
                        Map<WorldId, Role> roles = sharedRoles(playerUuid, shared);
                        NetworkPolicy pol = policySupplier.get();
                        int slots = storageTiers.slots(player, pol);
                        return MyWorldsScreenBuilder.build(messages, correlationId, owned, shared, roles, page, slots);
                    } catch (SQLException e) {
                        throw new CompletionException(e);
                    }
                },
                dbExecutor);
    }

    /**
     * The viewer's role in each world they were invited into (FR-7, FR-9c).
     *
     * <p>One query for every shared world rather than one per world: the list is
     * already bounded by how many invites a player has accepted, and the menu is
     * rendered on a database thread that other menus are queueing behind.
     */
    /** How many unspent upgrades of each kind a player is holding (FR-45). */
    private Map<UpgradeKind, Integer> unspentByKind(UUID ownerUuid) throws SQLException {
        Map<UpgradeKind, Integer> counts = new EnumMap<>(UpgradeKind.class);
        for (WorldUpgrade upgrade : upgradeRepository.listUnredeemed(ownerUuid)) {
            counts.merge(upgrade.kind(), 1, Integer::sum);
        }
        return counts;
    }

    private Map<WorldId, Role> sharedRoles(UUID playerUuid, List<PlayerWorld> shared) throws SQLException {
        if (shared.isEmpty()) {
            return Map.of();
        }
        Map<WorldId, Role> roles = new HashMap<>();
        for (WorldMember membership : membershipRepository.membershipsOf(playerUuid)) {
            roles.put(membership.worldId(), membership.role());
        }
        return Map.copyOf(roles);
    }

    /**
     * Builds the single world management screen for a world, as its owner sees it.
     */
    public CompletableFuture<RenderMenuPayload> buildWorldMenu(WorldId worldId, long correlationId) {
        return buildWorldMenu(worldId, null, correlationId);
    }

    /**
     * Builds the single world screen as {@code viewerUuid} sees it.
     *
     * <p>A member who does not own the world gets it without the management half.
     * The proxy refuses those actions from a non-owner anyway (FR-31a), but
     * {@code ACTION:ARCHIVE} names a world by <em>name</em> and resolves it
     * against the caller's own worlds — so drawing it for a visitor offers them a
     * button that succeeds against the wrong world.
     *
     * @param viewerUuid the player looking at it, or null for the owner's view
     */
    public CompletableFuture<RenderMenuPayload> buildWorldMenu(
            WorldId worldId, @Nullable UUID viewerUuid, long correlationId) {
        Objects.requireNonNull(worldId, "worldId");

        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        Optional<PlayerWorld> worldOpt = worldRepository.findById(worldId);
                        if (worldOpt.isEmpty()) {
                            throw new IllegalArgumentException("World not found: " + worldId);
                        }
                        PlayerWorld world = worldOpt.get();
                        boolean manage = viewerUuid == null || world.ownerUuid().equals(viewerUuid);
                        // Only an owner may spend an upgrade here, and only their own: the
                        // redeem entries are drawn from the viewer's unspent upgrades (FR-45).
                        Map<UpgradeKind, Integer> unspent =
                                manage && viewerUuid != null ? unspentByKind(viewerUuid) : Map.of();
                        return WorldDetailScreenBuilder.build(messages, correlationId, world, manage, unspent);
                    } catch (SQLException e) {
                        throw new CompletionException(e);
                    }
                },
                dbExecutor);
    }

    /**
     * Builds the world settings screen for a world.
     */
    public CompletableFuture<RenderMenuPayload> buildSettingsMenu(WorldId worldId, long correlationId) {
        Objects.requireNonNull(worldId, "worldId");

        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        Optional<PlayerWorld> worldOpt = worldRepository.findById(worldId);
                        if (worldOpt.isEmpty()) {
                            throw new IllegalArgumentException("World not found: " + worldId);
                        }
                        PlayerWorld world = worldOpt.get();
                        WorldSettings settings = WorldSettings.fromJson(world.settingsJson());
                        return SettingsScreenBuilder.build(messages, correlationId, world, settings);
                    } catch (SQLException e) {
                        throw new CompletionException(e);
                    }
                },
                dbExecutor);
    }

    /**
     * Builds page 0 of the members management screen for a world.
     */
    public CompletableFuture<RenderMenuPayload> buildMembersMenu(WorldId worldId, long correlationId) {
        return buildMembersMenu(worldId, 0, correlationId);
    }

    /**
     * Builds a specific page of the members management screen for a world.
     */
    public CompletableFuture<RenderMenuPayload> buildMembersMenu(WorldId worldId, int page, long correlationId) {
        Objects.requireNonNull(worldId, "worldId");

        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        Optional<PlayerWorld> worldOpt = worldRepository.findById(worldId);
                        if (worldOpt.isEmpty()) {
                            throw new IllegalArgumentException("World not found: " + worldId);
                        }
                        List<WorldMember> members = membershipRepository.listMembers(worldId);
                        List<UUID> uuids =
                                members.stream().map(WorldMember::uuid).toList();
                        Map<UUID, String> names = nameRepository.namesOf(uuids);
                        List<MembersScreenBuilder.MemberEntry> entries = members.stream()
                                .map(m -> new MembersScreenBuilder.MemberEntry(
                                        m.uuid(),
                                        names.getOrDefault(m.uuid(), m.uuid().toString()),
                                        m.role(),
                                        m.joinedAt()))
                                .toList();
                        return MembersScreenBuilder.build(messages, correlationId, worldOpt.get(), entries, page);
                    } catch (SQLException e) {
                        throw new CompletionException(e);
                    }
                },
                dbExecutor);
    }

    /**
     * Builds the storage breakdown screen for a player.
     */
    public CompletableFuture<RenderMenuPayload> buildStorageMenu(Player player, long correlationId) {
        Objects.requireNonNull(player, "player");
        UUID playerUuid = player.getUniqueId();

        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        List<PlayerWorld> owned = worldRepository.listOwnedBy(playerUuid);
                        long used = worldRepository.totalStorageUsedBy(playerUuid);
                        NetworkPolicy pol = policySupplier.get();
                        StorageQuota quota = storageTiers
                                .evaluate(player, used, pol, upgradeRepository.bonusStorageBytes(playerUuid))
                                .quota();
                        return StorageScreenBuilder.build(messages, correlationId, quota, owned);
                    } catch (SQLException e) {
                        throw new CompletionException(e);
                    }
                },
                dbExecutor);
    }

    /**
     * Builds page 0 of the pending invites and transfer requests screen.
     */
    public CompletableFuture<RenderMenuPayload> buildInvitesMenu(UUID playerUuid, long correlationId) {
        return buildInvitesMenu(playerUuid, 0, correlationId);
    }

    /**
     * Builds a specific page of the pending invites and transfer requests screen.
     */
    public CompletableFuture<RenderMenuPayload> buildInvitesMenu(UUID playerUuid, int page, long correlationId) {
        Objects.requireNonNull(playerUuid, "playerUuid");

        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        List<WorldInvite> liveInvites = membershipRepository.findLiveInvitesFor(playerUuid);
                        List<TransferRequest> liveTransfers = transferRepository.findLiveRequestsFor(playerUuid);

                        List<UUID> senderUuids = new ArrayList<>();
                        for (WorldInvite invite : liveInvites) {
                            senderUuids.add(invite.invitedBy());
                        }
                        for (TransferRequest req : liveTransfers) {
                            senderUuids.add(req.fromUuid());
                        }

                        Map<UUID, String> names =
                                !senderUuids.isEmpty() ? nameRepository.namesOf(senderUuids) : Map.of();
                        List<InvitesScreenBuilder.InviteEntry> entries = new ArrayList<>();

                        for (WorldInvite invite : liveInvites) {
                            String worldName = invite.worldId().toString();
                            Optional<PlayerWorld> w = worldRepository.findById(invite.worldId());
                            if (w.isPresent()) {
                                worldName = w.get().name();
                            }
                            String senderName = names.getOrDefault(
                                    invite.invitedBy(), invite.invitedBy().toString());
                            entries.add(new InvitesScreenBuilder.InviteEntry(
                                    invite.worldId(),
                                    worldName,
                                    invite.invitedBy(),
                                    senderName,
                                    invite.expiresAt(),
                                    false));
                        }

                        for (TransferRequest req : liveTransfers) {
                            String worldName = req.worldId().toString();
                            Optional<PlayerWorld> w = worldRepository.findById(req.worldId());
                            if (w.isPresent()) {
                                worldName = w.get().name();
                            }
                            String senderName = names.getOrDefault(
                                    req.fromUuid(), req.fromUuid().toString());
                            entries.add(new InvitesScreenBuilder.InviteEntry(
                                    req.worldId(), worldName, req.fromUuid(), senderName, req.expiresAt(), true));
                        }

                        return InvitesScreenBuilder.build(messages, correlationId, List.copyOf(entries), page);
                    } catch (SQLException e) {
                        throw new CompletionException(e);
                    }
                },
                dbExecutor);
    }

    /**
     * Builds page 0 of the banned players screen for a world.
     */
    public CompletableFuture<RenderMenuPayload> buildBansMenu(WorldId worldId, long correlationId) {
        return buildBansMenu(worldId, 0, correlationId);
    }

    /**
     * Builds a specific page of the banned players screen for a world.
     */
    public CompletableFuture<RenderMenuPayload> buildBansMenu(WorldId worldId, int page, long correlationId) {
        Objects.requireNonNull(worldId, "worldId");

        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        Optional<PlayerWorld> worldOpt = worldRepository.findById(worldId);
                        if (worldOpt.isEmpty()) {
                            throw new IllegalArgumentException("World not found: " + worldId);
                        }
                        List<WorldBan> bans = banRepository.listBans(worldId);
                        List<UUID> uuids = bans.stream().map(WorldBan::uuid).toList();
                        Map<UUID, String> names = nameRepository.namesOf(uuids);
                        List<BansScreenBuilder.BanEntry> entries = bans.stream()
                                .map(b -> new BansScreenBuilder.BanEntry(
                                        b.uuid(),
                                        names.getOrDefault(b.uuid(), b.uuid().toString()),
                                        b.reason(),
                                        b.bannedAt()))
                                .toList();
                        return BansScreenBuilder.build(messages, correlationId, worldOpt.get(), entries, page);
                    } catch (SQLException e) {
                        throw new CompletionException(e);
                    }
                },
                dbExecutor);
    }

    /**
     * Builds page 0 of the public worlds browse screen.
     */
    public CompletableFuture<RenderMenuPayload> buildBrowseMenu(long correlationId) {
        return buildBrowseMenu(0, correlationId);
    }

    /**
     * Builds a specific page of the public worlds browse screen.
     */
    public CompletableFuture<RenderMenuPayload> buildBrowseMenu(int page, long correlationId) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        List<PlayerWorld> publicWorlds = worldRepository.listPublicWorlds();
                        List<UUID> ownerUuids = publicWorlds.stream()
                                .map(PlayerWorld::ownerUuid)
                                .distinct()
                                .toList();
                        Map<UUID, String> names = !ownerUuids.isEmpty() ? nameRepository.namesOf(ownerUuids) : Map.of();
                        List<BrowseScreenBuilder.PublicWorldEntry> entries = publicWorlds.stream()
                                .map(w -> new BrowseScreenBuilder.PublicWorldEntry(
                                        w.id(),
                                        w.name(),
                                        w.ownerUuid(),
                                        names.getOrDefault(
                                                w.ownerUuid(), w.ownerUuid().toString()),
                                        w.description()))
                                .toList();
                        return BrowseScreenBuilder.build(messages, correlationId, entries, page);
                    } catch (SQLException e) {
                        throw new CompletionException(e);
                    }
                },
                dbExecutor);
    }

    /**
     * Builds a confirmation modal screen payload.
     */
    public RenderMenuPayload buildConfirmMenu(
            String title, String description, String confirmActionTag, String cancelActionTag, long correlationId) {
        return ConfirmScreenBuilder.build(
                messages, correlationId, title, description, confirmActionTag, cancelActionTag);
    }
}
