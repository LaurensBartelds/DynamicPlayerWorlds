package nl.gzmn.playerworlds.lobby;

import java.util.List;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import nl.gzmn.playerworlds.core.menu.MenuItemDescriptor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

/**
 * Pure utility class for converting {@link MenuItemDescriptor}s into Bukkit {@link ItemStack}s.
 */
public final class LobbyItemUtil {

    private LobbyItemUtil() {}

    /**
     * Converts a {@link MenuItemDescriptor} into a Bukkit {@link ItemStack}.
     *
     * @param descriptor the item descriptor
     * @return constructed ItemStack
     */
    public static ItemStack create(MenuItemDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");

        Material material = Material.matchMaterial(descriptor.materialName());
        if (material == null) {
            material = Material.STONE;
        }

        ItemStack item = new ItemStack(material, Math.max(1, descriptor.amount()));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(clean(LegacyComponentSerializer.legacySection().deserialize(descriptor.displayName())));
            List<Component> lore = descriptor.lore().stream()
                    .map(line -> clean(LegacyComponentSerializer.legacySection().deserialize(line)))
                    .toList();
            meta.lore(lore);

            if (descriptor.skullOwner() != null && meta instanceof SkullMeta skullMeta) {
                skullMeta.setOwningPlayer(Bukkit.getOfflinePlayer(descriptor.skullOwner()));
            }

            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            try {
                meta.addItemFlags(ItemFlag.valueOf("HIDE_ADDITIONAL_TOOLTIP"));
            } catch (IllegalArgumentException ignored) {
                // Ignore if not present on older Bukkit API builds
            }
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
