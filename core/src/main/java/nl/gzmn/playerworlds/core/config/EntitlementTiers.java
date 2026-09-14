package nl.gzmn.playerworlds.core.config;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Subscription tiers that are a plain number: world slots and border radius (FR-43).
 *
 * <p>The same shape as {@link StorageQuotaResolver}, and deliberately so — an operator who
 * has learned how {@code gzmn.worlds.storage.<size>} behaves already knows how these two
 * behave. Highest node held wins, and the network default is a floor rather than something
 * a tier replaces (FR-42): an operator who raises {@code worlds.max-per-player} has raised
 * it for subscribers too, and does not have to re-issue anything.
 *
 * <p>Two overloads for the same reason as storage. LuckPerms can hand over a player's whole
 * resolved permission map, and where it is installed any tier is honoured whether or not it
 * was configured. Velocity's own {@code PermissionSubject} answers one node at a time and
 * cannot list anything, so there the only tiers that exist are the ones an operator named
 * in {@code worlds.slot-tiers} and {@code worlds.border-tiers}.
 */
public final class EntitlementTiers {

    /** {@code gzmn.worlds.slots.<n>} — how many worlds a player may own (FR-1). */
    public static final String PERMISSION_SLOTS_PREFIX = "gzmn.worlds.slots.";

    /** {@code gzmn.worlds.border.<radius>} — the largest radius they may raise a world to. */
    public static final String PERMISSION_BORDER_PREFIX = "gzmn.worlds.border.";

    private static final Pattern SLOTS_PATTERN =
            Pattern.compile("^gzmn\\.worlds\\.slots\\.(\\d+)$", Pattern.CASE_INSENSITIVE);

    private static final Pattern BORDER_PATTERN =
            Pattern.compile("^gzmn\\.worlds\\.border\\.(\\d+)$", Pattern.CASE_INSENSITIVE);

    private EntitlementTiers() {}

    /**
     * Parses {@code gzmn.worlds.slots.<n>} into a world count.
     *
     * @param permission the permission node
     * @return the count, or -1 when the node is not a slots tier
     */
    public static int parseSlots(String permission) {
        return parse(SLOTS_PATTERN, permission);
    }

    /**
     * Parses {@code gzmn.worlds.border.<radius>} into a radius in blocks.
     *
     * @param permission the permission node
     * @return the radius, or -1 when the node is not a border tier
     */
    public static int parseBorder(String permission) {
        return parse(BORDER_PATTERN, permission);
    }

    /**
     * How many worlds a player may own, from permissions that can be enumerated.
     *
     * @param permissions every node the player holds
     * @param defaultMaxWorlds the network default, which is a floor (FR-42)
     * @return the effective cap
     */
    public static int resolveSlots(Collection<String> permissions, int defaultMaxWorlds) {
        return Math.max(defaultMaxWorlds, highest(permissions, EntitlementTiers::parseSlots));
    }

    /**
     * How many worlds a player may own, from a permission backend that can only be asked
     * about one node at a time.
     *
     * @param holdsPermission answers whether the player holds one node
     * @param tiers the configured {@code worlds.slot-tiers} suffixes
     * @param defaultMaxWorlds the network default, which is a floor (FR-42)
     * @return the effective cap
     */
    public static int resolveSlots(Predicate<String> holdsPermission, Collection<String> tiers, int defaultMaxWorlds) {
        return resolveSlots(heldOf(holdsPermission, tiers, PERMISSION_SLOTS_PREFIX), defaultMaxWorlds);
    }

    /**
     * The largest border radius a player may raise a world to, from enumerated permissions.
     *
     * @param permissions every node the player holds
     * @param defaultBorderRadius the network default, which is a floor (FR-42)
     * @return the effective radius allowance, before the {@code worlds.max-border-radius}
     *     ceiling is applied
     */
    public static int resolveBorder(Collection<String> permissions, int defaultBorderRadius) {
        return Math.max(defaultBorderRadius, highest(permissions, EntitlementTiers::parseBorder));
    }

    /**
     * The largest border radius a player may raise a world to, from a probed backend.
     *
     * @param holdsPermission answers whether the player holds one node
     * @param tiers the configured {@code worlds.border-tiers} suffixes
     * @param defaultBorderRadius the network default, which is a floor (FR-42)
     * @return the effective radius allowance, before the {@code worlds.max-border-radius}
     *     ceiling is applied
     */
    public static int resolveBorder(
            Predicate<String> holdsPermission, Collection<String> tiers, int defaultBorderRadius) {
        return resolveBorder(heldOf(holdsPermission, tiers, PERMISSION_BORDER_PREFIX), defaultBorderRadius);
    }

    /**
     * Turns configured tier suffixes into the permission nodes that name them.
     *
     * @param tiers suffixes such as {@code 5}, from {@code worlds.slot-tiers}
     * @param prefix the node prefix to qualify them with
     * @return fully qualified permission nodes
     */
    public static List<String> candidatePermissions(Collection<String> tiers, String prefix) {
        Objects.requireNonNull(tiers, "tiers");
        Objects.requireNonNull(prefix, "prefix");
        return tiers.stream()
                .filter(tier -> tier != null && !tier.isBlank())
                .map(tier -> prefix + tier.strip())
                .toList();
    }

    private static List<String> heldOf(Predicate<String> holdsPermission, Collection<String> tiers, String prefix) {
        Objects.requireNonNull(holdsPermission, "holdsPermission");
        return candidatePermissions(tiers, prefix).stream()
                .filter(holdsPermission)
                .toList();
    }

    private static int highest(Collection<String> permissions, ToIntFunction<String> parser) {
        if (permissions == null) {
            return -1;
        }
        int best = -1;
        for (String permission : permissions) {
            if (permission == null) {
                continue;
            }
            int parsed = parser.applyAsInt(permission);
            if (parsed > best) {
                best = parsed;
            }
        }
        return best;
    }

    private static int parse(Pattern pattern, String permission) {
        if (permission == null || permission.isBlank()) {
            return -1;
        }
        Matcher matcher = pattern.matcher(permission.trim().toLowerCase(Locale.ROOT));
        if (!matcher.matches()) {
            return -1;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            // A node with more digits than an int holds. Refusing it is right: it is a typo,
            // and honouring it as Integer.MAX_VALUE would hand out an unbounded allowance.
            return -1;
        }
    }
}
