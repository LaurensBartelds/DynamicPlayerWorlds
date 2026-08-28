package nl.gzmn.playerworlds.core.menu;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Data descriptor for a single GUI item slot within a rendered menu.
 *
 * @param slot 0-based inventory slot index
 * @param materialName standard Bukkit material identifier (e.g. "GRASS_BLOCK", "PLAYER_HEAD")
 * @param amount stack count (usually 1-64)
 * @param displayName formatted display name string
 * @param lore formatted lore lines
 * @param skullOwner player UUID for player heads, or null
 * @param actionTag action identifier returned upon click (e.g. "NAV:MY_WORLDS", "ACTION:JOIN:...")
 */
public record MenuItemDescriptor(
        int slot,
        String materialName,
        int amount,
        String displayName,
        List<String> lore,
        @Nullable UUID skullOwner,
        String actionTag) {

    public MenuItemDescriptor {
        Objects.requireNonNull(materialName, "materialName");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(lore, "lore");
        Objects.requireNonNull(actionTag, "actionTag");
        lore = List.copyOf(lore);
    }
}
