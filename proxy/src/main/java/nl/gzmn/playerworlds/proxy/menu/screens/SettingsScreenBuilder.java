package nl.gzmn.playerworlds.proxy.menu.screens;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import nl.gzmn.playerworlds.core.menu.MenuItemDescriptor;
import nl.gzmn.playerworlds.core.menu.RenderMenuPayload;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.WorldSettings;

/**
 * Builds the world settings configuration screen payload allowing owners to toggle
 * gameplay rules such as PvP, container access, visitor interaction, and mob griefing.
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

    private SettingsScreenBuilder() {}

    public static RenderMenuPayload build(long correlationId, PlayerWorld world, WorldSettings settings) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(settings, "settings");
        String title = "§8Settings: " + world.name();

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
                        "§6§lWorld Settings: " + world.name(),
                        List.of("§7Configure gameplay & interaction rules"),
                        null,
                        ""));

        // Slot 10: PvP
        items.set(
                SLOT_PVP,
                new MenuItemDescriptor(
                        SLOT_PVP,
                        "DIAMOND_SWORD",
                        1,
                        (settings.pvp() ? "§a§l" : "§c§l") + "PvP Combat: " + (settings.pvp() ? "Enabled" : "Disabled"),
                        List.of(
                                "§7Allows players to damage each other",
                                "",
                                "§e▶ Click to toggle " + (settings.pvp() ? "OFF" : "ON")),
                        null,
                        "ACTION:SET_SETTING:" + world.id().value() + ":pvp:" + !settings.pvp()));

        // Slot 12: Containers
        items.set(
                SLOT_CONTAINERS,
                new MenuItemDescriptor(
                        SLOT_CONTAINERS,
                        "CHEST",
                        1,
                        (settings.visitorsMayOpenContainers() ? "§a§l" : "§c§l")
                                + "Visitor Containers: "
                                + (settings.visitorsMayOpenContainers() ? "Allowed" : "Restricted"),
                        List.of(
                                "§7Allows visitors to open chests & containers",
                                "",
                                "§e▶ Click to toggle " + (settings.visitorsMayOpenContainers() ? "OFF" : "ON")),
                        null,
                        "ACTION:SET_SETTING:" + world.id().value() + ":containers:"
                                + !settings.visitorsMayOpenContainers()));

        // Slot 14: Interact
        items.set(
                SLOT_INTERACT,
                new MenuItemDescriptor(
                        SLOT_INTERACT,
                        "LEVER",
                        1,
                        (settings.visitorsMayInteract() ? "§a§l" : "§c§l")
                                + "Visitor Interact: "
                                + (settings.visitorsMayInteract() ? "Allowed" : "Restricted"),
                        List.of(
                                "§7Allows visitors to use doors, buttons & redstone",
                                "",
                                "§e▶ Click to toggle " + (settings.visitorsMayInteract() ? "OFF" : "ON")),
                        null,
                        "ACTION:SET_SETTING:" + world.id().value() + ":interact:" + !settings.visitorsMayInteract()));

        // Slot 16: Mob Griefing
        items.set(
                SLOT_MOB_GRIEFING,
                new MenuItemDescriptor(
                        SLOT_MOB_GRIEFING,
                        "CREEPER_HEAD",
                        1,
                        (settings.mobGriefing() ? "§a§l" : "§c§l")
                                + "Mob Griefing: "
                                + (settings.mobGriefing() ? "Enabled" : "Disabled"),
                        List.of(
                                "§7Controls Creeper explosions and mob damage",
                                "",
                                "§e▶ Click to toggle " + (settings.mobGriefing() ? "OFF" : "ON")),
                        null,
                        "ACTION:SET_SETTING:" + world.id().value() + ":mob-griefing:" + !settings.mobGriefing()));

        // Slot 22: Back
        items.set(
                SLOT_BACK,
                new MenuItemDescriptor(
                        SLOT_BACK,
                        "OAK_DOOR",
                        1,
                        "§c§lBack to World Menu",
                        List.of("§8▶ Click to return"),
                        null,
                        "NAV:WORLD:" + world.id().value()));

        return new RenderMenuPayload(correlationId, SCREEN_TYPE, title, SIZE, items);
    }
}
