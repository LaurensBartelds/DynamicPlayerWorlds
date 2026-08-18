package nl.gzmn.playerworlds.backend.gui;

import java.sql.SQLException;
import java.util.List;
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
import nl.gzmn.playerworlds.backend.gui.screen.ConfirmMenu;
import nl.gzmn.playerworlds.backend.gui.screen.MainMenu;
import nl.gzmn.playerworlds.backend.gui.screen.MyWorldsMenu;
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
import nl.gzmn.playerworlds.core.model.WorldId;
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

    /** Placeholder for members screen (Task 7). */
    public void openMembersMenu(Player player, WorldId worldId) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(worldId, "worldId");
        player.sendMessage(Component.text("Members menu coming soon...", NamedTextColor.GRAY));
    }

    /** Placeholder for settings screen (Task 7). */
    public void openSettingsMenu(Player player, WorldId worldId) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(worldId, "worldId");
        player.sendMessage(Component.text("Settings menu coming soon...", NamedTextColor.GRAY));
    }

    /** Placeholder for invites screen (Task 7). */
    public void openInvitesMenu(Player player) {
        Objects.requireNonNull(player, "player");
        player.sendMessage(Component.text("Invites menu coming soon...", NamedTextColor.GRAY));
    }

    /** Placeholder for bans screen (Task 7). */
    public void openBansMenu(Player player, WorldId worldId) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(worldId, "worldId");
        player.sendMessage(Component.text("Bans menu coming soon...", NamedTextColor.GRAY));
    }

    /** Placeholder for public browse screen (Task 8). */
    public void openBrowseMenu(Player player) {
        Objects.requireNonNull(player, "player");
        player.sendMessage(Component.text("Browse menu coming soon...", NamedTextColor.GRAY));
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
}
