package nl.gzmn.playerworlds.proxy.permission;

import com.velocitypowered.api.proxy.Player;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.config.StorageQuotaResolver;
import nl.gzmn.playerworlds.core.model.StorageQuota;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves a player's storage allowance from their permissions (§4, FR-30a).
 *
 * <p>Two ways of answering the same question, because the platform decides which is possible.
 * Velocity's {@code PermissionSubject} exposes {@code hasPermission} and {@code getPermissionValue}
 * and nothing that lists what a player holds, so on its own the proxy can only ask about nodes it
 * already knows to name — the tiers in {@code storage.quota-tiers}. LuckPerms can hand over the
 * whole resolved permission map, and where it is installed the tier a player holds is read
 * straight off it, so any tier works whether or not an operator remembered to configure it.
 *
 * <p>Detection is deliberately late and cached. A proxy plugin cannot assume LuckPerms has loaded
 * by the time this is constructed, so the first evaluation decides, and everything after it takes
 * the same route.
 */
public final class StorageTiers {

    private static final Logger log = LoggerFactory.getLogger(StorageTiers.class);

    /** Present exactly when the LuckPerms API is on the classpath. */
    private static final String LUCKPERMS_PROVIDER = "net.luckperms.api.LuckPermsProvider";

    private volatile @org.jspecify.annotations.Nullable Boolean enumerable;

    /** How the last evaluation answered, for {@code /world storage} to explain itself. */
    public enum Source {
        /** LuckPerms listed the player's permissions, so any tier is honoured. */
        ENUMERATED,
        /** Only the configured tiers were asked about. */
        PROBED
    }

    /** A resolved allowance and the route that produced it. */
    public record Resolution(StorageQuota quota, Source source) {
        public Resolution {
            Objects.requireNonNull(quota, "quota");
            Objects.requireNonNull(source, "source");
        }
    }

    /**
     * Evaluates one player's allowance.
     *
     * @param player the player to evaluate, who must be online
     * @param usedBytes storage already attributed to them
     * @param policy the network policy supplying the default limit and the configured tiers
     */
    public Resolution evaluate(Player player, long usedBytes, NetworkPolicy policy) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(policy, "policy");

        if (luckPermsPresent()) {
            Optional<Collection<String>> held = LuckPermsTiers.heldPermissions(player);
            if (held.isPresent()) {
                // The admin and unlimited nodes are in the same map, so one lookup settles both
                // the tier and the exemption.
                boolean unlimited = held.get().stream()
                        .anyMatch(node -> node.equalsIgnoreCase(StorageQuotaResolver.PERMISSION_ADMIN)
                                || node.equalsIgnoreCase(StorageQuotaResolver.PERMISSION_STORAGE_UNLIMITED)
                                || node.equals("*"));
                long limit =
                        StorageQuotaResolver.resolveLimitBytes(held.get(), false, policy.defaultStorageLimitBytes());
                return new Resolution(
                        new StorageQuota(player.getUniqueId(), usedBytes, limit, unlimited), Source.ENUMERATED);
            }
        }

        return new Resolution(
                StorageQuotaResolver.evaluate(
                        player.getUniqueId(),
                        usedBytes,
                        player::hasPermission,
                        policy.storageQuotaTiers(),
                        policy.defaultStorageLimitBytes()),
                Source.PROBED);
    }

    /** Whether LuckPerms is usable, decided once and remembered. */
    private boolean luckPermsPresent() {
        Boolean known = enumerable;
        if (known != null) {
            return known;
        }
        boolean present;
        try {
            Class.forName(LUCKPERMS_PROVIDER);
            present = LuckPermsTiers.usable();
        } catch (ClassNotFoundException e) {
            present = false;
        }
        enumerable = present;
        log.info(
                present
                        ? "LuckPerms detected: storage tiers are read from the player's permissions, so any"
                                + " gzmn.worlds.storage.<size> node is honoured"
                        : "LuckPerms not detected: storage tiers are limited to the nodes named by"
                                + " storage.quota-tiers, because Velocity cannot enumerate permissions");
        return present;
    }
}
