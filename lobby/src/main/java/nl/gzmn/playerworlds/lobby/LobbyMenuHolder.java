package nl.gzmn.playerworlds.lobby;

import java.util.Map;
import java.util.Objects;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jspecify.annotations.Nullable;

/**
 * Bukkit {@link InventoryHolder} representing an active menu screen rendered on the lobby server.
 *
 * <p>Carries the screen correlation ID, sequence number, and mapping of slot indices to action tags.
 */
public final class LobbyMenuHolder implements InventoryHolder {

    private final long correlationId;
    private final int screenSequence;
    private final Map<Integer, String> slotActions;
    private @Nullable Inventory inventory;

    public LobbyMenuHolder(long correlationId, int screenSequence, Map<Integer, String> slotActions) {
        this.correlationId = correlationId;
        this.screenSequence = screenSequence;
        this.slotActions = Map.copyOf(Objects.requireNonNull(slotActions, "slotActions"));
    }

    /**
     * Returns the action tag associated with the specified slot, or null if none.
     *
     * @param slot slot index
     * @return action tag or null
     */
    public @Nullable String actionTagForSlot(int slot) {
        return slotActions.get(slot);
    }

    /**
     * Associates the Bukkit {@link Inventory} with this holder.
     *
     * @param inventory the inventory
     */
    public void setInventory(Inventory inventory) {
        this.inventory = Objects.requireNonNull(inventory, "inventory");
    }

    @Override
    public Inventory getInventory() {
        if (inventory == null) {
            throw new IllegalStateException("Inventory has not been attached to LobbyMenuHolder");
        }
        return inventory;
    }

    public long correlationId() {
        return correlationId;
    }

    public int screenSequence() {
        return screenSequence;
    }

    public Map<Integer, String> slotActions() {
        return slotActions;
    }
}
