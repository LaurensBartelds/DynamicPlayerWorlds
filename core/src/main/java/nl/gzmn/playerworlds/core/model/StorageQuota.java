package nl.gzmn.playerworlds.core.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Storage quota evaluation for a player.
 *
 * @param playerUuid player UUID
 * @param usedBytes total storage used by all worlds owned by this player
 * @param limitBytes allowance from the network default and the player's subscription tier
 *     (FR-43), before purchased upgrades
 * @param bonusBytes allowance added by redeemed one-time {@code STORAGE} upgrades (FR-45)
 * @param unlimited whether the player is exempt from storage quotas
 */
public record StorageQuota(UUID playerUuid, long usedBytes, long limitBytes, long bonusBytes, boolean unlimited) {

    public StorageQuota {
        Objects.requireNonNull(playerUuid, "playerUuid");
        if (usedBytes < 0) {
            throw new IllegalArgumentException("usedBytes must not be negative: " + usedBytes);
        }
        if (limitBytes < 0) {
            throw new IllegalArgumentException("limitBytes must not be negative: " + limitBytes);
        }
        if (bonusBytes < 0) {
            throw new IllegalArgumentException("bonusBytes must not be negative: " + bonusBytes);
        }
    }

    // Deliberately no four-argument convenience constructor. Every site that builds a quota
    // has to say what the player has bought, because a site that forgets refuses a create to
    // somebody who paid for the space — and a default of zero would let it compile.

    /**
     * The allowance actually enforced: the tier plus whatever has been bought (FR-45).
     *
     * <p>Kept separate from {@link #limitBytes()} rather than pre-summed so a screen can say
     * "5 GB, plus 2 GB purchased" — a player who has paid for storage should be able to see
     * that they have it.
     *
     * @return total bytes this player may use
     */
    public long effectiveLimitBytes() {
        long total = limitBytes + bonusBytes;
        // Saturating rather than wrapping. An operator can configure a limit near the top of
        // the range, and an allowance that overflowed to negative would read as "exceeded"
        // and refuse every create on the network.
        return total < 0 ? Long.MAX_VALUE : total;
    }

    /**
     * Whether the player has reached or exceeded their storage limit.
     */
    public boolean isExceeded() {
        return !unlimited && usedBytes >= effectiveLimitBytes();
    }

    /**
     * Storage utilization percentage between 0.0 and 100.0.
     */
    public double percentage() {
        long limit = effectiveLimitBytes();
        if (unlimited || limit == 0) {
            return 0.0;
        }
        return Math.min(100.0, (usedBytes * 100.0) / limit);
    }
}
