package nl.gzmn.playerworlds.proxy.menu.screens;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import nl.gzmn.playerworlds.core.config.StorageQuotaResolver;
import nl.gzmn.playerworlds.core.menu.MenuItemDescriptor;
import nl.gzmn.playerworlds.core.menu.RenderMenuPayload;
import nl.gzmn.playerworlds.core.model.StorageQuota;

/**
 * Builds the main hub screen payload displaying owned worlds count, storage usage summary,
 * pending invites count, and navigation options.
 */
public final class MainScreenBuilder {

    public static final String SCREEN_TYPE = "MAIN";
    public static final int SIZE = 27;
    public static final String TITLE = "§8Dynamic Player Worlds";

    public static final int SLOT_MY_WORLDS = 10;
    public static final int SLOT_STORAGE = 12;
    public static final int SLOT_INVITES = 14;
    public static final int SLOT_BROWSE = 16;
    public static final int SLOT_CLOSE = 22;

    private MainScreenBuilder() {}

    public static RenderMenuPayload build(
            long correlationId,
            int ownedWorldsCount,
            int maxWorlds,
            int pendingInvitesCount,
            StorageQuota storageQuota) {
        Objects.requireNonNull(storageQuota, "storageQuota");

        List<MenuItemDescriptor> items = new ArrayList<>(SIZE);
        for (int i = 0; i < SIZE; i++) {
            items.add(new MenuItemDescriptor(i, "GRAY_STAINED_GLASS_PANE", 1, " ", List.of(), null, ""));
        }

        items.set(
                SLOT_MY_WORLDS,
                new MenuItemDescriptor(
                        SLOT_MY_WORLDS,
                        "GRASS_BLOCK",
                        1,
                        "§a§lMy Worlds",
                        List.of(
                                "§7View and manage your worlds",
                                "§8Owned: " + ownedWorldsCount + " / " + maxWorlds,
                                "",
                                "§e▶ Click to view"),
                        null,
                        "NAV:MY_WORLDS"));

        items.set(
                SLOT_STORAGE,
                new MenuItemDescriptor(
                        SLOT_STORAGE,
                        "CHEST",
                        1,
                        "§b§lStorage Usage",
                        List.of(
                                "§7Used: " + StorageQuotaResolver.formatBytes(storageQuota.usedBytes()),
                                "§7Limit: "
                                        + (storageQuota.unlimited()
                                                ? "Unlimited"
                                                : StorageQuotaResolver.formatBytes(storageQuota.limitBytes())),
                                "",
                                "§e▶ Click to view breakdown"),
                        null,
                        "NAV:STORAGE"));

        items.set(
                SLOT_INVITES,
                new MenuItemDescriptor(
                        SLOT_INVITES,
                        "WRITABLE_BOOK",
                        1,
                        "§6§lPending Invites",
                        List.of("§7Pending: " + pendingInvitesCount, "", "§e▶ Click to view invites"),
                        null,
                        "NAV:INVITES"));

        items.set(
                SLOT_BROWSE,
                new MenuItemDescriptor(
                        SLOT_BROWSE,
                        "COMPASS",
                        1,
                        "§d§lBrowse Public Worlds",
                        List.of("§7Explore worlds shared by the community", "", "§e▶ Click to browse"),
                        null,
                        "NAV:BROWSE"));

        items.set(
                SLOT_CLOSE,
                new MenuItemDescriptor(
                        SLOT_CLOSE,
                        "BARRIER",
                        1,
                        "§c§lClose Menu",
                        List.of("§8▶ Click to exit"),
                        null,
                        "ACTION:CLOSE"));

        return new RenderMenuPayload(correlationId, SCREEN_TYPE, TITLE, SIZE, items);
    }
}
