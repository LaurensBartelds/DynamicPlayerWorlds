package nl.gzmn.playerworlds.backend.gui.screen;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import nl.gzmn.playerworlds.backend.gui.GuiScreen;
import nl.gzmn.playerworlds.backend.gui.ItemUtil;
import nl.gzmn.playerworlds.backend.gui.MenuHolder;
import nl.gzmn.playerworlds.backend.gui.MenuService;
import nl.gzmn.playerworlds.backend.gui.Placeholders;
import nl.gzmn.playerworlds.core.config.StorageQuotaResolver;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.StorageQuota;
import nl.gzmn.playerworlds.core.model.WorldState;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

/**
 * Storage usage screen displaying a player's quota limit, current usage, and per-world breakdown.
 */
public final class StorageMenu implements GuiScreen {

    public static final int SLOT_OVERVIEW = 4;
    public static final int WORLDS_START_SLOT = 9;
    public static final int WORLDS_END_SLOT = 26;
    public static final int SLOT_BACK = 31;

    private final MenuService menuService;
    private final StorageQuota quota;
    private final List<PlayerWorld> ownedWorlds;

    public StorageMenu(MenuService menuService, StorageQuota quota, List<PlayerWorld> ownedWorlds) {
        this.menuService = Objects.requireNonNull(menuService, "menuService");
        this.quota = Objects.requireNonNull(quota, "quota");
        this.ownedWorlds = List.copyOf(Objects.requireNonNull(ownedWorlds, "ownedWorlds"));
    }

    public StorageQuota quota() {
        return quota;
    }

    public List<PlayerWorld> ownedWorlds() {
        return ownedWorlds;
    }

    @Override
    public Inventory render(Player player) {
        Objects.requireNonNull(player, "player");
        var messages = menuService.messages();
        MenuHolder holder = new MenuHolder(this);
        Inventory inventory = Bukkit.createInventory(holder, 36, messages.render("messages.gui.storage-menu.title"));
        holder.setInventory(inventory);

        for (int i = 0; i < 36; i++) {
            inventory.setItem(i, ItemUtil.filler());
        }

        // Slot 4: Overview
        inventory.setItem(
                SLOT_OVERVIEW,
                ItemUtil.create(
                        Material.ENDER_CHEST,
                        messages.render("messages.gui.storage-menu.item.overview.name"),
                        messages.renderLore(
                                "messages.gui.storage-menu.item.overview.lore",
                                Placeholders.bytes("used", quota.usedBytes()),
                                Placeholders.raw(
                                        "limit",
                                        quota.unlimited()
                                                ? "Unlimited"
                                                : StorageQuotaResolver.formatBytes(quota.limitBytes())),
                                Placeholders.raw(
                                        "usage",
                                        quota.unlimited()
                                                ? "Unlimited"
                                                : String.format(Locale.ROOT, "%.1f%%", quota.percentage())),
                                Placeholders.raw("bar", renderProgressBar(quota.percentage())))));

        // Middle slots: owned worlds
        int maxItems = Math.min(ownedWorlds.size(), WORLDS_END_SLOT - WORLDS_START_SLOT + 1);
        for (int i = 0; i < maxItems; i++) {
            PlayerWorld world = ownedWorlds.get(i);
            int slot = WORLDS_START_SLOT + i;
            Material mat = (world.state() == WorldState.ARCHIVED) ? Material.CHEST : Material.GRASS_BLOCK;

            inventory.setItem(
                    slot,
                    ItemUtil.create(
                            mat,
                            messages.render(
                                    "messages.gui.storage-menu.item.world-entry.name",
                                    Placeholders.text("world", world.name())),
                            messages.renderLore(
                                    "messages.gui.storage-menu.item.world-entry.lore",
                                    Placeholders.bytes("size", world.storageBytes()),
                                    Placeholders.raw("state", world.state().name()))));
        }

        // Slot 31: Back
        inventory.setItem(
                SLOT_BACK,
                ItemUtil.create(
                        Material.OAK_DOOR,
                        messages.render("messages.gui.storage-menu.item.back.name"),
                        messages.renderLore("messages.gui.storage-menu.item.back.lore")));

        return inventory;
    }

    private static String renderProgressBar(double percentage) {
        int filled = (int) Math.round(percentage / 10.0);
        StringBuilder bar = new StringBuilder(12).append('[');
        for (int i = 0; i < 10; i++) {
            bar.append(i < filled ? '|' : '.');
        }
        return bar.append(']').toString();
    }

    @Override
    public void handleClick(Player player, int slot, ClickType clickType) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(clickType, "clickType");

        if (slot >= WORLDS_START_SLOT && slot <= WORLDS_END_SLOT) {
            int index = slot - WORLDS_START_SLOT;
            if (index < ownedWorlds.size()) {
                var _ = menuService.openWorldMenu(player, ownedWorlds.get(index).id());
            }
            return;
        }

        if (slot == SLOT_BACK) {
            var _ = menuService.openMainMenu(player);
        }
    }

    @Override
    public void refresh(Player player) {
        Objects.requireNonNull(player, "player");
        var _ = menuService.openStorageMenu(player);
    }
}
