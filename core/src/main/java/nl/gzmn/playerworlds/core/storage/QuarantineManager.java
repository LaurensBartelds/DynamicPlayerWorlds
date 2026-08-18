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
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
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

    private static final String NETHER_SUFFIX = "_nether";
    private static final String END_SUFFIX = "_the_end";
    private static final String SNAPSHOT_PREFIX = "_snapshot_";
    private static final String SNAPSHOT_DIR = ".snapshots";

    private QuarantineManager() {}

    /**
     * Moves a world's scratch folders (overworld, nether, end) to a unique directory
     * in quarantine (MN-10).
     *
     * @return the list of quarantined target paths
     */
    public static List<Path> quarantineWorld(Path scratchRoot, Path quarantineRoot, WorldId worldId, String tag)
            throws IOException {
        Objects.requireNonNull(scratchRoot, "scratchRoot");
        Objects.requireNonNull(quarantineRoot, "quarantineRoot");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(tag, "tag");

        Files.createDirectories(quarantineRoot);
        String folderName = worldId.folder();

        List<Path> quarantined = new ArrayList<>();
        List<String> targetNames = List.of(folderName, folderName + NETHER_SUFFIX, folderName + END_SUFFIX);

        for (String name : targetNames) {
            Path source = scratchRoot.resolve(name);
            if (Files.exists(source)) {
                Path dest = quarantineRoot.resolve(name + "_" + tag);
                try {
                    Files.move(source, dest);
                    quarantined.add(dest);
                    log.warn("Quarantined scratch directory {} -> {}", source, dest);
                } catch (IOException e) {
                    log.error("Failed to move scratch directory {} to quarantine {}", source, dest, e);
                    throw e;
                }
            }
        }
        return quarantined;
    }

    public static List<Path> quarantineWorld(Path scratchRoot, Path quarantineRoot, WorldId worldId)
            throws IOException {
        return quarantineWorld(
                scratchRoot, quarantineRoot, worldId, UUID.randomUUID().toString());
    }

    /**
     * Startup sweep: deletes leftover snapshot directories (MN-5a) and moves any scratch
     * directories not covered by a lease this node holds into quarantine (MN-13).
     *
     * @param activeLeasedWorldIds the set of world IDs whose leases are currently held by this node
     * @return the list of quarantined directories
     */
    public static List<Path> sweepStartup(
            Path scratchRoot, Path quarantineRoot, Set<WorldId> activeLeasedWorldIds, String tag) throws IOException {
        Objects.requireNonNull(scratchRoot, "scratchRoot");
        Objects.requireNonNull(quarantineRoot, "quarantineRoot");
        Objects.requireNonNull(activeLeasedWorldIds, "activeLeasedWorldIds");
        Objects.requireNonNull(tag, "tag");

        if (!Files.isDirectory(scratchRoot)) {
            return List.of();
        }

        Files.createDirectories(quarantineRoot);
        List<Path> quarantined = new ArrayList<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(scratchRoot)) {
            for (Path entry : stream) {
                if (!Files.isDirectory(entry)) {
                    continue;
                }
                String name = entry.getFileName().toString();

                // Snapshot directories are derived data, deleted outright on startup (MN-5a)
                if (name.startsWith(SNAPSHOT_PREFIX) || name.equals(SNAPSHOT_DIR)) {
                    deleteRecursively(entry);
                    log.info("Deleted startup leftover snapshot directory: {}", entry);
                    continue;
                }

                // Check if this directory corresponds to a known active leased world
                if (isCoveredByLease(name, activeLeasedWorldIds)) {
                    continue;
                }

                // If it matches player world folder prefix (e.g. pw_), quarantine it as crash debris (MN-13)
                if (name.startsWith("pw_")) {
                    Path dest = quarantineRoot.resolve(name + "_crash_" + tag);
                    try {
                        Files.move(entry, dest);
                        quarantined.add(dest);
                        log.warn("Startup sweep quarantined unleased crash debris: {} -> {}", entry, dest);
                    } catch (IOException e) {
                        log.error("Failed to quarantine unleased directory {}", entry, e);
                    }
                }
            }
        }
        return quarantined;
    }

    public static List<Path> sweepStartup(Path scratchRoot, Path quarantineRoot, Set<WorldId> activeLeasedWorldIds)
            throws IOException {
        return sweepStartup(
                scratchRoot,
                quarantineRoot,
                activeLeasedWorldIds,
                UUID.randomUUID().toString());
    }

    /**
     * Enforces quarantine retention days and maximum byte bounds (MN-13a, FR-40).
     */
    public static void prune(Path quarantineRoot, long maxBytes, int retainDays, Instant now) throws IOException {
        Objects.requireNonNull(quarantineRoot, "quarantineRoot");
        Objects.requireNonNull(now, "now");
        if (!Files.isDirectory(quarantineRoot)) {
            return;
        }

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
                log.info("Pruned quarantine entry to stay within max-gb budget: {}", entry.path());
            }
        }
    }

    private static boolean isCoveredByLease(String folderName, Set<WorldId> leasedWorlds) {
        for (WorldId id : leasedWorlds) {
            String base = id.folder();
            if (folderName.equals(base)
                    || folderName.equals(base + NETHER_SUFFIX)
                    || folderName.equals(base + END_SUFFIX)) {
                return true;
            }
        }
        return false;
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
