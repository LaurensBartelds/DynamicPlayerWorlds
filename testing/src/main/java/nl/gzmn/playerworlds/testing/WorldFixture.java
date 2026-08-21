package nl.gzmn.playerworlds.testing;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import nl.gzmn.playerworlds.core.model.WorldId;

/**
 * Synthetic Anvil-shaped world folders for storage-layer tests (plan section 11).
 *
 * <p>Generates the MN-2a path set as ordinary files with deterministic bytes —
 * fast, no Minecraft server, and enough structure for content-addressing,
 * exclude-glob and path-walk tests. A real multi-megabyte Anvil world belongs
 * only in the e2e harness (F11), not on the main build classpath.
 *
 * <p>Layout mirrors Paper 26's nested world storage: every Bukkit world sits at
 * {@code <level-name>/dimensions/minecraft/<bukkitWorldName>/} with
 * {@code paper-world.yml} at the root and region/entities/poi/data beneath.
 * Kept here as constants rather than depending on {@code backend.platform}, so
 * {@code :testing} stays free of Paper.
 */
public final class WorldFixture {

    /** Primary save name used by the fixture ({@code level-name} default). */
    public static final String PRIMARY_LEVEL_NAME = "world";

    /** MN-2a directories that must never be omitted from a sync set. */
    public static final List<String> DIMENSION_CONTENT_DIRECTORIES = List.of("region", "entities", "poi", "data");

    /** World-root files that mark a materialised Paper 26 dimension. */
    public static final List<String> WORLD_ROOT_FILES = List.of("paper-world.yml");

    /**
     * Node-local files that travel with a live folder but must not reach object
     * storage (MN-2a exclude defaults).
     */
    public static final List<String> DEFAULT_EXCLUDE_NAMES = List.of("session.lock", "uid.dat");

    private static final byte[] PLACEHOLDER_MCA = new byte[8192];
    private static final byte[] PLACEHOLDER_DAT = "synthetic-level-dat-v1\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PLACEHOLDER_YML = "_version: 31\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PLACEHOLDER_LOCK = "node-local\n".getBytes(StandardCharsets.UTF_8);

    /** Which dimension folders to materialise. */
    public enum DimensionSet {
        /** Overworld Bukkit folder only. */
        OVERWORLD_ONLY,
        /** Overworld, nether and end — the full MN-2a set. */
        ALL_THREE
    }

    private WorldFixture() {}

    /**
     * Materialises a full three-dimension world under {@code scratchRoot} with a
     * fresh random id. Returns that id.
     */
    public static WorldId materialize(Path scratchRoot) throws IOException {
        WorldId id = WorldId.random();
        materialize(scratchRoot, id, DimensionSet.ALL_THREE);
        return id;
    }

    /**
     * Materialises the requested dimensions for {@code worldId} under
     * {@code scratchRoot}. Returns the overworld folder path.
     */
    public static Path materialize(Path scratchRoot, WorldId worldId, DimensionSet dimensions) throws IOException {
        Files.createDirectories(scratchRoot);
        String base = worldId.folder();
        Path overworld = writeBukkitWorld(dimensionFolder(scratchRoot, base), base + "/overworld");
        if (dimensions == DimensionSet.ALL_THREE) {
            writeBukkitWorld(dimensionFolder(scratchRoot, base + "_nether"), base + "/nether");
            writeBukkitWorld(dimensionFolder(scratchRoot, base + "_the_end"), base + "/end");
        }
        return overworld;
    }

    /**
     * The three dimension folders of {@code worldId}, relative to a scratch root.
     *
     * <p>What {@code DirtyScanner.scan} walks and what
     * {@code WorldDownloader.materialize} is allowed to prune. Fixtures are
     * written in the Paper 26 nested layout, so these are the nested paths.
     */
    public static List<Path> relativeDimensionFolders(WorldId worldId) {
        String base = worldId.folder();
        List<Path> roots = new ArrayList<>(3);
        for (String bukkitName : List.of(base, base + "_nether", base + "_the_end")) {
            roots.add(Path.of(PRIMARY_LEVEL_NAME, "dimensions", "minecraft", bukkitName));
        }
        return List.copyOf(roots);
    }

    /** Absolute Paper 26 dimension folder under the world container. */
    public static Path dimensionFolder(Path scratchRoot, String bukkitWorldName) {
        return scratchRoot
                .resolve(PRIMARY_LEVEL_NAME)
                .resolve("dimensions")
                .resolve("minecraft")
                .resolve(bukkitWorldName);
    }

    /**
     * Relative paths under {@code scratchRoot} that a sync should upload for
     * {@code worldId}: every regular file except the MN-2a exclude names.
     *
     * <p>Paths use {@code /} separators so assertions are OS-independent.
     */
    public static List<String> syncedRelativePaths(Path scratchRoot, WorldId worldId) throws IOException {
        String base = worldId.folder();
        List<String> bukkitNames = List.of(base, base + "_nether", base + "_the_end");
        List<String> found = new ArrayList<>();
        for (String bukkitName : bukkitNames) {
            Path root = dimensionFolder(scratchRoot, bukkitName);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                        .filter(path -> !isExcluded(path.getFileName().toString()))
                        .map(path -> toRelativeUnix(scratchRoot, path))
                        .forEach(found::add);
            }
        }
        found.sort(Comparator.naturalOrder());
        return List.copyOf(found);
    }

    private static Path writeBukkitWorld(Path worldFolder, String seedTag) throws IOException {
        Files.createDirectories(worldFolder);
        write(worldFolder.resolve("paper-world.yml"), withTag(PLACEHOLDER_YML, seedTag + "/paper-world.yml"));
        write(worldFolder.resolve("session.lock"), PLACEHOLDER_LOCK);
        write(worldFolder.resolve("uid.dat"), PLACEHOLDER_LOCK);

        for (String directory : DIMENSION_CONTENT_DIRECTORIES) {
            Path dir = worldFolder.resolve(directory);
            Files.createDirectories(dir);
            String fileName = directory.equals("data") ? "raids.dat" : "r.0.0.mca";
            byte[] base = directory.equals("data") ? PLACEHOLDER_DAT : PLACEHOLDER_MCA;
            write(dir.resolve(fileName), withTag(base, seedTag + "/" + directory + "/" + fileName));
        }
        return worldFolder;
    }

    private static boolean isExcluded(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String excluded : DEFAULT_EXCLUDE_NAMES) {
            if (excluded.equalsIgnoreCase(lower)) {
                return true;
            }
        }
        return false;
    }

    private static String toRelativeUnix(Path root, Path file) {
        String relative = root.relativize(file).toString().replace('\\', '/');
        return relative;
    }

    private static byte[] withTag(byte[] base, String tag) {
        byte[] tagBytes = (tag + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[base.length + tagBytes.length];
        System.arraycopy(base, 0, out, 0, base.length);
        System.arraycopy(tagBytes, 0, out, base.length, tagBytes.length);
        return out;
    }

    private static void write(Path path, byte[] bytes) throws IOException {
        Files.createDirectories(path.getParent());
        try (OutputStream out = Files.newOutputStream(path)) {
            out.write(bytes);
        }
    }
}
