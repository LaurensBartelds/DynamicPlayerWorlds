package nl.gzmn.playerworlds.backend.gui;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.jspecify.annotations.Nullable;

/**
 * Utility methods for building Bukkit {@link ItemStack}s with Adventure {@link Component}s
 * and custom metadata.
 */
public final class ItemUtil {

    private ItemUtil() {}

    /**
     * Creates an {@link ItemStack} with a single item, styled display name, and optional lore lines.
     *
     * @param material the material
     * @param name the display name component
     * @param lore optional lore lines
     * @return constructed ItemStack
     */
    public static ItemStack create(Material material, Component name, Component... lore) {
        return create(material, 1, name, List.of(lore));
    }

    /**
     * Creates an {@link ItemStack} with a single item, styled display name, and lore list.
     *
     * @param material the material
     * @param name the display name component
     * @param lore the lore lines
     * @return constructed ItemStack
     */
    public static ItemStack create(Material material, Component name, List<Component> lore) {
        return create(material, 1, name, lore);
    }

    /**
     * Creates an {@link ItemStack} with specified amount, display name, and lore list.
     *
     * @param material the material
     * @param amount the stack size
     * @param name the display name component
     * @param lore the lore lines
     * @return constructed ItemStack
     */
    public static ItemStack create(Material material, int amount, Component name, List<Component> lore) {
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(lore, "lore");

        ItemStack item = new ItemStack(material, Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(clean(name));
            meta.lore(lore.stream().map(ItemUtil::clean).toList());
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Creates a player head {@link ItemStack} for a given player UUID or fallback name.
     *
     * @param ownerUuid the player UUID
     * @param fallbackOwnerName fallback username if offline/unresolved
     * @param name the display name component
     * @param lore the lore lines
     * @return constructed player head ItemStack
     */
    public static ItemStack createPlayerHead(
            @Nullable UUID ownerUuid, @Nullable String fallbackOwnerName, Component name, List<Component> lore) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(lore, "lore");

        ItemStack item = new ItemStack(Material.PLAYER_HEAD, 1);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        if (meta != null) {
            meta.displayName(clean(name));
            meta.lore(lore.stream().map(ItemUtil::clean).toList());
            if (ownerUuid != null) {
                meta.setOwningPlayer(Bukkit.getOfflinePlayer(ownerUuid));
            } else if (fallbackOwnerName != null && !fallbackOwnerName.isBlank()) {
                meta.setOwningPlayer(Bukkit.getOfflinePlayer(fallbackOwnerName));
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Creates a filler item using gray stained glass pane with an empty display name.
     *
     * @return filler ItemStack
     */
    public static ItemStack filler() {
        return filler(Material.GRAY_STAINED_GLASS_PANE);
    }

    /**
     * Creates a filler item using the specified material with an empty display name.
     *
     * @param material the stained glass pane material
     * @return filler ItemStack
     */
    public static ItemStack filler(Material material) {
        Objects.requireNonNull(material, "material");
        ItemStack item = new ItemStack(material, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.empty());
            item.setItemMeta(meta);
        }
        return item;
    }

    private static Component clean(Component component) {
        if (!component.hasDecoration(TextDecoration.ITALIC)) {
            return component.decoration(TextDecoration.ITALIC, false);
        }
        return component;
    }
}
