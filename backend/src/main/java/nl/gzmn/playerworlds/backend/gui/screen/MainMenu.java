package nl.gzmn.playerworlds.backend.gui.screen;

import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import nl.gzmn.playerworlds.backend.gui.GuiScreen;
import nl.gzmn.playerworlds.backend.gui.ItemUtil;
import nl.gzmn.playerworlds.backend.gui.MenuHolder;
import nl.gzmn.playerworlds.backend.gui.MenuService;
import nl.gzmn.playerworlds.core.config.StorageQuotaResolver;
import nl.gzmn.playerworlds.core.model.StorageQuota;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

/**
 * Main hub screen displaying navigation options for owned worlds, pending invites,
 * public world browsing, and storage quota summary.
 */
public final class MainMenu implements GuiScreen {

    public static final int SLOT_MY_WORLDS = 10;
    public static final int SLOT_STORAGE = 12;
    public static final int SLOT_INVITES = 14;
    public static final int SLOT_BROWSE = 16;
    public static final int SLOT_CLOSE = 22;

    private final MenuService menuService;
    private final MainMenuData data;

    /**
     * View data record passed to {@link MainMenu}.
     */
    public record MainMenuData(
            int ownedWorldsCount, int maxWorlds, int pendingInvitesCount, StorageQuota storageQuota) {
        public MainMenuData {
            Objects.requireNonNull(storageQuota, "storageQuota");
        }
    }

    public MainMenu(MenuService menuService, MainMenuData data) {
        this.menuService = Objects.requireNonNull(menuService, "menuService");
        this.data = Objects.requireNonNull(data, "data");
    }

    public MainMenuData data() {
        return data;
    }

    @Override
    public Inventory render(Player player) {
        Objects.requireNonNull(player, "player");
        MenuHolder holder = new MenuHolder(this);
        Inventory inventory =
                Bukkit.createInventory(holder, 27, Component.text("Dynamic Player Worlds", NamedTextColor.DARK_GRAY));
        holder.setInventory(inventory);

        for (int i = 0; i < 27; i++) {
            inventory.setItem(i, ItemUtil.filler());
        }

        inventory.setItem(
                SLOT_MY_WORLDS,
                ItemUtil.create(
                        Material.GRASS_BLOCK,
                        Component.text("My Worlds", NamedTextColor.GREEN, TextDecoration.BOLD),
                        Component.text("View and manage your worlds", NamedTextColor.GRAY),
                        Component.text(
                                "Owned: " + data.ownedWorldsCount() + " / " + data.maxWorlds(),
                                NamedTextColor.DARK_GRAY),
                        Component.empty(),
                        Component.text("▶ Click to view", NamedTextColor.YELLOW)));

        inventory.setItem(
                SLOT_STORAGE,
                ItemUtil.create(
                        Material.CHEST,
                        Component.text("Storage Usage", NamedTextColor.AQUA, TextDecoration.BOLD),
                        Component.text(
                                "Used: "
                                        + StorageQuotaResolver.formatBytes(
                                                data.storageQuota().usedBytes()),
                                NamedTextColor.GRAY),
                        Component.text(
                                "Limit: "
                                        + (data.storageQuota().unlimited()
                                                ? "Unlimited"
                                                : StorageQuotaResolver.formatBytes(
                                                        data.storageQuota().limitBytes())),
                                NamedTextColor.GRAY),
                        Component.empty(),
                        Component.text("▶ Click to view breakdown", NamedTextColor.YELLOW)));

        inventory.setItem(
                SLOT_INVITES,
                ItemUtil.create(
                        Material.WRITABLE_BOOK,
                        Component.text("Pending Invites", NamedTextColor.GOLD, TextDecoration.BOLD),
                        Component.text("Pending: " + data.pendingInvitesCount(), NamedTextColor.GRAY),
                        Component.empty(),
                        Component.text("▶ Click to view invites", NamedTextColor.YELLOW)));

        inventory.setItem(
                SLOT_BROWSE,
                ItemUtil.create(
                        Material.COMPASS,
                        Component.text("Browse Public Worlds", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD),
                        Component.text("Explore worlds shared by the community", NamedTextColor.GRAY),
                        Component.empty(),
                        Component.text("▶ Click to browse", NamedTextColor.YELLOW)));

        inventory.setItem(
                SLOT_CLOSE,
                ItemUtil.create(
                        Material.BARRIER,
                        Component.text("Close Menu", NamedTextColor.RED, TextDecoration.BOLD),
                        Component.text("▶ Click to exit", NamedTextColor.DARK_GRAY)));

        return inventory;
    }

    @Override
    public void handleClick(Player player, int slot, ClickType clickType) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(clickType, "clickType");

        switch (slot) {
            case SLOT_MY_WORLDS -> {
                var _ = menuService.openMyWorldsMenu(player);
            }
            case SLOT_STORAGE -> {
                var _ = menuService.openStorageMenu(player);
            }
            case SLOT_INVITES -> {
                var _ = menuService.openInvitesMenu(player);
            }
            case SLOT_BROWSE -> menuService.openBrowseMenu(player);
            case SLOT_CLOSE -> player.closeInventory();
            default -> {
                // Non-clickable filler
            }
        }
    }

    @Override
    public void refresh(Player player) {
        Objects.requireNonNull(player, "player");
        var _ = menuService.openMainMenu(player);
    }
}
