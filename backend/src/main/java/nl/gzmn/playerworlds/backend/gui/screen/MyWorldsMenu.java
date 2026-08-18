package nl.gzmn.playerworlds.backend.gui.screen;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import nl.gzmn.playerworlds.backend.gui.GuiScreen;
import nl.gzmn.playerworlds.backend.gui.ItemUtil;
import nl.gzmn.playerworlds.backend.gui.MenuChannel;
import nl.gzmn.playerworlds.backend.gui.MenuHolder;
import nl.gzmn.playerworlds.backend.gui.MenuService;
import nl.gzmn.playerworlds.core.config.StorageQuotaResolver;
import nl.gzmn.playerworlds.core.menu.MenuIntent;
import nl.gzmn.playerworlds.core.menu.MenuResult;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.WorldState;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.jspecify.annotations.Nullable;

/**
 * Paginated screen listing a player's owned worlds with quick actions (join, manage)
 * and world creation trigger.
 */
public final class MyWorldsMenu implements GuiScreen {

    public static final int PAGE_SIZE = 36;
    public static final int SLOT_PREVIOUS_PAGE = 45;
    public static final int SLOT_BACK = 48;
    public static final int SLOT_CREATE = 49;
    public static final int SLOT_NEXT_PAGE = 53;

    private final MenuService menuService;
    private final @Nullable MenuChannel menuChannel;
    private final List<PlayerWorld> worlds;
    private final int page;
    private final int maxWorlds;

    public MyWorldsMenu(
            MenuService menuService,
            @Nullable MenuChannel menuChannel,
            List<PlayerWorld> worlds,
            int page,
            int maxWorlds) {
        this.menuService = Objects.requireNonNull(menuService, "menuService");
        this.menuChannel = menuChannel;
        this.worlds = List.copyOf(Objects.requireNonNull(worlds, "worlds"));
        this.page = Math.max(0, page);
        this.maxWorlds = maxWorlds;
    }

    public List<PlayerWorld> worlds() {
        return worlds;
    }

    public int page() {
        return page;
    }

    public int maxWorlds() {
        return maxWorlds;
    }

    @Override
    public Inventory render(Player player) {
        Objects.requireNonNull(player, "player");
        MenuHolder holder = new MenuHolder(this);
        int totalPages = Math.max(1, (int) Math.ceil((double) worlds.size() / PAGE_SIZE));
        String titleText = "My Worlds (Page " + (page + 1) + "/" + totalPages + ")";
        Inventory inventory = Bukkit.createInventory(holder, 54, Component.text(titleText, NamedTextColor.DARK_GRAY));
        holder.setInventory(inventory);

        // Fill background
        for (int i = 0; i < 54; i++) {
            inventory.setItem(i, ItemUtil.filler());
        }

        // Render world items in slots 0..35
        int startIndex = page * PAGE_SIZE;
        int endIndex = Math.min(worlds.size(), startIndex + PAGE_SIZE);

        for (int i = startIndex; i < endIndex; i++) {
            int slot = i - startIndex;
            PlayerWorld world = worlds.get(i);
            inventory.setItem(slot, renderWorldItem(world));
        }

        // Divider row
        for (int i = 36; i < 45; i++) {
            inventory.setItem(i, ItemUtil.filler(Material.BLACK_STAINED_GLASS_PANE));
        }

        // Navigation bottom row
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

        inventory.setItem(
                SLOT_CREATE,
                ItemUtil.create(
                        Material.NETHER_STAR,
                        Component.text("Create New World", NamedTextColor.GREEN, TextDecoration.BOLD),
                        Component.text("Owned: " + worlds.size() + " / " + maxWorlds, NamedTextColor.GRAY),
                        Component.empty(),
                        Component.text("▶ Click to create a world", NamedTextColor.YELLOW)));

        if ((page + 1) * PAGE_SIZE < worlds.size()) {
            inventory.setItem(
                    SLOT_NEXT_PAGE,
                    ItemUtil.create(
                            Material.ARROW, Component.text("Next Page ▶", NamedTextColor.YELLOW, TextDecoration.BOLD)));
        }

        return inventory;
    }

