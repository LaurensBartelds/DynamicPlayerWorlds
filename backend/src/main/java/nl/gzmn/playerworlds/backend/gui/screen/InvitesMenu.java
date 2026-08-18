package nl.gzmn.playerworlds.backend.gui.screen;

import java.time.Instant;
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
 * Paginated screen displaying incoming pending world invites and ownership transfer requests,
 * allowing players to accept (left-click) or decline/dismiss (right-click).
 */
public final class InvitesMenu implements GuiScreen {

    public static final int PAGE_SIZE = 36;
    public static final int SLOT_PREVIOUS_PAGE = 45;
    public static final int SLOT_BACK = 48;
    public static final int SLOT_NEXT_PAGE = 53;

    private final MenuService menuService;
    private final @Nullable MenuChannel menuChannel;
    private final List<InviteEntry> invites;
    private final int page;

    /**
     * View data record representing a pending invitation or transfer request.
     */
    public record InviteEntry(
            WorldId worldId,
            String worldName,
            UUID senderUuid,
            String senderName,
            Instant expiresAt,
            boolean isTransfer) {
        public InviteEntry {
            Objects.requireNonNull(worldId, "worldId");
            Objects.requireNonNull(worldName, "worldName");
            Objects.requireNonNull(senderUuid, "senderUuid");
            Objects.requireNonNull(senderName, "senderName");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    public InvitesMenu(
            MenuService menuService, @Nullable MenuChannel menuChannel, List<InviteEntry> invites, int page) {
        this.menuService = Objects.requireNonNull(menuService, "menuService");
        this.menuChannel = menuChannel;
        this.invites = List.copyOf(Objects.requireNonNull(invites, "invites"));
        this.page = Math.max(0, page);
    }

    public List<InviteEntry> invites() {
        return invites;
    }

    public int page() {
        return page;
    }

    @Override
    public Inventory render(Player player) {
        Objects.requireNonNull(player, "player");
        MenuHolder holder = new MenuHolder(this);
        int totalPages = Math.max(1, (int) Math.ceil((double) invites.size() / PAGE_SIZE));
        String titleText = "Pending Invites (" + (page + 1) + "/" + totalPages + ")";
        Inventory inventory = Bukkit.createInventory(holder, 54, Component.text(titleText, NamedTextColor.DARK_GRAY));
        holder.setInventory(inventory);

        for (int i = 0; i < 54; i++) {
            inventory.setItem(i, ItemUtil.filler());
        }

        if (invites.isEmpty()) {
            inventory.setItem(
                    22,
                    ItemUtil.create(
                            Material.WRITABLE_BOOK,
                            Component.text("No Pending Invites", NamedTextColor.GOLD, TextDecoration.BOLD),
                            Component.text("You have no pending invites or transfer requests.", NamedTextColor.GRAY)));
        } else {
            int startIndex = page * PAGE_SIZE;
            int endIndex = Math.min(invites.size(), startIndex + PAGE_SIZE);

            for (int i = startIndex; i < endIndex; i++) {
                int slot = i - startIndex;
                InviteEntry entry = invites.get(i);
                inventory.setItem(slot, renderInviteItem(entry));
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

        if ((page + 1) * PAGE_SIZE < invites.size()) {
            inventory.setItem(
                    SLOT_NEXT_PAGE,
                    ItemUtil.create(
                            Material.ARROW, Component.text("Next Page ▶", NamedTextColor.YELLOW, TextDecoration.BOLD)));
        }

        return inventory;
    }

    private org.bukkit.inventory.ItemStack renderInviteItem(InviteEntry entry) {
        Material mat = entry.isTransfer() ? Material.NETHER_STAR : Material.WRITABLE_BOOK;
        Component name = Component.text(
                (entry.isTransfer() ? "Transfer: " : "Invite: ") + entry.worldName(),
                NamedTextColor.GOLD,
                TextDecoration.BOLD);

        List<Component> lore = new java.util.ArrayList<>();
        lore.add(Component.text("From: " + entry.senderName(), NamedTextColor.YELLOW));
        lore.add(Component.text(
                "Type: " + (entry.isTransfer() ? "Ownership Transfer" : "World Membership"), NamedTextColor.GRAY));
        lore.add(Component.text("Expires: " + entry.expiresAt().toString().substring(0, 10), NamedTextColor.DARK_GRAY));
        lore.add(Component.empty());
        lore.add(Component.text("▶ Left-Click: Accept", NamedTextColor.GREEN));
        lore.add(Component.text("▶ Right-Click: Decline", NamedTextColor.RED));

        return ItemUtil.create(mat, name, lore);
    }

    @Override
    public void handleClick(Player player, int slot, ClickType clickType) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(clickType, "clickType");

        if (slot >= 0 && slot < PAGE_SIZE) {
            int index = page * PAGE_SIZE + slot;
            if (index < invites.size()) {
                InviteEntry entry = invites.get(index);
                if (clickType.isLeftClick()) {
                    if (menuChannel != null) {
                        MenuIntent intent = entry.isTransfer()
                                ? new MenuIntent.AcceptTransfer(entry.senderName())
                                : new MenuIntent.AcceptInvite(entry.senderName());
                        var _ = menuChannel.sendIntent(player, intent).whenComplete((result, ex) -> {
                            if (result instanceof MenuResult.Failed failed) {
                                player.sendMessage(Component.text(
                                        "Could not accept invite: " + failed.message(), NamedTextColor.RED));
                            }
                            var _ = menuService.openInvitesMenu(player, page);
                        });
                    }
                } else if (clickType.isRightClick()) {
                    if (menuChannel != null) {
                        MenuIntent intent = new MenuIntent.DeclineTransfer(entry.senderName());
                        var _ = menuChannel.sendIntent(player, intent).whenComplete((result, ex) -> {
                            if (result instanceof MenuResult.Failed failed) {
                                player.sendMessage(Component.text(
                                        "Could not decline invite: " + failed.message(), NamedTextColor.RED));
                            }
                            var _ = menuService.openInvitesMenu(player, page);
                        });
                    }
                }
            }
            return;
        }

        if (slot == SLOT_PREVIOUS_PAGE && page > 0) {
            var _ = menuService.openInvitesMenu(player, page - 1);
        } else if (slot == SLOT_BACK) {
            var _ = menuService.openMainMenu(player);
        } else if (slot == SLOT_NEXT_PAGE && (page + 1) * PAGE_SIZE < invites.size()) {
            var _ = menuService.openInvitesMenu(player, page + 1);
        }
    }

    @Override
    public void refresh(Player player) {
        Objects.requireNonNull(player, "player");
        var _ = menuService.openInvitesMenu(player, page);
    }
}
