package nl.gzmn.playerworlds.proxy.menu.screens;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import nl.gzmn.playerworlds.core.config.StorageQuotaResolver;
import nl.gzmn.playerworlds.core.menu.MenuItemDescriptor;
import nl.gzmn.playerworlds.core.menu.RenderMenuPayload;
import nl.gzmn.playerworlds.core.model.StorageQuota;
import nl.gzmn.playerworlds.proxy.command.Messages;
import nl.gzmn.playerworlds.proxy.command.Placeholders;

/**
 * Builds the main hub screen payload displaying owned worlds count, storage usage summary,
 * pending invites count, and navigation options.
 */
public final class MainScreenBuilder {

    public static final String SCREEN_TYPE = "MAIN";
    public static final int SIZE = 27;

    public static final int SLOT_MY_WORLDS = 10;
    public static final int SLOT_STORAGE = 12;
    public static final int SLOT_INVITES = 14;
    public static final int SLOT_BROWSE = 16;
    public static final int SLOT_CLOSE = 22;

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private MainScreenBuilder() {}

    public static RenderMenuPayload build(
            Messages messages,
            long correlationId,
            int ownedWorldsCount,
            int maxWorlds,
            int pendingInvitesCount,
            StorageQuota storageQuota) {
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(storageQuota, "storageQuota");

        String title = legacy(messages.render("messages.gui.main-menu.title"));

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
                        legacy(messages.render("messages.gui.main-menu.item.my-worlds.name")),
                        legacyLore(messages.renderLore(
                                "messages.gui.main-menu.item.my-worlds.lore",
                                Placeholders.count("owned", ownedWorldsCount),
                                Placeholders.count("max", maxWorlds))),
                        null,
                        "NAV:MY_WORLDS"));

        items.set(
                SLOT_STORAGE,
                new MenuItemDescriptor(
                        SLOT_STORAGE,
                        "CHEST",
                        1,
                        legacy(messages.render("messages.gui.main-menu.item.storage.name")),
                        legacyLore(messages.renderLore(
                                "messages.gui.main-menu.item.storage.lore",
                                Placeholders.bytes("used", storageQuota.usedBytes()),
                                Placeholders.raw(
                                        "limit",
                                        storageQuota.unlimited()
                                                ? "Unlimited"
                                                : StorageQuotaResolver.formatBytes(storageQuota.limitBytes())))),
                        null,
                        "NAV:STORAGE"));

        items.set(
                SLOT_INVITES,
                new MenuItemDescriptor(
                        SLOT_INVITES,
                        "WRITABLE_BOOK",
                        1,
                        legacy(messages.render("messages.gui.main-menu.item.invites.name")),
                        legacyLore(messages.renderLore(
                                "messages.gui.main-menu.item.invites.lore",
                                Placeholders.count("count", pendingInvitesCount))),
                        null,
                        "NAV:INVITES"));

        items.set(
                SLOT_BROWSE,
                new MenuItemDescriptor(
                        SLOT_BROWSE,
                        "COMPASS",
                        1,
                        legacy(messages.render("messages.gui.main-menu.item.browse.name")),
                        legacyLore(messages.renderLore("messages.gui.main-menu.item.browse.lore")),
                        null,
                        "NAV:BROWSE"));

        items.set(
                SLOT_CLOSE,
                new MenuItemDescriptor(
                        SLOT_CLOSE,
                        "BARRIER",
                        1,
                        legacy(messages.render("messages.gui.main-menu.item.close.name")),
                        legacyLore(messages.renderLore("messages.gui.main-menu.item.close.lore")),
                        null,
                        "ACTION:CLOSE"));

        return new RenderMenuPayload(correlationId, SCREEN_TYPE, title, SIZE, items);
    }

    private static String legacy(Component component) {
        return LEGACY.serialize(component);
    }

    private static List<String> legacyLore(List<Component> lines) {
        return lines.stream().map(MainScreenBuilder::legacy).toList();
    }
}
