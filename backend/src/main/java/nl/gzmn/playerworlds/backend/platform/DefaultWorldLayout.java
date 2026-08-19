package nl.gzmn.playerworlds.backend.platform;

import java.nio.file.Path;
import java.util.List;

/**
 * World folder layout for the Minecraft version this build targets and anything
 * newer until a more specific layout is registered.
 *
 * <p>Paper 26+ stores every Bukkit world under the primary save (the server's
 * {@code level-name}, usually {@code world}):
 *
 * <pre>
 *   &lt;level-name&gt;/dimensions/minecraft/&lt;bukkitWorldName&gt;/
 *     paper-world.yml
 *     region/
 *     entities/
 *     data/
 * </pre>
 *
 * <p>There is no top-level {@code <bukkitWorldName>/} folder and no
 * {@code DIM-1}/{@code DIM1} nesting. {@code level.dat} lives only on the
 * primary save root; each Bukkit world is marked by {@code paper-world.yml}.
 * Bukkit world names themselves still use the classic {@code _nether} /
 * {@code _the_end} suffixes (MN-2a).
 */
public final class DefaultWorldLayout implements WorldLayout {

    public static final DefaultWorldLayout INSTANCE = new DefaultWorldLayout();

    /** Per-dimension presence marker written by Paper 26+ under each world folder. */
    private static final List<String> WORLD_ROOT_FILES = List.of("paper-world.yml");

    private static final List<String> DIMENSION_CONTENT_DIRECTORIES = List.of("region", "entities", "poi", "data");

    private static final List<String> DEFAULT_EXCLUDE_GLOBS = List.of("session.lock", "uid.dat");

    private DefaultWorldLayout() {}

    @Override
    public String id() {
        // Read at call time so class init does not race Platform's statics.
        return "default-" + Platform.BUILD_DATA_VERSION;
    }

    @Override
    public int minDataVersion() {
        // Same number as Platform.BUILD_DATA_VERSION: this layout is the one
        // written against the pinned Paper build, and the fallback for anything
        // newer until a more specific layout is registered.
        return Platform.BUILD_DATA_VERSION;
    }

    @Override
    public String bukkitWorldName(String baseFolder, DimensionKind dimension) {
        return switch (dimension) {
            case OVERWORLD -> baseFolder;
            case NETHER -> baseFolder + "_nether";
            case END -> baseFolder + "_the_end";
        };
    }

    @Override
    public Path dimensionDataRelativePath(DimensionKind dimension) {
        // Paper 26 nested every former Bukkit world as a flat dimension folder;
        // region/entities/data sit at the world folder root for all three.
        return Path.of("");
    }

    @Override
    public Path relativeWorldFolder(String primaryLevelName, String baseFolder, DimensionKind dimension) {
        String level = primaryLevelName == null || primaryLevelName.isBlank() ? "world" : primaryLevelName;
        return Path.of(level, "dimensions", "minecraft", bukkitWorldName(baseFolder, dimension));
    }

    @Override
    public List<String> worldRootFiles() {
        return WORLD_ROOT_FILES;
    }

    @Override
    public List<String> dimensionContentDirectories() {
        return DIMENSION_CONTENT_DIRECTORIES;
    }

    @Override
    public List<String> defaultExcludeGlobs() {
        return DEFAULT_EXCLUDE_GLOBS;
    }
}
