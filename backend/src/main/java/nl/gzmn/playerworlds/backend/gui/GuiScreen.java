package nl.gzmn.playerworlds.backend.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

/**
 * Interface representing a GUI screen view, capable of rendering an inventory
 * and responding to slot click interactions.
 */
public interface GuiScreen {

    /**
     * Renders the Bukkit inventory for the given player.
     *
     * @param player the player viewing the screen
     * @return the rendered inventory
     */
    Inventory render(Player player);

    /**
     * Handles a click interaction on a specific slot in the screen.
     *
     * @param player the clicking player
     * @param slot the clicked slot index in the top inventory
     * @param clickType the type of click performed
     */
    void handleClick(Player player, int slot, ClickType clickType);

    /**
     * Refreshes the contents of the screen for the player.
     *
     * @param player the player viewing the screen
     */
    void refresh(Player player);
}
