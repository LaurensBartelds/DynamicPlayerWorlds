package nl.gzmn.playerworlds.core.profile;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Everything FR-14 scopes per {@code (uuid, world_id)}.
 *
 * <p>This is <em>our</em> envelope, and {@code format_version} tags it. The item
 * blobs inside are Minecraft's own version-tagged NBT, which DataFixerUpper
 * migrates on read; the two layers are deliberately distinct and conflating them
 * is how the migration story gets lost (ADR 0008).
 *
 * <p>Item containers are stored as one blob each rather than one per slot,
 * because the platform's codec already preserves empty slots — which is what
 * keeps armour and hotbar positions across a round trip.
 *
 * @param inventory the full slot array: main inventory, armour and offhand
 * @param enderChest the ender chest slot array
 * @param xpLevel FR-14's XP level
 * @param xpProgress progress towards the next level, 0..1
 * @param totalExperience the running total, kept because levels alone cannot
 *     reconstruct it
 * @param health FR-14
 * @param foodLevel FR-14's hunger
 * @param saturation FR-14
 * @param potionEffects FR-14, as platform-independent values
 * @param lastLocation where they were, {@code null} for a profile that has never
 *     been anywhere — a fresh one under FR-5
 */
// The blobs are opaque item NBT from the platform codec; wrapping each in a
// type would add a layer that carries no meaning. The hazard the check warns
// about — reference equality from the generated members — is removed by the
// explicit equals and hashCode below.
@SuppressWarnings("ArrayRecordComponent")
public record ProfileEnvelope(
        byte[] inventory,
        byte[] enderChest,
        int xpLevel,
        float xpProgress,
        int totalExperience,
        double health,
        int foodLevel,
        float saturation,
        List<StoredPotionEffect> potionEffects,
        @Nullable StoredLocation lastLocation) {

    public ProfileEnvelope {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(enderChest, "enderChest");
        Objects.requireNonNull(potionEffects, "potionEffects");
        potionEffects = List.copyOf(potionEffects);
        if (health < 0) {
            throw new IllegalArgumentException("health must not be negative, was: " + health);
        }
    }

    /** A potion effect, without depending on the platform's enum (FR-14). */
    public record StoredPotionEffect(
            String type, int duration, int amplifier, boolean ambient, boolean particles, boolean icon) {
        public StoredPotionEffect {
            Objects.requireNonNull(type, "type");
            if (type.isBlank()) {
                throw new IllegalArgumentException("potion effect type must not be blank");
            }
        }
    }

    /**
     * A position inside the world this profile belongs to.
     *
     * @param dimension which of FR-2's three, by the platform's dimension name
     */
    public record StoredLocation(String dimension, double x, double y, double z, float yaw, float pitch) {
        public StoredLocation {
            Objects.requireNonNull(dimension, "dimension");
        }
    }

    // Arrays in a record get reference equality from the generated members,
    // which would make two identical profiles compare unequal and quietly break
    // every test that round-trips one.
    @Override
    public boolean equals(@Nullable Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ProfileEnvelope that)) {
            return false;
        }
        return xpLevel == that.xpLevel
                && Float.compare(xpProgress, that.xpProgress) == 0
                && totalExperience == that.totalExperience
                && Double.compare(health, that.health) == 0
                && foodLevel == that.foodLevel
                && Float.compare(saturation, that.saturation) == 0
                && Arrays.equals(inventory, that.inventory)
                && Arrays.equals(enderChest, that.enderChest)
                && potionEffects.equals(that.potionEffects)
                && Objects.equals(lastLocation, that.lastLocation);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                Arrays.hashCode(inventory),
                Arrays.hashCode(enderChest),
                xpLevel,
                xpProgress,
                totalExperience,
                health,
                foodLevel,
                saturation,
                potionEffects,
                lastLocation);
    }

    @Override
    public String toString() {
        return "ProfileEnvelope[inventory=" + inventory.length + "B, enderChest=" + enderChest.length + "B, xpLevel="
                + xpLevel + ", health=" + health + ", effects=" + potionEffects.size() + "]";
    }
}
