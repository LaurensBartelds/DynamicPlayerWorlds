package nl.gzmn.playerworlds.proxy.menu.screens;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import nl.gzmn.playerworlds.core.menu.MenuItemDescriptor;
import nl.gzmn.playerworlds.core.menu.RenderMenuPayload;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.Visibility;
import nl.gzmn.playerworlds.core.model.WorldState;
import nl.gzmn.playerworlds.proxy.command.Messages;
import nl.gzmn.playerworlds.proxy.command.Placeholders;

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

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private WorldDetailScreenBuilder() {}

    /** The owner's view, with every control. */
    public static RenderMenuPayload build(Messages messages, long correlationId, PlayerWorld world) {
        return build(messages, correlationId, world, true);
    }

    /**
     * Builds the screen as one viewer sees it.
     *
     * @param manage whether the viewer is the world's owner (FR-31a). False draws
     *     the same screen without the controls only an owner may use.
     */
    public static RenderMenuPayload build(Messages messages, long correlationId, PlayerWorld world, boolean manage) {
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(world, "world");
        String title = legacy(messages.render(
                "messages.gui.world-menu.title",
                Placeholders.raw("prefix", manage ? "Manage" : "World"),
                Placeholders.text("world", world.name())));

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
                        legacy(messages.render(
                                "messages.gui.world-menu.item.info.name", Placeholders.text("world", world.name()))),
                        legacyLore(messages.renderLore(
                                "messages.gui.world-menu.item.info.lore",
                                Placeholders.raw("state", world.state().name()),
                                Placeholders.raw(
                                        "visibility", world.visibility().name()),
                                Placeholders.count("radius", world.borderRadius()),
                                Placeholders.count("seed", world.seed()),
                                Placeholders.bytes("size", world.storageBytes()))),
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
                                    legacy(messages.render("messages.gui.world-menu.item.restore.name")),
                                    legacyLore(messages.renderLore("messages.gui.world-menu.item.restore.lore")),
                                    null,
                                    "ACTION:RESTORE:" + world.name())
                            : new MenuItemDescriptor(
                                    SLOT_JOIN,
                                    "ANVIL",
                                    1,
                                    legacy(messages.render("messages.gui.world-menu.item.archived-locked.name")),
                                    legacyLore(
                                            messages.renderLore("messages.gui.world-menu.item.archived-locked.lore")),
                                    null,
                                    ""));
        } else {
            items.set(
                    SLOT_JOIN,
                    new MenuItemDescriptor(
                            SLOT_JOIN,
                            "ENDER_PEARL",
                            1,
                            legacy(messages.render("messages.gui.world-menu.item.join.name")),
                            legacyLore(messages.renderLore("messages.gui.world-menu.item.join.lore")),
                            null,
                            "ACTION:JOIN:" + world.id().value()));
        }

        if (manage) {
            addManagementControls(messages, items, world);
        }

        // Slot 18: Back
        items.set(
                SLOT_BACK,
                new MenuItemDescriptor(
                        SLOT_BACK,
                        "OAK_DOOR",
                        1,
                        legacy(messages.render("messages.gui.world-menu.item.back.name")),
                        legacyLore(messages.renderLore("messages.gui.world-menu.item.back.lore")),
                        null,
                        "NAV:MY_WORLDS"));

        return new RenderMenuPayload(correlationId, SCREEN_TYPE, title, SIZE, items);
    }

    /** The half of the screen only {@code owner_uuid} may act on (FR-31a). */
    private static void addManagementControls(Messages messages, List<MenuItemDescriptor> items, PlayerWorld world) {
        // Slot 11: Members
        items.set(
                SLOT_MEMBERS,
                new MenuItemDescriptor(
                        SLOT_MEMBERS,
                        "PLAYER_HEAD",
                        1,
                        legacy(messages.render("messages.gui.world-menu.item.members.name")),
                        legacyLore(messages.renderLore("messages.gui.world-menu.item.members.lore")),
                        null,
                        "NAV:MEMBERS:" + world.id().value()));

        // Slot 12: Settings
        items.set(
                SLOT_SETTINGS,
                new MenuItemDescriptor(
                        SLOT_SETTINGS,
                        "COMPARATOR",
                        1,
                        legacy(messages.render("messages.gui.world-menu.item.settings.name")),
                        legacyLore(messages.renderLore("messages.gui.world-menu.item.settings.lore")),
                        null,
                        "NAV:SETTINGS:" + world.id().value()));

        // Slot 13: Visibility
        Visibility nextVis = (world.visibility() == Visibility.PUBLIC) ? Visibility.PRIVATE : Visibility.PUBLIC;
        String visibilityDescription = world.visibility() == Visibility.PUBLIC
                ? "Public (anyone can browse and join)"
                : "Private (invite-only)";
        items.set(
                SLOT_VISIBILITY,
                new MenuItemDescriptor(
                        SLOT_VISIBILITY,
                        "ENDER_EYE",
                        1,
                        legacy(messages.render(
                                "messages.gui.world-menu.item.visibility.name",
                                Placeholders.raw(
                                        "visibility", world.visibility().name()))),
                        legacyLore(messages.renderLore(
                                "messages.gui.world-menu.item.visibility.lore",
                                Placeholders.raw("description", visibilityDescription))),
                        null,
                        "ACTION:SET_VISIBILITY:" + world.id().value() + ":" + nextVis.name()));

        // Slot 14: Bans
        items.set(
                SLOT_BANS,
                new MenuItemDescriptor(
                        SLOT_BANS,
                        "IRON_BARS",
                        1,
                        legacy(messages.render("messages.gui.world-menu.item.bans.name")),
                        legacyLore(messages.renderLore("messages.gui.world-menu.item.bans.lore")),
                        null,
                        "NAV:BANS:" + world.id().value()));

        // Slot 15: Storage
        items.set(
                SLOT_STORAGE,
                new MenuItemDescriptor(
                        SLOT_STORAGE,
                        "CHEST",
                        1,
                        legacy(messages.render("messages.gui.world-menu.item.storage.name")),
                        legacyLore(messages.renderLore(
                                "messages.gui.world-menu.item.storage.lore",
                                Placeholders.bytes("size", world.storageBytes()))),
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
                            legacy(messages.render("messages.gui.world-menu.item.delete-permanently.name")),
                            legacyLore(messages.renderLore("messages.gui.world-menu.item.delete-permanently.lore")),
                            null,
                            "ACTION:ARCHIVE:" + world.name()));
        } else {
            items.set(
                    SLOT_ARCHIVE,
                    new MenuItemDescriptor(
                            SLOT_ARCHIVE,
                            "TNT",
                            1,
                            legacy(messages.render("messages.gui.world-menu.item.archive.name")),
                            legacyLore(messages.renderLore("messages.gui.world-menu.item.archive.lore")),
                            null,
                            "ACTION:ARCHIVE:" + world.name()));
        }
    }

    private static String legacy(Component component) {
        return LEGACY.serialize(component);
    }

    private static List<String> legacyLore(List<Component> lines) {
        return lines.stream().map(WorldDetailScreenBuilder::legacy).toList();
    }
}
