package nl.gzmn.playerworlds.backend.gui;

import java.util.Objects;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jspecify.annotations.Nullable;

/**
 * Bukkit {@link InventoryHolder} implementation that wraps an active {@link GuiScreen}.
 */
public final class MenuHolder implements InventoryHolder {

    private final GuiScreen screen;
    private @Nullable Inventory inventory;

    public MenuHolder(GuiScreen screen) {
        this.screen = Objects.requireNonNull(screen, "screen");
    }

    public GuiScreen screen() {
        return screen;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = Objects.requireNonNull(inventory, "inventory");
    }

    @Override
    public Inventory getInventory() {
        if (inventory == null) {
            throw new IllegalStateException("Inventory has not been attached to MenuHolder");
        }
        return inventory;
    }
}
