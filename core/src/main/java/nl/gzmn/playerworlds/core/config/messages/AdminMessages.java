package nl.gzmn.playerworlds.core.config.messages;

import java.util.List;
import java.util.Set;

/** Feedback for {@code /world admin message <list|get|set|reset>} itself. */
public final class AdminMessages {

    private AdminMessages() {}

    public static final List<MessageKey> ENTRIES = List.of(
            MessageKey.of(
                    "messages.command.admin.message.unknown-key",
                    "<red>unknown message key '<key>'; see /world admin message list</red>",
                    Set.of("key")),
            MessageKey.of(
                    "messages.command.admin.message.invalid",
                    "<red>invalid MiniMessage for '<key>': <reason></red>",
                    Set.of("key", "reason")),
            MessageKey.of("messages.command.admin.message.set-success", "<green>updated <key></green>", Set.of("key")),
            MessageKey.of(
                    "messages.command.admin.message.reset-success",
                    "<green><key> reverted to its default</green>",
                    Set.of("key")),
            MessageKey.of(
                    "messages.command.admin.message.get-header",
                    "<dark_gray>┌─ <gray><key></gray> ─────────────</dark_gray>",
                    Set.of("key")),
            MessageKey.of(
                    "messages.command.admin.message.get-template",
                    "<dark_gray>│</dark_gray> <gray>template:</gray> <white><template></white>",
                    Set.of("template")),
            MessageKey.of(
                    "messages.command.admin.message.get-preview",
                    "<dark_gray>│</dark_gray> <gray>preview:</gray> <preview>",
                    Set.of("preview")),
            MessageKey.of(
                    "messages.command.admin.message.get-footer", "<dark_gray>└───────────────────────</dark_gray>"),
            MessageKey.of(
                    "messages.command.admin.message.list-header",
                    "<dark_gray>┌─ <gray>Message keys, page <page>/<pages></gray> ─────────────┐</dark_gray>",
                    Set.of("page", "pages")),
            MessageKey.of(
                    "messages.command.admin.message.list-entry",
                    "<dark_gray>│</dark_gray> <white><key></white>",
                    Set.of("key")),
            MessageKey.of(
                    // \<key\> is a MiniMessage-escaped literal (example command syntax), not an
                    // unresolved tag — this key declares no "key" placeholder.
                    "messages.command.admin.message.list-footer",
                    "<dark_gray>└─ <gray>/world admin message get \\<key\\> for details</gray> ────┘</dark_gray>"));
}
