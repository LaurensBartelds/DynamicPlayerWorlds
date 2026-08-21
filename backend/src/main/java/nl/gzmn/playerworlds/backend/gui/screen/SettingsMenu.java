package nl.gzmn.playerworlds.backend.gui.screen;

import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import nl.gzmn.playerworlds.backend.gui.GuiScreen;
import nl.gzmn.playerworlds.backend.gui.ItemUtil;
import nl.gzmn.playerworlds.backend.gui.MenuChannel;
import nl.gzmn.playerworlds.backend.gui.MenuHolder;
import nl.gzmn.playerworlds.backend.gui.MenuService;
import nl.gzmn.playerworlds.backend.gui.Messages;
import nl.gzmn.playerworlds.backend.gui.Placeholders;
import nl.gzmn.playerworlds.core.menu.MenuIntent;
import nl.gzmn.playerworlds.core.menu.MenuResult;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.WorldSettings;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * World settings configuration screen allowing owners to toggle gameplay rules
 * (FR-9e) and the broader set of vanilla gamerules (FR-9i): keep-inventory,
 * fall/fire/freeze/drowning damage, the day/weather cycle, insomnia, immediate
 * respawn, natural regeneration, and four numeric rules adjusted with paired
 * +/- steppers (sleep percentage, max entity cramming, respawn radius, max
 * snow height).
 */
public final class SettingsMenu implements GuiScreen {

    public static final int SLOT_INFO = 4;
    public static final int SLOT_PVP = 10;
    public static final int SLOT_CONTAINERS = 11;
    public static final int SLOT_INTERACT = 12;
    public static final int SLOT_MOB_GRIEFING = 13;
    public static final int SLOT_KEEP_INVENTORY = 14;
    public static final int SLOT_FALL_DAMAGE = 15;
    public static final int SLOT_FIRE_DAMAGE = 16;
    public static final int SLOT_FREEZE_DAMAGE = 19;
    public static final int SLOT_DROWNING_DAMAGE = 20;
    public static final int SLOT_ADVANCE_TIME = 21;
    public static final int SLOT_ADVANCE_WEATHER = 22;
    public static final int SLOT_SPAWN_PHANTOMS = 23;
    public static final int SLOT_IMMEDIATE_RESPAWN = 24;
    public static final int SLOT_NATURAL_REGENERATION = 25;
    public static final int SLOT_SLEEP_PERCENTAGE_DOWN = 28;
    public static final int SLOT_SLEEP_PERCENTAGE_VALUE = 29;
    public static final int SLOT_SLEEP_PERCENTAGE_UP = 30;
    public static final int SLOT_ENTITY_CRAMMING_DOWN = 31;
    public static final int SLOT_ENTITY_CRAMMING_VALUE = 32;
    public static final int SLOT_ENTITY_CRAMMING_UP = 33;
    public static final int SLOT_RESPAWN_RADIUS_DOWN = 37;
    public static final int SLOT_RESPAWN_RADIUS_VALUE = 38;
    public static final int SLOT_RESPAWN_RADIUS_UP = 39;
    public static final int SLOT_SNOW_HEIGHT_DOWN = 40;
    public static final int SLOT_SNOW_HEIGHT_VALUE = 41;
    public static final int SLOT_SNOW_HEIGHT_UP = 42;
    public static final int SLOT_BACK = 49;

    private static final int SLEEP_STEP = 10;
    private static final int SLEEP_MIN = 0;
    private static final int SLEEP_MAX = 100;
    private static final int ENTITY_CRAMMING_STEP = 4;
    private static final int ENTITY_CRAMMING_MIN = 0;
    private static final int ENTITY_CRAMMING_MAX = 64;
    private static final int RESPAWN_RADIUS_STEP = 4;
    private static final int RESPAWN_RADIUS_MIN = 0;
    private static final int RESPAWN_RADIUS_MAX = 64;
    private static final int SNOW_HEIGHT_STEP = 1;
    private static final int SNOW_HEIGHT_MIN = 0;
    private static final int SNOW_HEIGHT_MAX = 8;

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
        Messages messages = menuService.messages();
        MenuHolder holder = new MenuHolder(this);
        Inventory inventory = Bukkit.createInventory(
                holder,
                54,
                messages.render("messages.gui.settings-menu.title", Placeholders.text("world", world.name())));
        holder.setInventory(inventory);

