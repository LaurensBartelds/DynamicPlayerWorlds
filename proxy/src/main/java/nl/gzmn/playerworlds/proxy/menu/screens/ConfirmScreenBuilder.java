package nl.gzmn.playerworlds.proxy.menu.screens;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import nl.gzmn.playerworlds.core.menu.MenuItemDescriptor;
import nl.gzmn.playerworlds.core.menu.RenderMenuPayload;
import nl.gzmn.playerworlds.proxy.command.Messages;

/**
 * Builds the reusable confirmation modal payload for destructive or impactful actions.
 *
 * <p>Only this screen's own fixed chrome (window title, Confirm/Cancel buttons) is migrated
 * here — {@code title} and {@code description} are supplied by whichever caller opened the
 * modal and are migrated as part of that caller's own screen, matching {@code
 * GuiConfirmMenuMessages}'s note on the backend side.
 */
public final class ConfirmScreenBuilder {

    public static final String SCREEN_TYPE = "CONFIRM";
    public static final int SIZE = 27;

    public static final int SLOT_INFO = 4;
    public static final int SLOT_CONFIRM = 11;
    public static final int SLOT_CANCEL = 15;

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private ConfirmScreenBuilder() {}

    public static RenderMenuPayload build(
            Messages messages,
            long correlationId,
            String title,
            String description,
            String confirmActionTag,
            String cancelActionTag) {
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(confirmActionTag, "confirmActionTag");
        Objects.requireNonNull(cancelActionTag, "cancelActionTag");

        List<MenuItemDescriptor> items = new ArrayList<>(SIZE);
        for (int i = 0; i < SIZE; i++) {
            items.add(new MenuItemDescriptor(i, "GRAY_STAINED_GLASS_PANE", 1, " ", List.of(), null, ""));
        }

        // Slot 4: Info
        items.set(SLOT_INFO, new MenuItemDescriptor(SLOT_INFO, "PAPER", 1, title, List.of(description), null, ""));

        // Slot 11: Confirm (Green)
        items.set(
                SLOT_CONFIRM,
                new MenuItemDescriptor(
                        SLOT_CONFIRM,
                        "LIME_CONCRETE",
                        1,
                        legacy(messages.render("messages.gui.confirm-menu.item.confirm.name")),
                        legacyLore(messages.renderLore("messages.gui.confirm-menu.item.confirm.lore")),
                        null,
                        confirmActionTag));

        // Slot 15: Cancel (Red)
        items.set(
                SLOT_CANCEL,
                new MenuItemDescriptor(
                        SLOT_CANCEL,
                        "RED_CONCRETE",
                        1,
                        legacy(messages.render("messages.gui.confirm-menu.item.cancel.name")),
                        legacyLore(messages.renderLore("messages.gui.confirm-menu.item.cancel.lore")),
                        null,
                        cancelActionTag));

        return new RenderMenuPayload(
                correlationId, SCREEN_TYPE, legacy(messages.render("messages.gui.confirm-menu.title")), SIZE, items);
    }

    private static String legacy(Component component) {
        return LEGACY.serialize(component);
    }

    private static List<String> legacyLore(List<Component> lines) {
        return lines.stream().map(ConfirmScreenBuilder::legacy).toList();
    }
}
