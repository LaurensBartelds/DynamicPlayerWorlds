package nl.gzmn.playerworlds.proxy.menu.screens;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import nl.gzmn.playerworlds.core.menu.MenuItemDescriptor;
import nl.gzmn.playerworlds.core.menu.RenderMenuPayload;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.Role;
import nl.gzmn.playerworlds.proxy.command.Messages;
import nl.gzmn.playerworlds.proxy.command.Placeholders;
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

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    public record MemberEntry(
            UUID uuid, String name, Role role, @Nullable Instant joinedAt) {
        public MemberEntry {
            Objects.requireNonNull(uuid, "uuid");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(role, "role");
        }
    }

    private MembersScreenBuilder() {}

    public static RenderMenuPayload build(
            Messages messages, long correlationId, PlayerWorld world, List<MemberEntry> members, int page) {
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(members, "members");
        int validPage = Math.max(0, page);
        int totalPages = Math.max(1, (int) Math.ceil((double) members.size() / PAGE_SIZE));
        String title = legacy(messages.render(
                "messages.gui.members-menu.title",
                Placeholders.text("world", world.name()),
                Placeholders.count("page", validPage + 1),
                Placeholders.count("pages", totalPages)));

        List<MenuItemDescriptor> items = new ArrayList<>(SIZE);
        for (int i = 0; i < SIZE; i++) {
            items.add(new MenuItemDescriptor(i, "GRAY_STAINED_GLASS_PANE", 1, " ", List.of(), null, ""));
        }

        int startIndex = validPage * PAGE_SIZE;
        int endIndex = Math.min(members.size(), startIndex + PAGE_SIZE);

        for (int i = startIndex; i < endIndex; i++) {
            int slot = i - startIndex;
            MemberEntry member = members.get(i);
            items.set(slot, renderMemberItem(messages, slot, world, member));
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
                            legacy(messages.render("messages.gui.members-menu.item.previous-page.name")),
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
                        legacy(messages.render("messages.gui.members-menu.item.back.name")),
                        legacyLore(messages.renderLore("messages.gui.members-menu.item.back.lore")),
                        null,
                        "NAV:WORLD:" + world.id().value()));

        items.set(
                SLOT_INVITE,
                new MenuItemDescriptor(
                        SLOT_INVITE,
                        "EMERALD",
                        1,
                        legacy(messages.render("messages.gui.members-menu.item.invite.name")),
                        legacyLore(messages.renderLore("messages.gui.members-menu.item.invite.lore")),
                        null,
                        "ACTION:INVITE_INFO"));

        if ((validPage + 1) * PAGE_SIZE < members.size()) {
            items.set(
                    SLOT_NEXT_PAGE,
                    new MenuItemDescriptor(
                            SLOT_NEXT_PAGE,
                            "ARROW",
                            1,
                            legacy(messages.render("messages.gui.members-menu.item.next-page.name")),
                            List.of(),
                            null,
                            "NAV:MEMBERS:" + world.id().value() + ":" + (validPage + 1)));
        }

        return new RenderMenuPayload(correlationId, SCREEN_TYPE, title, SIZE, items);
    }

    private static MenuItemDescriptor renderMemberItem(
            Messages messages, int slot, PlayerWorld world, MemberEntry member) {
        Component nameValue = Component.text(member.name(), NamedTextColor.AQUA, TextDecoration.BOLD);
        Component name = messages.render(
                "messages.gui.members-menu.item.member.name", Placeholders.component("member", nameValue));

        List<Component> lore = new ArrayList<>();
        Component roleValue = Component.text("Role: " + member.role().name(), roleColor(member.role()));
        lore.add(messages.render(
                "messages.gui.members-menu.item.member.role-line", Placeholders.component("role", roleValue)));
        lore.add(messages.render(
                "messages.gui.members-menu.item.member.joined-line",
                Placeholders.raw(
                        "joined",
                        member.joinedAt() != null ? member.joinedAt().toString().substring(0, 10) : "Never")));
        lore.add(Component.empty());

        if (member.role() == Role.OWNER) {
            lore.add(messages.render("messages.gui.members-menu.item.member.owner-note"));
        } else {
            lore.add(messages.render("messages.gui.members-menu.item.member.promote-hint"));
            lore.add(messages.render("messages.gui.members-menu.item.member.kick-hint"));
        }

        String actionTag = (member.role() == Role.OWNER)
                ? ""
                : "ACTION:PROMOTE:" + world.id().value() + ":" + member.name();

        return new MenuItemDescriptor(slot, "PLAYER_HEAD", 1, legacy(name), legacyLore(lore), member.uuid(), actionTag);
    }

    private static TextColor roleColor(Role role) {
        return switch (role) {
            case OWNER -> NamedTextColor.GOLD;
            case BUILDER -> NamedTextColor.AQUA;
            case VISITOR -> NamedTextColor.GRAY;
        };
    }

    private static String legacy(Component component) {
        return LEGACY.serialize(component);
    }

    private static List<String> legacyLore(List<Component> lines) {
        return lines.stream().map(MembersScreenBuilder::legacy).toList();
    }
}
