package nl.gzmn.playerworlds.proxy.menu.screens;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import nl.gzmn.playerworlds.core.config.StorageQuotaResolver;
import nl.gzmn.playerworlds.core.menu.MenuItemDescriptor;
import nl.gzmn.playerworlds.core.menu.RenderMenuPayload;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.Role;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.model.WorldState;
import org.jspecify.annotations.Nullable;

/**
 * Builds the paginated screen payload listing the worlds a player can reach with
 * quick actions and world creation triggers.
 *
 * <p>Two lists, one screen: the worlds the player owns come first, then the ones
 * they were invited to and accepted (FR-7). A world an invite made reachable is
 * otherwise findable only by remembering its owner's name, which is the state
 * the invite was supposed to end. The owned count on the create button still
 * counts only owned worlds, because that is the number FR-1's cap is about.
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
        return build(correlationId, worlds, List.of(), Map.of(), page, maxWorlds);
    }

    /**
     * Builds the screen from the two lists separately, so the owned/shared split
     * survives into the rendering.
     *
     * @param owned worlds whose {@code owner_uuid} is the viewing player (FR-31a)
     * @param shared worlds the player is a member of but does not own
     * @param sharedRoles the player's role in each shared world, for the lore line
     */
    public static RenderMenuPayload build(
            long correlationId,
            List<PlayerWorld> owned,
            List<PlayerWorld> shared,
            Map<WorldId, Role> sharedRoles,
            int page,
            int maxWorlds) {
        Objects.requireNonNull(owned, "owned");
        Objects.requireNonNull(shared, "shared");
        Objects.requireNonNull(sharedRoles, "sharedRoles");

        List<PlayerWorld> all = new ArrayList<>(owned.size() + shared.size());
        all.addAll(owned);
        all.addAll(shared);

        int validPage = Math.max(0, page);
        int totalPages = Math.max(1, (int) Math.ceil((double) all.size() / PAGE_SIZE));
        String title = "§8My Worlds (Page " + (validPage + 1) + "/" + totalPages + ")";

        List<MenuItemDescriptor> items = new ArrayList<>(SIZE);
        for (int i = 0; i < SIZE; i++) {
            items.add(new MenuItemDescriptor(i, "GRAY_STAINED_GLASS_PANE", 1, " ", List.of(), null, ""));
        }

        int startIndex = validPage * PAGE_SIZE;
        int endIndex = Math.min(all.size(), startIndex + PAGE_SIZE);

        for (int i = startIndex; i < endIndex; i++) {
            int slot = i - startIndex;
            PlayerWorld world = all.get(i);
            boolean isOwned = i < owned.size();
            items.set(slot, renderWorldItem(slot, world, isOwned, isOwned ? Role.OWNER : sharedRoles.get(world.id())));
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

        List<String> createLore = new ArrayList<>();
        createLore.add("§7Owned: " + owned.size() + " / " + maxWorlds);
        if (!shared.isEmpty()) {
            createLore.add("§7Shared with you: " + shared.size());
        }
        createLore.add("");
        createLore.add("§e▶ Click to create a world");
        items.set(
                SLOT_CREATE,
                new MenuItemDescriptor(
                        SLOT_CREATE,
                        "NETHER_STAR",
                        1,
                        "§a§lCreate New World",
                        List.copyOf(createLore),
                        null,
                        "ACTION:CREATE"));

        if ((validPage + 1) * PAGE_SIZE < all.size()) {
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

    private static MenuItemDescriptor renderWorldItem(int slot, PlayerWorld world, boolean owned, @Nullable Role role) {
        String material =
                switch (world.state()) {
                    case READY -> owned ? "GRASS_BLOCK" : "PLAYER_HEAD";
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
        if (!owned) {
            lore.add("§d§oShared with you" + (role == null ? "" : " — " + role.name()));
        }
        lore.add("§7Visibility: " + world.visibility().name());
        lore.add("§7Size: " + StorageQuotaResolver.formatBytes(world.storageBytes()));
        lore.add("§8Border: ±" + world.borderRadius() + "m");
        lore.add("");
        if (world.state() == WorldState.READY) {
            lore.add("§a▶ Left-Click: Join World");
        }
        // The proxy is the authority on who may manage a world; a non-owner who
        // opens the detail screen sees it read-only rather than being told the
        // world does not exist, so the entry is offered either way.
        lore.add(owned ? "§e▶ Right-Click: Manage World" : "§e▶ Right-Click: World Details");

        return new MenuItemDescriptor(
                slot,
                material,
                1,
                (owned ? "§b§l" : "§d§l") + world.name(),
                lore,
                null,
                "NAV:WORLD:" + world.id().value());
    }
}
