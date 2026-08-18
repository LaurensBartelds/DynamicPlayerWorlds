package nl.gzmn.playerworlds.backend.gui.screen;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import nl.gzmn.playerworlds.backend.gui.GuiScreen;
import nl.gzmn.playerworlds.backend.gui.ItemUtil;
import nl.gzmn.playerworlds.backend.gui.MenuChannel;
import nl.gzmn.playerworlds.backend.gui.MenuHolder;
import nl.gzmn.playerworlds.backend.gui.MenuService;
import nl.gzmn.playerworlds.core.menu.MenuIntent;
import nl.gzmn.playerworlds.core.menu.MenuResult;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.Role;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.jspecify.annotations.Nullable;

/**
 * Paginated screen listing members and their roles for a single world,
 * allowing promotion, kicking (with confirmation), and inviting.
 */
public final class MembersMenu implements GuiScreen {

    public static final int PAGE_SIZE = 36;
    public static final int SLOT_PREVIOUS_PAGE = 45;
    public static final int SLOT_BACK = 48;
    public static final int SLOT_INVITE = 49;
    public static final int SLOT_NEXT_PAGE = 53;

    private final MenuService menuService;
    private final @Nullable MenuChannel menuChannel;
    private final PlayerWorld world;
    private final List<MemberEntry> members;
    private final int page;

    /**
     * View data record representing a member in the GUI.
     */
    public record MemberEntry(
            UUID uuid, String name, Role role, @Nullable Instant joinedAt) {
        public MemberEntry {
            Objects.requireNonNull(uuid, "uuid");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(role, "role");
        }
    }

    public MembersMenu(
            MenuService menuService,
            @Nullable MenuChannel menuChannel,
            PlayerWorld world,
            List<MemberEntry> members,
            int page) {
        this.menuService = Objects.requireNonNull(menuService, "menuService");
        this.menuChannel = menuChannel;
        this.world = Objects.requireNonNull(world, "world");
        this.members = List.copyOf(Objects.requireNonNull(members, "members"));
        this.page = Math.max(0, page);
    }

    public PlayerWorld world() {
        return world;
    }

    public List<MemberEntry> members() {
        return members;
    }

    public int page() {
        return page;
    }

    @Override
    public Inventory render(Player player) {
        Objects.requireNonNull(player, "player");
        MenuHolder holder = new MenuHolder(this);
        int totalPages = Math.max(1, (int) Math.ceil((double) members.size() / PAGE_SIZE));
        String titleText = "Members: " + world.name() + " (" + (page + 1) + "/" + totalPages + ")";
        Inventory inventory = Bukkit.createInventory(holder, 54, Component.text(titleText, NamedTextColor.DARK_GRAY));
        holder.setInventory(inventory);

        for (int i = 0; i < 54; i++) {
            inventory.setItem(i, ItemUtil.filler());
        }

        int startIndex = page * PAGE_SIZE;
        int endIndex = Math.min(members.size(), startIndex + PAGE_SIZE);

        for (int i = startIndex; i < endIndex; i++) {
            int slot = i - startIndex;
            MemberEntry member = members.get(i);
            inventory.setItem(slot, renderMemberItem(member));
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
                        Component.text("Back to World Menu", NamedTextColor.RED, TextDecoration.BOLD),
                        Component.text("▶ Click to return", NamedTextColor.DARK_GRAY)));

        inventory.setItem(
                SLOT_INVITE,
                ItemUtil.create(
                        Material.EMERALD,
                        Component.text("Invite Member", NamedTextColor.GREEN, TextDecoration.BOLD),
                        Component.text("Invite another player to this world", NamedTextColor.GRAY),
                        Component.empty(),
                        Component.text("▶ Click for instructions", NamedTextColor.YELLOW)));

        if ((page + 1) * PAGE_SIZE < members.size()) {
            inventory.setItem(
                    SLOT_NEXT_PAGE,
                    ItemUtil.create(
                            Material.ARROW, Component.text("Next Page ▶", NamedTextColor.YELLOW, TextDecoration.BOLD)));
        }

