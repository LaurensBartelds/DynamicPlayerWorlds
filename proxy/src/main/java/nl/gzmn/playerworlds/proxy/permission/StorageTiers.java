package nl.gzmn.playerworlds.proxy.permission;

import com.velocitypowered.api.proxy.Player;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import nl.gzmn.playerworlds.core.config.EntitlementTiers;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.config.StorageQuotaResolver;
import nl.gzmn.playerworlds.core.model.StorageQuota;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves a player's subscription allowances from their permissions (§4, FR-30a, FR-43).
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
 *
 * <p>Storage was the first of these and gives the class its name; {@link #slots} and
 * {@link #borderAllowance} are the same mechanism applied to the other two dials FR-42 puts up
 * for sale. One-time purchases are not here at all — they are rows in {@code world_upgrade},
 * for the reasons in FR-41 — and reach this class only as the {@code bonusBytes} argument.
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
     * @param bonusBytes bytes from their redeemed one-time {@code STORAGE} upgrades (FR-45),
     *     which are a purchase rather than a subscription and so are read from
     *     {@code world_upgrade} rather than from any permission
     */
    public Resolution evaluate(Player player, long usedBytes, NetworkPolicy policy, long bonusBytes) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(policy, "policy");

        Optional<Collection<String>> enumerated = enumeratedPermissions(player);
        if (enumerated.isPresent()) {
            Collection<String> held = enumerated.get();
            // The admin and unlimited nodes are in the same map, so one lookup settles both
            // the tier and the exemption.
            boolean unlimited = held.stream()
                    .anyMatch(node -> node.equalsIgnoreCase(StorageQuotaResolver.PERMISSION_ADMIN)
                            || node.equalsIgnoreCase(StorageQuotaResolver.PERMISSION_STORAGE_UNLIMITED)
                            || node.equals("*"));
            long limit = StorageQuotaResolver.resolveLimitBytes(held, false, policy.defaultStorageLimitBytes());
            return new Resolution(
                    new StorageQuota(player.getUniqueId(), usedBytes, limit, bonusBytes, unlimited), Source.ENUMERATED);
        }

        return new Resolution(
                StorageQuotaResolver.evaluate(
                        player.getUniqueId(),
                        usedBytes,
                        player::hasPermission,
                        policy.storageQuotaTiers(),
                        policy.defaultStorageLimitBytes(),
                        bonusBytes),
                Source.PROBED);
    }

    /**
     * How many worlds a player may own (FR-1, FR-43).
     *
     * @param player the player, who must be online
     * @param policy the network policy supplying the default cap and the configured tiers
     * @return the effective cap, never below {@code worlds.max-per-player}
     */
    public int slots(Player player, NetworkPolicy policy) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(policy, "policy");

        return enumeratedPermissions(player)
                .map(held -> EntitlementTiers.resolveSlots(held, policy.maxWorldsPerPlayer()))
                .orElseGet(() -> EntitlementTiers.resolveSlots(
                        player::hasPermission, policy.slotTiers(), policy.maxWorldsPerPlayer()));
    }

    /**
     * The largest border radius a player's subscription lets them raise a world to (FR-3c,
     * FR-43), already clamped to {@code worlds.max-border-radius}.
     *
     * <p>The clamp is here rather than left to callers because it is not an entitlement
     * question: NFR-3 bounds a world's disk usage by its border and by nothing else, so the
     * ceiling applies to everyone including whoever mistyped a tier.
     *
     * @param player the player, who must be online
     * @param policy the network policy supplying the default radius, tiers and ceiling
     * @return the largest radius they may ask for, before any per-world upgrade is added
     */
    public int borderAllowance(Player player, NetworkPolicy policy) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(policy, "policy");

        int allowance = enumeratedPermissions(player)
                .map(held -> EntitlementTiers.resolveBorder(held, policy.defaultBorderRadius()))
                .orElseGet(() -> EntitlementTiers.resolveBorder(
                        player::hasPermission, policy.borderTiers(), policy.defaultBorderRadius()));
        return Math.min(allowance, policy.maxBorderRadius());
    }

    /** The player's whole permission set, when something can list it. */
    private Optional<Collection<String>> enumeratedPermissions(Player player) {
        return luckPermsPresent() ? LuckPermsTiers.heldPermissions(player) : Optional.empty();
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
