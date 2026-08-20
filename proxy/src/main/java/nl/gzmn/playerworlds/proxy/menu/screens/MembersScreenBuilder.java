package nl.gzmn.playerworlds.proxy.menu.screens;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import nl.gzmn.playerworlds.core.menu.MenuItemDescriptor;
import nl.gzmn.playerworlds.core.menu.RenderMenuPayload;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.Role;
import org.jspecify.annotations.Nullable;

/**
 * Builds the paginated screen payload listing members and roles for a single world,
 * allowing promotion and kicking.
 */
public final class MembersScreenBuilder {

    public static final String SCREEN_TYPE = "MEMBERS";
    public static final int SIZE = 54;
    public static final int PAGE_SIZE = 36;

    public static final int SLOT_PREVIOUS_PAGE = 45;
    public static final int SLOT_BACK = 48;
    public static final int SLOT_INVITE = 49;
    public static final int SLOT_NEXT_PAGE = 53;

    public record MemberEntry(
            UUID uuid, String name, Role role, @Nullable Instant joinedAt) {
        public MemberEntry {
            Objects.requireNonNull(uuid, "uuid");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(role, "role");
        }
    }

    private MembersScreenBuilder() {}

    public static RenderMenuPayload build(long correlationId, PlayerWorld world, List<MemberEntry> members, int page) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(members, "members");
        int validPage = Math.max(0, page);
        int totalPages = Math.max(1, (int) Math.ceil((double) members.size() / PAGE_SIZE));
        String title = "§8Members: " + world.name() + " (" + (validPage + 1) + "/" + totalPages + ")";

        List<MenuItemDescriptor> items = new ArrayList<>(SIZE);
        for (int i = 0; i < SIZE; i++) {
            items.add(new MenuItemDescriptor(i, "GRAY_STAINED_GLASS_PANE", 1, " ", List.of(), null, ""));
        }

        int startIndex = validPage * PAGE_SIZE;
        int endIndex = Math.min(members.size(), startIndex + PAGE_SIZE);

        for (int i = startIndex; i < endIndex; i++) {
            int slot = i - startIndex;
            MemberEntry member = members.get(i);
            items.set(slot, renderMemberItem(slot, world, member));
        }

        // Divider row
        for (int i = 36; i < 45; i++) {
            items.set(i, new MenuItemDescriptor(i, "BLACK_STAINED_GLASS_PANE", 1, " ", List.of(), null, ""));
        }

        if (validPage > 0) {
            items.set(
                    SLOT_PREVIOUS_PAGE,
                    new MenuItemDescriptor(
                            SLOT_PREVIOUS_PAGE,
                            "ARROW",
                            1,
                            "§e§l◀ Previous Page",
                            List.of(),
                            null,
                            "NAV:MEMBERS:" + world.id().value() + ":" + (validPage - 1)));
        }

        items.set(
                SLOT_BACK,
                new MenuItemDescriptor(
                        SLOT_BACK,
                        "OAK_DOOR",
                        1,
                        "§c§lBack to World Menu",
                        List.of("§8▶ Click to return"),
                        null,
                        "NAV:WORLD:" + world.id().value()));

        items.set(
                SLOT_INVITE,
                new MenuItemDescriptor(
                        SLOT_INVITE,
                        "EMERALD",
                        1,
                        "§a§lInvite Member",
                        List.of("§7Invite another player to this world", "", "§e▶ Click for instructions"),
                        null,
                        "ACTION:INVITE_INFO"));

        if ((validPage + 1) * PAGE_SIZE < members.size()) {
            items.set(
                    SLOT_NEXT_PAGE,
                    new MenuItemDescriptor(
                            SLOT_NEXT_PAGE,
                            "ARROW",
                            1,
                            "§e§lNext Page ▶",
                            List.of(),
                            null,
                            "NAV:MEMBERS:" + world.id().value() + ":" + (validPage + 1)));
        }

        return new RenderMenuPayload(correlationId, SCREEN_TYPE, title, SIZE, items);
    }

    private static MenuItemDescriptor renderMemberItem(int slot, PlayerWorld world, MemberEntry member) {
        String roleColor =
                switch (member.role()) {
                    case OWNER -> "§6";
                    case BUILDER -> "§b";
                    case VISITOR -> "§7";
                };

        List<String> lore = new ArrayList<>();
        lore.add(roleColor + "Role: " + member.role().name());
        lore.add("§7Joined: "
                + (member.joinedAt() != null ? member.joinedAt().toString().substring(0, 10) : "Never"));
        lore.add("");
        if (member.role() == Role.OWNER) {
            lore.add("§6World Owner");
        } else {
            lore.add("§a▶ Left-Click: Promote to BUILDER");
            lore.add("§c▶ Right-Click: Kick Member");
        }

        String actionTag = (member.role() == Role.OWNER)
                ? ""
                : "ACTION:PROMOTE:" + world.id().value() + ":" + member.name();

        return new MenuItemDescriptor(slot, "PLAYER_HEAD", 1, "§b§l" + member.name(), lore, member.uuid(), actionTag);
    }
}
