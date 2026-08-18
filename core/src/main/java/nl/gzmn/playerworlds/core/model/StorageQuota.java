package nl.gzmn.playerworlds.core.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Storage quota evaluation for a player.
 *
 * @param playerUuid player UUID
 * @param usedBytes total storage used by all worlds owned by this player
 * @param limitBytes maximum allowed storage in bytes
 * @param unlimited whether the player is exempt from storage quotas
 */
public record StorageQuota(UUID playerUuid, long usedBytes, long limitBytes, boolean unlimited) {

    public StorageQuota {
        Objects.requireNonNull(playerUuid, "playerUuid");
        if (usedBytes < 0) {
            throw new IllegalArgumentException("usedBytes must not be negative: " + usedBytes);
        }
        if (limitBytes < 0) {
            throw new IllegalArgumentException("limitBytes must not be negative: " + limitBytes);
        }
    }

    /**
     * Whether the player has reached or exceeded their storage limit.
     */
    public boolean isExceeded() {
        return !unlimited && usedBytes >= limitBytes;
    }

    /**
     * Storage utilization percentage between 0.0 and 100.0.
     */
    public double percentage() {
        if (unlimited || limitBytes == 0) {
            return 0.0;
        }
        return Math.min(100.0, (usedBytes * 100.0) / limitBytes);
    }
}
