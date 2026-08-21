package nl.gzmn.playerworlds.lobby;

import java.util.Objects;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

/**
 * Event listener for chest inventory GUI interactions on the lobby server.
 *
 * <p>Cancels all item movements in {@link LobbyMenuHolder} inventories and dispatches click intents
 * and close notices to the Velocity proxy.
 */
public final class LobbyMenuListener implements Listener {

    private final LobbyMenuChannel menuChannel;

    public LobbyMenuListener(LobbyMenuChannel menuChannel) {
        this.menuChannel = Objects.requireNonNull(menuChannel, "menuChannel");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory topInventory = event.getView().getTopInventory();
        if (topInventory != null && topInventory.getHolder() instanceof LobbyMenuHolder holder) {
            event.setCancelled(true);

            if (event.getRawSlot() >= 0 && event.getRawSlot() < topInventory.getSize()) {
                String actionTag = holder.actionTagForSlot(event.getRawSlot());
                if (actionTag != null && !actionTag.isBlank() && event.getWhoClicked() instanceof Player player) {
                    menuChannel.sendClickIntent(player, holder.correlationId(), actionTag, holder.screenSequence());
                    player.playSound(
                            Objects.requireNonNull(player.getLocation(), "location"),
                            Sound.UI_BUTTON_CLICK,
                            1.0f,
                            1.0f);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory topInventory = event.getView().getTopInventory();
        if (topInventory != null && topInventory.getHolder() instanceof LobbyMenuHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        Inventory inventory = event.getInventory();
        if (inventory != null && inventory.getHolder() instanceof LobbyMenuHolder holder) {
            if (event.getPlayer() instanceof Player player) {
                menuChannel.sendClosedNotice(player, holder.correlationId());
            }
        }
    }
}
