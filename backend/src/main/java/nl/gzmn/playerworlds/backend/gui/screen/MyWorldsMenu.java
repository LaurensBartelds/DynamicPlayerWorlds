package nl.gzmn.playerworlds.backend.gui.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import nl.gzmn.playerworlds.backend.gui.GuiScreen;
import nl.gzmn.playerworlds.backend.gui.ItemUtil;
import nl.gzmn.playerworlds.backend.gui.MenuChannel;
import nl.gzmn.playerworlds.backend.gui.MenuHolder;
import nl.gzmn.playerworlds.backend.gui.MenuService;
import nl.gzmn.playerworlds.backend.gui.Messages;
import nl.gzmn.playerworlds.backend.gui.Placeholders;
import nl.gzmn.playerworlds.core.menu.MenuIntent;
import nl.gzmn.playerworlds.core.menu.MenuResult;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.Role;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.model.WorldState;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.jspecify.annotations.Nullable;

/**
 * Paginated screen listing the worlds a player can reach, with quick actions
 * (join, manage) and the world creation trigger.
 *
 * <p>Owned worlds come first, then the ones an accepted invite made reachable
 * (FR-7). Without the second list a member's only route back to a world is
 * remembering its owner's name, which is the problem the invite solved once
 * already. The create button still counts owned worlds alone: that is the
 * number FR-1's cap is about.
 */
public final class MyWorldsMenu implements GuiScreen {

    public static final int PAGE_SIZE = 36;
    public static final int SLOT_PREVIOUS_PAGE = 45;
    public static final int SLOT_BACK = 48;
    public static final int SLOT_CREATE = 49;
    public static final int SLOT_NEXT_PAGE = 53;

    private final MenuService menuService;
    private final @Nullable MenuChannel menuChannel;
    private final List<PlayerWorld> owned;
    private final List<PlayerWorld> shared;
    private final Map<WorldId, Role> sharedRoles;
    private final List<PlayerWorld> worlds;
    private final int page;
    private final int maxWorlds;

    public MyWorldsMenu(
            MenuService menuService,
            @Nullable MenuChannel menuChannel,
            List<PlayerWorld> worlds,
            int page,
            int maxWorlds) {
        this(menuService, menuChannel, worlds, List.of(), Map.of(), page, maxWorlds);
    }

    public MyWorldsMenu(
            MenuService menuService,
            @Nullable MenuChannel menuChannel,
            List<PlayerWorld> owned,
            List<PlayerWorld> shared,
            Map<WorldId, Role> sharedRoles,
            int page,
            int maxWorlds) {
        this.menuService = Objects.requireNonNull(menuService, "menuService");
        this.menuChannel = menuChannel;
        this.owned = List.copyOf(Objects.requireNonNull(owned, "owned"));
        this.shared = List.copyOf(Objects.requireNonNull(shared, "shared"));
        this.sharedRoles = Map.copyOf(Objects.requireNonNull(sharedRoles, "sharedRoles"));
        List<PlayerWorld> all = new ArrayList<>(this.owned.size() + this.shared.size());
        all.addAll(this.owned);
        all.addAll(this.shared);
        this.worlds = List.copyOf(all);
        this.page = Math.max(0, page);
        this.maxWorlds = maxWorlds;
    }

    /** Owned worlds first, then shared ones, in the order the slots use. */
    public List<PlayerWorld> worlds() {
        return worlds;
    }

    /** Worlds whose {@code owner_uuid} is the viewing player (FR-31a). */
    public List<PlayerWorld> owned() {
        return owned;
    }

