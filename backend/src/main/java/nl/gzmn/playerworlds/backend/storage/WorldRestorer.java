package nl.gzmn.playerworlds.backend.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import nl.gzmn.playerworlds.backend.world.WorldFolders;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.db.ArchiveRepository;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository;
import nl.gzmn.playerworlds.core.db.ProfileRepository;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.WorldArchive;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.model.WorldState;
import nl.gzmn.playerworlds.core.storage.DirtyScanner;
import nl.gzmn.playerworlds.core.storage.Manifest;
import nl.gzmn.playerworlds.core.storage.ObjectStore;
import nl.gzmn.playerworlds.core.storage.SnapshotEngine;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service for restoring player worlds from cold archives back to live snapshot storage (FR-36, §5.8).
 *
 * <p>Orchestrates the safe cold restore lifecycle:
 * <ol>
 *   <li>Acquires lease on the archived world and transitions state to {@link WorldState#RESTORING}.</li>
 *   <li>Fetches latest archive metadata from {@code player_world_archive}.</li>
 *   <li>Verifies Minecraft chunk DataVersion compatibility (MN-29).</li>
 *   <li>Downloads archive artifact and validates its SHA-256 checksum.</li>
 *   <li>Unpacks archive into a clean scratch directory.</li>
 *   <li>Creates a fresh point-in-time live snapshot (MN-6a) and uploads objects to {@link ObjectStore}.</li>
 *   <li>Atomically advances state to {@link WorldState#READY}, sets {@code manifest_key} and live
 *       {@code storage_bytes}, updates version tags, releases lease, and increments restore count.</li>
 *   <li>Cleans up temporary decompression directories.</li>
 * </ol>
 */
public final class WorldRestorer {

    private static final Logger log = LoggerFactory.getLogger(WorldRestorer.class);

    /**
     * The sequence a restore's snapshot is written at.
     *
     * <p>One, not zero, and it has to agree with the sequence the profile rows are
     * re-keyed onto: FR-15b loads profiles by the {@code (generation, sequence)}
     * parsed out of {@code manifest_key}, so the two are the same pair or the
     * inventories are unreachable. The generation is fresh — {@code
     * transitionToRestoring} bumped it — so no manifest exists under it and MN-3's
     * write-once key holds.
     */
    private static final int RESTORE_SEQUENCE = 1;

    private final PlayerWorldRepository worlds;
    private final ProfileRepository profiles;
    private final ArchiveRepository archiveRepo;
    private final ArchiveStorage archiveStorage;
    private final @Nullable SnapshotEngine snapshotEngine;
    private final @Nullable ObjectStore objectStore;
    private final Path scratchRoot;
    /** Owns the archive's flat layout as well as the node's nested one (R21). */
    private final WorldFolders folders;

    private final Supplier<NetworkPolicy> policy;
    private final String nodeId;
    private final int nodeDataVersion;
    private final String nodeMcVersion;

    /**
     * Outcome of a restore operation.
     */
    public record RestoreResult(
            boolean success,
            @Nullable String manifestKey,
            long liveStorageBytes,
            @Nullable String message) {

        /**
         * @param manifestKey the snapshot the restore committed, or {@code null}
         *     on a node with no object storage, where there is no manifest to name
         */
        public static RestoreResult ok(@Nullable String manifestKey, long liveStorageBytes) {
            return new RestoreResult(true, manifestKey, liveStorageBytes, null);
        }

        public static RestoreResult error(String message) {
            return new RestoreResult(false, null, 0L, message);
        }
    }

    public WorldRestorer(
            PlayerWorldRepository worlds,
            ProfileRepository profiles,
            ArchiveRepository archiveRepo,
            ArchiveStorage archiveStorage,
            @Nullable SnapshotEngine snapshotEngine,
            @Nullable ObjectStore objectStore,
            Path scratchRoot,
            WorldFolders folders,
            Supplier<NetworkPolicy> policy,
            String nodeId,
            int nodeDataVersion,
            String nodeMcVersion) {
        this.worlds = Objects.requireNonNull(worlds, "worlds");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.archiveRepo = Objects.requireNonNull(archiveRepo, "archiveRepo");
        this.archiveStorage = Objects.requireNonNull(archiveStorage, "archiveStorage");
        this.snapshotEngine = snapshotEngine;
        this.objectStore = objectStore;
        this.scratchRoot = Objects.requireNonNull(scratchRoot, "scratchRoot");
        this.folders = Objects.requireNonNull(folders, "folders");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.nodeDataVersion = nodeDataVersion;
        this.nodeMcVersion = Objects.requireNonNull(nodeMcVersion, "nodeMcVersion");
    }

    /**
     * Restores an archived world back into active ready state.
     *
     * @param worldId world identity to restore
     * @param targetOwnerUuid optional target owner override (e.g. for admin restores or ownership transfer)
     * @return outcome record containing new live manifest metadata or failure reason
     */
    public RestoreResult restoreWorld(WorldId worldId, @Nullable UUID targetOwnerUuid) {
        Objects.requireNonNull(worldId, "worldId");

        final PlayerWorld world;
        try {
            Optional<PlayerWorld> found = worlds.findById(worldId);
            if (found.isEmpty()) {
                return RestoreResult.error("World not found: " + worldId);
            }
            world = found.get();
        } catch (SQLException e) {
            return RestoreResult.error("Database error looking up world " + worldId + ": " + e.getMessage());
        }

        if (world.state() != WorldState.ARCHIVED && world.state() != WorldState.RESTORING) {
            return RestoreResult.error("World is not in ARCHIVED or RESTORING state: " + world.state());
        }

        // 1. Acquire lease and advance state to RESTORING
        NetworkPolicy currentPolicy = policy.get();
        final long generation;
        try {
            Optional<Long> granted = worlds.transitionToRestoring(worldId, nodeId, currentPolicy.leaseDuration());
            if (granted.isEmpty()) {
                return RestoreResult.error("Could not acquire lease for restore; world may be leased to another node");
            }
            // R22: the generation the transition granted, not zero. The snapshot
            // below is written under it and the profiles are re-keyed onto it.
            generation = granted.get();
        } catch (SQLException e) {
            return RestoreResult.error("Failed to acquire lease for restore: " + e.getMessage());
        }
        ProfileRepository.Snapshot restoreSnapshot = new ProfileRepository.Snapshot(generation, RESTORE_SEQUENCE);

        // 2. Fetch latest archive record
        final WorldArchive archive;
        try {
            Optional<WorldArchive> latestOpt = archiveRepo.findLatestByWorld(worldId);
            if (latestOpt.isEmpty()) {
                return abandon(worldId, "No archive found in database for world " + worldId);
            }
            archive = latestOpt.get();
        } catch (SQLException e) {
            return abandon(worldId, "Database error looking up archive for world " + worldId + ": " + e.getMessage());
        }

        // 3. Minecraft DataVersion compatibility check (MN-29)
        if (archive.dataVersion() > nodeDataVersion) {
            return abandon(
                    worldId,
                    "Archive DataVersion " + archive.dataVersion() + " is newer than node DataVersion "
                            + nodeDataVersion);
        }

        // 4. Download archive to temp file
        Path tempArchive;
        try {
            tempArchive = Files.createTempFile("restore-" + worldId.value(), ".tmp");
            archiveStorage.downloadArchive(archive.objectKey(), tempArchive);
        } catch (Exception e) {
            return abandon(worldId, "Failed to download archive " + archive.objectKey() + ": " + e.getMessage());
        }

        // 5. Verify SHA-256 checksum
        try {
            boolean checksumValid = ArchivePacker.verifyChecksum(tempArchive, archive.checksum());
            if (!checksumValid) {
                deleteQuietly(tempArchive);
                return abandon(worldId, "Archive checksum verification failed for " + archive.objectKey());
            }
        } catch (Exception e) {
            deleteQuietly(tempArchive);
            return abandon(worldId, "Failed to verify archive checksum: " + e.getMessage());
        }

        // 6. Unpack archive to clean extraction directory
        Path tempExtractDir;
        try {
            tempExtractDir = Files.createTempDirectory("restore-extract-" + worldId.value());
            ArchivePacker.unpack(tempArchive, tempExtractDir);
        } catch (Exception e) {
            deleteQuietly(tempArchive);
            return abandon(worldId, "Failed to unpack archive: " + e.getMessage());
        }

        // 7. Generate snapshot and upload objects
        @Nullable String manifestKey;
        long liveStorageBytes;
        if (snapshotEngine != null && objectStore != null) {
            try {
                // The extract tree is flat, which is the archive's layout rather
                // than the node's; WorldFolders owns both so the two cannot drift.
                DirtyScanner.Scan scan = DirtyScanner.scan(
                        tempExtractDir, folders.archiveDimensionFolders(worldId), Map.of(), List.of());
                SnapshotEngine.SnapshotResult snapResult = snapshotEngine.executeSnapshot(
                        tempExtractDir,
                        worldId,
                        generation,
                        RESTORE_SEQUENCE,
                        archive.dataVersion(),
                        nodeMcVersion,
                        Map.of(),
                        scan,
                        currentPolicy.verifyRegionStructure());
                Manifest manifest = snapResult.manifest();
                manifestKey = manifest.manifestKey();
                liveStorageBytes = manifest.totalBytes();
            } catch (Exception e) {
                deleteDirectoryRecursively(tempExtractDir);
                deleteQuietly(tempArchive);
                return abandon(worldId, "Failed to snapshot and upload restored world files: " + e.getMessage());
            }
        } else {
            try {
                copyDirectoryRecursively(tempExtractDir, scratchRoot);
                liveStorageBytes = calculateDirectorySize(tempExtractDir);
                // No object storage, so there is no manifest and manifest_key
                // stays null — which is what every other path in this mode means
                // by it. Writing a "local" sentinel made FR-15b's load path parse
                // it for a (generation, sequence), fail, and refuse every member
                // under R10's orphan rule.
                manifestKey = null;
            } catch (Exception e) {
                deleteDirectoryRecursively(tempExtractDir);
                deleteQuietly(tempArchive);
                return abandon(worldId, "Failed to materialize restored files to scratch: " + e.getMessage());
            }
        }

        // 8. Complete restore in database
        try {
            boolean completed = worlds.completeRestore(
                    worldId,
                    manifestKey,
                    liveStorageBytes,
                    archive.dataVersion(),
                    nodeMcVersion,
                    restoreSnapshot,
                    profiles);
            if (!completed) {
                deleteDirectoryRecursively(tempExtractDir);
                deleteQuietly(tempArchive);
                return abandon(worldId, "Failed to complete restore in database for world " + worldId);
            }
            archiveRepo.incrementRestoreCount(worldId, archive.archivedAt());
        } catch (SQLException e) {
            deleteDirectoryRecursively(tempExtractDir);
            deleteQuietly(tempArchive);
            return abandon(worldId, "Database error completing restore: " + e.getMessage());
        }

        // 9. Reassign owner once the restore itself has committed, so a restore that fails
        // late does not leave the world in someone else's hands.
        if (targetOwnerUuid != null && !targetOwnerUuid.equals(world.ownerUuid())) {
            try {
                var _ = worlds.transferOwnership(
                        worldId, world.ownerUuid(), targetOwnerUuid, "Restored with target owner override");
            } catch (SQLException e) {
                log.warn("Could not transfer ownership of restored world {}: {}", worldId, e.getMessage());
            }
        }

        deleteDirectoryRecursively(tempExtractDir);
        deleteQuietly(tempArchive);

        log.info(
                "Restored world {} from {} (manifest {}, {} live bytes)",
                worldId,
                archive.objectKey(),
                manifestKey,
                liveStorageBytes);
        return RestoreResult.ok(manifestKey, liveStorageBytes);
    }

    /**
     * Rolls a failed restore back to {@link WorldState#ARCHIVED} and releases the lease before
     * reporting the failure (FR-36). The archive is untouched by this flow, so the world is
     * exactly as it was and the owner can retry at once.
     */
    private RestoreResult abandon(WorldId worldId, String message) {
        try {
            if (!worlds.abandonRestore(worldId, nodeId)) {
                log.warn(
                        "Could not roll world {} back to ARCHIVED after a failed restore;"
                                + " the lease has probably been fenced",
                        worldId);
            }
        } catch (SQLException e) {
            log.error("Failed to roll world {} back to ARCHIVED after a failed restore", worldId, e);
        }
        return RestoreResult.error(message);
    }

    /** Best effort: a leftover scratch file is swept by FR-40, a failed restore must not be. */
    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.debug("Could not delete temporary restore file {}", file, e);
        }
    }

    private static void deleteDirectoryRecursively(@Nullable Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(WorldRestorer::deleteQuietly);
        } catch (IOException e) {
            log.debug("Could not walk {} for deletion", dir, e);
        }
    }

    private static void copyDirectoryRecursively(Path source, Path target) throws IOException {
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                Path relative = source.relativize(path);
                Path dest = target.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(dest);
                } else if (Files.isRegularFile(path)) {
                    Path parent = dest.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(path, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static long calculateDirectorySize(Path dir) {
        if (!Files.isDirectory(dir)) return 0L;
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            return 0L;
                        }
                    })
                    .sum();
        } catch (IOException e) {
            return 0L;
        }
    }
}
