package nl.gzmn.playerworlds.backend.gui;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import nl.gzmn.playerworlds.backend.gui.screen.BansMenu;
import nl.gzmn.playerworlds.backend.gui.screen.BrowseMenu;
import nl.gzmn.playerworlds.backend.gui.screen.ConfirmMenu;
import nl.gzmn.playerworlds.backend.gui.screen.InvitesMenu;
import nl.gzmn.playerworlds.backend.gui.screen.MainMenu;
import nl.gzmn.playerworlds.backend.gui.screen.MembersMenu;
import nl.gzmn.playerworlds.backend.gui.screen.MyWorldsMenu;
import nl.gzmn.playerworlds.backend.gui.screen.SettingsMenu;
import nl.gzmn.playerworlds.backend.gui.screen.StorageMenu;
import nl.gzmn.playerworlds.backend.gui.screen.WorldMenu;
import nl.gzmn.playerworlds.core.concurrent.MainThread;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.config.StorageQuotaResolver;
import nl.gzmn.playerworlds.core.db.MembershipRepository;
import nl.gzmn.playerworlds.core.db.PlayerNameRepository;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository;
import nl.gzmn.playerworlds.core.db.TransferRequestRepository;
import nl.gzmn.playerworlds.core.db.WorldBanRepository;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.StorageQuota;
import nl.gzmn.playerworlds.core.model.TransferRequest;
import nl.gzmn.playerworlds.core.model.WorldBan;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.model.WorldInvite;
import nl.gzmn.playerworlds.core.model.WorldMember;
import nl.gzmn.playerworlds.core.model.WorldSettings;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service coordinating inventory GUI interactions and views for players on the backend.
 *
 * <p>Enforces NFR-2 by executing all database reads asynchronously on {@link PluginExecutors#db()}
 * and UI operations on {@link PluginExecutors#main()}.
 */
public class MenuService {

    private static final Logger log = LoggerFactory.getLogger(MenuService.class);

    private final @Nullable PlayerWorldRepository worldRepository;
    private final @Nullable MembershipRepository membershipRepository;
    private final @Nullable TransferRequestRepository transferRepository;
    private final @Nullable WorldBanRepository banRepository;
    private final @Nullable PlayerNameRepository nameRepository;
    private final @Nullable MenuChannel channel;
    private final PluginExecutors executors;
    private final @Nullable Supplier<NetworkPolicy> policy;

    private final ConcurrentMap<UUID, GuiScreen> activeScreens = new ConcurrentHashMap<>();
    private @Nullable Function<Player, GuiScreen> mainMenuFactory;

    public MenuService(
            @Nullable PlayerWorldRepository worldRepository,
            @Nullable MembershipRepository membershipRepository,
            @Nullable TransferRequestRepository transferRepository,
            @Nullable WorldBanRepository banRepository,
            @Nullable PlayerNameRepository nameRepository,
            @Nullable MenuChannel channel,
            PluginExecutors executors,
            @Nullable Supplier<NetworkPolicy> policy) {
        this.worldRepository = worldRepository;
        this.membershipRepository = membershipRepository;
        this.transferRepository = transferRepository;
        this.banRepository = banRepository;
        this.nameRepository = nameRepository;
        this.channel = channel;
        this.executors = Objects.requireNonNull(executors, "executors");
        this.policy = policy;
    }

    public @Nullable PlayerWorldRepository worldRepository() {
        return worldRepository;
    }

    public @Nullable MembershipRepository membershipRepository() {
        return membershipRepository;
    }

    public @Nullable TransferRequestRepository transferRepository() {
        return transferRepository;
    }

    public @Nullable WorldBanRepository banRepository() {
        return banRepository;
    }

    public @Nullable PlayerNameRepository nameRepository() {
        return nameRepository;
    }

    public @Nullable MenuChannel channel() {
        return channel;
    }

    public PluginExecutors executors() {
        return executors;
    }

    public @Nullable Supplier<NetworkPolicy> policy() {
        return policy;
    }

    /** Configures an override factory used to produce the main menu screen (e.g. for tests). */
    public void setMainMenuFactory(@Nullable Function<Player, GuiScreen> factory) {
        this.mainMenuFactory = factory;
    }

    /**
     * Opens a specific {@link GuiScreen} for the player, ensuring rendering and opening occur on the main thread.
     *
     * @param player the player viewing the screen
     * @param screen the screen to open
     */
    public void openScreen(Player player, GuiScreen screen) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(screen, "screen");

        executors.main().execute(() -> {
            Inventory inventory = screen.render(player);
            player.openInventory(inventory);
            activeScreens.put(player.getUniqueId(), screen);
        });
    }

    /**
     * Retrieves the active screen currently open for the player, if any.
     *
     * @param player the player
     * @return Optional containing the active screen
     */
    public Optional<GuiScreen> activeScreen(Player player) {
        Objects.requireNonNull(player, "player");
        return Optional.ofNullable(activeScreens.get(player.getUniqueId()));
    }

    /**
     * Refreshes the currently active screen for the player on the main thread.
     *
     * @param player the player
     */
    public void refreshScreen(Player player) {
        Objects.requireNonNull(player, "player");
        executors.main().execute(() -> {
            GuiScreen screen = activeScreens.get(player.getUniqueId());
            if (screen != null) {
                screen.refresh(player);
            }
        });
    }

    /**
     * Opens the main menu for the player, loading database records off the main thread first (NFR-2).
     *
     * @param player the player
     * @return CompletableFuture completing when the screen is rendered and displayed
     */
    public CompletableFuture<Void> openMainMenu(Player player) {
        Objects.requireNonNull(player, "player");

        Function<Player, GuiScreen> factory = this.mainMenuFactory;
        if (factory != null) {
            return CompletableFuture.runAsync(
                            () -> {
                                MainThread.assertOff();
                                if (worldRepository != null) {
                                    try {
                                        worldRepository.listOwnedBy(player.getUniqueId());
                                    } catch (SQLException e) {
                                        log.warn("Failed to pre-fetch worlds for player {}", player.getUniqueId(), e);
                                    }
                                }
                            },
                            executors.db())
                    .thenAcceptAsync(
                            v -> {
                                GuiScreen screen = factory.apply(player);
                                openScreen(player, screen);
                            },
                            executors.main());
        }

        return CompletableFuture.supplyAsync(
                        () -> {
                            MainThread.assertOff();
                            int owned = 0;
                            int invites = 0;
                            long used = 0L;
                            try {
                                if (worldRepository != null) {
                                    owned = worldRepository.countOwnedBy(player.getUniqueId());
                                    used = worldRepository.totalStorageUsedBy(player.getUniqueId());
                                }
                                if (membershipRepository != null) {
                                    invites = membershipRepository
                                            .findLiveInvitesFor(player.getUniqueId())
                                            .size();
                                }
                            } catch (SQLException e) {
                                log.warn("Failed to fetch menu data for player {}", player.getUniqueId(), e);
                            }
                            NetworkPolicy pol = policy != null ? policy.get() : NetworkPolicy.defaults();
                            StorageQuota quota = StorageQuotaResolver.evaluate(
                                    player.getUniqueId(),
                                    used,
                                    player::hasPermission,
                                    pol.storageQuotaTiers(),
                                    pol.defaultStorageLimitBytes());
                            return new MainMenu.MainMenuData(owned, pol.maxWorldsPerPlayer(), invites, quota);
                        },
                        executors.db())
                .thenAcceptAsync(data -> openScreen(player, new MainMenu(this, data)), executors.main());
    }

    /**
     * Opens page 0 of the player's owned worlds menu.
     *
     * @param player the player
     * @return CompletableFuture completing when opened
     */
    public CompletableFuture<Void> openMyWorldsMenu(Player player) {
        return openMyWorldsMenu(player, 0);
    }

    /**
     * Opens a specific page of the player's owned worlds menu.
     *
     * @param player the player
     * @param page page index (0-based)
     * @return CompletableFuture completing when opened
     */
    public CompletableFuture<Void> openMyWorldsMenu(Player player, int page) {
        Objects.requireNonNull(player, "player");

        return CompletableFuture.supplyAsync(
                        () -> {
                            MainThread.assertOff();
                            List<PlayerWorld> worlds = List.of();
                            if (worldRepository != null) {
                                try {
                                    worlds = worldRepository.listOwnedBy(player.getUniqueId());
                                } catch (SQLException e) {
                                    log.warn("Failed to fetch owned worlds for player {}", player.getUniqueId(), e);
                                }
                            }
                            NetworkPolicy pol = policy != null ? policy.get() : NetworkPolicy.defaults();
                            return new MyWorldsData(worlds, pol.maxWorldsPerPlayer());
                        },
                        executors.db())
                .thenAcceptAsync(
                        data -> openScreen(
                                player, new MyWorldsMenu(this, channel, data.worlds(), page, data.maxWorlds())),
                        executors.main());
    }

    /**
     * Opens the single world management screen for a world.
     *
     * @param player the viewing player
     * @param worldId world ID
     * @return CompletableFuture completing when opened
     */
    public CompletableFuture<Void> openWorldMenu(Player player, WorldId worldId) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(worldId, "worldId");

        return CompletableFuture.supplyAsync(
                        () -> {
                            MainThread.assertOff();
                            if (worldRepository != null) {
                                try {
                                    return worldRepository.findById(worldId);
                                } catch (SQLException e) {
                                    log.warn(
                                            "Failed to fetch world {} for player {}", worldId, player.getUniqueId(), e);
                                }
                            }
                            return Optional.<PlayerWorld>empty();
                        },
                        executors.db())
                .thenAcceptAsync(
                        worldOpt -> {
                            if (worldOpt.isPresent()) {
                                openScreen(player, new WorldMenu(this, channel, worldOpt.get()));
                            } else {
                                player.sendMessage(Component.text("World not found", NamedTextColor.RED));
                                var _ = openMyWorldsMenu(player);
                            }
                        },
                        executors.main());
    }

    /**
     * Opens the storage usage breakdown screen for a player.
     *
     * @param player the player
     * @return CompletableFuture completing when opened
     */
    public CompletableFuture<Void> openStorageMenu(Player player) {
        Objects.requireNonNull(player, "player");

        return CompletableFuture.supplyAsync(
                        () -> {
                            MainThread.assertOff();
                            List<PlayerWorld> owned = List.of();
                            long used = 0L;
                            if (worldRepository != null) {
                                try {
                                    owned = worldRepository.listOwnedBy(player.getUniqueId());
                                    used = worldRepository.totalStorageUsedBy(player.getUniqueId());
                                } catch (SQLException e) {
                                    log.warn("Failed to fetch storage data for player {}", player.getUniqueId(), e);
                                }
                            }
                            NetworkPolicy pol = policy != null ? policy.get() : NetworkPolicy.defaults();
                            StorageQuota quota = StorageQuotaResolver.evaluate(
                                    player.getUniqueId(),
                                    used,
                                    player::hasPermission,
                                    pol.storageQuotaTiers(),
                                    pol.defaultStorageLimitBytes());
                            return new StorageMenuData(quota, owned);
                        },
                        executors.db())
                .thenAcceptAsync(
                        data -> openScreen(player, new StorageMenu(this, data.quota(), data.owned())),
                        executors.main());
    }

    /**
     * Opens a click-to-confirm modal screen.
     *
     * @param player the player
     * @param title title component
     * @param description description component
     * @param onConfirm action on confirm click
     * @param onCancel action on cancel click
     */
    public void openConfirmMenu(
            Player player, Component title, Component description, Runnable onConfirm, Runnable onCancel) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(onConfirm, "onConfirm");
        Objects.requireNonNull(onCancel, "onCancel");

        openScreen(player, new ConfirmMenu(title, description, onConfirm, onCancel));
    }

    /**
     * Opens page 0 of the members management screen for a world.
     *
     * @param player the player
     * @param worldId world ID
     * @return CompletableFuture completing when opened
     */
    public CompletableFuture<Void> openMembersMenu(Player player, WorldId worldId) {
        return openMembersMenu(player, worldId, 0);
    }

    /**
     * Opens a specific page of the members management screen for a world.
     *
     * @param player the player
     * @param worldId world ID
     * @param page 0-based page index
     * @return CompletableFuture completing when opened
     */
    public CompletableFuture<Void> openMembersMenu(Player player, WorldId worldId, int page) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(worldId, "worldId");

        return CompletableFuture.supplyAsync(
                        () -> {
                            MainThread.assertOff();
                            Optional<PlayerWorld> worldOpt = Optional.empty();
                            List<MembersMenu.MemberEntry> entries = List.of();
                            try {
                                if (worldRepository != null) {
                                    worldOpt = worldRepository.findById(worldId);
                                }
                                if (membershipRepository != null && nameRepository != null) {
                                    List<WorldMember> members = membershipRepository.listMembers(worldId);
                                    List<UUID> uuids = members.stream()
                                            .map(WorldMember::uuid)
                                            .toList();
                                    Map<UUID, String> names = nameRepository.namesOf(uuids);
                                    entries = members.stream()
                                            .map(m -> new MembersMenu.MemberEntry(
                                                    m.uuid(),
                                                    names.getOrDefault(
                                                            m.uuid(), m.uuid().toString()),
                                                    m.role(),
                                                    m.joinedAt()))
                                            .toList();
                                }
                            } catch (SQLException e) {
                                log.warn("Failed to fetch members for world {}", worldId, e);
                            }
                            return new MembersData(worldOpt, entries);
                        },
                        executors.db())
                .thenAcceptAsync(
                        data -> {
                            if (data.world().isPresent()) {
                                openScreen(
                                        player,
                                        new MembersMenu(
                                                this, channel, data.world().get(), data.entries(), page));
                            } else {
                                player.sendMessage(Component.text("World not found", NamedTextColor.RED));
                                var _ = openMyWorldsMenu(player);
                            }
                        },
                        executors.main());
    }

    /**
     * Opens the world settings configuration screen for a world.
     *
     * @param player the player
     * @param worldId world ID
     * @return CompletableFuture completing when opened
     */
    public CompletableFuture<Void> openSettingsMenu(Player player, WorldId worldId) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(worldId, "worldId");

        return CompletableFuture.supplyAsync(
                        () -> {
                            MainThread.assertOff();
                            Optional<PlayerWorld> worldOpt = Optional.empty();
                            if (worldRepository != null) {
                                try {
                                    worldOpt = worldRepository.findById(worldId);
                                } catch (SQLException e) {
                                    log.warn("Failed to fetch world {} for settings", worldId, e);
                                }
                            }
                            return worldOpt;
                        },
                        executors.db())
                .thenAcceptAsync(
                        worldOpt -> {
                            if (worldOpt.isPresent()) {
                                PlayerWorld world = worldOpt.get();
                                WorldSettings settings = WorldSettings.fromJson(world.settingsJson());
                                openScreen(player, new SettingsMenu(this, channel, world, settings));
                            } else {
                                player.sendMessage(Component.text("World not found", NamedTextColor.RED));
                                var _ = openMyWorldsMenu(player);
                            }
                        },
                        executors.main());
    }

    /**
     * Opens page 0 of the pending invites and transfer requests screen.
     *
     * @param player the player
     * @return CompletableFuture completing when opened
     */
    public CompletableFuture<Void> openInvitesMenu(Player player) {
        return openInvitesMenu(player, 0);
    }

    /**
     * Opens a specific page of the pending invites and transfer requests screen.
     *
     * @param player the player
     * @param page 0-based page index
     * @return CompletableFuture completing when opened
     */
    public CompletableFuture<Void> openInvitesMenu(Player player, int page) {
        Objects.requireNonNull(player, "player");

        return CompletableFuture.supplyAsync(
                        () -> {
                            MainThread.assertOff();
                            List<InvitesMenu.InviteEntry> entries = new ArrayList<>();
                            try {
                                List<WorldInvite> liveInvites = List.of();
                                if (membershipRepository != null) {
                                    liveInvites = membershipRepository.findLiveInvitesFor(player.getUniqueId());
                                }
                                List<TransferRequest> liveTransfers = List.of();
                                if (transferRepository != null) {
                                    liveTransfers = transferRepository.findLiveRequestsFor(player.getUniqueId());
                                }

                                List<UUID> senderUuids = new ArrayList<>();
                                for (WorldInvite invite : liveInvites) {
                                    senderUuids.add(invite.invitedBy());
                                }
                                for (TransferRequest req : liveTransfers) {
                                    senderUuids.add(req.fromUuid());
                                }

                                Map<UUID, String> names = (nameRepository != null && !senderUuids.isEmpty())
                                        ? nameRepository.namesOf(senderUuids)
                                        : Map.of();

                                for (WorldInvite invite : liveInvites) {
                                    String worldName = invite.worldId().toString();
                                    if (worldRepository != null) {
                                        Optional<PlayerWorld> w = worldRepository.findById(invite.worldId());
                                        if (w.isPresent()) {
                                            worldName = w.get().name();
                                        }
                                    }
                                    String senderName = names.getOrDefault(
                                            invite.invitedBy(),
                                            invite.invitedBy().toString());
                                    entries.add(new InvitesMenu.InviteEntry(
                                            invite.worldId(),
                                            worldName,
                                            invite.invitedBy(),
                                            senderName,
                                            invite.expiresAt(),
                                            false));
                                }

                                for (TransferRequest req : liveTransfers) {
                                    String worldName = req.worldId().toString();
                                    if (worldRepository != null) {
                                        Optional<PlayerWorld> w = worldRepository.findById(req.worldId());
                                        if (w.isPresent()) {
                                            worldName = w.get().name();
                                        }
                                    }
                                    String senderName = names.getOrDefault(
                                            req.fromUuid(), req.fromUuid().toString());
                                    entries.add(new InvitesMenu.InviteEntry(
                                            req.worldId(),
                                            worldName,
                                            req.fromUuid(),
                                            senderName,
                                            req.expiresAt(),
                                            true));
                                }
                            } catch (SQLException e) {
                                log.warn("Failed to fetch invites for player {}", player.getUniqueId(), e);
                            }
                            return List.copyOf(entries);
                        },
                        executors.db())
                .thenAcceptAsync(
                        entries -> openScreen(player, new InvitesMenu(this, channel, entries, page)), executors.main());
    }

    /**
     * Opens page 0 of the bans management screen for a world.
     *
     * @param player the player
     * @param worldId world ID
     * @return CompletableFuture completing when opened
     */
    public CompletableFuture<Void> openBansMenu(Player player, WorldId worldId) {
        return openBansMenu(player, worldId, 0);
    }

    /**
     * Opens a specific page of the bans management screen for a world.
     *
     * @param player the player
     * @param worldId world ID
     * @param page 0-based page index
     * @return CompletableFuture completing when opened
     */
    public CompletableFuture<Void> openBansMenu(Player player, WorldId worldId, int page) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(worldId, "worldId");

        return CompletableFuture.supplyAsync(
                        () -> {
                            MainThread.assertOff();
                            Optional<PlayerWorld> worldOpt = Optional.empty();
                            List<BansMenu.BanEntry> entries = List.of();
                            try {
                                if (worldRepository != null) {
                                    worldOpt = worldRepository.findById(worldId);
                                }
                                if (banRepository != null && nameRepository != null) {
                                    List<WorldBan> bans = banRepository.listBans(worldId);
                                    List<UUID> uuids =
                                            bans.stream().map(WorldBan::uuid).toList();
                                    Map<UUID, String> names = nameRepository.namesOf(uuids);
                                    entries = bans.stream()
                                            .map(b -> new BansMenu.BanEntry(
                                                    b.uuid(),
                                                    names.getOrDefault(
                                                            b.uuid(), b.uuid().toString()),
                                                    b.reason(),
                                                    b.bannedAt()))
                                            .toList();
                                }
                            } catch (SQLException e) {
                                log.warn("Failed to fetch bans for world {}", worldId, e);
                            }
                            return new BansData(worldOpt, entries);
                        },
                        executors.db())
                .thenAcceptAsync(
                        data -> {
                            if (data.world().isPresent()) {
                                openScreen(
                                        player,
                                        new BansMenu(this, channel, data.world().get(), data.entries(), page));
                            } else {
                                player.sendMessage(Component.text("World not found", NamedTextColor.RED));
                                var _ = openMyWorldsMenu(player);
                            }
                        },
                        executors.main());
    }

    /**
     * Opens page 0 of the public worlds browse screen.
     *
     * @param player the player
     * @return CompletableFuture completing when opened
     */
    public CompletableFuture<Void> openBrowseMenu(Player player) {
        return openBrowseMenu(player, 0);
    }

    /**
     * Opens a specific page of the public worlds browse screen.
     *
     * @param player the player
     * @param page 0-based page index
     * @return CompletableFuture completing when opened
     */
    public CompletableFuture<Void> openBrowseMenu(Player player, int page) {
        Objects.requireNonNull(player, "player");

        return CompletableFuture.supplyAsync(
                        () -> {
                            MainThread.assertOff();
                            List<BrowseMenu.PublicWorldEntry> entries = List.of();
                            try {
                                if (worldRepository != null) {
                                    List<PlayerWorld> publicWorlds = worldRepository.listPublicWorlds();
                                    if (!publicWorlds.isEmpty() && nameRepository != null) {
                                        List<UUID> ownerUuids = publicWorlds.stream()
                                                .map(PlayerWorld::ownerUuid)
                                                .distinct()
                                                .toList();
                                        Map<UUID, String> names = nameRepository.namesOf(ownerUuids);
                                        entries = publicWorlds.stream()
                                                .map(w -> new BrowseMenu.PublicWorldEntry(
                                                        w.id(),
                                                        w.name(),
                                                        w.ownerUuid(),
                                                        names.getOrDefault(
                                                                w.ownerUuid(),
                                                                w.ownerUuid().toString()),
                                                        w.description()))
                                                .toList();
                                    } else {
                                        entries = publicWorlds.stream()
                                                .map(w -> new BrowseMenu.PublicWorldEntry(
                                                        w.id(),
                                                        w.name(),
                                                        w.ownerUuid(),
                                                        w.ownerUuid().toString(),
                                                        w.description()))
                                                .toList();
                                    }
                                }
                            } catch (SQLException e) {
                                log.warn("Failed to fetch public worlds for browse menu", e);
                            }
                            return entries;
                        },
                        executors.db())
                .thenAcceptAsync(
                        entries -> openScreen(player, new BrowseMenu(this, channel, entries, page)), executors.main());
    }

    /** Cleans up active screen reference when a player closes an inventory. */
    public void handleClose(Player player) {
        Objects.requireNonNull(player, "player");
        activeScreens.remove(player.getUniqueId());
    }

    /** Cleans up active screen reference when a player quits the server. */
    public void handleQuit(Player player) {
        Objects.requireNonNull(player, "player");
        activeScreens.remove(player.getUniqueId());
    }

    private record MyWorldsData(List<PlayerWorld> worlds, int maxWorlds) {}

    private record StorageMenuData(StorageQuota quota, List<PlayerWorld> owned) {}

    private record MembersData(Optional<PlayerWorld> world, List<MembersMenu.MemberEntry> entries) {}

    private record BansData(Optional<PlayerWorld> world, List<BansMenu.BanEntry> entries) {}
}
