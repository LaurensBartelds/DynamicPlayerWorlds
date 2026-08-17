package nl.gzmn.playerworlds.backend.platform;

import java.nio.file.Path;
import java.util.List;

/**
 * How a player world's three dimensions sit on disk (MN-2a).
 *
 * <p>The synced path set is version-sensitive: {@code poi/} appeared in 1.14,
 * {@code entities/} split out in 1.17, and Bukkit's {@code DIM-1}/{@code DIM1}
 * nesting is its own quirk. Hardcoding MN-2a's table once is how the next format
 * change silently drops a directory. A version-keyed layout with a test per
 * version turns that into a failing test instead.
 *
 * <p>Anything present under a Bukkit world folder is synced unless it matches
 * {@link #defaultExcludeGlobs()}; the required paths are the ones whose absence
 * is silent data loss rather than a degradation, and tests assert they stay
 * listed.
 */
public interface WorldLayout {

    /** Stable id for logs and the startup line that says which layout was chosen. */
    String id();

    /**
     * Lowest chunk {@code DataVersion} this layout is intended for, inclusive.
     * Selection prefers the highest {@code minDataVersion} that does not exceed
     * the node's version.
     */
    int minDataVersion();

    /**
     * Bukkit world name for one dimension of a player world.
     *
     * @param baseFolder overworld folder from {@code WorldId#folder()} (FR-2a)
     */
    String bukkitWorldName(String baseFolder, DimensionKind dimension);

    /**
     * Path of the dimension's region data root relative to the Bukkit world
     * folder. Empty for the overworld; {@code DIM-1} for the nether;
     * {@code DIM1} for the end.
     */
    Path dimensionDataRelativePath(DimensionKind dimension);

    /**
     * Files that live at the Bukkit world root and must be synced. Today that is
     * {@code level.dat} (spawn, gamerules, dragon fight state — FR-3b).
     */
    List<String> worldRootFiles();

    /**
     * Directories under the dimension data root that must never be omitted from
     * the sync set (MN-2a table): {@code region/}, {@code entities/},
     * {@code poi/}, {@code data/}.
     */
    List<String> dimensionContentDirectories();

    /**
     * Default {@code storage.exclude-globs}. Session locks and {@code uid.dat}
     * are node-local and must not travel with the world (MN-2a).
     */
    List<String> defaultExcludeGlobs();

    /** Absolute Bukkit world folder under the node's scratch root. */
    default Path bukkitWorldFolder(Path scratchRoot, String baseFolder, DimensionKind dimension) {
        return scratchRoot.resolve(bukkitWorldName(baseFolder, dimension));
    }

    /** Absolute path of the dimension's region data root under scratch. */
    default Path dimensionDataRoot(Path scratchRoot, String baseFolder, DimensionKind dimension) {
        Path worldFolder = bukkitWorldFolder(scratchRoot, baseFolder, dimension);
        Path relative = dimensionDataRelativePath(dimension);
        return relative.toString().isEmpty() ? worldFolder : worldFolder.resolve(relative);
    }
}
