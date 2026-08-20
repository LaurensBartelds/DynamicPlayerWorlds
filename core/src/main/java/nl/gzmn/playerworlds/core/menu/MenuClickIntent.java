package nl.gzmn.playerworlds.core.menu;

import java.util.Objects;

/**
 * Menu click intent sent when a player clicks a slot with an action tag in the rendered menu.
 *
 * @param correlationId unique correlation id
 * @param actionTag the action token of the clicked slot
 * @param screenSequence sequence number of the screen when clicked
 */
public record MenuClickIntent(long correlationId, String actionTag, int screenSequence) {

    public MenuClickIntent {
        Objects.requireNonNull(actionTag, "actionTag");
    }
}
