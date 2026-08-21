package nl.gzmn.playerworlds.backend.gui.screen;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import nl.gzmn.playerworlds.backend.gui.GuiScreen;
import nl.gzmn.playerworlds.backend.gui.ItemUtil;
import nl.gzmn.playerworlds.backend.gui.MenuChannel;
import nl.gzmn.playerworlds.backend.gui.MenuHolder;
import nl.gzmn.playerworlds.backend.gui.MenuService;
import nl.gzmn.playerworlds.backend.gui.Placeholders;
import nl.gzmn.playerworlds.core.menu.MenuIntent;
import nl.gzmn.playerworlds.core.menu.MenuResult;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.jspecify.annotations.Nullable;

/**
 * Paginated screen displaying players banned from a specific world with click-to-unban action.
 */
public final class BansMenu implements GuiScreen {

    public static final int PAGE_SIZE = 36;
    public static final int SLOT_PREVIOUS_PAGE = 45;
    public static final int SLOT_BACK = 48;
    public static final int SLOT_NEXT_PAGE = 53;

    private final MenuService menuService;
    private final @Nullable MenuChannel menuChannel;
    private final PlayerWorld world;
    private final List<BanEntry> bans;
    private final int page;

    /**
     * View data record representing a banned player.
     */
    public record BanEntry(UUID uuid, String name, @Nullable String reason, Instant bannedAt) {
        public BanEntry {
            Objects.requireNonNull(uuid, "uuid");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(bannedAt, "bannedAt");
        }
    }

    public BansMenu(
            MenuService menuService,
            @Nullable MenuChannel menuChannel,
            PlayerWorld world,
            List<BanEntry> bans,
            int page) {
        this.menuService = Objects.requireNonNull(menuService, "menuService");
        this.menuChannel = menuChannel;
        this.world = Objects.requireNonNull(world, "world");
        this.bans = List.copyOf(Objects.requireNonNull(bans, "bans"));
        this.page = Math.max(0, page);
    }

    public PlayerWorld world() {
        return world;
    }

    public List<BanEntry> bans() {
        return bans;
    }

    public int page() {
        return page;
    }

    @Override
    public Inventory render(Player player) {
        Objects.requireNonNull(player, "player");
        var messages = menuService.messages();
        MenuHolder holder = new MenuHolder(this);
        int totalPages = Math.max(1, (int) Math.ceil((double) bans.size() / PAGE_SIZE));
        Inventory inventory = Bukkit.createInventory(
                holder,
                54,
                messages.render(
                        "messages.gui.bans-menu.title",
                        Placeholders.text("world", world.name()),
                        Placeholders.count("page", page + 1),
                        Placeholders.count("pages", totalPages)));
        holder.setInventory(inventory);

        for (int i = 0; i < 54; i++) {
            inventory.setItem(i, ItemUtil.filler());
        }

        if (bans.isEmpty()) {
            inventory.setItem(
                    22,
                    ItemUtil.create(
                            Material.IRON_BARS,
                            messages.render("messages.gui.bans-menu.item.empty.name"),
                            messages.renderLore("messages.gui.bans-menu.item.empty.lore")));
        } else {
            int startIndex = page * PAGE_SIZE;
            int endIndex = Math.min(bans.size(), startIndex + PAGE_SIZE);

            for (int i = startIndex; i < endIndex; i++) {
                int slot = i - startIndex;
                BanEntry ban = bans.get(i);
                inventory.setItem(slot, renderBanItem(ban));
            }
        }

        for (int i = 36; i < 45; i++) {
            inventory.setItem(i, ItemUtil.filler(Material.BLACK_STAINED_GLASS_PANE));
        }

        if (page > 0) {
            inventory.setItem(
                    SLOT_PREVIOUS_PAGE,
                    ItemUtil.create(Material.ARROW, messages.render("messages.gui.bans-menu.item.previous-page.name")));
        }

        inventory.setItem(
                SLOT_BACK,
                ItemUtil.create(
                        Material.OAK_DOOR,
                        messages.render("messages.gui.bans-menu.item.back.name"),
                        messages.renderLore("messages.gui.bans-menu.item.back.lore")));

        if ((page + 1) * PAGE_SIZE < bans.size()) {
            inventory.setItem(
                    SLOT_NEXT_PAGE,
                    ItemUtil.create(Material.ARROW, messages.render("messages.gui.bans-menu.item.next-page.name")));
        }

        return inventory;
    }

    private org.bukkit.inventory.ItemStack renderBanItem(BanEntry ban) {
        var messages = menuService.messages();
        Component name =
                messages.render("messages.gui.bans-menu.item.ban-entry.name", Placeholders.text("player", ban.name()));

        String reasonText = ban.reason() != null ? ban.reason() : "No reason provided";
        List<Component> lore = messages.renderLore(
                "messages.gui.bans-menu.item.ban-entry.lore",
                Placeholders.text("reason", reasonText),
                Placeholders.raw("banned-at", ban.bannedAt().toString().substring(0, 10)));

        return ItemUtil.createPlayerHead(ban.uuid(), ban.name(), name, lore);
    }

    @Override
    public void handleClick(Player player, int slot, ClickType clickType) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(clickType, "clickType");

        if (slot >= 0 && slot < PAGE_SIZE) {
            int index = page * PAGE_SIZE + slot;
            if (index < bans.size()) {
                BanEntry ban = bans.get(index);
                if (menuChannel != null) {
                    var _ = menuChannel
                            .sendIntent(player, new MenuIntent.UnbanPlayer(ban.name(), world.id()))
                            .whenComplete((result, ex) -> {
                                if (result instanceof MenuResult.Failed failed) {
                                    player.sendMessage(
                                            GsonComponentSerializer.gson().deserialize(failed.message()));
                                }
                                var _ = menuService.openBansMenu(player, world.id(), page);
                            });
                }
            }
            return;
        }

        if (slot == SLOT_PREVIOUS_PAGE && page > 0) {
            var _ = menuService.openBansMenu(player, world.id(), page - 1);
        } else if (slot == SLOT_BACK) {
            var _ = menuService.openWorldMenu(player, world.id());
        } else if (slot == SLOT_NEXT_PAGE && (page + 1) * PAGE_SIZE < bans.size()) {
            var _ = menuService.openBansMenu(player, world.id(), page + 1);
        }
    }

    @Override
    public void refresh(Player player) {
        Objects.requireNonNull(player, "player");
        var _ = menuService.openBansMenu(player, world.id(), page);
    }
}