        for (int i = 0; i < 54; i++) {
            inventory.setItem(i, ItemUtil.filler());
        }

        inventory.setItem(
                SLOT_INFO,
                ItemUtil.create(
                        Material.BEACON,
                        messages.render(
                                "messages.gui.settings-menu.item.info.name", Placeholders.text("world", world.name())),
                        messages.renderLore("messages.gui.settings-menu.item.info.lore")));

        inventory.setItem(SLOT_PVP, toggleItem(messages, Material.DIAMOND_SWORD, "pvp", settings.pvp()));
        inventory.setItem(
                SLOT_CONTAINERS,
                toggleItem(
                        messages,
                        Material.CHEST,
                        "containers",
                        settings.visitorsMayOpenContainers(),
                        "Allowed",
                        "Restricted"));
        inventory.setItem(
                SLOT_INTERACT,
                toggleItem(
                        messages, Material.LEVER, "interact", settings.visitorsMayInteract(), "Allowed", "Restricted"));
        inventory.setItem(
                SLOT_MOB_GRIEFING, toggleItem(messages, Material.CREEPER_HEAD, "mob-griefing", settings.mobGriefing()));
        inventory.setItem(
                SLOT_KEEP_INVENTORY,
                toggleItem(messages, Material.TOTEM_OF_UNDYING, "keep-inventory", settings.keepInventory()));
        inventory.setItem(
                SLOT_FALL_DAMAGE, toggleItem(messages, Material.FEATHER, "fall-damage", settings.fallDamage()));
        inventory.setItem(
                SLOT_FIRE_DAMAGE, toggleItem(messages, Material.BLAZE_POWDER, "fire-damage", settings.fireDamage()));
        inventory.setItem(
                SLOT_FREEZE_DAMAGE,
                toggleItem(messages, Material.POWDER_SNOW_BUCKET, "freeze-damage", settings.freezeDamage()));
        inventory.setItem(
                SLOT_DROWNING_DAMAGE,
                toggleItem(messages, Material.TRIDENT, "drowning-damage", settings.drowningDamage()));
        inventory.setItem(
                SLOT_ADVANCE_TIME, toggleItem(messages, Material.CLOCK, "daylight-cycle", settings.advanceTime()));
        inventory.setItem(
                SLOT_ADVANCE_WEATHER,
                toggleItem(messages, Material.WATER_BUCKET, "weather-cycle", settings.advanceWeather()));
        inventory.setItem(
                SLOT_SPAWN_PHANTOMS,
                toggleItem(messages, Material.PHANTOM_MEMBRANE, "insomnia", settings.spawnPhantoms()));
        inventory.setItem(
                SLOT_IMMEDIATE_RESPAWN,
                toggleItem(messages, Material.RESPAWN_ANCHOR, "immediate-respawn", settings.immediateRespawn()));
        inventory.setItem(
                SLOT_NATURAL_REGENERATION,
                toggleItem(
                        messages, Material.GOLDEN_APPLE, "natural-regeneration", settings.naturalHealthRegeneration()));

        renderStepper(
                messages,
                inventory,
                SLOT_SLEEP_PERCENTAGE_DOWN,
                SLOT_SLEEP_PERCENTAGE_VALUE,
                SLOT_SLEEP_PERCENTAGE_UP,
                Material.RED_BED,
                "sleep-percentage",
                settings.playersSleepingPercentage(),
                "%",
                SLEEP_STEP,
                SLEEP_MIN,
                SLEEP_MAX);
        renderStepper(
                messages,
                inventory,
                SLOT_ENTITY_CRAMMING_DOWN,
                SLOT_ENTITY_CRAMMING_VALUE,
                SLOT_ENTITY_CRAMMING_UP,
                Material.SLIME_BALL,
                "entity-cramming",
                settings.maxEntityCramming(),
                "",
                ENTITY_CRAMMING_STEP,
                ENTITY_CRAMMING_MIN,
                ENTITY_CRAMMING_MAX);
        renderStepper(
                messages,
                inventory,
                SLOT_RESPAWN_RADIUS_DOWN,
                SLOT_RESPAWN_RADIUS_VALUE,
                SLOT_RESPAWN_RADIUS_UP,
                Material.COMPASS,
                "respawn-radius",
                settings.respawnRadius(),
                "",
                RESPAWN_RADIUS_STEP,
                RESPAWN_RADIUS_MIN,
                RESPAWN_RADIUS_MAX);
        renderStepper(
                messages,
                inventory,
                SLOT_SNOW_HEIGHT_DOWN,
                SLOT_SNOW_HEIGHT_VALUE,
                SLOT_SNOW_HEIGHT_UP,
                Material.SNOW,
                "snow-height",
                settings.maxSnowAccumulationHeight(),
                "",
                SNOW_HEIGHT_STEP,
                SNOW_HEIGHT_MIN,
                SNOW_HEIGHT_MAX);

