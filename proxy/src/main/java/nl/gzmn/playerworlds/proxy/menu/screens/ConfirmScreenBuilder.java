package nl.gzmn.playerworlds.proxy.menu.screens;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import nl.gzmn.playerworlds.core.menu.MenuItemDescriptor;
import nl.gzmn.playerworlds.core.menu.RenderMenuPayload;

/**
 * Builds the reusable confirmation modal payload for destructive or impactful actions.
 */
public final class ConfirmScreenBuilder {

    public static final String SCREEN_TYPE = "CONFIRM";
    public static final int SIZE = 27;

    public static final int SLOT_INFO = 4;
    public static final int SLOT_CONFIRM = 11;
    public static final int SLOT_CANCEL = 15;

    private ConfirmScreenBuilder() {}

    public static RenderMenuPayload build(
            long correlationId, String title, String description, String confirmActionTag, String cancelActionTag) {
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
                        "§a§lConfirm",
                        List.of("§7Click to proceed with this action"),
                        null,
                        confirmActionTag));

        // Slot 15: Cancel (Red)
        items.set(
                SLOT_CANCEL,
                new MenuItemDescriptor(
                        SLOT_CANCEL,
                        "RED_CONCRETE",
                        1,
                        "§c§lCancel",
                        List.of("§7Click to return without making changes"),
                        null,
                        cancelActionTag));

        return new RenderMenuPayload(correlationId, SCREEN_TYPE, "§4Confirm Action", SIZE, items);
    }
}
