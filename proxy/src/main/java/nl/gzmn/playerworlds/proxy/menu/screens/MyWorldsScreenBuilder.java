package nl.gzmn.playerworlds.proxy.menu.screens;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import nl.gzmn.playerworlds.core.config.StorageQuotaResolver;
import nl.gzmn.playerworlds.core.menu.MenuItemDescriptor;
import nl.gzmn.playerworlds.core.menu.RenderMenuPayload;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.WorldState;

/**
 * Builds the paginated screen payload listing a player's owned worlds with quick actions
 * and world creation triggers.
 */
public final class MyWorldsScreenBuilder {

    public static final String SCREEN_TYPE = "MY_WORLDS";
    public static final int SIZE = 54;
    public static final int PAGE_SIZE = 36;

    public static final int SLOT_PREVIOUS_PAGE = 45;
    public static final int SLOT_BACK = 48;
    public static final int SLOT_CREATE = 49;
    public static final int SLOT_NEXT_PAGE = 53;

    private MyWorldsScreenBuilder() {}

    public static RenderMenuPayload build(long correlationId, List<PlayerWorld> worlds, int page, int maxWorlds) {
        Objects.requireNonNull(worlds, "worlds");
        int validPage = Math.max(0, page);
        int totalPages = Math.max(1, (int) Math.ceil((double) worlds.size() / PAGE_SIZE));
        String title = "§8My Worlds (Page " + (validPage + 1) + "/" + totalPages + ")";

        List<MenuItemDescriptor> items = new ArrayList<>(SIZE);
        for (int i = 0; i < SIZE; i++) {
            items.add(new MenuItemDescriptor(i, "GRAY_STAINED_GLASS_PANE", 1, " ", List.of(), null, ""));
        }

        int startIndex = validPage * PAGE_SIZE;
        int endIndex = Math.min(worlds.size(), startIndex + PAGE_SIZE);

        for (int i = startIndex; i < endIndex; i++) {
            int slot = i - startIndex;
            PlayerWorld world = worlds.get(i);
            items.set(slot, renderWorldItem(slot, world));
        }

        // Divider row
        for (int i = 36; i < 45; i++) {
            items.set(i, new MenuItemDescriptor(i, "BLACK_STAINED_GLASS_PANE", 1, " ", List.of(), null, ""));
        }

        // Navigation bottom row
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
                            "NAV:MY_WORLDS:" + (validPage - 1)));
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

        items.set(
                SLOT_CREATE,
                new MenuItemDescriptor(
                        SLOT_CREATE,
                        "NETHER_STAR",
                        1,
                        "§a§lCreate New World",
                        List.of("§7Owned: " + worlds.size() + " / " + maxWorlds, "", "§e▶ Click to create a world"),
                        null,
                        "ACTION:CREATE"));

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
                            "NAV:MY_WORLDS:" + (validPage + 1)));
        }

        return new RenderMenuPayload(correlationId, SCREEN_TYPE, title, SIZE, items);
    }

    private static MenuItemDescriptor renderWorldItem(int slot, PlayerWorld world) {
        String material =
                switch (world.state()) {
                    case READY -> "GRASS_BLOCK";
                    case CREATING -> "OAK_SAPLING";
                    case ARCHIVED -> "CHEST";
                    case ARCHIVING, RESTORING -> "CLOCK";
                };

        String stateColor =
                switch (world.state()) {
                    case READY -> "§a";
                    case CREATING -> "§e";
                    case ARCHIVED -> "§7";
                    case ARCHIVING, RESTORING -> "§6";
                };

        List<String> lore = new ArrayList<>();
        lore.add(stateColor + "State: " + world.state().name());
        lore.add("§7Visibility: " + world.visibility().name());
        lore.add("§7Size: " + StorageQuotaResolver.formatBytes(world.storageBytes()));
        lore.add("§8Border: ±" + world.borderRadius() + "m");
        lore.add("");
        if (world.state() == WorldState.READY) {
            lore.add("§a▶ Left-Click: Join World");
            lore.add("§e▶ Right-Click: Manage World");
        } else {
            lore.add("§e▶ Right-Click: Manage World");
        }

        return new MenuItemDescriptor(
                slot,
                material,
                1,
                "§b§l" + world.name(),
                lore,
                null,
                "NAV:WORLD:" + world.id().value());
    }
}
