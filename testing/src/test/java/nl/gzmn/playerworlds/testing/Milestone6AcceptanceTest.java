package nl.gzmn.playerworlds.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import nl.gzmn.playerworlds.core.config.StorageClientSettings;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.storage.LocalObjectCache;
import nl.gzmn.playerworlds.core.storage.PlainFileCloner;
import nl.gzmn.playerworlds.core.storage.RegionStructureException;
import nl.gzmn.playerworlds.core.storage.S3ObjectStore;
import nl.gzmn.playerworlds.core.storage.SnapshotCopier;
import nl.gzmn.playerworlds.core.storage.SnapshotEngine;
import nl.gzmn.playerworlds.core.storage.WorldDownloader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Milestone6AcceptanceTest {

    @Test
    @DisplayName("survives scratch wipe and restores all dimensions, mobs, entities, and POIs byte-for-byte")
    void survivesScratchWipeAndRestoresMobsAndEntities(@TempDir Path tempDir) throws Exception {
        Path scratch = tempDir.resolve("scratch");
        Path engineCacheRoot = tempDir.resolve("engine-cache");
        Path downloaderCacheRoot = tempDir.resolve("downloader-cache");
        Path restoreScratch = tempDir.resolve("restored-scratch");

        WorldId worldId = WorldFixture.materialize(scratch);
        List<String> expectedPaths = WorldFixture.syncedRelativePaths(scratch, worldId);
        assertThat(expectedPaths).isNotEmpty();

        // Verify entities and poi files are included in the materialized fixture
        assertThat(expectedPaths).anyMatch(p -> p.contains("/entities/"));
        assertThat(expectedPaths).anyMatch(p -> p.contains("/poi/"));

        StorageClientSettings settings = TestObjectStore.settingsForNewBucket();
        try (S3ObjectStore store = S3ObjectStore.open(settings)) {
            LocalObjectCache engineCache = new LocalObjectCache(engineCacheRoot, PlainFileCloner.INSTANCE);
            SnapshotCopier copier = new SnapshotCopier(PlainFileCloner.INSTANCE);
            SnapshotEngine engine = new SnapshotEngine(store, engineCache, copier);

            List<Path> dirty = expectedPaths.stream().map(Path::of).toList();
            SnapshotEngine.SnapshotResult snapResult =
                    engine.executeSnapshot(scratch, worldId, 0L, 1, 4903, "26.2", Map.of(), dirty, true);

            assertThat(snapResult.dirtyCount()).isEqualTo(expectedPaths.size());
            assertThat(snapResult.manifest().entries()).hasSameSizeAs(expectedPaths);

            // Cold download onto clean destination directory simulating total scratch wipe
            LocalObjectCache downloaderCache = new LocalObjectCache(downloaderCacheRoot, PlainFileCloner.INSTANCE);
            WorldDownloader downloader = new WorldDownloader(store, downloaderCache, PlainFileCloner.INSTANCE);

            WorldDownloader.Result coldResult = downloader.materialize(snapResult.manifest(), restoreScratch);

            assertThat(coldResult.wasWarm()).isFalse();
            assertThat(coldResult.filesChecked()).isEqualTo(expectedPaths.size());
            assertThat(coldResult.filesRestored()).isEqualTo(expectedPaths.size());
            assertThat(coldResult.filesDownloaded()).isEqualTo(expectedPaths.size());
            assertThat(coldResult.bytesDownloaded()).isPositive();

            // Assert all files (including entities/mobs and POIs) are byte-for-byte identical to original fixture
            for (String relativePath : expectedPaths) {
                Path original = scratch.resolve(relativePath);
                Path restored = restoreScratch.resolve(relativePath);

                assertThat(Files.isRegularFile(restored))
                        .as("Restored file must exist: %s", relativePath)
                        .isTrue();
                assertThat(Files.readAllBytes(restored))
                        .as("Restored file must be byte-for-byte identical: %s", relativePath)
                        .isEqualTo(Files.readAllBytes(original));
            }

            // Second materialization on populated directory: warm check
            WorldDownloader.Result warmResult = downloader.materialize(snapResult.manifest(), restoreScratch);

            assertThat(warmResult.wasWarm()).isTrue();
            assertThat(warmResult.filesChecked()).isEqualTo(expectedPaths.size());
            assertThat(warmResult.filesRestored()).isZero();
            assertThat(warmResult.filesDownloaded()).isZero();
            assertThat(warmResult.bytesDownloaded()).isZero();
        }
    }

    @Test
    @DisplayName("rejects corrupt MCA file during snapshot without uploading corrupt objects or manifest")
    void rejectsCorruptMcaFileDuringSnapshot(@TempDir Path tempDir) throws Exception {
        Path scratch = tempDir.resolve("scratch");
        Path cacheRoot = tempDir.resolve("cache");

        WorldId worldId = WorldFixture.materialize(scratch);
        List<String> expectedPaths = WorldFixture.syncedRelativePaths(scratch, worldId);

        // Corrupt r.0.0.mca sector header (e.g. invalid sector offset pointing inside header)
        Path mca = WorldFixture.dimensionFolder(scratch, worldId.folder())
                        .resolve("region")
                        .resolve("r.0.0.mca");
        byte[] corrupted = new byte[8192];
        corrupted[0] = 0;
        corrupted[1] = 0;
        corrupted[2] = 1; // sector offset 1 (overlaps 8 KiB header)
        corrupted[3] = 1; // 1 sector
        Files.write(mca, corrupted);

        StorageClientSettings settings = TestObjectStore.settingsForNewBucket();
        try (S3ObjectStore store = S3ObjectStore.open(settings)) {
            LocalObjectCache cache = new LocalObjectCache(cacheRoot, PlainFileCloner.INSTANCE);
            SnapshotCopier copier = new SnapshotCopier(PlainFileCloner.INSTANCE);
            SnapshotEngine engine = new SnapshotEngine(store, cache, copier);

            List<Path> dirty = expectedPaths.stream().map(Path::of).toList();

            assertThatThrownBy(
                            () -> engine.executeSnapshot(scratch, worldId, 0L, 1, 4903, "26.2", Map.of(), dirty, true))
                    .isInstanceOf(RegionStructureException.class);

            // Verify manifest was NOT uploaded to S3
            String manifestKey = "worlds/" + worldId.value() + "/manifest/0-1.json";
            assertThat(store.exists(manifestKey)).isFalse();

            // Verify temp directories were cleaned up
            try (Stream<Path> stream = Files.list(scratch)) {
                List<String> remaining =
                        stream.map(p -> p.getFileName().toString()).toList();
                assertThat(remaining).noneMatch(name -> name.startsWith(".snapshot-"));
            }
        }
    }
}
