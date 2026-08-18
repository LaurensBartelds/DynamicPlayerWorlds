package nl.gzmn.playerworlds.backend.gui;

import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;

/**
 * Bukkit event listener for inventory click, drag, close, and quit events.
 *
 * <p>Cancels unwanted item movement in custom menu GUIs and routes slot click interactions
 * to the corresponding {@link GuiScreen}.
 */
public final class MenuListener implements Listener {

    private final MenuService menuService;
    private final MenuChannel menuChannel;

    public MenuListener(MenuService menuService, MenuChannel menuChannel) {
        this.menuService = Objects.requireNonNull(menuService, "menuService");
        this.menuChannel = Objects.requireNonNull(menuChannel, "menuChannel");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory topInventory = event.getView().getTopInventory();
        if (topInventory.getHolder() instanceof MenuHolder menuHolder) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                if (event.getClickedInventory() != null
                        && event.getClickedInventory().getHolder() instanceof MenuHolder) {
                    menuHolder.screen().handleClick(player, event.getSlot(), event.getClick());
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MenuHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof MenuHolder) {
            if (event.getPlayer() instanceof Player player) {
                menuService.handleClose(player);
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        menuService.handleQuit(player);
        menuChannel.handleQuit(player);
    }
}
