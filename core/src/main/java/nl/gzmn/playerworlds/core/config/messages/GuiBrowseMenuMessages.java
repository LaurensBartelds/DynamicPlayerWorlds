package nl.gzmn.playerworlds.core.config.messages;

import java.util.List;
import java.util.Set;

/** {@code backend/gui/screen/BrowseMenu.java} and its proxy mirror, {@code BrowseScreenBuilder}. */
public final class GuiBrowseMenuMessages {

    private GuiBrowseMenuMessages() {}

    public static final List<MessageKey> ENTRIES = List.of(
            MessageKey.of(
                    "messages.gui.browse-menu.title",
                    "<dark_gray>Public Worlds (Page <page>/<pages>)</dark_gray>",
                    Set.of("page", "pages")),
            MessageKey.of("messages.gui.browse-menu.item.empty.name", "<yellow><bold>No Public Worlds</bold></yellow>"),
            MessageKey.lore(
                    "messages.gui.browse-menu.item.empty.lore",
                    List.of("<gray>There are no public worlds currently available.</gray>")),
            MessageKey.of(
                    "messages.gui.browse-menu.item.world-entry.name",
                    "<aqua><bold><world></bold></aqua>",
                    Set.of("world")),
            MessageKey.lore(
                    "messages.gui.browse-menu.item.world-entry.lore",
                    List.of(
                            "<yellow>Owner: <owner></yellow>",
                            "<gray><description></gray>",
                            "",
                            "<green>▶ Click to Join World</green>"),
                    Set.of("owner", "description")),
            MessageKey.lore(
                    "messages.gui.browse-menu.item.world-entry.lore-no-description",
                    List.of("<yellow>Owner: <owner></yellow>", "", "<green>▶ Click to Join World</green>"),
                    Set.of("owner")),
            MessageKey.of(
                    "messages.gui.browse-menu.item.previous-page.name",
                    "<yellow><bold>◀ Previous Page</bold></yellow>"),
            MessageKey.of("messages.gui.browse-menu.item.back.name", "<red><bold>Back to Main Menu</bold></red>"),
            MessageKey.lore(
                    "messages.gui.browse-menu.item.back.lore", List.of("<dark_gray>▶ Click to return</dark_gray>")),
            MessageKey.of("messages.gui.browse-menu.item.next-page.name", "<yellow><bold>Next Page ▶</bold></yellow>"));
}
