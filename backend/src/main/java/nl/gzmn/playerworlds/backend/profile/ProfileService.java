package nl.gzmn.playerworlds.backend.profile;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import nl.gzmn.playerworlds.backend.platform.ItemCodec;
import nl.gzmn.playerworlds.core.concurrent.MainThread;
import nl.gzmn.playerworlds.core.profile.ProfileEnvelope;
import nl.gzmn.playerworlds.core.profile.ProfileEnvelope.StoredLocation;
import nl.gzmn.playerworlds.core.profile.ProfileEnvelope.StoredPotionEffect;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Turns a live {@link Player} into a {@link ProfileEnvelope} and back (FR-14).
 *
 * <p>Both directions run on the main thread — they read and write live entity
 * state — and both assert it, because doing either off-thread produces a torn
 * inventory rather than an exception.
 *
 * <p>Item stacks go through {@link ItemCodec} so what is stored is Minecraft's
 * own version-tagged NBT, which DataFixerUpper migrates on read. Everything
 * around them is this plugin's envelope and versions separately (ADR 0008).
 */
public final class ProfileService {

    private final ItemCodec items;

    public ProfileService(ItemCodec items) {
        this.items = Objects.requireNonNull(items, "items");
    }

    /**
     * Reads everything FR-14 scopes per {@code (uuid, world_id)}.
     *
     * @param dimensionName the Bukkit world the player is in, stored so a rejoin
     *     returns them where they were rather than to spawn
     */
    public ProfileEnvelope capture(Player player, String dimensionName) {
        MainThread.assertOn();
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(dimensionName, "dimensionName");

        List<StoredPotionEffect> effects = new ArrayList<>();
        for (PotionEffect effect : player.getActivePotionEffects()) {
            effects.add(new StoredPotionEffect(
                    effect.getType().getKey().toString(),
                    effect.getDuration(),
                    effect.getAmplifier(),
                    effect.isAmbient(),
                    effect.hasParticles(),
                    effect.hasIcon()));
        }

        // getLocation() is annotated nullable on the entity contract even though
        // a live player always has one; falling back to the world spawn keeps the
        // capture total rather than making a profile depend on that promise.
        Location where = player.getLocation();
        Location position = where != null ? where : player.getWorld().getSpawnLocation();
        return new ProfileEnvelope(
                // getContents() is the full slot array including armour and
                // offhand, and the codec preserves empty slots, so hotbar and
                // armour positions survive the round trip.
                items.serializeItems(player.getInventory().getContents()),
                items.serializeItems(player.getEnderChest().getContents()),
                player.getLevel(),
                player.getExp(),
                player.getTotalExperience(),
                player.getHealth(),
                player.getFoodLevel(),
                player.getSaturation(),
                effects,
                new StoredLocation(
                        dimensionName,
                        position.getX(),
                        position.getY(),
                        position.getZ(),
                        position.getYaw(),
                        position.getPitch()));
    }

    /**
     * Applies a stored profile to a player.
     *
     * <p>Does not teleport: the caller owns where the player ends up, because the
     * stored location may name a dimension that is not materialised, or the
     * player may be arriving somewhere else entirely.
     */
    public void restore(Player player, ProfileEnvelope profile) {
        MainThread.assertOn();
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(profile, "profile");

        player.getInventory().setContents(items.deserializeItems(profile.inventory()));
        player.getEnderChest().setContents(items.deserializeItems(profile.enderChest()));
        player.setLevel(profile.xpLevel());
        player.setExp(profile.xpProgress());
        player.setTotalExperience(profile.totalExperience());
        player.setHealth(Math.min(profile.health(), maxHealth(player)));
        player.setFoodLevel(profile.foodLevel());
        player.setSaturation(profile.saturation());

        for (PotionEffect active : player.getActivePotionEffects()) {
            player.removePotionEffect(active.getType());
        }
        for (StoredPotionEffect stored : profile.potionEffects()) {
            PotionEffectType type = Registry.EFFECT.get(NamespacedKey.fromString(stored.type()));
            if (type == null) {
                // An effect this server does not know: a removed vanilla effect,
                // or one from a plugin that is no longer installed. Dropping it
                // is right — the alternative is refusing the whole profile over
                // something cosmetic.
                continue;
            }
            player.addPotionEffect(new PotionEffect(
                    type, stored.duration(), stored.amplifier(), stored.ambient(), stored.particles(), stored.icon()));
        }
    }

    /**
     * FR-5's fresh profile: empty inventory, full health, no XP.
     *
     * <p>Used when FR-15b finds no row for the snapshot, meaning the player has
     * never played in this world.
     */
    public void applyFresh(Player player) {
        MainThread.assertOn();
        Objects.requireNonNull(player, "player");
        player.getInventory().clear();
        player.getEnderChest().clear();
        player.setLevel(0);
        player.setExp(0f);
        player.setTotalExperience(0);
        player.setHealth(maxHealth(player));
        player.setFoodLevel(20);
        player.setSaturation(5f);
        for (PotionEffect active : player.getActivePotionEffects()) {
            player.removePotionEffect(active.getType());
        }
    }

    /** The player's own maximum, which an attribute or another plugin may have changed. */
    private static double maxHealth(Player player) {
        var attribute = player.getAttribute(Attribute.MAX_HEALTH);
        return attribute == null ? 20.0 : attribute.getValue();
    }
}
