package nl.gzmn.playerworlds.core.storage;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages crash debris and quarantined world working copies (MN-10, MN-13, MN-13a).
 *
 * <p>When a world loses its lease or a node starts with unleased working directories,
 * the Anvil folders are moved to quarantine rather than deleted or uploaded (MN-13).
 * Quarantine disk usage and retention are bounded by {@code storage.quarantine-max-gb}
 * and {@code storage.quarantine-retain-days} (MN-13a).
 */
public final class QuarantineManager {

    private static final Logger log = LoggerFactory.getLogger(QuarantineManager.class);

    private static final String SNAPSHOT_PREFIX = "_snapshot_";
    private static final String SNAPSHOT_DIR = ".snapshots";

    private QuarantineManager() {}

    /**
     * Moves a world's dimension folders to quarantine (MN-10, MN-13).
     *
     * <p>The folders are supplied rather than derived. On-disk layout is
     * version-sensitive and {@code :core} may not see {@code WorldLayout}
     * (CONTRIBUTING rule 2); this used to append {@code _nether} and
     * {@code _the_end} to a base name and rebuild Paper 26's nesting by hand,
     * which is two copies of a decision that lives in one place. Quarantine
     * destinations keep the flat {@code <name>_<tag>} form for operator
     * inspection.
     *
     * @param dimensionFolders absolute paths of the world's dimension folders;
     *     ones that do not exist are skipped, which is the normal case for a
     *     world that never materialised its nether or end
     * @param scratchRoot the world container, so the world's clean-unload marker
     *     goes with the files it vouches for
     * @return the list of quarantined target paths
     */
    public static List<Path> quarantineWorld(
            Path scratchRoot, Path quarantineRoot, WorldId worldId, Collection<Path> dimensionFolders, String tag)
            throws IOException {
        Objects.requireNonNull(scratchRoot, "scratchRoot");
        Objects.requireNonNull(quarantineRoot, "quarantineRoot");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(dimensionFolders, "dimensionFolders");
        Objects.requireNonNull(tag, "tag");

        Files.createDirectories(quarantineRoot);
        List<Path> quarantined = new ArrayList<>();

        for (Path source : dimensionFolders) {
            Objects.requireNonNull(source, "dimensionFolder");
            if (!Files.exists(source)) {
                continue;
            }
            Path name = source.getFileName();
            Path dest = quarantineRoot.resolve((name == null ? worldId.folder() : name.toString()) + "_" + tag);
            try {
                Files.move(source, dest);
                quarantined.add(dest);
                log.warn("Quarantined scratch directory {} -> {}", source, dest);
            } catch (IOException e) {
                log.error("Failed to move scratch directory {} to quarantine {}", source, dest, e);
                throw e;
            }
        }

        // The marker vouches for files that are no longer here.
        CleanUnloadMarker.clear(scratchRoot, worldId);
        return quarantined;
    }

    /** {@link #quarantineWorld} with a generated tag. */
    public static List<Path> quarantineWorld(
            Path scratchRoot, Path quarantineRoot, WorldId worldId, Collection<Path> dimensionFolders)
            throws IOException {
        return quarantineWorld(
                scratchRoot,
                quarantineRoot,
                worldId,
                dimensionFolders,
                UUID.randomUUID().toString());
    }