    /** Worlds the player is a member of but does not own (FR-7). */
    public List<PlayerWorld> shared() {
        return shared;
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
        var messages = menuService.messages();
        MenuHolder holder = new MenuHolder(this);
        int totalPages = Math.max(1, (int) Math.ceil((double) worlds.size() / PAGE_SIZE));
        Inventory inventory = Bukkit.createInventory(
                holder,
                54,
                messages.render(
                        "messages.gui.my-worlds-menu.title",
                        Placeholders.count("page", page + 1),
                        Placeholders.count("pages", totalPages)));
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
            boolean isOwned = i < owned.size();
            inventory.setItem(
                    slot, renderWorldItem(world, isOwned, isOwned ? Role.OWNER : sharedRoles.get(world.id())));
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
                            Material.ARROW, messages.render("messages.gui.my-worlds-menu.item.previous-page.name")));
        }

        inventory.setItem(
                SLOT_BACK,
                ItemUtil.create(
                        Material.OAK_DOOR,
                        messages.render("messages.gui.my-worlds-menu.item.back.name"),
                        messages.renderLore("messages.gui.my-worlds-menu.item.back.lore")));

        List<Component> createLore = new ArrayList<>();
        createLore.add(messages.render(
                "messages.gui.my-worlds-menu.item.create.owned-line",
                Placeholders.count("owned", owned.size()),
                Placeholders.count("max", maxWorlds)));
        if (!shared.isEmpty()) {
            createLore.add(messages.render(
                    "messages.gui.my-worlds-menu.item.create.shared-line", Placeholders.count("count", shared.size())));
        }
        createLore.add(Component.empty());
        createLore.add(messages.render("messages.gui.my-worlds-menu.item.create.hint"));
        inventory.setItem(
                SLOT_CREATE,
                ItemUtil.create(
                        Material.NETHER_STAR,
                        messages.render("messages.gui.my-worlds-menu.item.create.name"),
                        createLore));

        if ((page + 1) * PAGE_SIZE < worlds.size()) {
            inventory.setItem(
                    SLOT_NEXT_PAGE,
                    ItemUtil.create(
                            Material.ARROW, messages.render("messages.gui.my-worlds-menu.item.next-page.name")));
        }

        return inventory;
    }

    private org.bukkit.inventory.ItemStack renderWorldItem(PlayerWorld world, boolean owned, @Nullable Role role) {
        Messages messages = menuService.messages();
        Material material =
                switch (world.state()) {
                    case READY -> owned ? Material.GRASS_BLOCK : Material.PLAYER_HEAD;
                    case CREATING -> Material.OAK_SAPLING;
                    case ARCHIVED -> Material.CHEST;
                    case ARCHIVING, RESTORING -> Material.CLOCK;
                };

        Component nameValue = Component.text(
                world.name(), owned ? NamedTextColor.AQUA : NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD);
        Component name = messages.render(
                "messages.gui.my-worlds-menu.item.world-entry.name", Placeholders.component("world", nameValue));

        List<Component> lore = new ArrayList<>();
        Component stateValue = Component.text("State: " + world.state().name(), stateColor(world.state()));
        lore.add(messages.render(
                "messages.gui.my-worlds-menu.item.world-entry.state-line",
                Placeholders.component("state", stateValue)));
        if (!owned) {
            lore.add(messages.render(
                    "messages.gui.my-worlds-menu.item.world-entry.shared-note",
                    Placeholders.raw("role-suffix", role == null ? "" : " — " + role.name())));
        }
        lore.add(messages.render(
                "messages.gui.my-worlds-menu.item.world-entry.visibility-line",
                Placeholders.raw("visibility", world.visibility().name())));
        lore.add(messages.render(
                "messages.gui.my-worlds-menu.item.world-entry.size-line",
                Placeholders.bytes("size", world.storageBytes())));
        lore.add(messages.render(
                "messages.gui.my-worlds-menu.item.world-entry.border-line",
                Placeholders.count("radius", world.borderRadius())));
        lore.add(Component.empty());

        if (world.state() == WorldState.READY) {
            lore.add(messages.render("messages.gui.my-worlds-menu.item.world-entry.join-hint"));
        }
        // The proxy is the authority on who may manage a world (FR-31a); a
        // non-owner opening the detail screen reads it rather than being told
        // the world does not exist, so the entry is offered either way.
        lore.add(messages.render(
                "messages.gui.my-worlds-menu.item.world-entry.manage-hint",
                Placeholders.raw("action", owned ? "Manage World" : "World Details")));

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
                                            player.sendMessage(GsonComponentSerializer.gson()
                                                    .deserialize(failed.message()));
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
                String defaultName = player.getName().toLowerCase(Locale.ROOT) + "-" + (owned.size() + 1);
                var _ = menuChannel
                        .sendIntent(player, new MenuIntent.CreateWorld(defaultName, null))
                        .whenComplete((result, ex) -> {
                            if (result instanceof MenuResult.Failed failed) {
                                player.sendMessage(
                                        GsonComponentSerializer.gson().deserialize(failed.message()));
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
