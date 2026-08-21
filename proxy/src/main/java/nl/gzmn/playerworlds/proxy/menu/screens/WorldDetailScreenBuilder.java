package nl.gzmn.playerworlds.proxy.menu.screens;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import nl.gzmn.playerworlds.core.config.StorageQuotaResolver;
import nl.gzmn.playerworlds.core.menu.MenuItemDescriptor;
import nl.gzmn.playerworlds.core.menu.RenderMenuPayload;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.Visibility;
import nl.gzmn.playerworlds.core.model.WorldState;

/**
 * Builds the management screen payload for a single world, allowing the owner to join/restore,
 * manage members/bans, toggle visibility, configure settings, view storage, or archive/delete.
 *
 * <p>A member who is not the owner gets the same screen without the management
 * half. That is not decoration: {@code ACTION:ARCHIVE} carries a world
 * <em>name</em>, which the proxy resolves against the caller's own worlds — so a
 * visitor pressing Archive on a world called "home" would have archived their
 * own world of that name. Everything else here is refused for a non-owner
 * (FR-31a), but a control that cannot succeed should not be drawn, and this one
 * could succeed against the wrong world.
 */
public final class WorldDetailScreenBuilder {

    public static final String SCREEN_TYPE = "WORLD_DETAILS";
    public static final int SIZE = 27;

    public static final int SLOT_INFO = 4;
    public static final int SLOT_JOIN = 10;
    public static final int SLOT_MEMBERS = 11;
    public static final int SLOT_SETTINGS = 12;
    public static final int SLOT_VISIBILITY = 13;
    public static final int SLOT_BANS = 14;
    public static final int SLOT_STORAGE = 15;
    public static final int SLOT_ARCHIVE = 16;
    public static final int SLOT_BACK = 18;

    private WorldDetailScreenBuilder() {}

    /** The owner's view, with every control. */
    public static RenderMenuPayload build(long correlationId, PlayerWorld world) {
        return build(correlationId, world, true);
    }

    /**
     * Builds the screen as one viewer sees it.
     *
     * @param manage whether the viewer is the world's owner (FR-31a). False draws
     *     the same screen without the controls only an owner may use.
     */
    public static RenderMenuPayload build(long correlationId, PlayerWorld world, boolean manage) {
        Objects.requireNonNull(world, "world");
        String title = (manage ? "§8Manage: " : "§8World: ") + world.name();

        List<MenuItemDescriptor> items = new ArrayList<>(SIZE);
        for (int i = 0; i < SIZE; i++) {
            items.add(new MenuItemDescriptor(i, "GRAY_STAINED_GLASS_PANE", 1, " ", List.of(), null, ""));
        }

        // Slot 4: Overview
        items.set(
                SLOT_INFO,
                new MenuItemDescriptor(
                        SLOT_INFO,
                        "BEACON",
                        1,
                        "§6§l" + world.name(),
                        List.of(
                                "§7State: " + world.state().name(),
                                "§7Visibility: " + world.visibility().name(),
                                "§7Border: ±" + world.borderRadius() + "m",
                                "§8Seed: " + world.seed(),
                                "§7Storage: " + StorageQuotaResolver.formatBytes(world.storageBytes())),
                        null,
                        ""));

        // Slot 10: Join or Restore
        if (world.state() == WorldState.ARCHIVED) {
            items.set(
                    SLOT_JOIN,
                    manage
                            ? new MenuItemDescriptor(
                                    SLOT_JOIN,
                                    "ANVIL",
                                    1,
                                    "§a§lRestore World",
                                    List.of("§7Restore this world from cold storage", "", "§e▶ Click to restore"),
                                    null,
                                    "ACTION:RESTORE:" + world.name())
                            : new MenuItemDescriptor(
                                    SLOT_JOIN,
                                    "ANVIL",
                                    1,
                                    "§7§lArchived",
                                    List.of("§8Only its owner can bring this world back"),
                                    null,
                                    ""));
        } else {
            items.set(
                    SLOT_JOIN,
                    new MenuItemDescriptor(
                            SLOT_JOIN,
                            "ENDER_PEARL",
                            1,
                            "§a§lJoin World",
                            List.of("§7Teleport directly to this world", "", "§e▶ Click to join"),
                            null,
                            "ACTION:JOIN:" + world.id().value()));
        }

        if (manage) {
            addManagementControls(items, world);
        }

        // Slot 18: Back
        items.set(
                SLOT_BACK,
                new MenuItemDescriptor(
                        SLOT_BACK,
                        "OAK_DOOR",
                        1,
                        "§c§lBack to My Worlds",
                        List.of("§8▶ Click to return"),
                        null,
                        "NAV:MY_WORLDS"));

        return new RenderMenuPayload(correlationId, SCREEN_TYPE, title, SIZE, items);
    }

