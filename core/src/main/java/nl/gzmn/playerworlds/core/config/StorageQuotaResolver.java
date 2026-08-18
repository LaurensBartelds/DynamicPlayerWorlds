package nl.gzmn.playerworlds.core.config;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import nl.gzmn.playerworlds.core.model.StorageQuota;

/**
 * Resolves player storage quota limits based on LuckPerms permissions and network policy defaults.
 */
public final class StorageQuotaResolver {

    public static final String PERMISSION_ADMIN = "gzmn.worlds.admin";
    public static final String PERMISSION_STORAGE_UNLIMITED = "gzmn.worlds.storage.unlimited";
    public static final String PERMISSION_STORAGE_PREFIX = "gzmn.worlds.storage.";

    private static final Pattern STORAGE_PERMISSION_PATTERN =
            Pattern.compile("^gzmn\\.worlds\\.storage\\.(\\d+)(b|kb|mb|gb|tb)?$", Pattern.CASE_INSENSITIVE);

    /**
     * Tier nodes probed when a permission backend cannot be enumerated.
     *
     * <p>Velocity's {@code PermissionSubject} answers one node at a time — {@code hasPermission}
     * and {@code getPermissionValue} — and offers no way to list what a player holds. A caller
     * with only that API therefore cannot scan for {@code gzmn.worlds.storage.*} and has to ask
     * about specific nodes instead. This is the ladder it asks about: any tier here is honoured,
     * and {@link #parsePermissionLimit} still governs what each one means, so a backend that
     * <em>can</em> enumerate keeps the fully dynamic behaviour through the collection overloads.
     */
    private static final List<String> CANDIDATE_TIERS = List.of(
            "100mb", "250mb", "500mb", "750mb", "1gb", "2gb", "3gb", "5gb", "10gb", "15gb", "20gb", "25gb", "50gb",
            "75gb", "100gb", "250gb", "500gb", "1tb", "2tb", "5tb");

    private static final long BYTES_IN_KB = 1024L;
    private static final long BYTES_IN_MB = 1024L * 1024L;
    private static final long BYTES_IN_GB = 1024L * 1024L * 1024L;
    private static final long BYTES_IN_TB = 1024L * 1024L * 1024L * 1024L;

    private StorageQuotaResolver() {}

    /**
     * Parses a permission string formatted as {@code gzmn.worlds.storage.<amount><unit>} into byte count.
     *
     * <p>Supported units: {@code b}, {@code kb}, {@code mb}, {@code gb}, {@code tb} (case-insensitive).
     * If unit is omitted, defaults to megabytes (MB).
     *
     * @param permission permission string
     * @return parsed byte count, or -1 if the permission does not match the numeric storage format
     */
    public static long parsePermissionLimit(String permission) {
        if (permission == null || permission.isBlank()) {
            return -1L;
        }

        Matcher matcher = STORAGE_PERMISSION_PATTERN.matcher(permission.trim());
        if (!matcher.matches()) {
            return -1L;
        }

        String amountStr = matcher.group(1);
        long amount;
        try {
            amount = Long.parseLong(amountStr);
        } catch (NumberFormatException e) {
            return -1L;
        }

        if (amount < 0) {
            return -1L;
        }

        String unit = matcher.group(2);
        long multiplier = unitMultiplier(unit);

        if (amount > Long.MAX_VALUE / multiplier) {
            return Long.MAX_VALUE;
        }

        return amount * multiplier;
    }

    private static long unitMultiplier(String unit) {
        if (unit == null || unit.isEmpty()) {
            return BYTES_IN_MB;
        }
        return switch (unit.toLowerCase(Locale.ROOT)) {
            case "b" -> 1L;
            case "kb" -> BYTES_IN_KB;
            case "mb" -> BYTES_IN_MB;
            case "gb" -> BYTES_IN_GB;
            case "tb" -> BYTES_IN_TB;
            default -> BYTES_IN_MB;
        };
    }

