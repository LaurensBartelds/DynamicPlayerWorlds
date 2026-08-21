package nl.gzmn.playerworlds.core.config.messages;

import java.util.List;
import java.util.Set;

/** {@code backend/gui/screen/InvitesMenu.java} and its proxy mirror, {@code InvitesScreenBuilder}. */
public final class GuiInvitesMenuMessages {

    private GuiInvitesMenuMessages() {}

    public static final List<MessageKey> ENTRIES = List.of(
            MessageKey.of(
                    "messages.gui.invites-menu.title",
                    "<dark_gray>Pending Invites (<page>/<pages>)</dark_gray>",
                    Set.of("page", "pages")),
            MessageKey.of("messages.gui.invites-menu.item.empty.name", "<gold><bold>No Pending Invites</bold></gold>"),
            MessageKey.lore(
                    "messages.gui.invites-menu.item.empty.lore",
                    List.of("<gray>You have no pending invites or transfer requests.</gray>")),
            MessageKey.of(
                    "messages.gui.invites-menu.item.invite-entry.name",
                    "<gold><bold><kind>: <world></bold></gold>",
                    Set.of("kind", "world")),
            MessageKey.lore(
                    "messages.gui.invites-menu.item.invite-entry.lore",
                    List.of(
                            "<yellow>From: <sender></yellow>",
                            "<gray>Type: <type></gray>",
                            "<dark_gray>Expires: <expires-at></dark_gray>",
                            "",
                            "<green>▶ Left-Click: Accept</green>",
                            "<red>▶ Right-Click: Decline</red>"),
                    Set.of("sender", "type", "expires-at")),
            MessageKey.of(
                    "messages.gui.invites-menu.item.previous-page.name",
                    "<yellow><bold>◀ Previous Page</bold></yellow>"),
            MessageKey.of("messages.gui.invites-menu.item.back.name", "<red><bold>Back to Main Menu</bold></red>"),
            MessageKey.lore(
                    "messages.gui.invites-menu.item.back.lore", List.of("<dark_gray>▶ Click to return</dark_gray>")),
            MessageKey.of(
                    "messages.gui.invites-menu.item.next-page.name", "<yellow><bold>Next Page ▶</bold></yellow>"));
}
