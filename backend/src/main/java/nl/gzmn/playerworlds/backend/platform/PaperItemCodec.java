package nl.gzmn.playerworlds.backend.platform;

import java.util.Objects;
import org.bukkit.inventory.ItemStack;

/**
 * Paper's version-tagged NBT item codec.
 *
 * <p>Calls {@link ItemStack#serializeAsBytes()} and
 * {@link ItemStack#deserializeBytes(byte[])} by name. Those two methods are the
 * entire version-proofing decision for profile item data (plan section 5.5):
 * renaming them on a Paper upgrade is a compile failure here, which is the pin
 * F5 exists to hold. Do not replace with {@code BukkitObjectOutputStream}, YAML
 * {@code ConfigurationSerializable}, or a hand-rolled encoder — those are
 * Bukkit-version-coupled and historically break across updates (ADR 0008).
 */
public final class PaperItemCodec implements ItemCodec {

    public static final PaperItemCodec INSTANCE = new PaperItemCodec();

    private PaperItemCodec() {}

    @Override
    public byte[] serialize(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        return stack.serializeAsBytes();
    }

    @Override
    public ItemStack deserialize(byte[] data) {
        Objects.requireNonNull(data, "data");
        return ItemStack.deserializeBytes(data);
    }

    @Override
    public byte[] serializeItems(ItemStack[] items) {
        Objects.requireNonNull(items, "items");
        return ItemStack.serializeItemsAsBytes(items);
    }

    @Override
    public ItemStack[] deserializeItems(byte[] data) {
        Objects.requireNonNull(data, "data");
        return ItemStack.deserializeItemsFromBytes(data);
    }
}
