package nl.gzmn.playerworlds.core.config.messages;

import java.util.List;
import java.util.Set;

/**
 * {@code backend/gui/screen/SettingsMenu.java} and its proxy mirror, {@code SettingsScreenBuilder}.
 *
 * <p>Every toggle/stepper item has its own {@code .name}/{@code .description} pair so an admin can
 * reword each one independently; the {@code state}/{@code value} placeholder and the shared hint
 * templates carry the part that changes per click. A toggle's name is rendered with no explicit
 * color in its default template — {@code colorIfAbsent} applies green/red for enabled/disabled in
 * Java, the same fallback pattern {@code WorldActions.info/success/error} use — so an admin who
 * writes their own color into the template keeps it, and one who doesn't still gets the right
 * color for the current state.
 */
public final class GuiSettingsMenuMessages {

    private GuiSettingsMenuMessages() {}

    public static final List<MessageKey> ENTRIES = List.of(
            MessageKey.of(
                    "messages.gui.settings-menu.title", "<dark_gray>Settings: <world></dark_gray>", Set.of("world")),
            MessageKey.of(
                    "messages.gui.settings-menu.item.info.name",
                    "<gold><bold>World Settings: <world></bold></gold>",
                    Set.of("world")),
            MessageKey.lore(
                    "messages.gui.settings-menu.item.info.lore",
                    List.of("<gray>Configure gameplay & interaction rules</gray>")),
            MessageKey.of(
                    "messages.gui.settings-menu.item.toggle.hint",
                    "<yellow>▶ Click to toggle <next-state></yellow>",
                    Set.of("next-state")),
            MessageKey.of(
                    "messages.gui.settings-menu.item.pvp.name", "<bold>PvP Combat: <state></bold>", Set.of("state")),
            MessageKey.of(
                    "messages.gui.settings-menu.item.pvp.description",
                    "<gray>Allows players to damage each other</gray>"),
            MessageKey.of(
                    "messages.gui.settings-menu.item.containers.name",
                    "<bold>Visitor Containers: <state></bold>",
                    Set.of("state")),
            MessageKey.of(
                    "messages.gui.settings-menu.item.containers.description",
                    "<gray>Allows visitors to open chests & containers</gray>"),
            MessageKey.of(
                    "messages.gui.settings-menu.item.interact.name",
                    "<bold>Visitor Interact: <state></bold>",
                    Set.of("state")),
            MessageKey.of(
                    "messages.gui.settings-menu.item.interact.description",
                    "<gray>Allows visitors to use doors, buttons & redstone</gray>"),
            MessageKey.of(
                    "messages.gui.settings-menu.item.mob-griefing.name",
                    "<bold>Mob Griefing: <state></bold>",
                    Set.of("state")),
            MessageKey.of(
                    "messages.gui.settings-menu.item.mob-griefing.description",
                    "<gray>Controls Creeper explosions and mob damage</gray>"),
            MessageKey.of(
                    "messages.gui.settings-menu.item.keep-inventory.name",
                    "<bold>Keep Inventory: <state></bold>",
                    Set.of("state")),
            MessageKey.of(
                    "messages.gui.settings-menu.item.keep-inventory.description",
                    "<gray>Players keep their items and XP on death</gray>"),
            MessageKey.of(
                    "messages.gui.settings-menu.item.fall-damage.name",
                    "<bold>Fall Damage: <state></bold>",
                    Set.of("state")),
            MessageKey.of(
                    "messages.gui.settings-menu.item.fall-damage.description",
                    "<gray>Whether players take fall damage</gray>"),
            MessageKey.of(
                    "messages.gui.settings-menu.item.fire-damage.name",
                    "<bold>Fire Damage: <state></bold>",
                    Set.of("state")),
            MessageKey.of(
                    "messages.gui.settings-menu.item.fire-damage.description",
                    "<gray>Whether players take fire damage</gray>"),
            MessageKey.of(
                    "messages.gui.settings-menu.item.freeze-damage.name",
                    "<bold>Freeze Damage: <state></bold>",
                    Set.of("state")),
            MessageKey.of(
                    "messages.gui.settings-menu.item.freeze-damage.description",
                    "<gray>Whether players take freeze damage</gray>"),
            MessageKey.of(
                    "messages.gui.settings-menu.item.drowning-damage.name",
                    "<bold>Drowning Damage: <state></bold>",
                    Set.of("state")),
            MessageKey.of(
                    "messages.gui.settings-menu.item.drowning-damage.description",
                    "<gray>Whether players take drowning damage</gray>"),
            MessageKey.of(
                    "messages.gui.settings-menu.item.daylight-cycle.name",
                    "<bold>Daylight Cycle: <state></bold>",
                    Set.of("state")),
            MessageKey.of(
                    "messages.gui.settings-menu.item.daylight-cycle.description",
                    "<gray>Whether the day/night cycle advances</gray>"),
            MessageKey.of(
                    "messages.gui.settings-menu.item.weather-cycle.name",
                    "<bold>Weather Cycle: <state></bold>",
                    Set.of("state")),
            MessageKey.of(
                    "messages.gui.settings-menu.item.weather-cycle.description",
                    "<gray>Whether the weather cycle advances</gray>"),
            MessageKey.of(
                    "messages.gui.settings-menu.item.insomnia.name", "<bold>Insomnia: <state></bold>", Set.of("state")),
            MessageKey.of(
                    "messages.gui.settings-menu.item.insomnia.description",
                    "<gray>Whether phantoms can spawn from insomnia</gray>"),
            MessageKey.of(
                    "messages.gui.settings-menu.item.immediate-respawn.name",
                    "<bold>Immediate Respawn: <state></bold>",
                    Set.of("state")),
            MessageKey.of(
                    "messages.gui.settings-menu.item.immediate-respawn.description",
                    "<gray>Whether players skip the death screen</gray>"),
            MessageKey.of(
                    "messages.gui.settings-menu.item.natural-regeneration.name",
                    "<bold>Natural Regeneration: <state></bold>",
                    Set.of("state")),
            MessageKey.of(
                    "messages.gui.settings-menu.item.natural-regeneration.description",
                    "<gray>Whether players regenerate health from hunger</gray>"),
            MessageKey.of(
                    "messages.gui.settings-menu.item.stepper.adjusts-hint",
                    "<dark_gray>Adjusts by <step> per click</dark_gray>",
                    Set.of("step")),
            MessageKey.of(
                    "messages.gui.settings-menu.item.sleep-percentage.name",
                    "<aqua><bold>Sleep Percentage: <value></bold></aqua>",
                    Set.of("value")),
            MessageKey.of(
                    "messages.gui.settings-menu.item.sleep-percentage.description",
                    "<gray>Percent of players who must sleep to skip the night</gray>"),
            MessageKey.of(
                    "messages.gui.settings-menu.item.entity-cramming.name",
                    "<aqua><bold>Max Entity Cramming: <value></bold></aqua>",
                    Set.of("value")),
            MessageKey.of(
                    "messages.gui.settings-menu.item.entity-cramming.description",
                    "<gray>Entities per block before cramming damage</gray>"),
            MessageKey.of(
                    "messages.gui.settings-menu.item.respawn-radius.name",
                    "<aqua><bold>Respawn Radius: <value></bold></aqua>",
                    Set.of("value")),
            MessageKey.of(
                    "messages.gui.settings-menu.item.respawn-radius.description",
                    "<gray>Radius around world spawn a respawn may land</gray>"),
            MessageKey.of(
                    "messages.gui.settings-menu.item.snow-height.name",
                    "<aqua><bold>Max Snow Height: <value></bold></aqua>",
                    Set.of("value")),
            MessageKey.of(
                    "messages.gui.settings-menu.item.snow-height.description",
                    "<gray>Max layers of snow that can accumulate</gray>"),
            MessageKey.of(
                    "messages.gui.settings-menu.item.stepper-arrow.enabled.name",
                    "<yellow><bold><label></bold></yellow>",
                    Set.of("label")),
            MessageKey.of(
                    "messages.gui.settings-menu.item.stepper-arrow.enabled.hint", "<yellow>▶ Click to adjust</yellow>"),
            MessageKey.of(
                    "messages.gui.settings-menu.item.stepper-arrow.disabled.name",
                    "<dark_gray><label></dark_gray>",
                    Set.of("label")),
            MessageKey.of(
                    "messages.gui.settings-menu.item.stepper-arrow.disabled.hint",
                    "<dark_gray>Already at limit</dark_gray>"),
            MessageKey.of("messages.gui.settings-menu.item.back.name", "<red><bold>Back to World Menu</bold></red>"),
            MessageKey.lore(
                    "messages.gui.settings-menu.item.back.lore", List.of("<dark_gray>▶ Click to return</dark_gray>")));
}
