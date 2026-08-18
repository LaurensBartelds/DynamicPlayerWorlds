package nl.gzmn.playerworlds.backend.gui.screen;

import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import nl.gzmn.playerworlds.backend.gui.GuiScreen;
import nl.gzmn.playerworlds.backend.gui.ItemUtil;
import nl.gzmn.playerworlds.backend.gui.MenuChannel;
import nl.gzmn.playerworlds.backend.gui.MenuHolder;
import nl.gzmn.playerworlds.backend.gui.MenuService;
import nl.gzmn.playerworlds.core.menu.MenuIntent;
import nl.gzmn.playerworlds.core.menu.MenuResult;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.WorldSettings;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.jspecify.annotations.Nullable;

/**
 * World settings configuration screen allowing owners to toggle gameplay rules
 * such as PvP, container access, visitor interaction, and mob griefing.
 */
public final class SettingsMenu implements GuiScreen {

    public static final int SLOT_INFO = 4;
    public static final int SLOT_PVP = 10;
    public static final int SLOT_CONTAINERS = 12;
    public static final int SLOT_INTERACT = 14;
    public static final int SLOT_MOB_GRIEFING = 16;
    public static final int SLOT_BACK = 22;

    private final MenuService menuService;
    private final @Nullable MenuChannel menuChannel;
    private final PlayerWorld world;
    private final WorldSettings settings;

    public SettingsMenu(
            MenuService menuService, @Nullable MenuChannel menuChannel, PlayerWorld world, WorldSettings settings) {
        this.menuService = Objects.requireNonNull(menuService, "menuService");
        this.menuChannel = menuChannel;
        this.world = Objects.requireNonNull(world, "world");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public PlayerWorld world() {
        return world;
    }

    public WorldSettings settings() {
        return settings;
    }

    @Override
    public Inventory render(Player player) {
        Objects.requireNonNull(player, "player");
        MenuHolder holder = new MenuHolder(this);
        Inventory inventory = Bukkit.createInventory(
                holder, 27, Component.text("Settings: " + world.name(), NamedTextColor.DARK_GRAY));
        holder.setInventory(inventory);

        for (int i = 0; i < 27; i++) {
            inventory.setItem(i, ItemUtil.filler());
        }

        // Slot 4: Overview
        inventory.setItem(
                SLOT_INFO,
                ItemUtil.create(
                        Material.BEACON,
                        Component.text("World Settings: " + world.name(), NamedTextColor.GOLD, TextDecoration.BOLD),
                        Component.text("Configure gameplay & interaction rules", NamedTextColor.GRAY)));

        // Slot 10: PvP
        inventory.setItem(
                SLOT_PVP,
                ItemUtil.create(
                        Material.DIAMOND_SWORD,
                        Component.text(
                                "PvP Combat: " + (settings.pvp() ? "Enabled" : "Disabled"),
                                settings.pvp() ? NamedTextColor.GREEN : NamedTextColor.RED,
                                TextDecoration.BOLD),
                        Component.text("Allows players to damage each other", NamedTextColor.GRAY),
                        Component.empty(),
                        Component.text("▶ Click to toggle " + (settings.pvp() ? "OFF" : "ON"), NamedTextColor.YELLOW)));

        // Slot 12: Containers
        inventory.setItem(
                SLOT_CONTAINERS,
                ItemUtil.create(
                        Material.CHEST,
                        Component.text(
                                "Visitor Containers: "
                                        + (settings.visitorsMayOpenContainers() ? "Allowed" : "Restricted"),
                                settings.visitorsMayOpenContainers() ? NamedTextColor.GREEN : NamedTextColor.RED,
                                TextDecoration.BOLD),
                        Component.text("Allows visitors to open chests & containers", NamedTextColor.GRAY),
                        Component.empty(),
                        Component.text(
                                "▶ Click to toggle " + (settings.visitorsMayOpenContainers() ? "OFF" : "ON"),
                                NamedTextColor.YELLOW)));

        // Slot 14: Interact (doors, buttons, redstone)
        inventory.setItem(
                SLOT_INTERACT,
                ItemUtil.create(
                        Material.LEVER,
                        Component.text(
                                "Visitor Interact: " + (settings.visitorsMayInteract() ? "Allowed" : "Restricted"),
                                settings.visitorsMayInteract() ? NamedTextColor.GREEN : NamedTextColor.RED,
                                TextDecoration.BOLD),
                        Component.text("Allows visitors to use doors, buttons & redstone", NamedTextColor.GRAY),
                        Component.empty(),
                        Component.text(
                                "▶ Click to toggle " + (settings.visitorsMayInteract() ? "OFF" : "ON"),
                                NamedTextColor.YELLOW)));

        // Slot 16: Mob Griefing
        inventory.setItem(
                SLOT_MOB_GRIEFING,
                ItemUtil.create(
                        Material.CREEPER_HEAD,
                        Component.text(
                                "Mob Griefing: " + (settings.mobGriefing() ? "Enabled" : "Disabled"),
                                settings.mobGriefing() ? NamedTextColor.GREEN : NamedTextColor.RED,
                                TextDecoration.BOLD),
                        Component.text("Controls Creeper explosions and mob damage", NamedTextColor.GRAY),
                        Component.empty(),
                        Component.text(
                                "▶ Click to toggle " + (settings.mobGriefing() ? "OFF" : "ON"),
                                NamedTextColor.YELLOW)));

        // Slot 22: Back
        inventory.setItem(
                SLOT_BACK,
                ItemUtil.create(
                        Material.OAK_DOOR,
                        Component.text("Back to World Menu", NamedTextColor.RED, TextDecoration.BOLD),
                        Component.text("▶ Click to return", NamedTextColor.DARK_GRAY)));

        return inventory;
    }

    @Override
    public void handleClick(Player player, int slot, ClickType clickType) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(clickType, "clickType");

        switch (slot) {
            case SLOT_PVP -> toggleSetting(player, "pvp", String.valueOf(!settings.pvp()));
            case SLOT_CONTAINERS ->
                toggleSetting(player, "containers", String.valueOf(!settings.visitorsMayOpenContainers()));
            case SLOT_INTERACT -> toggleSetting(player, "interact", String.valueOf(!settings.visitorsMayInteract()));
            case SLOT_MOB_GRIEFING -> toggleSetting(player, "mob-griefing", String.valueOf(!settings.mobGriefing()));
            case SLOT_BACK -> {
                var _ = menuService.openWorldMenu(player, world.id());
            }
            default -> {
                // Non-clickable filler
            }
        }
    }

    private void toggleSetting(Player player, String settingKey, String newValue) {
        if (menuChannel != null) {
            var _ = menuChannel
                    .sendIntent(player, new MenuIntent.SetSetting(world.id(), settingKey, newValue))
                    .whenComplete((result, ex) -> {
                        if (result instanceof MenuResult.Failed failed) {
                            player.sendMessage(Component.text(
                                    "Could not update setting: " + failed.message(), NamedTextColor.RED));
                        }
                        var _ = menuService.openSettingsMenu(player, world.id());
                    });
        }
    }

    @Override
    public void refresh(Player player) {
        Objects.requireNonNull(player, "player");
        var _ = menuService.openSettingsMenu(player, world.id());
    }
}
