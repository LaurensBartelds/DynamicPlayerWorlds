package nl.gzmn.playerworlds.core.config.messages;

import java.util.List;
import java.util.Set;

/**
 * Standalone notices sent outside a command's own success/failure reply — currently just the
 * world-invite notice, which carries a clickable {@code <click:run_command>} button.
 */
public final class NoticeMessages {

    private NoticeMessages() {}

    public static final List<MessageKey> ENTRIES = List.of(
            // <button> is a whole pre-built Component (ClickEvent/HoverEvent attached in Java),
            // not literal markup: MiniMessage's <click:run_command:'...'> argument is a plain
            // string that is never re-parsed for tags, so a placeholder embedded inside it (e.g.
            // '/world accept <owner>') would reach the client unresolved. Composing the button
            // in code and inserting it whole is the correct pattern for a dynamic click target.
            MessageKey.of(
                    "messages.notice.invite",
                    "<green><owner> invited you to their world '<world>'.</green>\n"
                            + "<button> <gray>or type /world accept <owner></gray>",
                    Set.of("owner", "world", "button")),

            // FR-5b, sent by the node as the player is put back on the lobby. The
            // proxy has its own refusal for the next time they try to come back
            // (messages.command.join.hardcore-dead); this is the one that says it
            // happened, at the moment it does.
            MessageKey.of(
                    "messages.notice.hardcore-death",
                    "<red>you died in '<world>'. It is a hardcore world, so that is the end of it for you.</red>",
                    Set.of("world")));
}
