package nl.gzmn.playerworlds.core.config.messages;

import java.util.List;

/**
 * {@code backend/gui/screen/ConfirmMenu.java} and its proxy mirror, {@code ConfirmScreenBuilder}.
 *
 * <p>Only this screen's own fixed chrome (window title, Confirm/Cancel buttons) — the title and
 * description shown above them are supplied by whichever screen opened the modal (e.g. {@code
 * WorldMenu}'s archive/delete confirmations, {@code MembersMenu}'s kick confirmation) and are
 * migrated as part of those screens.
 */
public final class GuiConfirmMenuMessages {

    private GuiConfirmMenuMessages() {}

    public static final List<MessageKey> ENTRIES = List.of(
            MessageKey.of("messages.gui.confirm-menu.title", "<dark_red>Confirm Action</dark_red>"),
            MessageKey.of("messages.gui.confirm-menu.item.confirm.name", "<green><bold>Confirm</bold></green>"),
            MessageKey.lore(
                    "messages.gui.confirm-menu.item.confirm.lore",
                    List.of("<gray>Click to proceed with this action</gray>")),
            MessageKey.of("messages.gui.confirm-menu.item.cancel.name", "<red><bold>Cancel</bold></red>"),
            MessageKey.lore(
                    "messages.gui.confirm-menu.item.cancel.lore",
                    List.of("<gray>Click to return without making changes</gray>")));
}
