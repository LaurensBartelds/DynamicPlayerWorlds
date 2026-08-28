package nl.gzmn.playerworlds.core.config.messages;

import java.util.List;
import java.util.Set;

/** {@code backend/gui/screen/MembersMenu.java} and its proxy mirror, {@code MembersScreenBuilder}. */
public final class GuiMembersMenuMessages {

    private GuiMembersMenuMessages() {}

    public static final List<MessageKey> ENTRIES = List.of(
            MessageKey.of(
                    "messages.gui.members-menu.title",
                    "<dark_gray>Members: <world> (<page>/<pages>)</dark_gray>",
                    Set.of("world", "page", "pages")),
            MessageKey.of(
                    "messages.gui.members-menu.item.previous-page.name",
                    "<yellow><bold>◀ Previous Page</bold></yellow>"),
            MessageKey.of("messages.gui.members-menu.item.back.name", "<red><bold>Back to World Menu</bold></red>"),
            MessageKey.lore(
                    "messages.gui.members-menu.item.back.lore", List.of("<dark_gray>▶ Click to return</dark_gray>")),
            MessageKey.of("messages.gui.members-menu.item.invite.name", "<green><bold>Invite Member</bold></green>"),
            MessageKey.lore(
                    "messages.gui.members-menu.item.invite.lore",
                    List.of(
                            "<gray>Invite another player to this world</gray>",
                            "",
                            "<yellow>▶ Click for instructions</yellow>")),
            MessageKey.of("messages.gui.members-menu.item.next-page.name", "<yellow><bold>Next Page ▶</bold></yellow>"),
            // Role/joined-date color and text are chosen from a fixed, code-controlled
            // set (Role, a formatted date) — the name/role/joined lines carry their own
            // pre-styled Component and the template just places them.
            MessageKey.of("messages.gui.members-menu.item.member.name", "<member>", Set.of("member")),
            MessageKey.of("messages.gui.members-menu.item.member.role-line", "<role>", Set.of("role")),
            MessageKey.of(
                    "messages.gui.members-menu.item.member.joined-line",
                    "<gray>Joined: <joined></gray>",
                    Set.of("joined")),
            MessageKey.of("messages.gui.members-menu.item.member.owner-note", "<gold>World Owner</gold>"),
            MessageKey.of(
                    "messages.gui.members-menu.item.member.promote-hint",
                    "<green>▶ Left-Click: Promote to BUILDER</green>"),
            MessageKey.of("messages.gui.members-menu.item.member.kick-hint", "<red>▶ Right-Click: Kick Member</red>"),
            MessageKey.of(
                    "messages.gui.members-menu.confirm.kick.title",
                    "<dark_red><bold>Kick <member>?</bold></dark_red>",
                    Set.of("member")),
            MessageKey.of(
                    "messages.gui.members-menu.confirm.kick.body",
                    "<gray>Remove <member> from '<world>'</gray>",
                    Set.of("member", "world")),
            MessageKey.of(
                    // \<player\> is a MiniMessage-escaped literal, not an unresolved tag:
                    // there is no "player" placeholder here, this is example command syntax.
                    "messages.gui.members-menu.invite-instructions",
                    "<yellow>To invite a player, run: /world invite \\<player\\></yellow>"));
}
