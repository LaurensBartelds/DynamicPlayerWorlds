package nl.gzmn.playerworlds.backend.platform;

import org.bukkit.inventory.ItemStack;

/**
 * Item stack serialisation for player profiles (FR-14, FR-17).
 *
 * <p>Profiles are the one place Minecraft's own item format is stored outside a
 * world folder, so they outlive several server versions. The implementation must
 * produce version-tagged NBT that DataFixerUpper can migrate on read — see
 * {@link PaperItemCodec} and ADR 0008. FR-17's {@code format_version} column
 * tags <em>our</em> envelope around these blobs, not the item NBT itself.
 */
public interface ItemCodec {

    /** Serialises one stack to version-tagged NBT bytes. */
    byte[] serialize(ItemStack stack);

    /**
     * Deserialises one stack, migrating to the running server's data version
     * when needed. A payload that cannot be read must throw — FR-16 refuses a
     * broken profile rather than granting an empty inventory.
     */
    ItemStack deserialize(byte[] data);

    /**
     * Serialises a fixed-length inventory slot array, preserving empty slots so
     * armour and hotbar positions survive the round trip.
     */
    byte[] serializeItems(ItemStack[] items);

    /** Inverse of {@link #serializeItems(ItemStack[])}. */
    ItemStack[] deserializeItems(byte[] data);
}