    /**
     * Resolves the maximum storage quota limit in bytes from the granted permissions.
     *
     * @param permissions collection of permission strings assigned to the player
     * @param isAdmin whether the player has admin bypass
     * @param defaultLimitBytes default network policy quota limit if no numeric permission matches
     * @return highest resolved limit in bytes, or {@code defaultLimitBytes}
     */
    public static long resolveLimitBytes(Collection<String> permissions, boolean isAdmin, long defaultLimitBytes) {
        long highest = -1L;
        if (permissions != null) {
            for (String permission : permissions) {
                long parsed = parsePermissionLimit(permission);
                if (parsed > highest) {
                    highest = parsed;
                }
            }
        }

        return highest > 0 ? highest : defaultLimitBytes;
    }

    /**
     * Evaluates a complete {@link StorageQuota} for a player.
     *
     * @param playerUuid player UUID
     * @param usedBytes total storage used by player
     * @param permissions collection of permission strings assigned to the player
     * @param isAdmin whether the player is an admin
     * @param defaultLimitBytes default network policy quota limit
     * @return evaluated StorageQuota
     */
    public static StorageQuota evaluate(
            UUID playerUuid, long usedBytes, Collection<String> permissions, boolean isAdmin, long defaultLimitBytes) {
        Objects.requireNonNull(playerUuid, "playerUuid");

        boolean unlimited = isAdmin || hasUnlimitedPermission(permissions);
        long limitBytes = resolveLimitBytes(permissions, isAdmin, defaultLimitBytes);

        return new StorageQuota(playerUuid, usedBytes, limitBytes, unlimited);
    }

    private static boolean hasUnlimitedPermission(Collection<String> permissions) {
        if (permissions == null) {
            return false;
        }
        for (String permission : permissions) {
            if (permission != null) {
                if (permission.equalsIgnoreCase(PERMISSION_STORAGE_UNLIMITED)
                        || permission.equalsIgnoreCase(PERMISSION_ADMIN)
                        || permission.equals("*")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Storage permission nodes worth probing, highest-value last.
     *
     * @return unmodifiable list of fully qualified permission nodes
     */
    public static List<String> candidatePermissions() {
        return CANDIDATE_TIERS.stream()
                .map(tier -> PERMISSION_STORAGE_PREFIX + tier)
                .toList();
    }

    /**
     * Evaluates a {@link StorageQuota} against a permission backend that can only be asked about
     * one node at a time, such as Velocity's.
     *
     * <p>Probes {@link #candidatePermissions()} and evaluates the highest node the player actually
     * holds. Use the {@link #evaluate(UUID, long, Collection, boolean, long)} overload wherever the
     * granted permissions can be listed outright — that one sees tiers this ladder does not name.
     *
     * @param playerUuid player UUID
     * @param usedBytes total storage used by the player
     * @param holdsPermission answers whether the player holds one permission node
     * @param defaultLimitBytes default network policy quota limit
     * @return evaluated StorageQuota
     */
    public static StorageQuota evaluate(
            UUID playerUuid, long usedBytes, Predicate<String> holdsPermission, long defaultLimitBytes) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(holdsPermission, "holdsPermission");

        boolean unlimited =
                holdsPermission.test(PERMISSION_ADMIN) || holdsPermission.test(PERMISSION_STORAGE_UNLIMITED);
        List<String> held =
                candidatePermissions().stream().filter(holdsPermission).toList();
        return new StorageQuota(playerUuid, usedBytes, resolveLimitBytes(held, false, defaultLimitBytes), unlimited);
    }

    /**
     * Formats bytes into a human-readable string (e.g. "1.25 GB", "500.00 MB", "500 B").
     *
     * @param bytes byte count
     * @return formatted string
     */
    public static String formatBytes(long bytes) {
        if (bytes <= 0) {
            return "0 B";
        }
        if (bytes >= BYTES_IN_TB) {
            return String.format(Locale.ROOT, "%.2f TB", bytes / (double) BYTES_IN_TB);
        }
        if (bytes >= BYTES_IN_GB) {
            return String.format(Locale.ROOT, "%.2f GB", bytes / (double) BYTES_IN_GB);
        }
        if (bytes >= BYTES_IN_MB) {
            return String.format(Locale.ROOT, "%.2f MB", bytes / (double) BYTES_IN_MB);
        }
        if (bytes >= BYTES_IN_KB) {
            return String.format(Locale.ROOT, "%.2f KB", bytes / (double) BYTES_IN_KB);
        }
        return bytes + " B";
    }
}