    private org.bukkit.inventory.ItemStack renderWorldItem(PlayerWorld world) {
        Material material =
                switch (world.state()) {
                    case READY -> Material.GRASS_BLOCK;
                    case CREATING -> Material.OAK_SAPLING;
                    case ARCHIVED -> Material.CHEST;
                    case ARCHIVING, RESTORING -> Material.CLOCK;
                };

        Component name = Component.text(world.name(), NamedTextColor.AQUA, TextDecoration.BOLD);

        List<Component> lore = new java.util.ArrayList<>();
        lore.add(Component.text("State: " + world.state().name(), stateColor(world.state())));
        lore.add(Component.text("Visibility: " + world.visibility().name(), NamedTextColor.GRAY));
        lore.add(
                Component.text("Size: " + StorageQuotaResolver.formatBytes(world.storageBytes()), NamedTextColor.GRAY));
        lore.add(Component.text("Border: ±" + world.borderRadius() + "m", NamedTextColor.DARK_GRAY));
        lore.add(Component.empty());

        if (world.state() == WorldState.READY) {
            lore.add(Component.text("▶ Left-Click: Join World", NamedTextColor.GREEN));
            lore.add(Component.text("▶ Right-Click: Manage World", NamedTextColor.YELLOW));
        } else {
            lore.add(Component.text("▶ Right-Click: Manage World", NamedTextColor.YELLOW));
        }

        return ItemUtil.create(material, name, lore);
    }

    private static TextColor stateColor(WorldState state) {
        return switch (state) {
            case READY -> NamedTextColor.GREEN;
            case CREATING -> NamedTextColor.YELLOW;
            case ARCHIVED -> NamedTextColor.GRAY;
            case ARCHIVING, RESTORING -> NamedTextColor.GOLD;
        };
    }

    @Override
    public void handleClick(Player player, int slot, ClickType clickType) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(clickType, "clickType");

        if (slot >= 0 && slot < PAGE_SIZE) {
            int index = page * PAGE_SIZE + slot;
            if (index < worlds.size()) {
                PlayerWorld world = worlds.get(index);
                if (clickType.isRightClick()) {
                    var _ = menuService.openWorldMenu(player, world.id());
                } else if (clickType.isLeftClick()) {
                    if (world.state() == WorldState.READY) {
                        if (menuChannel != null) {
                            var _ = menuChannel
                                    .sendIntent(player, new MenuIntent.JoinWorld(world.id()))
                                    .whenComplete((result, ex) -> {
                                        if (result instanceof MenuResult.Failed failed) {
                                            player.sendMessage(Component.text(
                                                    "Could not join world: " + failed.message(), NamedTextColor.RED));
                                            var _ = menuService.openMyWorldsMenu(player, page);
                                        }
                                    });
                        }
                    } else {
                        var _ = menuService.openWorldMenu(player, world.id());
                    }
                }
            }
            return;
        }

        if (slot == SLOT_PREVIOUS_PAGE && page > 0) {
            var _ = menuService.openMyWorldsMenu(player, page - 1);
        } else if (slot == SLOT_BACK) {
            var _ = menuService.openMainMenu(player);
        } else if (slot == SLOT_CREATE) {
            if (menuChannel != null) {
                String defaultName = player.getName().toLowerCase(Locale.ROOT) + "-" + (worlds.size() + 1);
                var _ = menuChannel
                        .sendIntent(player, new MenuIntent.CreateWorld(defaultName, null))
                        .whenComplete((result, ex) -> {
                            if (result instanceof MenuResult.Failed failed) {
                                player.sendMessage(Component.text(
                                        "Could not create world: " + failed.message(), NamedTextColor.RED));
                                var _ = menuService.openMyWorldsMenu(player, page);
                            }
                        });
            }
        } else if (slot == SLOT_NEXT_PAGE && (page + 1) * PAGE_SIZE < worlds.size()) {
            var _ = menuService.openMyWorldsMenu(player, page + 1);
        }
    }

    @Override
    public void refresh(Player player) {
        Objects.requireNonNull(player, "player");
        var _ = menuService.openMyWorldsMenu(player, page);
    }
}