    /** The half of the screen only {@code owner_uuid} may act on (FR-31a). */
    private static void addManagementControls(List<MenuItemDescriptor> items, PlayerWorld world) {
        // Slot 11: Members
        items.set(
                SLOT_MEMBERS,
                new MenuItemDescriptor(
                        SLOT_MEMBERS,
                        "PLAYER_HEAD",
                        1,
                        "§b§lMembers & Permissions",
                        List.of(
                                "§7View members, invite players, or promote builders",
                                "",
                                "§e▶ Click to manage members"),
                        null,
                        "NAV:MEMBERS:" + world.id().value()));

        // Slot 12: Settings
        items.set(
                SLOT_SETTINGS,
                new MenuItemDescriptor(
                        SLOT_SETTINGS,
                        "COMPARATOR",
                        1,
                        "§e§lWorld Settings",
                        List.of("§7Configure PvP, container access, and mob griefing", "", "§e▶ Click to configure"),
                        null,
                        "NAV:SETTINGS:" + world.id().value()));

        // Slot 13: Visibility
        Visibility nextVis = (world.visibility() == Visibility.PUBLIC) ? Visibility.PRIVATE : Visibility.PUBLIC;
        items.set(
                SLOT_VISIBILITY,
                new MenuItemDescriptor(
                        SLOT_VISIBILITY,
                        "ENDER_EYE",
                        1,
                        "§d§lVisibility: " + world.visibility().name(),
                        List.of(
                                "§7Current: "
                                        + (world.visibility() == Visibility.PUBLIC
                                                ? "Public (anyone can browse and join)"
                                                : "Private (invite-only)"),
                                "",
                                "§e▶ Click to toggle Public / Private"),
                        null,
                        "ACTION:SET_VISIBILITY:" + world.id().value() + ":" + nextVis.name()));

        // Slot 14: Bans
        items.set(
                SLOT_BANS,
                new MenuItemDescriptor(
                        SLOT_BANS,
                        "IRON_BARS",
                        1,
                        "§c§lBanned Players",
                        List.of("§7View and revoke bans from this world", "", "§e▶ Click to manage bans"),
                        null,
                        "NAV:BANS:" + world.id().value()));

        // Slot 15: Storage
        items.set(
                SLOT_STORAGE,
                new MenuItemDescriptor(
                        SLOT_STORAGE,
                        "CHEST",
                        1,
                        "§9§lStorage Usage",
                        List.of(
                                "§7World size: " + StorageQuotaResolver.formatBytes(world.storageBytes()),
                                "",
                                "§e▶ Click to view storage breakdown"),
                        null,
                        "NAV:STORAGE"));

        // Slot 16: Archive or Permanently Delete
        if (world.state() == WorldState.ARCHIVED) {
            items.set(
                    SLOT_ARCHIVE,
                    new MenuItemDescriptor(
                            SLOT_ARCHIVE,
                            "LAVA_BUCKET",
                            1,
                            "§4§lPermanently Delete World",
                            List.of(
                                    "§c§l⚠ Irreversible Action",
                                    "§7Permanently destroys all chunks and backup archives.",
                                    "",
                                    "§4▶ Click to delete permanently (requires confirm)"),
                            null,
                            "ACTION:ARCHIVE:" + world.name()));
        } else {
            items.set(
                    SLOT_ARCHIVE,
                    new MenuItemDescriptor(
                            SLOT_ARCHIVE,
                            "TNT",
                            1,
                            "§4§lArchive World",
                            List.of(
                                    "§7Pack this world into cold storage and free a slot",
                                    "",
                                    "§c▶ Click to archive (requires confirm)"),
                            null,
                            "ACTION:ARCHIVE:" + world.name()));
        }
    }
}
