package nl.gzmn.playerworlds.core.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates point-in-time quiesced S3 snapshot manifests from live scratch directories (MN-5a, MN-5c).
 *
 * <p>Orchestrates the quiesced snapshot pipeline:
 * <ol>
 *   <li>Copies dirty files to an isolated snapshot directory using {@link SnapshotCopier} with re-stat stability checks.</li>
 *   <li>Computes SHA-256 content hashes and optionally validates region structure via {@link ContentHasher#hashAndValidate(Path, boolean)}.</li>
 *   <li>Caches files in {@link LocalObjectCache} and uploads missing blobs to {@link ObjectStore}.</li>
 *   <li>Encodes and writes the updated {@link Manifest} to {@link ObjectStore} at {@code manifest.manifestKey()}.</li>
 *   <li>Cleans up temporary snapshot working directories.</li>
 * </ol>
 */
public final class SnapshotEngine {

    private static final Logger log = LoggerFactory.getLogger(SnapshotEngine.class);

    private final ObjectStore objectStore;
    private final LocalObjectCache cache;
    private final SnapshotCopier copier;
    private final Clock clock;

    /**
     * Outcome and summary statistics of a snapshot operation.
     *
     * @param manifest updated snapshot manifest uploaded to object storage
     * @param dirtyCount number of dirty files copied and merged into the snapshot
     * @param uploadedBytes total payload bytes uploaded to object storage (excludes deduplicated blobs)
     */
    public record SnapshotResult(Manifest manifest, int dirtyCount, long uploadedBytes) {
        public SnapshotResult {
            Objects.requireNonNull(manifest, "manifest");
            if (dirtyCount < 0) {
                throw new IllegalArgumentException("dirtyCount must be >= 0");
            }
            if (uploadedBytes < 0) {
                throw new IllegalArgumentException("uploadedBytes must be >= 0");
            }
        }
    }

    public SnapshotEngine(ObjectStore objectStore, LocalObjectCache cache, SnapshotCopier copier) {
        this(objectStore, cache, copier, Clock.systemUTC());
    }

    public SnapshotEngine(ObjectStore objectStore, LocalObjectCache cache, SnapshotCopier copier, Clock clock) {
        this.objectStore = Objects.requireNonNull(objectStore, "objectStore");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.copier = Objects.requireNonNull(copier, "copier");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Executes a snapshot of the given dirty files for a world.
     *
     * @param scratchRoot root directory containing local world folders
     * @param worldId world identity being snapshotted
     * @param generation epoch generation counter
     * @param sequence monotonic sequence counter within the generation
     * @param dataVersion Minecraft data version (from {@code level.dat})
     * @param mcVersion Minecraft release version string (e.g. {@code 26.2})
     * @param baselineEntries previous manifest entries to merge over (or empty map for initial snapshot)
     * @param dirtyRelativePaths paths relative to {@code scratchRoot} of modified or new files to snapshot
     * @param verifyRegionStructure whether to validate Anvil region file structure (MN-5c)
     * @return result record containing the new manifest and summary metrics
     * @throws RegionStructureException if region structure validation fails
     * @throws StorageException on copy, hash, cache or upload errors
     */
    public SnapshotResult executeSnapshot(
            Path scratchRoot,
            WorldId worldId,
            long generation,
            int sequence,
            int dataVersion,
            String mcVersion,
            Map<String, ManifestEntry> baselineEntries,
            Collection<Path> dirtyRelativePaths,
            boolean verifyRegionStructure) {
        Objects.requireNonNull(scratchRoot, "scratchRoot");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(mcVersion, "mcVersion");
        Objects.requireNonNull(baselineEntries, "baselineEntries");
        Objects.requireNonNull(dirtyRelativePaths, "dirtyRelativePaths");

        Path tempSnapshotDir = scratchRoot.resolve(".snapshot-" + worldId.value() + "-" + UUID.randomUUID());
        try {
            // 1. Copy dirty files into isolated temporary snapshot directory
            List<SnapshotCopier.CopiedFile> copied = copier.copyAll(scratchRoot, tempSnapshotDir, dirtyRelativePaths);

            // 2. Hash, validate .mca structure (MN-5c), cache and upload objects to ObjectStore
            Map<String, ManifestEntry> newEntries = new LinkedHashMap<>(baselineEntries);
            long uploadedBytes = 0;

            for (SnapshotCopier.CopiedFile file : copied) {
                String unixRel = file.relative().toString().replace('\\', '/');
                HashedContent hashed = ContentHasher.hashAndValidate(file.snapshotPath(), verifyRegionStructure);
                String sha256 = hashed.sha256Hex();

                cache.put(sha256, file.snapshotPath());

                String s3Key = "worlds/" + worldId.value() + "/data/" + sha256;
                if (!objectStore.exists(s3Key)) {
                    objectStore.putObject(s3Key, file.snapshotPath());
                    uploadedBytes += hashed.sizeBytes();
                }

                newEntries.put(
                        unixRel,
                        new ManifestEntry(
                                unixRel,
                                sha256,
                                hashed.sizeBytes(),
                                file.fingerprint().lastModifiedTime().toMillis()));
            }

            // 3. Construct and upload updated Manifest
            Manifest manifest =
                    new Manifest(worldId, generation, sequence, dataVersion, mcVersion, clock.instant(), newEntries);
            String manifestJson = ManifestCodec.encode(manifest);
            objectStore.putBytes(
                    manifest.manifestKey(), manifestJson.getBytes(StandardCharsets.UTF_8), "application/json");

            log.debug(
                    "Snapshot complete for world {}: gen={}, seq={}, dirty={}, uploadedBytes={}, manifestKey={}",
                    worldId.value(),
                    generation,
                    sequence,
                    copied.size(),
                    uploadedBytes,
                    manifest.manifestKey());

            return new SnapshotResult(manifest, copied.size(), uploadedBytes);
        } finally {
            deleteDirectoryRecursively(tempSnapshotDir);
        }
    }

    private static void deleteDirectoryRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    log.debug("Could not delete temporary snapshot file: {}", path);
                }
            });
        } catch (IOException ignored) {
            log.debug("Could not walk temporary snapshot directory for cleanup: {}", root);
        }
    }
}
