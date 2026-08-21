package nl.gzmn.playerworlds.core.config.messages;

import java.util.List;
import java.util.Set;

/** {@code backend/gui/screen/BansMenu.java} and its proxy mirror, {@code BansScreenBuilder}. */
public final class GuiBansMenuMessages {

    private GuiBansMenuMessages() {}

    public static final List<MessageKey> ENTRIES = List.of(
            MessageKey.of(
                    "messages.gui.bans-menu.title",
                    "<dark_gray>Bans: <world> (<page>/<pages>)</dark_gray>",
                    Set.of("world", "page", "pages")),
            MessageKey.of("messages.gui.bans-menu.item.empty.name", "<green><bold>No Banned Players</bold></green>"),
            MessageKey.lore(
                    "messages.gui.bans-menu.item.empty.lore",
                    List.of("<gray>No players are currently banned from this world.</gray>")),
            MessageKey.of(
                    "messages.gui.bans-menu.item.ban-entry.name", "<red><bold><player></bold></red>", Set.of("player")),
            MessageKey.lore(
                    "messages.gui.bans-menu.item.ban-entry.lore",
                    List.of(
                            "<gray>Reason: <reason></gray>",
                            "<dark_gray>Banned: <banned-at></dark_gray>",
                            "",
                            "<yellow>▶ Click to unban</yellow>"),
                    Set.of("reason", "banned-at")),
            MessageKey.of(
                    "messages.gui.bans-menu.item.previous-page.name", "<yellow><bold>◀ Previous Page</bold></yellow>"),
            MessageKey.of("messages.gui.bans-menu.item.back.name", "<red><bold>Back to World Menu</bold></red>"),
            MessageKey.lore(
                    "messages.gui.bans-menu.item.back.lore", List.of("<dark_gray>▶ Click to return</dark_gray>")),
            MessageKey.of("messages.gui.bans-menu.item.next-page.name", "<yellow><bold>Next Page ▶</bold></yellow>"));
}
