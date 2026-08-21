package nl.gzmn.playerworlds.core.config.messages;

import java.util.List;
import java.util.Set;

/** {@code backend/gui/screen/StorageMenu.java} and its proxy mirror, {@code StorageScreenBuilder}. */
public final class GuiStorageMenuMessages {

    private GuiStorageMenuMessages() {}

    public static final List<MessageKey> ENTRIES = List.of(
            MessageKey.of("messages.gui.storage-menu.title", "<dark_gray>Storage Breakdown</dark_gray>"),
            MessageKey.of(
                    "messages.gui.storage-menu.item.overview.name", "<gold><bold>Storage Allowance</bold></gold>"),
            MessageKey.lore(
                    "messages.gui.storage-menu.item.overview.lore",
                    List.of(
                            "<gray>Used: <used></gray>",
                            "<gray>Limit: <limit></gray>",
                            "<aqua>Usage: <usage></aqua>",
                            "<dark_aqua><bar></dark_aqua>"),
                    Set.of("used", "limit", "usage", "bar")),
            MessageKey.of(
                    "messages.gui.storage-menu.item.world-entry.name",
                    "<yellow><bold><world></bold></yellow>",
                    Set.of("world")),
            MessageKey.lore(
                    "messages.gui.storage-menu.item.world-entry.lore",
                    List.of(
                            "<gray>Size: <size></gray>",
                            "<dark_gray>State: <state></dark_gray>",
                            "",
                            "<yellow>▶ Click to manage</yellow>"),
                    Set.of("size", "state")),
            MessageKey.of("messages.gui.storage-menu.item.back.name", "<red><bold>Back to Main Menu</bold></red>"),
            MessageKey.lore(
                    "messages.gui.storage-menu.item.back.lore", List.of("<dark_gray>▶ Click to return</dark_gray>")));
}
