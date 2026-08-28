package nl.gzmn.playerworlds.backend.gui.screen;

import java.util.Objects;
import nl.gzmn.playerworlds.backend.gui.GuiScreen;
import nl.gzmn.playerworlds.backend.gui.ItemUtil;
import nl.gzmn.playerworlds.backend.gui.MenuHolder;
import nl.gzmn.playerworlds.backend.gui.MenuService;
import nl.gzmn.playerworlds.backend.gui.Placeholders;
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
        var messages = menuService.messages();
        MenuHolder holder = new MenuHolder(this);
        Inventory inventory = Bukkit.createInventory(holder, 27, messages.render("messages.gui.main-menu.title"));
        holder.setInventory(inventory);

        for (int i = 0; i < 27; i++) {
            inventory.setItem(i, ItemUtil.filler());
        }

        inventory.setItem(
                SLOT_MY_WORLDS,
                ItemUtil.create(
                        Material.GRASS_BLOCK,
                        messages.render("messages.gui.main-menu.item.my-worlds.name"),
                        messages.renderLore(
                                "messages.gui.main-menu.item.my-worlds.lore",
                                Placeholders.count("owned", data.ownedWorldsCount()),
                                Placeholders.count("max", data.maxWorlds()))));

        inventory.setItem(
                SLOT_STORAGE,
                ItemUtil.create(
                        Material.CHEST,
                        messages.render("messages.gui.main-menu.item.storage.name"),
                        messages.renderLore(
                                "messages.gui.main-menu.item.storage.lore",
                                Placeholders.bytes("used", data.storageQuota().usedBytes()),
                                Placeholders.raw(
                                        "limit",
                                        data.storageQuota().unlimited()
                                                ? "Unlimited"
                                                : StorageQuotaResolver.formatBytes(
                                                        data.storageQuota().limitBytes())))));

        inventory.setItem(
                SLOT_INVITES,
                ItemUtil.create(
                        Material.WRITABLE_BOOK,
                        messages.render("messages.gui.main-menu.item.invites.name"),
                        messages.renderLore(
                                "messages.gui.main-menu.item.invites.lore",
                                Placeholders.count("count", data.pendingInvitesCount()))));

        inventory.setItem(
                SLOT_BROWSE,
                ItemUtil.create(
                        Material.COMPASS,
                        messages.render("messages.gui.main-menu.item.browse.name"),
                        messages.renderLore("messages.gui.main-menu.item.browse.lore")));

        inventory.setItem(
                SLOT_CLOSE,
                ItemUtil.create(
                        Material.BARRIER,
                        messages.render("messages.gui.main-menu.item.close.name"),
                        messages.renderLore("messages.gui.main-menu.item.close.lore")));

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
            case SLOT_BROWSE -> {
                var _ = menuService.openBrowseMenu(player);
            }
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
