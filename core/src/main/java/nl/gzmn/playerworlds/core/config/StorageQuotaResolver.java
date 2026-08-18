package nl.gzmn.playerworlds.core.config;

import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
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