    /**
     * What a startup sweep needs to know about this node.
     *
     * <p>A parameter object because the sweep needs six facts and five of them
     * are version-sensitive or database-derived: {@code :core} may not see
     * {@link nl.gzmn.playerworlds.core.model.WorldId}'s on-disk layout
     * (CONTRIBUTING rule 2) and has no repository, so it is told rather than
     * deriving folder names from string suffixes or querying for itself.
     *
     * @param scratchRoot the world container, which also holds the clean-unload markers
     * @param dimensionsRoot the directory holding every player world's dimension
     *     folders, resolved by the caller from its {@code WorldLayout}
     * @param quarantineRoot where debris is moved to
     * @param resolveFolderName maps a dimension folder name to the world it
     *     belongs to, and answers empty for anything that is not one of ours
     * @param leasedToThisNode worlds this node still holds a live lease on; at
     *     startup that means the previous process died holding them
     * @param currentManifestKey the world's {@code manifest_key} now, empty when
     *     it has none or the row is gone
     * @param tag distinguishes one sweep's quarantine directories from another's
     */
    public record StartupSweep(
            Path scratchRoot,
            Path dimensionsRoot,
            Path quarantineRoot,
            Function<String, Optional<WorldId>> resolveFolderName,
            Set<WorldId> leasedToThisNode,
            Function<WorldId, Optional<String>> currentManifestKey,
            String tag) {

        public StartupSweep {
            Objects.requireNonNull(scratchRoot, "scratchRoot");
            Objects.requireNonNull(dimensionsRoot, "dimensionsRoot");
            Objects.requireNonNull(quarantineRoot, "quarantineRoot");
            Objects.requireNonNull(resolveFolderName, "resolveFolderName");
            Objects.requireNonNull(leasedToThisNode, "leasedToThisNode");
            Objects.requireNonNull(currentManifestKey, "currentManifestKey");
            Objects.requireNonNull(tag, "tag");
            leasedToThisNode = Set.copyOf(leasedToThisNode);
        }
    }

    /**
     * Startup sweep: deletes leftover snapshot directories (MN-5a) and quarantines
     * every world directory that cannot be vouched for (MN-13, MN-4, D18).
     *
     * <p>A directory is kept as a warm cache (MN-5) when its clean-unload marker
     * names the world's current {@code manifest_key}, and quarantined otherwise.
     * See {@link CleanUnloadMarker} for why that, rather than MN-13's literal
     * "not covered by a lease", is the test.
     *
     * <p>A world this node still holds a live lease on is quarantined regardless
     * of its marker. A live lease at startup means the previous process died
     * holding it — a clean shutdown releases every lease it held (FR-25, FR-28) —
     * so the directory is debris whatever else says.
     *
     * @return the list of quarantined directories
     */
    public static List<Path> sweepStartup(StartupSweep sweep) throws IOException {
        Objects.requireNonNull(sweep, "sweep");

        if (!Files.isDirectory(sweep.scratchRoot())) {
            return List.of();
        }

        Files.createDirectories(sweep.quarantineRoot());
        List<Path> quarantined = new ArrayList<>();

        // Snapshot directories may still sit at the world-container root (MN-5a).
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(sweep.scratchRoot())) {
            for (Path entry : stream) {
                if (!Files.isDirectory(entry)) {
                    continue;
                }
                String name = entry.getFileName().toString();
                if (name.startsWith(SNAPSHOT_PREFIX) || name.equals(SNAPSHOT_DIR)) {
                    deleteRecursively(entry);
                    log.info("Deleted startup leftover snapshot directory: {}", entry);
                }
            }
        }

        if (!Files.isDirectory(sweep.dimensionsRoot())) {
            return quarantined;
        }

