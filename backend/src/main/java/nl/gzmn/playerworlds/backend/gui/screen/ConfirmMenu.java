package nl.gzmn.playerworlds.backend.gui.screen;

import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import nl.gzmn.playerworlds.backend.gui.GuiScreen;
import nl.gzmn.playerworlds.backend.gui.ItemUtil;
import nl.gzmn.playerworlds.backend.gui.MenuHolder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

/**
 * Reusable click-to-confirm modal screen for destructive or impactful actions.
 *
 * <p>This modal is the GUI substitute for the typed {@code confirm} token that
 * chat commands require:
 * <ul>
 *   <li>FR-27 — archive / soft-delete ({@code /world delete <name> confirm})</li>
 *   <li>FR-37 — permanent hard-delete of an archived world</li>
 * </ul>
 * A click on {@link #SLOT_CONFIRM} is the only path that runs {@code onConfirm},
 * which is what authorises the backend to emit {@code ArchiveWorld} /
 * {@code HardDeleteWorld} intents with {@code confirmed = true}. Cancelling or
 * closing the inventory must not fire the destructive action.
 */
public final class ConfirmMenu implements GuiScreen {

    public static final int SLOT_INFO = 4;
    public static final int SLOT_CONFIRM = 11;
    public static final int SLOT_CANCEL = 15;

    private final Component title;
    private final Component description;
    private final Runnable onConfirm;
    private final Runnable onCancel;

    public ConfirmMenu(Component title, Component description, Runnable onConfirm, Runnable onCancel) {
        this.title = Objects.requireNonNull(title, "title");
        this.description = Objects.requireNonNull(description, "description");
        this.onConfirm = Objects.requireNonNull(onConfirm, "onConfirm");
        this.onCancel = Objects.requireNonNull(onCancel, "onCancel");
    }

    public Component title() {
        return title;
    }

    public Component description() {
        return description;
    }

    @Override
    public Inventory render(Player player) {
        Objects.requireNonNull(player, "player");
        MenuHolder holder = new MenuHolder(this);
        Inventory inventory =
                Bukkit.createInventory(holder, 27, Component.text("Confirm Action", NamedTextColor.DARK_RED));
        holder.setInventory(inventory);

        for (int i = 0; i < 27; i++) {
            inventory.setItem(i, ItemUtil.filler());
        }

        // Slot 4: Info
        inventory.setItem(SLOT_INFO, ItemUtil.create(Material.PAPER, title, description));

        // Slot 11: Confirm (Green)
        inventory.setItem(
                SLOT_CONFIRM,
                ItemUtil.create(
                        Material.LIME_CONCRETE,
                        Component.text("Confirm", NamedTextColor.GREEN, TextDecoration.BOLD),
                        Component.text("Click to proceed with this action", NamedTextColor.GRAY)));

        // Slot 15: Cancel (Red)
        inventory.setItem(
                SLOT_CANCEL,
                ItemUtil.create(
                        Material.RED_CONCRETE,
                        Component.text("Cancel", NamedTextColor.RED, TextDecoration.BOLD),
                        Component.text("Click to return without making changes", NamedTextColor.GRAY)));

        return inventory;
    }

    @Override
    public void handleClick(Player player, int slot, ClickType clickType) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(clickType, "clickType");

        if (slot == SLOT_CONFIRM) {
            onConfirm.run();
        } else if (slot == SLOT_CANCEL) {
            onCancel.run();
        }
    }

    @Override
    public void refresh(Player player) {
        Objects.requireNonNull(player, "player");
        // No-op for confirmation dialog
    }
}
