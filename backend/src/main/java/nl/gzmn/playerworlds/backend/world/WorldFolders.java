package nl.gzmn.playerworlds.backend.world;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import nl.gzmn.playerworlds.backend.platform.DimensionKind;
import nl.gzmn.playerworlds.backend.platform.WorldLayout;
import nl.gzmn.playerworlds.core.model.WorldId;

/**
 * Reads a Bukkit world name back into "which player world, which dimension".
 *
 * <p>{@link WorldLayout} maps a world id to three Bukkit names; every event
 * handler needs the inverse, because Bukkit hands out a world and asks what it
 * is. The suffixes are never hardcoded here — they are recovered from the layout
 * itself, so a layout that renames {@code _the_end} keeps both directions in
 * agreement instead of silently disagreeing in one of them.
 */
public final class WorldFolders {

    /**
     * A stand-in base folder used to recover each dimension's suffix from the
     * layout. Never reaches a filesystem: it is passed to
     * {@link WorldLayout#bukkitWorldName} once at construction and the result is
     * only ever inspected. It cannot collide with a real folder, which FR-2a
     * fixes to {@code pw_} followed by 32 hex characters.
     */
    private static final String PROBE = "__gzmn_probe__";

    private final WorldLayout layout;

    /** Longest suffix first, so {@code _the_end} is matched before the empty overworld suffix. */
    private final List<DimensionSuffix> suffixes;

    public WorldFolders(WorldLayout layout) {
        this.layout = Objects.requireNonNull(layout, "layout");
        this.suffixes = deriveSuffixes(layout);
    }