        inventory.setItem(
                SLOT_BACK,
                ItemUtil.create(
                        Material.OAK_DOOR,
                        messages.render("messages.gui.settings-menu.item.back.name"),
                        messages.renderLore("messages.gui.settings-menu.item.back.lore")));

        return inventory;
    }

    private static ItemStack toggleItem(Messages messages, Material material, String settingId, boolean enabled) {
        return toggleItem(messages, material, settingId, enabled, "Enabled", "Disabled");
    }

    private static ItemStack toggleItem(
            Messages messages, Material material, String settingId, boolean enabled, String onWord, String offWord) {
        Component name = messages.render(
                        "messages.gui.settings-menu.item." + settingId + ".name",
                        Placeholders.raw("state", enabled ? onWord : offWord))
                .colorIfAbsent(enabled ? NamedTextColor.GREEN : NamedTextColor.RED);
        return ItemUtil.create(
                material,
                name,
                messages.render("messages.gui.settings-menu.item." + settingId + ".description"),
                Component.empty(),
                messages.render(
                        "messages.gui.settings-menu.item.toggle.hint",
                        Placeholders.raw("next-state", enabled ? "OFF" : "ON")));
    }

    private static void renderStepper(
            Messages messages,
            Inventory inventory,
            int downSlot,
            int valueSlot,
            int upSlot,
            Material material,
            String settingId,
            int currentValue,
            String unitSuffix,
            int step,
            int min,
            int max) {
        inventory.setItem(downSlot, stepperArrow(messages, currentValue > min, "◀ -" + step));
        inventory.setItem(
                valueSlot,
                ItemUtil.create(
                        material,
                        messages.render(
                                "messages.gui.settings-menu.item." + settingId + ".name",
                                Placeholders.raw("value", currentValue + unitSuffix)),
                        messages.render("messages.gui.settings-menu.item." + settingId + ".description"),
                        messages.render(
                                "messages.gui.settings-menu.item.stepper.adjusts-hint",
                                Placeholders.count("step", step))));
        inventory.setItem(upSlot, stepperArrow(messages, currentValue < max, "+" + step + " ▶"));
    }

    private static ItemStack stepperArrow(Messages messages, boolean enabled, String label) {
        String prefix = "messages.gui.settings-menu.item.stepper-arrow." + (enabled ? "enabled" : "disabled");
        return ItemUtil.create(
                Material.ARROW,
                messages.render(prefix + ".name", Placeholders.raw("label", label)),
                messages.render(prefix + ".hint"));
    }

    @Override
    public void handleClick(Player player, int slot, ClickType clickType) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(clickType, "clickType");

        switch (slot) {
            case SLOT_PVP -> sendSetting(player, "pvp", String.valueOf(!settings.pvp()));
            case SLOT_CONTAINERS ->
                sendSetting(player, "containers", String.valueOf(!settings.visitorsMayOpenContainers()));
            case SLOT_INTERACT -> sendSetting(player, "interact", String.valueOf(!settings.visitorsMayInteract()));
            case SLOT_MOB_GRIEFING -> sendSetting(player, "mob-griefing", String.valueOf(!settings.mobGriefing()));
            case SLOT_KEEP_INVENTORY ->
                sendSetting(player, "keep-inventory", String.valueOf(!settings.keepInventory()));
            case SLOT_FALL_DAMAGE -> sendSetting(player, "fall-damage", String.valueOf(!settings.fallDamage()));
            case SLOT_FIRE_DAMAGE -> sendSetting(player, "fire-damage", String.valueOf(!settings.fireDamage()));
            case SLOT_FREEZE_DAMAGE -> sendSetting(player, "freeze-damage", String.valueOf(!settings.freezeDamage()));
            case SLOT_DROWNING_DAMAGE ->
                sendSetting(player, "drowning-damage", String.valueOf(!settings.drowningDamage()));
            case SLOT_ADVANCE_TIME -> sendSetting(player, "daylight-cycle", String.valueOf(!settings.advanceTime()));
            case SLOT_ADVANCE_WEATHER ->
                sendSetting(player, "weather-cycle", String.valueOf(!settings.advanceWeather()));
            case SLOT_SPAWN_PHANTOMS -> sendSetting(player, "insomnia", String.valueOf(!settings.spawnPhantoms()));
            case SLOT_IMMEDIATE_RESPAWN ->
                sendSetting(player, "immediate-respawn", String.valueOf(!settings.immediateRespawn()));
            case SLOT_NATURAL_REGENERATION ->
                sendSetting(player, "natural-regeneration", String.valueOf(!settings.naturalHealthRegeneration()));
            case SLOT_SLEEP_PERCENTAGE_DOWN ->
                adjustNumericSetting(
                        player,
                        "sleep-percentage",
                        settings.playersSleepingPercentage(),
                        -SLEEP_STEP,
                        SLEEP_MIN,
                        SLEEP_MAX);
            case SLOT_SLEEP_PERCENTAGE_UP ->
                adjustNumericSetting(
                        player,
                        "sleep-percentage",
                        settings.playersSleepingPercentage(),
                        SLEEP_STEP,
                        SLEEP_MIN,
                        SLEEP_MAX);
            case SLOT_ENTITY_CRAMMING_DOWN ->
                adjustNumericSetting(
                        player,
                        "entity-cramming",
                        settings.maxEntityCramming(),
                        -ENTITY_CRAMMING_STEP,
                        ENTITY_CRAMMING_MIN,
                        ENTITY_CRAMMING_MAX);
            case SLOT_ENTITY_CRAMMING_UP ->
                adjustNumericSetting(
                        player,
                        "entity-cramming",
                        settings.maxEntityCramming(),
                        ENTITY_CRAMMING_STEP,
                        ENTITY_CRAMMING_MIN,
                        ENTITY_CRAMMING_MAX);
            case SLOT_RESPAWN_RADIUS_DOWN ->
                adjustNumericSetting(
                        player,
                        "respawn-radius",
                        settings.respawnRadius(),
                        -RESPAWN_RADIUS_STEP,
                        RESPAWN_RADIUS_MIN,
                        RESPAWN_RADIUS_MAX);
            case SLOT_RESPAWN_RADIUS_UP ->
                adjustNumericSetting(
                        player,
                        "respawn-radius",
                        settings.respawnRadius(),
                        RESPAWN_RADIUS_STEP,
                        RESPAWN_RADIUS_MIN,
                        RESPAWN_RADIUS_MAX);
            case SLOT_SNOW_HEIGHT_DOWN ->
                adjustNumericSetting(
                        player,
                        "snow-height",
                        settings.maxSnowAccumulationHeight(),
                        -SNOW_HEIGHT_STEP,
                        SNOW_HEIGHT_MIN,
                        SNOW_HEIGHT_MAX);
            case SLOT_SNOW_HEIGHT_UP ->
                adjustNumericSetting(
                        player,
                        "snow-height",
                        settings.maxSnowAccumulationHeight(),
                        SNOW_HEIGHT_STEP,
                        SNOW_HEIGHT_MIN,
                        SNOW_HEIGHT_MAX);
            case SLOT_BACK -> {
                var _ = menuService.openWorldMenu(player, world.id());
            }
            default -> {
                // Non-clickable filler or value display
            }
        }
    }

    /** Adjusts a numeric setting by {@code delta}, clamped to {@code [min, max]}; a no-op at either boundary. */
    private void adjustNumericSetting(Player player, String settingKey, int currentValue, int delta, int min, int max) {
        int clamped = Math.max(min, Math.min(max, currentValue + delta));
        if (clamped == currentValue) {
            return;
        }
        sendSetting(player, settingKey, String.valueOf(clamped));
    }

    private void sendSetting(Player player, String settingKey, String newValue) {
        if (menuChannel != null) {
            var _ = menuChannel
                    .sendIntent(player, new MenuIntent.SetSetting(world.id(), settingKey, newValue))
                    .whenComplete((result, ex) -> {
                        if (result instanceof MenuResult.Failed failed) {
                            player.sendMessage(GsonComponentSerializer.gson().deserialize(failed.message()));
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
