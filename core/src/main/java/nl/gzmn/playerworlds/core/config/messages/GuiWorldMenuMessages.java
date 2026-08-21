package nl.gzmn.playerworlds.core.config.messages;

import java.util.List;
import java.util.Set;

/** {@code backend/gui/screen/WorldMenu.java} and its proxy mirror, {@code WorldDetailScreenBuilder}. */
public final class GuiWorldMenuMessages {

    private GuiWorldMenuMessages() {}

    public static final List<MessageKey> ENTRIES = List.of(
            MessageKey.of(
                    "messages.gui.world-menu.title",
                    "<dark_gray><prefix>: <world></dark_gray>",
                    Set.of("prefix", "world")),
            MessageKey.of(
                    "messages.gui.world-menu.item.info.name", "<gold><bold><world></bold></gold>", Set.of("world")),
            MessageKey.lore(
                    "messages.gui.world-menu.item.info.lore",
                    List.of(
                            "<gray>State: <state></gray>",
                            "<gray>Visibility: <visibility></gray>",
                            "<gray>Border: ±<radius>m</gray>",
                            "<dark_gray>Seed: <seed></dark_gray>",
                            "<gray>Storage: <size></gray>"),
                    Set.of("state", "visibility", "radius", "seed", "size")),
            MessageKey.of("messages.gui.world-menu.item.join.name", "<green><bold>Join World</bold></green>"),
            MessageKey.lore(
                    "messages.gui.world-menu.item.join.lore",
                    List.of("<gray>Teleport directly to this world</gray>", "", "<yellow>▶ Click to join</yellow>")),
            MessageKey.of("messages.gui.world-menu.item.restore.name", "<green><bold>Restore World</bold></green>"),
            MessageKey.lore(
                    "messages.gui.world-menu.item.restore.lore",
                    List.of(
                            "<gray>Restore this world from cold storage</gray>",
                            "",
                            "<yellow>▶ Click to restore</yellow>")),
            MessageKey.of("messages.gui.world-menu.item.archived-locked.name", "<gray><bold>Archived</bold></gray>"),
            MessageKey.lore(
                    "messages.gui.world-menu.item.archived-locked.lore",
                    List.of("<dark_gray>Only its owner can bring this world back</dark_gray>")),
            MessageKey.of(
                    "messages.gui.world-menu.item.members.name", "<aqua><bold>Members & Permissions</bold></aqua>"),
            MessageKey.lore(
                    "messages.gui.world-menu.item.members.lore",
                    List.of(
                            "<gray>View members, invite players, or promote builders</gray>",
                            "",
                            "<yellow>▶ Click to manage members</yellow>")),
            MessageKey.of("messages.gui.world-menu.item.settings.name", "<yellow><bold>World Settings</bold></yellow>"),
            MessageKey.lore(
                    "messages.gui.world-menu.item.settings.lore",
                    List.of(
                            "<gray>Configure PvP, container access, and mob griefing</gray>",
                            "",
                            "<yellow>▶ Click to configure</yellow>")),
            MessageKey.of(
                    "messages.gui.world-menu.item.visibility.name",
                    "<light_purple><bold>Visibility: <visibility></bold></light_purple>",
                    Set.of("visibility")),
            MessageKey.lore(
                    "messages.gui.world-menu.item.visibility.lore",
                    List.of(
                            "<gray>Current: <description></gray>",
                            "",
                            "<yellow>▶ Click to toggle Public / Private</yellow>"),
                    Set.of("description")),
            MessageKey.of("messages.gui.world-menu.item.bans.name", "<red><bold>Banned Players</bold></red>"),
            MessageKey.lore(
                    "messages.gui.world-menu.item.bans.lore",
                    List.of(
                            "<gray>View and revoke bans from this world</gray>",
                            "",
                            "<yellow>▶ Click to manage bans</yellow>")),
            MessageKey.of("messages.gui.world-menu.item.storage.name", "<blue><bold>Storage Usage</bold></blue>"),
            MessageKey.lore(
                    "messages.gui.world-menu.item.storage.lore",
                    List.of(
                            "<gray>World size: <size></gray>",
                            "",
                            "<yellow>▶ Click to view storage breakdown</yellow>"),
                    Set.of("size")),
            MessageKey.of(
                    "messages.gui.world-menu.item.delete-permanently.name",
                    "<dark_red><bold>Permanently Delete World</bold></dark_red>"),
            MessageKey.lore(
                    "messages.gui.world-menu.item.delete-permanently.lore",
                    List.of(
                            "<red><bold>⚠ Irreversible Action</bold></red>",
                            "<gray>Permanently destroys all chunks and backup archives.</gray>",
                            "",
                            "<dark_red>▶ Click to delete permanently (requires confirm)</dark_red>")),
            MessageKey.of(
                    "messages.gui.world-menu.item.archive.name", "<dark_red><bold>Archive World</bold></dark_red>"),
            MessageKey.lore(
                    "messages.gui.world-menu.item.archive.lore",
                    List.of(
                            "<gray>Pack this world into cold storage and free a slot</gray>",
                            "",
                            "<red>▶ Click to archive (requires confirm)</red>")),
            MessageKey.of("messages.gui.world-menu.item.back.name", "<red><bold>Back to My Worlds</bold></red>"),
            MessageKey.lore(
                    "messages.gui.world-menu.item.back.lore", List.of("<dark_gray>▶ Click to return</dark_gray>")),
            MessageKey.of(
                    "messages.gui.world-menu.confirm.delete.title",
                    "<dark_red><bold>Permanently Delete '<world>'?</bold></dark_red>",
                    Set.of("world")),
            MessageKey.of(
                    "messages.gui.world-menu.confirm.delete.body",
                    "<red>Permanently destroy '<world>'? All archives will be lost forever.</red>",
                    Set.of("world")),
            MessageKey.of(
                    "messages.gui.world-menu.confirm.archive.title",
                    "<dark_red><bold>Archive '<world>'?</bold></dark_red>",
                    Set.of("world")),
            MessageKey.of(
                    "messages.gui.world-menu.confirm.archive.body",
                    "<gray>This packs the world to cold storage. You can restore it later.</gray>",
                    Set.of()));
}
