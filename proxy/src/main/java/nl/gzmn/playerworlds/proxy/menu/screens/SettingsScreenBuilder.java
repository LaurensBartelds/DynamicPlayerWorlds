package nl.gzmn.playerworlds.proxy.menu.screens;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import nl.gzmn.playerworlds.core.menu.MenuItemDescriptor;
import nl.gzmn.playerworlds.core.menu.RenderMenuPayload;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.WorldSettings;
import nl.gzmn.playerworlds.proxy.command.Messages;
import nl.gzmn.playerworlds.proxy.command.Placeholders;

/**
 * Builds the world settings configuration screen payload allowing owners to toggle
 * gameplay rules such as PvP, container access, visitor interaction, and mob griefing.
 *
 * <p>The backend's {@code SettingsMenu} additionally renders the FR-9i gamerule
 * toggles and numeric steppers; this proxy mirror does not yet, since the
 * {@code ACTION:SET_SETTING} handling for those setting ids belongs to the
 * in-progress {@code WorldActions}/{@code WorldCommand} effort this migration
 * does not touch. This screen keeps its existing scope (pvp, containers,
 * interact, mob-griefing) and only migrates that scope's text to the message
 * catalog.
 */
public final class SettingsScreenBuilder {

    public static final String SCREEN_TYPE = "SETTINGS";
    public static final int SIZE = 27;

    public static final int SLOT_INFO = 4;
    public static final int SLOT_PVP = 10;
    public static final int SLOT_CONTAINERS = 12;
    public static final int SLOT_INTERACT = 14;
    public static final int SLOT_MOB_GRIEFING = 16;
    public static final int SLOT_BACK = 22;

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private SettingsScreenBuilder() {}

    public static RenderMenuPayload build(
            Messages messages, long correlationId, PlayerWorld world, WorldSettings settings) {
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(settings, "settings");
        String title =
                legacy(messages.render("messages.gui.settings-menu.title", Placeholders.text("world", world.name())));

        List<MenuItemDescriptor> items = new ArrayList<>(SIZE);
        for (int i = 0; i < SIZE; i++) {
            items.add(new MenuItemDescriptor(i, "GRAY_STAINED_GLASS_PANE", 1, " ", List.of(), null, ""));
        }

        // Slot 4: Info
        items.set(
                SLOT_INFO,
                new MenuItemDescriptor(
                        SLOT_INFO,
                        "BEACON",
                        1,
                        legacy(messages.render(
                                "messages.gui.settings-menu.item.info.name", Placeholders.text("world", world.name()))),
                        legacyLore(messages.renderLore("messages.gui.settings-menu.item.info.lore")),
                        null,
                        ""));

        // Slot 10: PvP
        items.set(SLOT_PVP, toggleItem(messages, SLOT_PVP, "DIAMOND_SWORD", "pvp", settings.pvp(), world));

        // Slot 12: Containers
        items.set(
                SLOT_CONTAINERS,
                toggleItem(
                        messages,
                        SLOT_CONTAINERS,
                        "CHEST",
                        "containers",
                        settings.visitorsMayOpenContainers(),
                        world,
                        "Allowed",
                        "Restricted"));

        // Slot 14: Interact
        items.set(
                SLOT_INTERACT,
                toggleItem(
                        messages,
                        SLOT_INTERACT,
                        "LEVER",
                        "interact",
                        settings.visitorsMayInteract(),
                        world,
                        "Allowed",
                        "Restricted"));

        // Slot 16: Mob Griefing
        items.set(
                SLOT_MOB_GRIEFING,
                toggleItem(messages, SLOT_MOB_GRIEFING, "CREEPER_HEAD", "mob-griefing", settings.mobGriefing(), world));

        // Slot 22: Back
        items.set(
                SLOT_BACK,
                new MenuItemDescriptor(
                        SLOT_BACK,
                        "OAK_DOOR",
                        1,
                        legacy(messages.render("messages.gui.settings-menu.item.back.name")),
                        legacyLore(messages.renderLore("messages.gui.settings-menu.item.back.lore")),
                        null,
                        "NAV:WORLD:" + world.id().value()));

        return new RenderMenuPayload(correlationId, SCREEN_TYPE, title, SIZE, items);
    }

    /** A toggle whose lore reports "Enabled"/"Disabled" (pvp, mob-griefing). */
    private static MenuItemDescriptor toggleItem(
            Messages messages, int slot, String material, String settingId, boolean enabled, PlayerWorld world) {
        return toggleItem(messages, slot, material, settingId, enabled, world, "Enabled", "Disabled");
    }

    /** A toggle with custom on/off words (containers: Allowed/Restricted, interact: Allowed/Restricted). */
    private static MenuItemDescriptor toggleItem(
            Messages messages,
            int slot,
            String material,
            String settingId,
            boolean enabled,
            PlayerWorld world,
            String onWord,
            String offWord) {
        Component name = messages.render(
                        "messages.gui.settings-menu.item." + settingId + ".name",
                        Placeholders.raw("state", enabled ? onWord : offWord))
                .colorIfAbsent(enabled ? NamedTextColor.GREEN : NamedTextColor.RED);
        List<Component> lore = List.of(
                messages.render("messages.gui.settings-menu.item." + settingId + ".description"),
                Component.empty(),
                messages.render(
                        "messages.gui.settings-menu.item.toggle.hint",
                        Placeholders.raw("next-state", enabled ? "OFF" : "ON")));
        return new MenuItemDescriptor(
                slot,
                material,
                1,
                legacy(name),
                legacyLore(lore),
                null,
                "ACTION:SET_SETTING:" + world.id().value() + ":" + settingId + ":" + !enabled);
    }

    private static String legacy(Component component) {
        return LEGACY.serialize(component);
    }

    private static List<String> legacyLore(List<Component> lines) {
        return lines.stream().map(SettingsScreenBuilder::legacy).toList();
    }
}
