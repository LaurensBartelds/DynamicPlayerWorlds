package nl.gzmn.playerworlds.backend.gui;

import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import nl.gzmn.playerworlds.core.concurrent.MainThread;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.db.MembershipRepository;
import nl.gzmn.playerworlds.core.db.PlayerNameRepository;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository;
import nl.gzmn.playerworlds.core.db.TransferRequestRepository;
import nl.gzmn.playerworlds.core.db.WorldBanRepository;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
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
    private Function<Player, GuiScreen> mainMenuFactory = p -> new PlaceholderMainMenu();

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

    /** Configures the factory used to produce the main menu screen. */
    public void setMainMenuFactory(Function<Player, GuiScreen> factory) {
        this.mainMenuFactory = Objects.requireNonNull(factory, "factory");
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

        return CompletableFuture.runAsync(
                        () -> {
                            MainThread.assertOff();
                            if (worldRepository != null) {
                                try {
                                    // Pre-load worlds for owner
                                    worldRepository.listOwnedBy(player.getUniqueId());
                                } catch (SQLException e) {
                                    log.warn("Failed to pre-fetch worlds for player {}", player.getUniqueId(), e);
                                }
                            }
                        },
                        executors.db())
                .thenAcceptAsync(
                        v -> {
                            GuiScreen screen = mainMenuFactory.apply(player);
                            openScreen(player, screen);
                        },
                        executors.main());
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

    private static final class PlaceholderMainMenu implements GuiScreen {

        @Override
        public Inventory render(Player player) {
            MenuHolder holder = new MenuHolder(this);
            Inventory inventory = Bukkit.createInventory(holder, 27, Component.text("Worlds Menu"));
            holder.setInventory(inventory);
            return inventory;
        }

        @Override
        public void handleClick(Player player, int slot, ClickType clickType) {
            // Placeholder: no-op
        }

        @Override
        public void refresh(Player player) {
            // Placeholder: no-op
        }
    }
}
