package nl.gzmn.playerworlds.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import nl.gzmn.playerworlds.core.config.StorageClientSettings;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.storage.DirtyScanner;
import nl.gzmn.playerworlds.core.storage.LocalObjectCache;
import nl.gzmn.playerworlds.core.storage.Manifest;
import nl.gzmn.playerworlds.core.storage.ManifestCodec;
import nl.gzmn.playerworlds.core.storage.ManifestEntry;
import nl.gzmn.playerworlds.core.storage.PlainFileCloner;
import nl.gzmn.playerworlds.core.storage.RegionStructureException;
import nl.gzmn.playerworlds.core.storage.S3ObjectStore;
import nl.gzmn.playerworlds.core.storage.SnapshotCopier;
import nl.gzmn.playerworlds.core.storage.SnapshotEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SnapshotEngineTest {

    @Test
    @DisplayName("executes snapshot, uploads objects to S3, caches locally, and writes manifest")
    void executesSnapshotAndUploadsObjectsAndManifest(@TempDir Path tempDir) throws Exception {
        StorageClientSettings settings = TestObjectStore.settingsForNewBucket();
        try (S3ObjectStore store = S3ObjectStore.open(settings)) {
            Path scratch = tempDir.resolve("scratch");
            Path cacheRoot = tempDir.resolve("cache");

            LocalObjectCache cache = new LocalObjectCache(cacheRoot, PlainFileCloner.INSTANCE);
            SnapshotCopier copier = new SnapshotCopier(PlainFileCloner.INSTANCE);
            SnapshotEngine engine = new SnapshotEngine(store, cache, copier);

            WorldId worldId = WorldFixture.materialize(scratch);
            List<String> expectedPaths = WorldFixture.syncedRelativePaths(scratch, worldId);
            DirtyScanner.Scan scan = DirtyScanner.scan(
                    scratch,
                    WorldFixture.relativeDimensionFolders(worldId),
                    Map.of(),
                    List.of("session.lock", "uid.dat"));

            SnapshotEngine.SnapshotResult result =
                    engine.executeSnapshot(scratch, worldId, 0L, 1, 4903, "26.2", Map.of(), scan, true);

            assertThat(result.dirtyCount()).isEqualTo(expectedPaths.size());
            assertThat(result.uploadedBytes()).isPositive();

            Manifest manifest = result.manifest();
            assertThat(manifest.worldId()).isEqualTo(worldId);
            assertThat(manifest.generation()).isZero();
            assertThat(manifest.sequence()).isEqualTo(1);
            assertThat(manifest.dataVersion()).isEqualTo(4903);
            assertThat(manifest.mcVersion()).isEqualTo("26.2");
            assertThat(manifest.entries()).hasSameSizeAs(expectedPaths);

            // Verify each object is uploaded to S3 and stored in local cache
            for (ManifestEntry entry : manifest.entries().values()) {
                String s3Key = "worlds/" + worldId.value() + "/data/" + entry.sha256Hex();
                assertThat(store.exists(s3Key)).isTrue();
                assertThat(cache.contains(entry.sha256Hex())).isTrue();
                assertThat(Files.size(cache.pathOf(entry.sha256Hex()))).isEqualTo(entry.sizeBytes());
            }

            // Verify manifest is written to S3 and can be decoded
            String manifestKey = manifest.manifestKey();
            assertThat(store.exists(manifestKey)).isTrue();
            byte[] manifestBytes = store.getBytes(manifestKey);
            Manifest decoded = ManifestCodec.decode(new String(manifestBytes, StandardCharsets.UTF_8));
            assertThat(decoded.worldId()).isEqualTo(manifest.worldId());
            assertThat(decoded.generation()).isEqualTo(manifest.generation());
            assertThat(decoded.sequence()).isEqualTo(manifest.sequence());
            assertThat(decoded.entries()).isEqualTo(manifest.entries());

            // Verify temp snapshot directory was cleaned up
            try (Stream<Path> stream = Files.list(scratch)) {
                List<String> remainingFolders =
                        stream.map(p -> p.getFileName().toString()).toList();
                assertThat(remainingFolders).noneMatch(name -> name.startsWith(".snapshot-"));
            }
        }
    }

    @Test
    @DisplayName("incremental snapshot copies and uploads only modified files and merges with baseline")
    void incrementalSnapshotUploadsOnlyDirtyFiles(@TempDir Path tempDir) throws Exception {
        StorageClientSettings settings = TestObjectStore.settingsForNewBucket();
        try (S3ObjectStore store = S3ObjectStore.open(settings)) {
            Path scratch = tempDir.resolve("scratch");
            Path cacheRoot = tempDir.resolve("cache");

            LocalObjectCache cache = new LocalObjectCache(cacheRoot, PlainFileCloner.INSTANCE);
            SnapshotCopier copier = new SnapshotCopier(PlainFileCloner.INSTANCE);
            SnapshotEngine engine = new SnapshotEngine(store, cache, copier);

            WorldId worldId = WorldFixture.materialize(scratch);
            List<String> allPaths = WorldFixture.syncedRelativePaths(scratch, worldId);
            DirtyScanner.Scan initial = DirtyScanner.scan(
                    scratch,
                    WorldFixture.relativeDimensionFolders(worldId),
                    Map.of(),
                    List.of("session.lock", "uid.dat"));

            // Initial full snapshot
            SnapshotEngine.SnapshotResult snap1 =
                    engine.executeSnapshot(scratch, worldId, 0L, 1, 4903, "26.2", Map.of(), initial, true);

            assertThat(snap1.dirtyCount()).isEqualTo(allPaths.size());

            // Modify only paper-world.yml (Paper 26 dimension marker)
            Path paperWorldYml =
                    WorldFixture.dimensionFolder(scratch, worldId.folder()).resolve("paper-world.yml");
            byte[] newContent = "updated-paper-world-yml-12345".getBytes(StandardCharsets.UTF_8);
            Files.write(paperWorldYml, newContent);
            Files.setLastModifiedTime(paperWorldYml, FileTime.fromMillis(9999999L));

            DirtyScanner.Scan scan2 = DirtyScanner.scan(
                    scratch,
                    WorldFixture.relativeDimensionFolders(worldId),
                    snap1.manifest().entries(),
                    List.of("session.lock", "uid.dat"));

            Path expectedDirty = Path.of("world", "dimensions", "minecraft", worldId.folder(), "paper-world.yml");
            assertThat(scan2.dirty()).containsExactly(expectedDirty);

            // Incremental snapshot 2
            SnapshotEngine.SnapshotResult snap2 = engine.executeSnapshot(
                    scratch, worldId, 0L, 2, 4903, "26.2", snap1.manifest().entries(), scan2, true);

            assertThat(snap2.dirtyCount()).isEqualTo(1);
            assertThat(snap2.uploadedBytes()).isEqualTo(newContent.length);

            Manifest manifest2 = snap2.manifest();
            assertThat(manifest2.sequence()).isEqualTo(2);
            assertThat(manifest2.entries()).hasSameSizeAs(allPaths);

            // Modified entry matches new content
            String paperWorldKey = "world/dimensions/minecraft/" + worldId.folder() + "/paper-world.yml";
            ManifestEntry paperWorldEntry = manifest2.entries().get(paperWorldKey);
            assertThat(paperWorldEntry.sizeBytes()).isEqualTo(newContent.length);
            assertThat(paperWorldEntry.lastModifiedMillis()).isEqualTo(9999999L);
            assertThat(paperWorldEntry.sha256Hex())
                    .isNotEqualTo(snap1.manifest().entries().get(paperWorldKey).sha256Hex());

            // Unmodified entries retain old sha256
            for (String p : allPaths) {
                if (!p.equals(paperWorldKey)) {
                    assertThat(manifest2.entries().get(p).sha256Hex())
                            .isEqualTo(snap1.manifest().entries().get(p).sha256Hex());
                }
            }
        }
    }

    @Test
    @DisplayName("rejects corrupt region file without uploading objects or manifest")
    void rejectsCorruptRegionFileWithoutUploading(@TempDir Path tempDir) throws Exception {
        StorageClientSettings settings = TestObjectStore.settingsForNewBucket();
        try (S3ObjectStore store = S3ObjectStore.open(settings)) {
            Path scratch = tempDir.resolve("scratch");
            Path cacheRoot = tempDir.resolve("cache");

            LocalObjectCache cache = new LocalObjectCache(cacheRoot, PlainFileCloner.INSTANCE);
            SnapshotCopier copier = new SnapshotCopier(PlainFileCloner.INSTANCE);
            SnapshotEngine engine = new SnapshotEngine(store, cache, copier);

            WorldId worldId = WorldFixture.materialize(scratch);

            // Corrupt r.0.0.mca header with invalid sector offset inside header
            Path mca = WorldFixture.dimensionFolder(scratch, worldId.folder())
                    .resolve("region")
                    .resolve("r.0.0.mca");
            byte[] corrupted = new byte[8192];
            corrupted[0] = 0;
            corrupted[1] = 0;
            corrupted[2] = 1; // sector offset 1 (inside header!)
            corrupted[3] = 1; // 1 sector
            Files.write(mca, corrupted);

            String corruptedRel = "world/dimensions/minecraft/" + worldId.folder() + "/region/r.0.0.mca";
            DirtyScanner.Scan scan = new DirtyScanner.Scan(List.of(Path.of(corruptedRel)), List.of(corruptedRel));

            assertThatThrownBy(
                            () -> engine.executeSnapshot(scratch, worldId, 0L, 1, 4903, "26.2", Map.of(), scan, true))
                    .isInstanceOf(RegionStructureException.class);

            // Verify manifest was not uploaded
            String manifestKey = "worlds/" + worldId.value() + "/manifest/0-1.json";
            assertThat(store.exists(manifestKey)).isFalse();

            // Verify temp directory cleaned up
            try (Stream<Path> stream = Files.list(scratch)) {
                List<String> remaining =
                        stream.map(p -> p.getFileName().toString()).toList();
                assertThat(remaining).noneMatch(name -> name.startsWith(".snapshot-"));
            }
        }
    }

    @Test
    @DisplayName("a file deleted from the world leaves the next manifest (MN-3, D16)")
    void aDeletedFileLeavesTheNextManifest_MN3(@TempDir Path tempDir) throws Exception {
        Path scratch = tempDir.resolve("scratch");
        Path cacheRoot = tempDir.resolve("cache");

        StorageClientSettings settings = TestObjectStore.settingsForNewBucket();
        try (S3ObjectStore store = S3ObjectStore.open(settings)) {
            LocalObjectCache cache = new LocalObjectCache(cacheRoot, PlainFileCloner.INSTANCE);
            SnapshotEngine engine = new SnapshotEngine(store, cache, new SnapshotCopier(PlainFileCloner.INSTANCE));

            WorldId worldId = WorldFixture.materialize(scratch);
            List<Path> roots = WorldFixture.relativeDimensionFolders(worldId);
            List<String> excludes = List.of("session.lock", "uid.dat");

            SnapshotEngine.SnapshotResult first = engine.executeSnapshot(
                    scratch,
                    worldId,
                    0L,
                    1,
                    4903,
                    "26.2",
                    Map.of(),
                    DirtyScanner.scan(scratch, roots, Map.of(), excludes),
                    true);

            // A region file the world trimmed away. Nothing else changes, so the
            // dirty set is empty and only the observed set can notice.
            String deletedRel = "world/dimensions/minecraft/" + worldId.folder() + "/region/r.0.0.mca";
            Path deleted = scratch.resolve(deletedRel);
            assertThat(Files.deleteIfExists(deleted)).isTrue();
            assertThat(first.manifest().entries()).containsKey(deletedRel);

            Map<String, ManifestEntry> baseline = first.manifest().entries();
            DirtyScanner.Scan second = DirtyScanner.scan(scratch, roots, baseline, excludes);
            assertThat(second.dirty()).isEmpty();

            SnapshotEngine.SnapshotResult snap2 =
                    engine.executeSnapshot(scratch, worldId, 0L, 2, 4903, "26.2", baseline, second, true);

            // MN-3: the manifest describes the world, so a file that is gone is
            // gone from it. Carried forward, it would be resurrected by the next
            // cold load and its object could never be collected by MN-2b.
            assertThat(snap2.manifest().entries()).doesNotContainKey(deletedRel);
            assertThat(snap2.manifest().entries()).hasSize(baseline.size() - 1);
            // Everything else is carried over untouched rather than re-uploaded.
            assertThat(snap2.dirtyCount()).isZero();
            assertThat(snap2.uploadedBytes()).isZero();
        }
    }

    @Test
    @DisplayName("validates arguments")
    void validatesArguments(@TempDir Path tempDir) {
        StorageClientSettings settings = TestObjectStore.settingsForNewBucket();
        try (S3ObjectStore store = S3ObjectStore.open(settings)) {
            Path cacheRoot = tempDir.resolve("cache");
            LocalObjectCache cache = new LocalObjectCache(cacheRoot, PlainFileCloner.INSTANCE);
            SnapshotCopier copier = new SnapshotCopier(PlainFileCloner.INSTANCE);

            assertThatNullPointerException().isThrownBy(() -> new SnapshotEngine(null, cache, copier));
            assertThatNullPointerException().isThrownBy(() -> new SnapshotEngine(store, null, copier));
            assertThatNullPointerException().isThrownBy(() -> new SnapshotEngine(store, cache, null));

            SnapshotEngine engine = new SnapshotEngine(store, cache, copier);
            WorldId worldId = WorldId.random();

            DirtyScanner.Scan empty = new DirtyScanner.Scan(List.of(), List.of());
            assertThatNullPointerException()
                    .isThrownBy(
                            () -> engine.executeSnapshot(null, worldId, 0L, 1, 4903, "26.2", Map.of(), empty, true));
            assertThatNullPointerException()
                    .isThrownBy(
                            () -> engine.executeSnapshot(tempDir, null, 0L, 1, 4903, "26.2", Map.of(), empty, true));
            assertThatNullPointerException()
                    .isThrownBy(
                            () -> engine.executeSnapshot(tempDir, worldId, 0L, 1, 4903, null, Map.of(), empty, true));
            assertThatNullPointerException()
                    .isThrownBy(() -> engine.executeSnapshot(tempDir, worldId, 0L, 1, 4903, "26.2", null, empty, true));
            assertThatNullPointerException()
                    .isThrownBy(
                            () -> engine.executeSnapshot(tempDir, worldId, 0L, 1, 4903, "26.2", Map.of(), null, true));
        }
    }
}