        return inventory;
    }

    private org.bukkit.inventory.ItemStack renderMemberItem(MemberEntry member) {
        Component name = Component.text(member.name(), NamedTextColor.AQUA, TextDecoration.BOLD);

        List<Component> lore = new java.util.ArrayList<>();
        lore.add(Component.text("Role: " + member.role().name(), roleColor(member.role())));
        lore.add(Component.text(
                "Joined: "
                        + (member.joinedAt() != null
                                ? member.joinedAt().toString().substring(0, 10)
                                : "Never"),
                NamedTextColor.GRAY));
        lore.add(Component.empty());

        if (member.role() == Role.OWNER) {
            lore.add(Component.text("World Owner", NamedTextColor.GOLD));
        } else {
            lore.add(Component.text("▶ Left-Click: Promote to BUILDER", NamedTextColor.GREEN));
            lore.add(Component.text("▶ Right-Click: Kick Member", NamedTextColor.RED));
        }

        return ItemUtil.createPlayerHead(member.uuid(), member.name(), name, lore);
    }

    private static TextColor roleColor(Role role) {
        return switch (role) {
            case OWNER -> NamedTextColor.GOLD;
            case BUILDER -> NamedTextColor.AQUA;
            case VISITOR -> NamedTextColor.GRAY;
        };
    }

    @Override
    public void handleClick(Player player, int slot, ClickType clickType) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(clickType, "clickType");

        if (slot >= 0 && slot < PAGE_SIZE) {
            int index = page * PAGE_SIZE + slot;
            if (index < members.size()) {
                MemberEntry member = members.get(index);
                if (member.role() == Role.OWNER) {
                    return;
                }

                if (clickType.isLeftClick()) {
                    if (menuChannel != null) {
                        var _ = menuChannel
                                .sendIntent(player, new MenuIntent.PromoteMember(member.name(), world.id()))
                                .whenComplete((result, ex) -> {
                                    if (result instanceof MenuResult.Failed failed) {
                                        player.sendMessage(Component.text(
                                                "Could not promote member: " + failed.message(), NamedTextColor.RED));
                                    }
                                    var _ = menuService.openMembersMenu(player, world.id(), page);
                                });
                    }
                } else if (clickType.isRightClick()) {
                    menuService.openConfirmMenu(
                            player,
                            Component.text("Kick " + member.name() + "?", NamedTextColor.DARK_RED, TextDecoration.BOLD),
                            Component.text(
                                    "Remove " + member.name() + " from '" + world.name() + "'", NamedTextColor.GRAY),
                            () -> {
                                if (menuChannel != null) {
                                    var _ = menuChannel
                                            .sendIntent(player, new MenuIntent.KickMember(member.name(), world.id()))
                                            .whenComplete((result, ex) -> {
                                                if (result instanceof MenuResult.Failed failed) {
                                                    player.sendMessage(Component.text(
                                                            "Could not kick member: " + failed.message(),
                                                            NamedTextColor.RED));
                                                }
                                                var _ = menuService.openMembersMenu(player, world.id(), page);
                                            });
                                }
                            },
                            () -> {
                                var _ = menuService.openMembersMenu(player, world.id(), page);
                            });
                }
            }
            return;
        }

        if (slot == SLOT_PREVIOUS_PAGE && page > 0) {
            var _ = menuService.openMembersMenu(player, world.id(), page - 1);
        } else if (slot == SLOT_BACK) {
            var _ = menuService.openWorldMenu(player, world.id());
        } else if (slot == SLOT_INVITE) {
            player.sendMessage(
                    Component.text("To invite a player, run: /world invite <player>", NamedTextColor.YELLOW));
        } else if (slot == SLOT_NEXT_PAGE && (page + 1) * PAGE_SIZE < members.size()) {
            var _ = menuService.openMembersMenu(player, world.id(), page + 1);
        }
    }

    @Override
    public void refresh(Player player) {
        Objects.requireNonNull(player, "player");
        var _ = menuService.openMembersMenu(player, world.id(), page);
    }
}
