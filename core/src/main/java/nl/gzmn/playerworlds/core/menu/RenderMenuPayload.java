package nl.gzmn.playerworlds.core.menu;

import java.util.List;
import java.util.Objects;

/**
 * Full screen view payload containing menu metadata and item descriptors.
 *
 * @param correlationId unique correlation id
 * @param screenType screen type identifier (e.g. "MAIN", "MY_WORLDS")
 * @param title formatted title of the inventory
 * @param size inventory size (multiple of 9)
 * @param items item descriptors to render in the menu
 */
public record RenderMenuPayload(
        long correlationId, String screenType, String title, int size, List<MenuItemDescriptor> items) {

    public RenderMenuPayload {
        Objects.requireNonNull(screenType, "screenType");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(items, "items");
        items = List.copyOf(items);
    }
}