    /** The Bukkit world name for one dimension of {@code id}. */
    public String bukkitWorldName(WorldId id, DimensionKind dimension) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(dimension, "dimension");
        return layout.bukkitWorldName(id.folder(), dimension);
    }

    /** The layout that owns on-disk path resolution for this server's data version. */
    public WorldLayout layout() {
        return layout;
    }

    /**
     * Relative paths under the world container for every dimension folder of {@code id}.
     *
     * <p>These are the roots {@code DirtyScanner} and archival walk for live Paper 26
     * worlds ({@code <level>/dimensions/minecraft/<bukkitName>/}).
     */
    public List<Path> relativeDimensionFolders(String primaryLevelName, WorldId id) {
        Objects.requireNonNull(primaryLevelName, "primaryLevelName");
        Objects.requireNonNull(id, "id");
        List<Path> roots = new ArrayList<>(DimensionKind.values().length);
        for (DimensionKind dimension : DimensionKind.values()) {
            roots.add(layout.relativeWorldFolder(primaryLevelName, id.folder(), dimension));
        }
        return List.copyOf(roots);
    }

    /**
     * Relative folders inside an archive's extract tree, one per dimension.
     *
     * <p>Flat, not nested: {@code ArchivePacker} names each tar entry
     * {@code <bukkitWorldName>/<path>} because it walks the dimension directories
     * it is handed and uses their own names. This is the inverse of that, and the
     * only other place that has to agree about it — recovered from the layout
     * rather than by appending {@code _nether} and {@code _the_end}, for the same
     * reason {@link #resolve} does not hardcode them.
     */
    public List<Path> archiveDimensionFolders(WorldId id) {
        Objects.requireNonNull(id, "id");
        List<Path> roots = new ArrayList<>(DimensionKind.values().length);
        for (DimensionKind dimension : DimensionKind.values()) {
            roots.add(Path.of(bukkitWorldName(id, dimension)));
        }
        return List.copyOf(roots);
    }

    /**
     * The directory that holds every player world's dimension folders.
     *
     * <p>Recovered from the layout with the same probe {@link #resolve} uses for
     * the suffixes, so the startup sweep does not rebuild Paper 26's
     * {@code <level>/dimensions/minecraft/} nesting by hand — which is what it
     * did, in a second copy that a layout change would have left behind.
     */
    public Path dimensionsRoot(Path scratchRoot, String primaryLevelName) {
        Objects.requireNonNull(scratchRoot, "scratchRoot");
        Objects.requireNonNull(primaryLevelName, "primaryLevelName");
        Path probe = layout.bukkitWorldFolder(scratchRoot, primaryLevelName, PROBE, DimensionKind.OVERWORLD);
        Path parent = probe.getParent();
        if (parent == null) {
            throw new IllegalStateException("layout put a world folder at the filesystem root: " + probe);
        }
        return parent;
    }

    /** Absolute on-disk folders for every dimension of {@code id}. */
    public List<Path> onDiskFolders(Path scratchRoot, String primaryLevelName, WorldId id) {
        List<Path> folders = new ArrayList<>(DimensionKind.values().length);
        for (DimensionKind dimension : DimensionKind.values()) {
            folders.add(onDiskFolder(scratchRoot, primaryLevelName, id, dimension));
        }
        return List.copyOf(folders);
    }

    /** Which player world a dimension folder name belongs to, or empty when it is not one of ours. */
    public Optional<WorldId> worldIdOf(String bukkitWorldName) {
        return resolve(bukkitWorldName).map(PlayerWorldDimension::worldId);
    }

    /** Absolute on-disk folder for one dimension under the world container. */
    public Path onDiskFolder(Path scratchRoot, String primaryLevelName, WorldId id, DimensionKind dimension) {
        Objects.requireNonNull(scratchRoot, "scratchRoot");
        Objects.requireNonNull(primaryLevelName, "primaryLevelName");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(dimension, "dimension");
        return layout.bukkitWorldFolder(scratchRoot, primaryLevelName, id.folder(), dimension);
    }

    /**
     * Which player world and dimension a Bukkit world name refers to, or empty
     * when it is not one of ours.
     *
     * <p>Empty is the common answer: the lobby, the server's own worlds and
     * anything another plugin created all land here on every portal transit and
     * every join.
     */
    public Optional<PlayerWorldDimension> resolve(String bukkitWorldName) {
        Objects.requireNonNull(bukkitWorldName, "bukkitWorldName");
        for (DimensionSuffix candidate : suffixes) {
            if (!bukkitWorldName.endsWith(candidate.suffix())) {
                continue;
            }
            String baseFolder = bukkitWorldName.substring(
                    0, bukkitWorldName.length() - candidate.suffix().length());
            Optional<WorldId> id = WorldId.fromFolder(baseFolder);
            if (id.isPresent()) {
                return Optional.of(new PlayerWorldDimension(id.get(), candidate.dimension()));
            }
        }
        return Optional.empty();
    }

    /** Whether this Bukkit world belongs to a player world at all. */
    public boolean isPlayerWorld(String bukkitWorldName) {
        return resolve(bukkitWorldName).isPresent();
    }

    /**
     * Suffix per dimension, recovered from the layout rather than assumed.
     *
     * @throws IllegalStateException if the layout does not name dimensions by
     *     suffixing the base folder. That is not a limitation worth working
     *     around silently — it would mean the forward and inverse mappings had
     *     diverged, which is exactly the class of bug this indirection exists to
     *     prevent.
     */
    private static List<DimensionSuffix> deriveSuffixes(WorldLayout layout) {
        DimensionSuffix[] derived = new DimensionSuffix[DimensionKind.values().length];
        int index = 0;
        for (DimensionKind dimension : DimensionKind.values()) {
            String probed = layout.bukkitWorldName(PROBE, dimension);
            if (!probed.startsWith(PROBE)) {
                throw new IllegalStateException("world layout " + layout.id() + " does not name dimension " + dimension
                        + " by suffixing the base folder (got '" + probed
                        + "'); WorldFolders cannot invert it, and a forward mapping "
                        + "without a matching inverse is how a portal lands in the wrong world");
            }
            derived[index++] = new DimensionSuffix(dimension, probed.substring(PROBE.length()));
        }
        // Longest first: every name ends with the overworld's empty suffix.
        java.util.Arrays.sort(
                derived,
                java.util.Comparator.comparingInt(
                                (DimensionSuffix s) -> s.suffix().length())
                        .reversed());
        return List.of(derived);
    }

    /** A player world and one of its three dimensions. */
    public record PlayerWorldDimension(WorldId worldId, DimensionKind dimension) {
        public PlayerWorldDimension {
            Objects.requireNonNull(worldId, "worldId");
            Objects.requireNonNull(dimension, "dimension");
        }
    }

    private record DimensionSuffix(DimensionKind dimension, String suffix) {}
}
