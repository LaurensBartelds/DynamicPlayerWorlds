package nl.gzmn.playerworlds.backend.gui.screen;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import nl.gzmn.playerworlds.backend.gui.GuiScreen;
import nl.gzmn.playerworlds.backend.gui.ItemUtil;
import nl.gzmn.playerworlds.backend.gui.MenuChannel;
import nl.gzmn.playerworlds.backend.gui.MenuHolder;
import nl.gzmn.playerworlds.backend.gui.MenuService;
import nl.gzmn.playerworlds.core.menu.MenuIntent;
import nl.gzmn.playerworlds.core.menu.MenuResult;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.jspecify.annotations.Nullable;

/**
 * Paginated screen displaying public worlds across the network with owner names,
 * descriptions, and click-to-join actions.
 */
public final class BrowseMenu implements GuiScreen {

    public static final int PAGE_SIZE = 36;
    public static final int SLOT_PREVIOUS_PAGE = 45;
    public static final int SLOT_BACK = 48;
    public static final int SLOT_NEXT_PAGE = 53;

    private final MenuService menuService;
    private final @Nullable MenuChannel menuChannel;
    private final List<PublicWorldEntry> worlds;
    private final int page;

    /**
     * View data record representing a public world entry in the browse menu.
     */
    public record PublicWorldEntry(
            WorldId worldId,
            String worldName,
            UUID ownerUuid,
            String ownerName,
            @Nullable String description) {
        public PublicWorldEntry {
            Objects.requireNonNull(worldId, "worldId");
            Objects.requireNonNull(worldName, "worldName");
            Objects.requireNonNull(ownerUuid, "ownerUuid");
            Objects.requireNonNull(ownerName, "ownerName");
        }
    }

    public BrowseMenu(
            MenuService menuService, @Nullable MenuChannel menuChannel, List<PublicWorldEntry> worlds, int page) {
        this.menuService = Objects.requireNonNull(menuService, "menuService");
        this.menuChannel = menuChannel;
        this.worlds = List.copyOf(Objects.requireNonNull(worlds, "worlds"));
        this.page = Math.max(0, page);
    }

    public List<PublicWorldEntry> worlds() {
        return worlds;
    }

    public int page() {
        return page;
    }

    @Override
    public Inventory render(Player player) {
        Objects.requireNonNull(player, "player");
        MenuHolder holder = new MenuHolder(this);
        int totalPages = Math.max(1, (int) Math.ceil((double) worlds.size() / PAGE_SIZE));
        String titleText = "Public Worlds (Page " + (page + 1) + "/" + totalPages + ")";
        Inventory inventory = Bukkit.createInventory(holder, 54, Component.text(titleText, NamedTextColor.DARK_GRAY));
        holder.setInventory(inventory);

        for (int i = 0; i < 54; i++) {
            inventory.setItem(i, ItemUtil.filler());
        }

        if (worlds.isEmpty()) {
            inventory.setItem(
                    22,
                    ItemUtil.create(
                            Material.COMPASS,
                            Component.text("No Public Worlds", NamedTextColor.YELLOW, TextDecoration.BOLD),
                            Component.text("There are no public worlds currently available.", NamedTextColor.GRAY)));
        } else {
            int startIndex = page * PAGE_SIZE;
            int endIndex = Math.min(worlds.size(), startIndex + PAGE_SIZE);

            for (int i = startIndex; i < endIndex; i++) {
                int slot = i - startIndex;
                PublicWorldEntry entry = worlds.get(i);
                inventory.setItem(slot, renderWorldItem(entry));
            }
        }

        for (int i = 36; i < 45; i++) {
            inventory.setItem(i, ItemUtil.filler(Material.BLACK_STAINED_GLASS_PANE));
        }

        if (page > 0) {
            inventory.setItem(
                    SLOT_PREVIOUS_PAGE,
                    ItemUtil.create(
                            Material.ARROW,
                            Component.text("◀ Previous Page", NamedTextColor.YELLOW, TextDecoration.BOLD)));
        }

        inventory.setItem(
                SLOT_BACK,
                ItemUtil.create(
                        Material.OAK_DOOR,
                        Component.text("Back to Main Menu", NamedTextColor.RED, TextDecoration.BOLD),
                        Component.text("▶ Click to return", NamedTextColor.DARK_GRAY)));

        if ((page + 1) * PAGE_SIZE < worlds.size()) {
            inventory.setItem(
                    SLOT_NEXT_PAGE,
                    ItemUtil.create(
                            Material.ARROW, Component.text("Next Page ▶", NamedTextColor.YELLOW, TextDecoration.BOLD)));
        }

        return inventory;
    }

    private org.bukkit.inventory.ItemStack renderWorldItem(PublicWorldEntry entry) {
        Component name = Component.text(entry.worldName(), NamedTextColor.AQUA, TextDecoration.BOLD);

        List<Component> lore = new java.util.ArrayList<>();
        lore.add(Component.text("Owner: " + entry.ownerName(), NamedTextColor.YELLOW));
        if (entry.description() != null && !entry.description().isBlank()) {
            lore.add(Component.text(entry.description(), NamedTextColor.GRAY));
        }
        lore.add(Component.empty());
        lore.add(Component.text("▶ Click to Join World", NamedTextColor.GREEN));

        return ItemUtil.create(Material.GRASS_BLOCK, name, lore);
    }

    @Override
    public void handleClick(Player player, int slot, ClickType clickType) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(clickType, "clickType");

        if (slot >= 0 && slot < PAGE_SIZE) {
            int index = page * PAGE_SIZE + slot;
            if (index < worlds.size()) {
                PublicWorldEntry entry = worlds.get(index);
                if (menuChannel != null) {
                    var _ = menuChannel
                            .sendIntent(player, new MenuIntent.JoinWorld(entry.worldId()))
                            .whenComplete((result, ex) -> {
                                if (result instanceof MenuResult.Failed failed) {
                                    player.sendMessage(Component.text(
                                            "Could not join world: " + failed.message(), NamedTextColor.RED));
                                    var _ = menuService.openBrowseMenu(player, page);
                                }
                            });
                }
            }
            return;
        }

        if (slot == SLOT_PREVIOUS_PAGE && page > 0) {
            var _ = menuService.openBrowseMenu(player, page - 1);
        } else if (slot == SLOT_BACK) {
            var _ = menuService.openMainMenu(player);
        } else if (slot == SLOT_NEXT_PAGE && (page + 1) * PAGE_SIZE < worlds.size()) {
            var _ = menuService.openBrowseMenu(player, page + 1);
        }
    }

    @Override
    public void refresh(Player player) {
        Objects.requireNonNull(player, "player");
        var _ = menuService.openBrowseMenu(player, page);
    }
}
