package nl.gzmn.playerworlds.proxy.menu.screens;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import nl.gzmn.playerworlds.core.menu.MenuItemDescriptor;
import nl.gzmn.playerworlds.core.menu.RenderMenuPayload;
import nl.gzmn.playerworlds.core.model.WorldId;

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

    public static RenderMenuPayload build(long correlationId, List<InviteEntry> invites, int page) {
        Objects.requireNonNull(invites, "invites");
        int validPage = Math.max(0, page);
        int totalPages = Math.max(1, (int) Math.ceil((double) invites.size() / PAGE_SIZE));
        String title = "§8Pending Invites (" + (validPage + 1) + "/" + totalPages + ")";

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
                            "§6§lNo Pending Invites",
                            List.of("§7You have no pending invites or transfer requests."),
                            null,
                            ""));
        } else {
            int startIndex = validPage * PAGE_SIZE;
            int endIndex = Math.min(invites.size(), startIndex + PAGE_SIZE);

            for (int i = startIndex; i < endIndex; i++) {
                int slot = i - startIndex;
                InviteEntry entry = invites.get(i);
                items.set(slot, renderInviteItem(slot, entry));
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
                            "§e§l◀ Previous Page",
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
                        "§c§lBack to Main Menu",
                        List.of("§8▶ Click to return"),
                        null,
                        "NAV:MAIN"));

        if ((validPage + 1) * PAGE_SIZE < invites.size()) {
            items.set(
                    SLOT_NEXT_PAGE,
                    new MenuItemDescriptor(
                            SLOT_NEXT_PAGE,
                            "ARROW",
                            1,
                            "§e§lNext Page ▶",
                            List.of(),
                            null,
                            "NAV:INVITES:" + (validPage + 1)));
        }

        return new RenderMenuPayload(correlationId, SCREEN_TYPE, title, SIZE, items);
    }

    private static MenuItemDescriptor renderInviteItem(int slot, InviteEntry entry) {
        String mat = entry.isTransfer() ? "NETHER_STAR" : "WRITABLE_BOOK";
        String name = "§6§l" + (entry.isTransfer() ? "Transfer: " : "Invite: ") + entry.worldName();

        List<String> lore = new ArrayList<>();
        lore.add("§eFrom: " + entry.senderName());
        lore.add("§7Type: " + (entry.isTransfer() ? "Ownership Transfer" : "World Membership"));
        lore.add("§8Expires: " + entry.expiresAt().toString().substring(0, 10));
        lore.add("");
        lore.add("§a▶ Left-Click: Accept");
        lore.add("§c▶ Right-Click: Decline");

        String actionTag = entry.isTransfer()
                ? "ACTION:ACCEPT_TRANSFER:" + entry.senderName()
                : "ACTION:ACCEPT_INVITE:" + entry.senderName();

        return new MenuItemDescriptor(slot, mat, 1, name, lore, null, actionTag);
    }
}
