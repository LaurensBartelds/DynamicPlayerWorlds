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
 * <p>Layout mirrors Bukkit's multi-world layout (MN-2a): overworld content under
 * {@code <folder>/}, nether under {@code <folder>_nether/DIM-1/}, end under
 * {@code <folder>_the_end/DIM1/}, each Bukkit world carrying its own
 * {@code level.dat}. Kept here as constants rather than depending on
 * {@code backend.platform}, so {@code :testing} stays free of Paper.
 */
public final class WorldFixture {

    /** MN-2a directories that must never be omitted from a sync set. */
    public static final List<String> DIMENSION_CONTENT_DIRECTORIES = List.of("region", "entities", "poi", "data");

    /** World-root files that must be synced (FR-3b dragon state lives here). */
    public static final List<String> WORLD_ROOT_FILES = List.of("level.dat");

    /**
     * Node-local files that travel with a live folder but must not reach object
     * storage (MN-2a exclude defaults).
     */
    public static final List<String> DEFAULT_EXCLUDE_NAMES = List.of("session.lock", "uid.dat");

    private static final byte[] PLACEHOLDER_MCA = new byte[8192];
    private static final byte[] PLACEHOLDER_DAT = "synthetic-level-dat-v1\n".getBytes(StandardCharsets.UTF_8);
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
        Path overworld = writeBukkitWorld(scratchRoot.resolve(base), Path.of(""), base + "/overworld");
        if (dimensions == DimensionSet.ALL_THREE) {
            writeBukkitWorld(scratchRoot.resolve(base + "_nether"), Path.of("DIM-1"), base + "/nether");
            writeBukkitWorld(scratchRoot.resolve(base + "_the_end"), Path.of("DIM1"), base + "/end");
        }
        return overworld;
    }

    /**
     * Relative paths under {@code scratchRoot} that a sync should upload for
     * {@code worldId}: every regular file except the MN-2a exclude names.
     *
     * <p>Paths use {@code /} separators so assertions are OS-independent.
     */
    public static List<String> syncedRelativePaths(Path scratchRoot, WorldId worldId) throws IOException {
        String base = worldId.folder();
        List<String> prefixes = List.of(base, base + "_nether", base + "_the_end");
        List<String> found = new ArrayList<>();
        for (String prefix : prefixes) {
            Path root = scratchRoot.resolve(prefix);
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

    private static Path writeBukkitWorld(Path worldFolder, Path dimensionRelative, String seedTag) throws IOException {
        Files.createDirectories(worldFolder);
        write(worldFolder.resolve("level.dat"), withTag(PLACEHOLDER_DAT, seedTag + "/level.dat"));
        write(worldFolder.resolve("session.lock"), PLACEHOLDER_LOCK);
        write(worldFolder.resolve("uid.dat"), PLACEHOLDER_LOCK);

        Path dataRoot = dimensionRelative.toString().isEmpty() ? worldFolder : worldFolder.resolve(dimensionRelative);
        Files.createDirectories(dataRoot);

        for (String directory : DIMENSION_CONTENT_DIRECTORIES) {
            Path dir = dataRoot.resolve(directory);
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
