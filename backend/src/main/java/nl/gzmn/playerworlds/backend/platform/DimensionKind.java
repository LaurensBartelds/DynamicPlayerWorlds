package nl.gzmn.playerworlds.backend.platform;

/**
 * The three dimensions that make up one player world (FR-2).
 *
 * <p>Kept as a platform type rather than a {@code core.model} enum because the
 * Bukkit environment names and folder suffixes are version-sensitive surfaces
 * that {@link WorldLayout} owns. Core talks about worlds by id; the platform
 * maps a world onto three Bukkit worlds.
 */
public enum DimensionKind {
    OVERWORLD,
    NETHER,
    END
}
