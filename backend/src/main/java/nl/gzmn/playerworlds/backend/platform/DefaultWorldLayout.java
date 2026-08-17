package nl.gzmn.playerworlds.backend.platform;

import java.nio.file.Path;
import java.util.List;

/**
 * World folder layout for the Minecraft version this build targets and anything
 * newer until a more specific layout is registered.
 *
 * <p>Bukkit does not nest dimensions the way vanilla does. For base folder
 * {@code foo}: overworld content is under {@code foo/}; nether regions are at
 * {@code foo_nether/DIM-1/}; end regions are at {@code foo_the_end/DIM1/}; each
 * Bukkit world carries its own {@code level.dat} at its root (MN-2a).
 */
public final class DefaultWorldLayout implements WorldLayout {

    public static final DefaultWorldLayout INSTANCE = new DefaultWorldLayout();

    private static final List<String> WORLD_ROOT_FILES = List.of("level.dat");

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
        return switch (dimension) {
            case OVERWORLD -> Path.of("");
            case NETHER -> Path.of("DIM-1");
            case END -> Path.of("DIM1");
        };
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
