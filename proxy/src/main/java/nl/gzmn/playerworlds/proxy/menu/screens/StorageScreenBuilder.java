package nl.gzmn.playerworlds.proxy.menu.screens;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import nl.gzmn.playerworlds.core.config.StorageQuotaResolver;
import nl.gzmn.playerworlds.core.menu.MenuItemDescriptor;
import nl.gzmn.playerworlds.core.menu.RenderMenuPayload;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.StorageQuota;
import nl.gzmn.playerworlds.core.model.WorldState;
import nl.gzmn.playerworlds.proxy.command.Messages;
import nl.gzmn.playerworlds.proxy.command.Placeholders;

/**
 * Builds the storage usage screen payload displaying quota limit, current usage,
 * and per-world breakdown.
 */
public final class StorageScreenBuilder {

    public static final String SCREEN_TYPE = "STORAGE";
    public static final int SIZE = 36;

    public static final int SLOT_OVERVIEW = 4;
    public static final int WORLDS_START_SLOT = 9;
    public static final int WORLDS_END_SLOT = 26;
    public static final int SLOT_BACK = 31;

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private StorageScreenBuilder() {}

    public static RenderMenuPayload build(
            Messages messages, long correlationId, StorageQuota quota, List<PlayerWorld> ownedWorlds) {
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(quota, "quota");
        Objects.requireNonNull(ownedWorlds, "ownedWorlds");
        String title = legacy(messages.render("messages.gui.storage-menu.title"));

        List<MenuItemDescriptor> items = new ArrayList<>(SIZE);
        for (int i = 0; i < SIZE; i++) {
            items.add(new MenuItemDescriptor(i, "GRAY_STAINED_GLASS_PANE", 1, " ", List.of(), null, ""));
        }

        // Slot 4: Overview
        items.set(
                SLOT_OVERVIEW,
                new MenuItemDescriptor(
                        SLOT_OVERVIEW,
                        "ENDER_CHEST",
                        1,
                        legacy(messages.render("messages.gui.storage-menu.item.overview.name")),
                        legacyLore(messages.renderLore(
                                "messages.gui.storage-menu.item.overview.lore",
                                Placeholders.bytes("used", quota.usedBytes()),
                                Placeholders.raw(
                                        "limit",
                                        quota.unlimited()
                                                ? "Unlimited"
                                                : StorageQuotaResolver.formatBytes(quota.limitBytes())),
                                Placeholders.raw(
                                        "usage",
                                        quota.unlimited()
                                                ? "Unlimited"
                                                : String.format(Locale.ROOT, "%.1f%%", quota.percentage())),
                                Placeholders.raw("bar", renderProgressBar(quota.percentage())))),
                        null,
                        ""));

        int maxItems = Math.min(ownedWorlds.size(), WORLDS_END_SLOT - WORLDS_START_SLOT + 1);
        for (int i = 0; i < maxItems; i++) {
            PlayerWorld world = ownedWorlds.get(i);
            int slot = WORLDS_START_SLOT + i;
            String mat = (world.state() == WorldState.ARCHIVED) ? "CHEST" : "GRASS_BLOCK";

            items.set(
                    slot,
                    new MenuItemDescriptor(
                            slot,
                            mat,
                            1,
                            legacy(messages.render(
                                    "messages.gui.storage-menu.item.world-entry.name",
                                    Placeholders.text("world", world.name()))),
                            legacyLore(messages.renderLore(
                                    "messages.gui.storage-menu.item.world-entry.lore",
                                    Placeholders.bytes("size", world.storageBytes()),
                                    Placeholders.raw("state", world.state().name()))),
                            null,
                            "NAV:WORLD:" + world.id().value()));
        }

        // Slot 31: Back
        items.set(
                SLOT_BACK,
                new MenuItemDescriptor(
                        SLOT_BACK,
                        "OAK_DOOR",
                        1,
                        legacy(messages.render("messages.gui.storage-menu.item.back.name")),
                        legacyLore(messages.renderLore("messages.gui.storage-menu.item.back.lore")),
                        null,
                        "NAV:MAIN"));

        return new RenderMenuPayload(correlationId, SCREEN_TYPE, title, SIZE, items);
    }

    private static String renderProgressBar(double percentage) {
        int filled = (int) Math.round(percentage / 10.0);
        StringBuilder bar = new StringBuilder(12).append('[');
        for (int i = 0; i < 10; i++) {
            bar.append(i < filled ? '|' : '.');
        }
        return bar.append(']').toString();
    }

    private static String legacy(Component component) {
        return LEGACY.serialize(component);
    }

    private static List<String> legacyLore(List<Component> lines) {
        return lines.stream().map(StorageScreenBuilder::legacy).toList();
    }
}
