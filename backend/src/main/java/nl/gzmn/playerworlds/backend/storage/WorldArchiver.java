package nl.gzmn.playerworlds.backend.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;
import nl.gzmn.playerworlds.backend.control.WorldHandoff;
import nl.gzmn.playerworlds.backend.platform.DimensionKind;
import nl.gzmn.playerworlds.backend.platform.WorldLayout;
import nl.gzmn.playerworlds.backend.world.WorldRegistry;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.db.DbClock;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.model.WorldState;
import nl.gzmn.playerworlds.core.storage.LocalObjectCache;
import nl.gzmn.playerworlds.core.storage.Manifest;
import nl.gzmn.playerworlds.core.storage.ManifestCodec;
import nl.gzmn.playerworlds.core.storage.ObjectStore;
import nl.gzmn.playerworlds.core.storage.PlainFileCloner;
import nl.gzmn.playerworlds.core.storage.StorageException;
import nl.gzmn.playerworlds.core.storage.WorldDownloader;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service for archiving player worlds to cold compressed storage (FR-34, FR-35, §5.8).
 *
 * <p>Orchestrates the safe cold archival lifecycle:
 * <ol>
 *   <li>Acquires lease on the world and transitions state to {@link WorldState#ARCHIVING}.</li>
 *   <li>Unloads dimensions locally (or signals node) if currently active.</li>
 *   <li>Packs dimension folders into a single compressed tarball (.tar.zst / .tar.gz).</li>
 *   <li>Uploads archive artifact to {@link ArchiveStorage} and verifies checksum/size.</li>
 *   <li>Atomically advances state to {@link WorldState#ARCHIVED}, updates {@code storage_bytes},
 *       clears lease and manifest pointers, and inserts {@code player_world_archive} row.</li>
 *   <li>Purges live object storage prefix ({@code worlds/<world_id>/data/} and {@code manifest/})
 *       and local scratch files only after successful verification and database commit.</li>
 * </ol>
 */
public final class WorldArchiver {

    private static final Logger log = LoggerFactory.getLogger(WorldArchiver.class);

    private final PlayerWorldRepository worlds;
    private final DbClock clock;
    private final ArchiveStorage archiveStorage;
    private final Path scratchRoot;
    private final WorldLayout worldLayout;
    private final String primaryLevelName;
    private final @Nullable ObjectStore objectStore;
    private final @Nullable WorldRegistry registry;
    private final @Nullable WorldHandoff handoff;
    private final Supplier<NetworkPolicy> policy;
    private final String nodeId;
    private final int nodeDataVersion;

    /**
     * Outcome of an archival operation.
     */
    public record ArchiveResult(
            boolean success,
            @Nullable String archiveKey,
            long sizeBytes,
            @Nullable String checksum,
            @Nullable String message) {

        public static ArchiveResult ok(String archiveKey, long sizeBytes, String checksum) {
            return new ArchiveResult(true, archiveKey, sizeBytes, checksum, null);
        }

        public static ArchiveResult error(String message) {
            return new ArchiveResult(false, null, 0L, null, message);
        }
    }

    public WorldArchiver(
            PlayerWorldRepository worlds,
            DbClock clock,
            ArchiveStorage archiveStorage,
            Path scratchRoot,
            WorldLayout worldLayout,
            String primaryLevelName,
            @Nullable ObjectStore objectStore,
            @Nullable WorldRegistry registry,
            @Nullable WorldHandoff handoff,
            Supplier<NetworkPolicy> policy,
            String nodeId,
            int nodeDataVersion) {
        this.worlds = Objects.requireNonNull(worlds, "worlds");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.archiveStorage = Objects.requireNonNull(archiveStorage, "archiveStorage");
        this.scratchRoot = Objects.requireNonNull(scratchRoot, "scratchRoot");
        this.worldLayout = Objects.requireNonNull(worldLayout, "worldLayout");
        this.primaryLevelName = Objects.requireNonNull(primaryLevelName, "primaryLevelName");
        this.objectStore = objectStore;
        this.registry = registry;
        this.handoff = handoff;
        this.policy = Objects.requireNonNull(policy, "policy");
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.nodeDataVersion = nodeDataVersion;
    }

    /**
     * Archives a world with optional owner validation.
     *
     * @param worldId world identity to archive
     * @param ownerUuid expected owner UUID, or {@code null} if administrative / system archival
     * @return outcome record containing archive metadata or failure reason
     */
    public ArchiveResult archiveWorld(WorldId worldId, @Nullable UUID ownerUuid) {
        Objects.requireNonNull(worldId, "worldId");

        final PlayerWorld world;
        try {
            Optional<PlayerWorld> found = worlds.findById(worldId);
            if (found.isEmpty()) {
                return ArchiveResult.error("World not found: " + worldId);
            }
            world = found.get();
        } catch (SQLException e) {
            return ArchiveResult.error("Database error looking up world " + worldId + ": " + e.getMessage());
        }

        if (ownerUuid != null && !world.ownerUuid().equals(ownerUuid)) {
            return ArchiveResult.error("Owner mismatch: expected " + world.ownerUuid() + ", was " + ownerUuid);
        }

        if (world.state() == WorldState.ARCHIVED) {
            return ArchiveResult.error("World is already archived: " + worldId);
        }
        if (world.state() == WorldState.CREATING || world.state() == WorldState.RESTORING) {
            return ArchiveResult.error("Cannot archive world in state " + world.state());
        }

        // 1. Give the world up if this node is holding it.
        //
        // The outcome is acted on, not logged. Blocked means a dimension refused
        // to unload and the world is still ticking; CommitFailed means the final
        // snapshot did not land and WorldHandoff deliberately left the world
        // loaded and leased. Continuing past either packs a live world folder
        // with ArchivePacker — bypassing the whole of MN-5a's quiesce-snapshot-
        // verify — and then deletes it from under three loaded Bukkit worlds.
        if (registry != null && registry.find(worldId).isPresent()) {
            if (handoff == null) {
                return ArchiveResult.error(
                        "World " + worldId + " is loaded here but there is no handoff to release it");
            }
            final WorldHandoff.Outcome released;
            try {
                released = handoff.release(worldId, 0, "Archiving world")
                        .get(policy.get().commitTimeout().plusSeconds(10).toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ArchiveResult.error("Interrupted while unloading world " + worldId + " for archival");
            } catch (Exception e) {
                return ArchiveResult.error("Could not unload world " + worldId + " for archival: " + e.getMessage());
            }
            switch (released) {
                case WorldHandoff.Outcome.Released ignored -> {
                    // The lease is released too: WorldHandoff waits for it, so the
                    // acquisition below is not racing a queued release.
                }
                case WorldHandoff.Outcome.NotHeld ignored -> {
                    // Fenced or migrated while we were asking. Nothing loaded here.
                }
                case WorldHandoff.Outcome.Blocked blocked -> {
                    return ArchiveResult.error("Cannot archive world " + worldId + ": dimension "
                            + blocked.dimension() + " would not unload ("
                            + String.join(", ", blocked.blockers())
                            + "). The world is still loaded; FR-40 retries it.");
                }
                case WorldHandoff.Outcome.CommitFailed failed -> {
                    return ArchiveResult.error("Cannot archive world " + worldId
                            + ": the final snapshot commit failed (" + failed.detail()
                            + "). The world is still loaded and leased; FR-40 retries it.");
                }
            }
        }

        // 2. Acquire lease and set state to ARCHIVING
        NetworkPolicy currentPolicy = policy.get();
        try {
            Optional<PlayerWorldRepository.LeaseGrant> grant =
                    worlds.acquireLease(worldId, nodeId, nodeDataVersion, currentPolicy.leaseDuration());
            if (grant.isEmpty()) {
                Optional<String> holder = worlds.leaseHolder(worldId);
                if (holder.isEmpty() || !nodeId.equals(holder.get())) {
                    return ArchiveResult.error(
                            "Could not acquire lease for archival; held by " + holder.orElse("another node"));
                }
            }
            // FR-35 is explicit that the state is set with the lease held, and the whole crash
            // contract rests on it: a run that packed and uploaded without ever reaching
            // ARCHIVING would leave FR-40's sweep nothing to recognise. A refused transition
            // means the world moved under us, so stop before anything is written.
            if (world.state() != WorldState.ARCHIVING
                    && !worlds.transitionState(worldId, WorldState.READY, WorldState.ARCHIVING)) {
                return ArchiveResult.error("World " + worldId + " changed state before archival could start");
            }
        } catch (SQLException e) {
            return ArchiveResult.error("Failed to acquire lease for archival: " + e.getMessage());
        }

        // 3. Collect dimension folders (Paper 26 nested layout under the primary save)
        String folderBase = world.folder();
        List<Path> liveDimensionDirs = new ArrayList<>();
        for (DimensionKind dimension : DimensionKind.values()) {
            Path dir = worldLayout.bukkitWorldFolder(scratchRoot, primaryLevelName, folderBase, dimension);
            if (Files.isDirectory(dir)) {
                liveDimensionDirs.add(dir);
            }
        }

        List<Path> dimensionDirs = new ArrayList<>(liveDimensionDirs);

        Path tempMaterializeDir = null;
        if (dimensionDirs.isEmpty()) {
            // World files not in scratch; try materializing from object storage snapshot
            if (world.manifestKey() != null && objectStore != null) {
                try {
                    byte[] manifestBytes = objectStore.getBytes(world.manifestKey());
                    Manifest manifest = ManifestCodec.decode(new String(manifestBytes, StandardCharsets.UTF_8));
                    tempMaterializeDir = Files.createTempDirectory("archive-mat-" + worldId.value());
                    LocalObjectCache cache =
                            new LocalObjectCache(tempMaterializeDir.resolve(".cache"), PlainFileCloner.INSTANCE);
                    WorldDownloader downloader = new WorldDownloader(objectStore, cache, PlainFileCloner.INSTANCE);
                    downloader.materialize(manifest, tempMaterializeDir);

                    for (DimensionKind dimension : DimensionKind.values()) {
                        Path matDir = worldLayout.bukkitWorldFolder(
                                tempMaterializeDir, primaryLevelName, folderBase, dimension);
                        if (Files.isDirectory(matDir)) {
                            dimensionDirs.add(matDir);
                        }
                    }
                } catch (Exception e) {
                    log.error("Failed to materialize world from snapshot before archival: {}", worldId, e);
                }
            }
        }

        if (dimensionDirs.isEmpty()) {
            if (tempMaterializeDir != null) deleteDirectoryRecursively(tempMaterializeDir);
            return ArchiveResult.error("No world dimensions found to archive for " + worldId);
        }

        // 4. Pack archive
        boolean useZstd = !"gzip".equalsIgnoreCase(currentPolicy.archiveCompression());
        String ext = useZstd ? ".tar.zst" : ".tar.gz";
        Path tempArchive;
        ArchivePacker.PackResult packResult;
        try {
            tempArchive = Files.createTempFile("archive-" + worldId.value(), ext);
            packResult = ArchivePacker.pack(dimensionDirs, tempArchive, useZstd, currentPolicy.excludeGlobs());
        } catch (Exception e) {
            if (tempMaterializeDir != null) deleteDirectoryRecursively(tempMaterializeDir);
            return ArchiveResult.error("Failed to pack archive: " + e.getMessage());
        } finally {
            if (tempMaterializeDir != null) {
                deleteDirectoryRecursively(tempMaterializeDir);
            }
        }

        // 5. Upload archive. The key is stamped with database time, not this node's (rule 5,
        // MN-10b): an archive key is permanent, and two nodes with drifted clocks must not
        // disagree about which of their archives is the later one.
        final String archiveKey;
        try {
            archiveKey = "worlds/" + worldId.value() + "/archive/" + worldId.value() + "-"
                    + clock.now().toEpochMilli() + ext;
        } catch (SQLException e) {
            deleteQuietly(tempArchive);
            return ArchiveResult.error("Failed to read database time for archive key: " + e.getMessage());
        }
        try {
            archiveStorage.uploadArchive(archiveKey, tempArchive);
        } catch (Exception e) {
            deleteQuietly(tempArchive);
            return ArchiveResult.error("Failed to upload archive: " + e.getMessage());
        }

        // 6. Verify the stored archive (FR-35, CONTRIBUTING rule 8).
        //
        // Steps 7 and 8 delete the live folders and the whole per-world object
        // prefix, so this is the last moment at which a second copy of the world
        // exists. FR-35 is explicit that the deletions happen "only after the
        // checksum of the written archive verifies" — a length comparison is not
        // that, and a corrupt part with the right length passes it.
        try {
            if (!archiveStorage.exists(archiveKey)) {
                throw new StorageException("Archive verification failed: key not found " + archiveKey);
            }
            long storedSize = archiveStorage.getArchiveSize(archiveKey);
            if (storedSize != packResult.sizeBytes()) {
                throw new StorageException(
                        "Archive size mismatch: expected " + packResult.sizeBytes() + ", got " + storedSize);
            }
            if (!archiveStorage.verifyStoredArchive(archiveKey, packResult.checksum())) {
                throw new StorageException("Archive checksum mismatch for " + archiveKey + "; expected "
                        + packResult.checksum() + ". Nothing has been deleted.");
            }
        } catch (Exception e) {
            archiveStorage.deleteArchive(archiveKey);
            deleteQuietly(tempArchive);
            return ArchiveResult.error("Archive verification failed: " + e.getMessage());
        }

        // 7. Atomic DB transition to ARCHIVED
        int dataVersion = world.dataVersion() != null ? world.dataVersion() : nodeDataVersion;
        try {
            boolean committed = worlds.transitionToArchived(
                    worldId, archiveKey, packResult.sizeBytes(), packResult.checksum(), dataVersion);
            if (!committed) {
                archiveStorage.deleteArchive(archiveKey);
                deleteQuietly(tempArchive);
                return ArchiveResult.error("Failed to commit ARCHIVED state to database for world " + worldId);
            }
        } catch (SQLException e) {
            archiveStorage.deleteArchive(archiveKey);
            deleteQuietly(tempArchive);
            return ArchiveResult.error("Database commit error: " + e.getMessage());
        }

        // 8. Purge live snapshot prefix & local scratch folders
        if (objectStore != null) {
            try {
                objectStore.deletePrefix("worlds/" + worldId.value() + "/data/");
                objectStore.deletePrefix("worlds/" + worldId.value() + "/manifest/");
            } catch (Exception e) {
                log.warn("Could not purge live object prefix for archived world {}: {}", worldId, e.getMessage());
            }
        }

        for (Path liveDir : liveDimensionDirs) {
            deleteDirectoryRecursively(liveDir);
        }
        deleteQuietly(tempArchive);

        log.info(
                "Archived world {} to {} ({} bytes, checksum {})",
                worldId,
                archiveKey,
                packResult.sizeBytes(),
                packResult.checksum());
        return ArchiveResult.ok(archiveKey, packResult.sizeBytes(), packResult.checksum());
    }

    /** Best effort: a leftover scratch file is swept by FR-40, a failed archival must not be. */
    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.debug("Could not delete temporary archive file {}", file, e);
        }
    }

    private static void deleteDirectoryRecursively(@Nullable Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(WorldArchiver::deleteQuietly);
        } catch (IOException e) {
            log.debug("Could not walk {} for deletion", dir, e);
        }
    }
}