        // Decided once per world rather than once per dimension folder: the three
        // folders of one world share a fate, and reading the marker three times
        // would let two of them disagree if a commit landed in between.
        Map<WorldId, Boolean> warm = new HashMap<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(sweep.dimensionsRoot())) {
            for (Path entry : stream) {
                if (!Files.isDirectory(entry)) {
                    continue;
                }
                String name = entry.getFileName().toString();
                Optional<WorldId> owner = sweep.resolveFolderName().apply(name);
                if (owner.isEmpty()) {
                    // The lobby, the server's own worlds, another plugin's.
                    continue;
                }
                WorldId worldId = owner.get();

                boolean keep = warm.computeIfAbsent(worldId, id -> isWarmCache(sweep, id));
                if (keep) {
                    continue;
                }

                Path dest = sweep.quarantineRoot().resolve(name + "_crash_" + sweep.tag());
                try {
                    Files.move(entry, dest);
                    quarantined.add(dest);
                    log.warn("Startup sweep quarantined crash debris: {} -> {}", entry, dest);
                } catch (IOException e) {
                    log.error("Failed to quarantine directory {}", entry, e);
                }
            }
        }

        // A marker for a world whose directories have just been quarantined would
        // vouch for files that are no longer there.
        for (Map.Entry<WorldId, Boolean> decision : warm.entrySet()) {
            if (!decision.getValue()) {
                CleanUnloadMarker.clear(sweep.scratchRoot(), decision.getKey());
            }
        }
        return quarantined;
    }

    private static boolean isWarmCache(StartupSweep sweep, WorldId worldId) {
        if (sweep.leasedToThisNode().contains(worldId)) {
            log.warn(
                    "world {} is still leased to this node at startup, so the previous process died holding it; "
                            + "its scratch copy is debris (MN-13)",
                    worldId);
            return false;
        }
        String current = sweep.currentManifestKey().apply(worldId).orElse(null);
        boolean warm = CleanUnloadMarker.isWarmCache(sweep.scratchRoot(), worldId, current);
        if (warm) {
            log.info("world {} was cleanly unloaded at {}; keeping its warm copy (MN-5, MN-15a)", worldId, current);
        }
        return warm;
    }

    /**
     * Enforces quarantine retention days and maximum byte bounds (MN-13a, FR-40).
     *
     * <p>Node-local, so it must run on every node rather than only on whichever
     * one holds FR-40's advisory lock: each node fills its own disk. MN-13a is
     * explicit that without this a crash-looping node fills its scratch volume
     * with quarantined copies and then fails NFR-3's free-space check, turning a
     * recoverable fault into an unrecoverable one.
     *
     * @return quarantine directories removed
     */
    public static int prune(Path quarantineRoot, long maxBytes, int retainDays, Instant now) throws IOException {
        Objects.requireNonNull(quarantineRoot, "quarantineRoot");
        Objects.requireNonNull(now, "now");
        if (!Files.isDirectory(quarantineRoot)) {
            return 0;
        }
        int removed = 0;

        Instant cutoff = now.minus(Duration.ofDays(retainDays));
        List<QuarantineEntry> entries = new ArrayList<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(quarantineRoot)) {
            for (Path entry : stream) {
                if (!Files.isDirectory(entry)) {
                    continue;
                }
                try {
                    BasicFileAttributes attrs = Files.readAttributes(entry, BasicFileAttributes.class);
                    Instant modified = attrs.lastModifiedTime().toInstant();
                    if (modified.isBefore(cutoff)) {
                        deleteRecursively(entry);
                        removed++;
                        log.info("Pruned expired quarantine entry (older than {} days): {}", retainDays, entry);
                        continue;
                    }
                    long size = calculateDirectorySize(entry);
                    entries.add(new QuarantineEntry(entry, modified, size));
                } catch (IOException e) {
                    log.warn("Could not inspect quarantine entry {}", entry, e);
                }
            }
        }

        // Enforce maxBytes limit by deleting oldest entries first
        long totalBytes = entries.stream().mapToLong(QuarantineEntry::size).sum();
        if (totalBytes > maxBytes) {
            entries.sort(Comparator.comparing(QuarantineEntry::lastModified));
            for (QuarantineEntry entry : entries) {
                if (totalBytes <= maxBytes) {
                    break;
                }
                deleteRecursively(entry.path());
                totalBytes -= entry.size();
                removed++;
                log.info("Pruned quarantine entry to stay within max-gb budget: {}", entry.path());
            }
        }
        return removed;
    }

    public static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public static long calculateDirectorySize(Path dir) throws IOException {
        long[] size = new long[1];
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                size[0] += attrs.size();
                return FileVisitResult.CONTINUE;
            }
        });
        return size[0];
    }

    private record QuarantineEntry(Path path, Instant lastModified, long size) {}
}
