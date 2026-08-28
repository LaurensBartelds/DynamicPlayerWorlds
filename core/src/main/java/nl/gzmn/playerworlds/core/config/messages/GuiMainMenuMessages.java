package nl.gzmn.playerworlds.core.config.messages;

import java.util.List;
import java.util.Set;

/** {@code backend/gui/screen/MainMenu.java} and its proxy mirror, {@code MainScreenBuilder}. */
public final class GuiMainMenuMessages {

    private GuiMainMenuMessages() {}

    public static final List<MessageKey> ENTRIES = List.of(
            MessageKey.of("messages.gui.main-menu.title", "<dark_gray>Dynamic Player Worlds</dark_gray>"),
            MessageKey.of("messages.gui.main-menu.item.my-worlds.name", "<green><bold>My Worlds</bold></green>"),
            MessageKey.lore(
                    "messages.gui.main-menu.item.my-worlds.lore",
                    List.of(
                            "<gray>View and manage your worlds</gray>",
                            "<dark_gray>Owned: <owned> / <max></dark_gray>",
                            "",
                            "<yellow>▶ Click to view</yellow>"),
                    Set.of("owned", "max")),
            MessageKey.of("messages.gui.main-menu.item.storage.name", "<aqua><bold>Storage Usage</bold></aqua>"),
            MessageKey.lore(
                    "messages.gui.main-menu.item.storage.lore",
                    List.of(
                            "<gray>Used: <used></gray>",
                            "<gray>Limit: <limit></gray>",
                            "",
                            "<yellow>▶ Click to view breakdown</yellow>"),
                    Set.of("used", "limit")),
            MessageKey.of("messages.gui.main-menu.item.invites.name", "<gold><bold>Pending Invites</bold></gold>"),
            MessageKey.lore(
                    "messages.gui.main-menu.item.invites.lore",
                    List.of("<gray>Pending: <count></gray>", "", "<yellow>▶ Click to view invites</yellow>"),
                    Set.of("count")),
            MessageKey.of(
                    "messages.gui.main-menu.item.browse.name",
                    "<light_purple><bold>Browse Public Worlds</bold></light_purple>"),
            MessageKey.lore(
                    "messages.gui.main-menu.item.browse.lore",
                    List.of(
                            "<gray>Explore worlds shared by the community</gray>",
                            "",
                            "<yellow>▶ Click to browse</yellow>")),
            MessageKey.of("messages.gui.main-menu.item.close.name", "<red><bold>Close Menu</bold></red>"),
            MessageKey.lore(
                    "messages.gui.main-menu.item.close.lore", List.of("<dark_gray>▶ Click to exit</dark_gray>")));
}
