package nl.gzmn.playerworlds.proxy.menu.screens;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import nl.gzmn.playerworlds.core.menu.MenuItemDescriptor;
import nl.gzmn.playerworlds.core.menu.RenderMenuPayload;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.proxy.command.Messages;
import nl.gzmn.playerworlds.proxy.command.Placeholders;

/**
 * Builds the paginated screen payload displaying incoming pending world invites
 * and ownership transfer requests.
 */
public final class InvitesScreenBuilder {

    public static final String SCREEN_TYPE = "INVITES";
    public static final int SIZE = 54;
    public static final int PAGE_SIZE = 36;

    public static final int SLOT_PREVIOUS_PAGE = 45;
    public static final int SLOT_BACK = 48;
    public static final int SLOT_NEXT_PAGE = 53;

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

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

    private InvitesScreenBuilder() {}

    public static RenderMenuPayload build(Messages messages, long correlationId, List<InviteEntry> invites, int page) {
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(invites, "invites");
        int validPage = Math.max(0, page);
        int totalPages = Math.max(1, (int) Math.ceil((double) invites.size() / PAGE_SIZE));
        String title = legacy(messages.render(
                "messages.gui.invites-menu.title",
                Placeholders.count("page", validPage + 1),
                Placeholders.count("pages", totalPages)));

        List<MenuItemDescriptor> items = new ArrayList<>(SIZE);
        for (int i = 0; i < SIZE; i++) {
            items.add(new MenuItemDescriptor(i, "GRAY_STAINED_GLASS_PANE", 1, " ", List.of(), null, ""));
        }

        if (invites.isEmpty()) {
            items.set(
                    22,
                    new MenuItemDescriptor(
                            22,
                            "WRITABLE_BOOK",
                            1,
                            legacy(messages.render("messages.gui.invites-menu.item.empty.name")),
                            legacyLore(messages.renderLore("messages.gui.invites-menu.item.empty.lore")),
                            null,
                            ""));
        } else {
            int startIndex = validPage * PAGE_SIZE;
            int endIndex = Math.min(invites.size(), startIndex + PAGE_SIZE);

            for (int i = startIndex; i < endIndex; i++) {
                int slot = i - startIndex;
                InviteEntry entry = invites.get(i);
                items.set(slot, renderInviteItem(messages, slot, entry));
            }
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
                            legacy(messages.render("messages.gui.invites-menu.item.previous-page.name")),
                            List.of(),
                            null,
                            "NAV:INVITES:" + (validPage - 1)));
        }

        items.set(
                SLOT_BACK,
                new MenuItemDescriptor(
                        SLOT_BACK,
                        "OAK_DOOR",
                        1,
                        legacy(messages.render("messages.gui.invites-menu.item.back.name")),
                        legacyLore(messages.renderLore("messages.gui.invites-menu.item.back.lore")),
                        null,
                        "NAV:MAIN"));

        if ((validPage + 1) * PAGE_SIZE < invites.size()) {
            items.set(
                    SLOT_NEXT_PAGE,
                    new MenuItemDescriptor(
                            SLOT_NEXT_PAGE,
                            "ARROW",
                            1,
                            legacy(messages.render("messages.gui.invites-menu.item.next-page.name")),
                            List.of(),
                            null,
                            "NAV:INVITES:" + (validPage + 1)));
        }

        return new RenderMenuPayload(correlationId, SCREEN_TYPE, title, SIZE, items);
    }

    private static MenuItemDescriptor renderInviteItem(Messages messages, int slot, InviteEntry entry) {
        String mat = entry.isTransfer() ? "NETHER_STAR" : "WRITABLE_BOOK";
        Component name = messages.render(
                "messages.gui.invites-menu.item.invite-entry.name",
                Placeholders.raw("kind", entry.isTransfer() ? "Transfer" : "Invite"),
                Placeholders.text("world", entry.worldName()));

        List<Component> lore = messages.renderLore(
                "messages.gui.invites-menu.item.invite-entry.lore",
                Placeholders.text("sender", entry.senderName()),
                Placeholders.raw("type", entry.isTransfer() ? "Ownership Transfer" : "World Membership"),
                Placeholders.raw("expires-at", entry.expiresAt().toString().substring(0, 10)));

        String actionTag = entry.isTransfer()
                ? "ACTION:ACCEPT_TRANSFER:" + entry.senderName()
                : "ACTION:ACCEPT_INVITE:" + entry.senderName();

        return new MenuItemDescriptor(slot, mat, 1, legacy(name), legacyLore(lore), null, actionTag);
    }

    private static String legacy(Component component) {
        return LEGACY.serialize(component);
    }

    private static List<String> legacyLore(List<Component> lines) {
        return lines.stream().map(InvitesScreenBuilder::legacy).toList();
    }
}
