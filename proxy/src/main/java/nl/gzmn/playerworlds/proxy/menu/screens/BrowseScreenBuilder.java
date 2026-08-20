package nl.gzmn.playerworlds.proxy.menu.screens;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import nl.gzmn.playerworlds.core.menu.MenuItemDescriptor;
import nl.gzmn.playerworlds.core.menu.RenderMenuPayload;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.jspecify.annotations.Nullable;

/**
 * Builds the paginated screen payload displaying public worlds across the network.
 */
public final class BrowseScreenBuilder {

    public static final String SCREEN_TYPE = "BROWSE";
    public static final int SIZE = 54;
    public static final int PAGE_SIZE = 36;

    public static final int SLOT_PREVIOUS_PAGE = 45;
    public static final int SLOT_BACK = 48;
    public static final int SLOT_NEXT_PAGE = 53;

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

    private BrowseScreenBuilder() {}

    public static RenderMenuPayload build(long correlationId, List<PublicWorldEntry> worlds, int page) {
        Objects.requireNonNull(worlds, "worlds");
        int validPage = Math.max(0, page);
        int totalPages = Math.max(1, (int) Math.ceil((double) worlds.size() / PAGE_SIZE));
        String title = "§8Public Worlds (Page " + (validPage + 1) + "/" + totalPages + ")";

        List<MenuItemDescriptor> items = new ArrayList<>(SIZE);
        for (int i = 0; i < SIZE; i++) {
            items.add(new MenuItemDescriptor(i, "GRAY_STAINED_GLASS_PANE", 1, " ", List.of(), null, ""));
        }

        if (worlds.isEmpty()) {
            items.set(
                    22,
                    new MenuItemDescriptor(
                            22,
                            "COMPASS",
                            1,
                            "§e§lNo Public Worlds",
                            List.of("§7There are no public worlds currently available."),
                            null,
                            ""));
        } else {
            int startIndex = validPage * PAGE_SIZE;
            int endIndex = Math.min(worlds.size(), startIndex + PAGE_SIZE);

            for (int i = startIndex; i < endIndex; i++) {
                int slot = i - startIndex;
                PublicWorldEntry entry = worlds.get(i);
                items.set(slot, renderWorldItem(slot, entry));
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
                            "NAV:BROWSE:" + (validPage - 1)));
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

        if ((validPage + 1) * PAGE_SIZE < worlds.size()) {
            items.set(
                    SLOT_NEXT_PAGE,
                    new MenuItemDescriptor(
                            SLOT_NEXT_PAGE,
                            "ARROW",
                            1,
                            "§e§lNext Page ▶",
                            List.of(),
                            null,
                            "NAV:BROWSE:" + (validPage + 1)));
        }

        return new RenderMenuPayload(correlationId, SCREEN_TYPE, title, SIZE, items);
    }

    private static MenuItemDescriptor renderWorldItem(int slot, PublicWorldEntry entry) {
        List<String> lore = new ArrayList<>();
        lore.add("§eOwner: " + entry.ownerName());
        if (entry.description() != null && !entry.description().isBlank()) {
            lore.add("§7" + entry.description());
        }
        lore.add("");
        lore.add("§a▶ Click to Join World");

        return new MenuItemDescriptor(
                slot,
                "GRASS_BLOCK",
                1,
                "§b§l" + entry.worldName(),
                lore,
                null,
                "ACTION:JOIN:" + entry.worldId().value());
    }
}
