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
            List<Path> dirty = expectedPaths.stream().map(Path::of).toList();

            SnapshotEngine.SnapshotResult result =
                    engine.executeSnapshot(scratch, worldId, 0L, 1, 4903, "26.2", Map.of(), dirty, true);

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
            List<Path> initialDirty = allPaths.stream().map(Path::of).toList();

            // Initial full snapshot
            SnapshotEngine.SnapshotResult snap1 =
                    engine.executeSnapshot(scratch, worldId, 0L, 1, 4903, "26.2", Map.of(), initialDirty, true);

            assertThat(snap1.dirtyCount()).isEqualTo(allPaths.size());

            // Modify only level.dat
            Path levelDat = scratch.resolve(worldId.folder()).resolve("level.dat");
            byte[] newContent = "updated-level-dat-bytes-12345".getBytes(StandardCharsets.UTF_8);
            Files.write(levelDat, newContent);
            Files.setLastModifiedTime(levelDat, FileTime.fromMillis(9999999L));

            List<Path> dirty2 = DirtyScanner.scanDirty(
                    scratch, worldId, snap1.manifest().entries(), List.of("session.lock", "uid.dat"));

            assertThat(dirty2).containsExactly(Path.of(worldId.folder(), "level.dat"));

            // Incremental snapshot 2
            SnapshotEngine.SnapshotResult snap2 = engine.executeSnapshot(
                    scratch, worldId, 0L, 2, 4903, "26.2", snap1.manifest().entries(), dirty2, true);

            assertThat(snap2.dirtyCount()).isEqualTo(1);
            assertThat(snap2.uploadedBytes()).isEqualTo(newContent.length);

            Manifest manifest2 = snap2.manifest();
            assertThat(manifest2.sequence()).isEqualTo(2);
            assertThat(manifest2.entries()).hasSameSizeAs(allPaths);

            // Modified entry matches new content
            String levelDatKey = worldId.folder() + "/level.dat";
            ManifestEntry levelDatEntry = manifest2.entries().get(levelDatKey);
            assertThat(levelDatEntry.sizeBytes()).isEqualTo(newContent.length);
            assertThat(levelDatEntry.lastModifiedMillis()).isEqualTo(9999999L);
            assertThat(levelDatEntry.sha256Hex())
                    .isNotEqualTo(snap1.manifest().entries().get(levelDatKey).sha256Hex());

            // Unmodified entries retain old sha256
            for (String p : allPaths) {
                if (!p.equals(levelDatKey)) {
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
            Path mca = scratch.resolve(worldId.folder()).resolve("region").resolve("r.0.0.mca");
            byte[] corrupted = new byte[8192];
            corrupted[0] = 0;
            corrupted[1] = 0;
            corrupted[2] = 1; // sector offset 1 (inside header!)
            corrupted[3] = 1; // 1 sector
            Files.write(mca, corrupted);

            List<Path> dirty = List.of(Path.of(worldId.folder(), "region", "r.0.0.mca"));

            assertThatThrownBy(
                            () -> engine.executeSnapshot(scratch, worldId, 0L, 1, 4903, "26.2", Map.of(), dirty, true))
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

            assertThatNullPointerException()
                    .isThrownBy(() ->
                            engine.executeSnapshot(null, worldId, 0L, 1, 4903, "26.2", Map.of(), List.of(), true));
            assertThatNullPointerException()
                    .isThrownBy(() ->
                            engine.executeSnapshot(tempDir, null, 0L, 1, 4903, "26.2", Map.of(), List.of(), true));
            assertThatNullPointerException()
                    .isThrownBy(() ->
                            engine.executeSnapshot(tempDir, worldId, 0L, 1, 4903, null, Map.of(), List.of(), true));
            assertThatNullPointerException()
                    .isThrownBy(
                            () -> engine.executeSnapshot(tempDir, worldId, 0L, 1, 4903, "26.2", null, List.of(), true));
            assertThatNullPointerException()
                    .isThrownBy(
                            () -> engine.executeSnapshot(tempDir, worldId, 0L, 1, 4903, "26.2", Map.of(), null, true));
        }
    }
}
