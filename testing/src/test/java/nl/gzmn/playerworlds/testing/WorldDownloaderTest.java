package nl.gzmn.playerworlds.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import nl.gzmn.playerworlds.core.config.StorageClientSettings;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.storage.ContentHasher;
import nl.gzmn.playerworlds.core.storage.FileFingerprint;
import nl.gzmn.playerworlds.core.storage.HashedContent;
import nl.gzmn.playerworlds.core.storage.LocalObjectCache;
import nl.gzmn.playerworlds.core.storage.Manifest;
import nl.gzmn.playerworlds.core.storage.ManifestEntry;
import nl.gzmn.playerworlds.core.storage.PlainFileCloner;
import nl.gzmn.playerworlds.core.storage.S3ObjectStore;
import nl.gzmn.playerworlds.core.storage.WorldDownloader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldDownloaderTest {

    @Test
    @DisplayName("materialize performs cold download on wiped scratch directory (NFR-9)")
    void scratchWipeRecoveryColdDownload(@TempDir Path tempDir) throws Exception {
        StorageClientSettings settings = TestObjectStore.settingsForNewBucket();
        try (S3ObjectStore store = S3ObjectStore.open(settings)) {
            Path sourceScratch = tempDir.resolve("source-scratch");
            Path targetScratch = tempDir.resolve("target-scratch");
            Path cacheRoot = tempDir.resolve("cache");

            LocalObjectCache cache = new LocalObjectCache(cacheRoot, PlainFileCloner.INSTANCE);
            WorldDownloader downloader = new WorldDownloader(store, cache, PlainFileCloner.INSTANCE);

            WorldId worldId = WorldId.random();
            WorldFixture.materialize(sourceScratch, worldId, WorldFixture.DimensionSet.ALL_THREE);
            List<String> relativePaths = WorldFixture.syncedRelativePaths(sourceScratch, worldId);

            Map<String, ManifestEntry> entries = new HashMap<>();
            long totalBytes = 0;
            for (String relPath : relativePaths) {
                Path sourceFile = sourceScratch.resolve(relPath);
                HashedContent hashed = ContentHasher.hash(sourceFile);
                long mtime = Files.getLastModifiedTime(sourceFile).toMillis();

                String objectKey = "worlds/" + worldId.value() + "/data/" + hashed.sha256Hex();
                store.putObject(objectKey, sourceFile);

                entries.put(relPath, new ManifestEntry(relPath, hashed.sha256Hex(), hashed.sizeBytes(), mtime));
                totalBytes += hashed.sizeBytes();
            }

            Manifest manifest = new Manifest(worldId, 1L, 1, 3953, "1.21.4", Instant.now(), entries);

            // Cold download into empty targetScratch
            WorldDownloader.Result coldResult = downloader.materialize(manifest, targetScratch);

            assertThat(coldResult.filesChecked()).isEqualTo(entries.size());
            assertThat(coldResult.filesRestored()).isEqualTo(entries.size());
            assertThat(coldResult.filesDownloaded()).isEqualTo(entries.size());
            assertThat(coldResult.bytesDownloaded()).isEqualTo(totalBytes);
            assertThat(coldResult.wasWarm()).isFalse();

            // Verify all materialized files match source
            for (String relPath : relativePaths) {
                Path sourceFile = sourceScratch.resolve(relPath);
                Path targetFile = targetScratch.resolve(relPath);

                assertThat(Files.isRegularFile(targetFile)).isTrue();
                assertThat(Files.readAllBytes(targetFile)).isEqualTo(Files.readAllBytes(sourceFile));

                FileFingerprint sourceFp = FileFingerprint.of(sourceFile);
                FileFingerprint targetFp = FileFingerprint.of(targetFile);
                assertThat(targetFp.sizeBytes()).isEqualTo(sourceFp.sizeBytes());
                assertThat(targetFp.lastModifiedTime().toMillis())
                        .isEqualTo(sourceFp.lastModifiedTime().toMillis());
            }

            // Warm check on already populated scratch
            WorldDownloader.Result warmResult = downloader.materialize(manifest, targetScratch);

            assertThat(warmResult.filesChecked()).isEqualTo(entries.size());
            assertThat(warmResult.filesRestored()).isZero();
            assertThat(warmResult.filesDownloaded()).isZero();
            assertThat(warmResult.bytesDownloaded()).isZero();
            assertThat(warmResult.wasWarm()).isTrue();
        }
    }

    @Test
    @DisplayName("materialize restores from local cache without downloading when cache is warm")
    void warmCacheRestoresWithoutDownload(@TempDir Path tempDir) throws Exception {
        StorageClientSettings settings = TestObjectStore.settingsForNewBucket();
        try (S3ObjectStore store = S3ObjectStore.open(settings)) {
            Path sourceScratch = tempDir.resolve("source-scratch");
            Path targetScratch1 = tempDir.resolve("target-scratch-1");
            Path targetScratch2 = tempDir.resolve("target-scratch-2");
            Path cacheRoot = tempDir.resolve("cache");

            LocalObjectCache cache = new LocalObjectCache(cacheRoot, PlainFileCloner.INSTANCE);
            WorldDownloader downloader = new WorldDownloader(store, cache, PlainFileCloner.INSTANCE);

            WorldId worldId = WorldId.random();
            WorldFixture.materialize(sourceScratch, worldId, WorldFixture.DimensionSet.OVERWORLD_ONLY);
            List<String> relativePaths = WorldFixture.syncedRelativePaths(sourceScratch, worldId);

            Map<String, ManifestEntry> entries = new HashMap<>();
            for (String relPath : relativePaths) {
                Path sourceFile = sourceScratch.resolve(relPath);
                HashedContent hashed = ContentHasher.hash(sourceFile);
                long mtime = Files.getLastModifiedTime(sourceFile).toMillis();

                String objectKey = "worlds/" + worldId.value() + "/data/" + hashed.sha256Hex();
                store.putObject(objectKey, sourceFile);

                entries.put(relPath, new ManifestEntry(relPath, hashed.sha256Hex(), hashed.sizeBytes(), mtime));
            }

            Manifest manifest = new Manifest(worldId, 1L, 1, 3953, "1.21.4", Instant.now(), entries);

            // Cold download first to fill cache
            WorldDownloader.Result firstResult = downloader.materialize(manifest, targetScratch1);
            assertThat(firstResult.filesDownloaded()).isEqualTo(entries.size());
            assertThat(firstResult.wasWarm()).isFalse();

            // Materialize second scratch from warm cache
            WorldDownloader.Result secondResult = downloader.materialize(manifest, targetScratch2);
            assertThat(secondResult.filesChecked()).isEqualTo(entries.size());
            assertThat(secondResult.filesRestored()).isEqualTo(entries.size());
            assertThat(secondResult.filesDownloaded()).isZero();
            assertThat(secondResult.bytesDownloaded()).isZero();
            assertThat(secondResult.wasWarm()).isTrue();

            for (String relPath : relativePaths) {
                Path targetFile = targetScratch2.resolve(relPath);
                assertThat(Files.isRegularFile(targetFile)).isTrue();
                assertThat(Files.readAllBytes(targetFile))
                        .isEqualTo(Files.readAllBytes(sourceScratch.resolve(relPath)));
            }
        }
    }

    @Test
    @DisplayName("materialize restores single mutated or corrupted file")
    void restoresSingleMutatedFile(@TempDir Path tempDir) throws Exception {
        StorageClientSettings settings = TestObjectStore.settingsForNewBucket();
        try (S3ObjectStore store = S3ObjectStore.open(settings)) {
            Path sourceScratch = tempDir.resolve("source-scratch");
            Path targetScratch = tempDir.resolve("target-scratch");
            Path cacheRoot = tempDir.resolve("cache");

            LocalObjectCache cache = new LocalObjectCache(cacheRoot, PlainFileCloner.INSTANCE);
            WorldDownloader downloader = new WorldDownloader(store, cache, PlainFileCloner.INSTANCE);

            WorldId worldId = WorldId.random();
            WorldFixture.materialize(sourceScratch, worldId, WorldFixture.DimensionSet.OVERWORLD_ONLY);
            List<String> relativePaths = WorldFixture.syncedRelativePaths(sourceScratch, worldId);

            Map<String, ManifestEntry> entries = new HashMap<>();
            for (String relPath : relativePaths) {
                Path sourceFile = sourceScratch.resolve(relPath);
                HashedContent hashed = ContentHasher.hash(sourceFile);
                long mtime = Files.getLastModifiedTime(sourceFile).toMillis();

                String objectKey = "worlds/" + worldId.value() + "/data/" + hashed.sha256Hex();
                store.putObject(objectKey, sourceFile);

                entries.put(relPath, new ManifestEntry(relPath, hashed.sha256Hex(), hashed.sizeBytes(), mtime));
            }

            Manifest manifest = new Manifest(worldId, 1L, 1, 3953, "1.21.4", Instant.now(), entries);
            downloader.materialize(manifest, targetScratch);

            // Mutate one file's content and mtime
            String mutatedPath = relativePaths.get(0);
            Path mutatedFile = targetScratch.resolve(mutatedPath);
            Files.write(mutatedFile, new byte[] {0x42, 0x43});
            Files.setLastModifiedTime(mutatedFile, FileTime.fromMillis(12345L));

            WorldDownloader.Result result = downloader.materialize(manifest, targetScratch);
            assertThat(result.filesChecked()).isEqualTo(entries.size());
            assertThat(result.filesRestored()).isEqualTo(1);
            assertThat(result.filesDownloaded()).isZero();
            assertThat(result.bytesDownloaded()).isZero();
            assertThat(result.wasWarm()).isTrue();

            assertThat(Files.readAllBytes(mutatedFile))
                    .isEqualTo(Files.readAllBytes(sourceScratch.resolve(mutatedPath)));
            assertThat(Files.getLastModifiedTime(mutatedFile).toMillis())
                    .isEqualTo(Files.getLastModifiedTime(sourceScratch.resolve(mutatedPath))
                            .toMillis());
        }
    }

    @Test
    @DisplayName("validates arguments and guards against path traversal")
    void validatesArgumentsAndPathTraversal(@TempDir Path tempDir) {
        StorageClientSettings settings = TestObjectStore.settingsForNewBucket();
        try (S3ObjectStore store = S3ObjectStore.open(settings)) {
            Path cacheRoot = tempDir.resolve("cache");
            LocalObjectCache cache = new LocalObjectCache(cacheRoot, PlainFileCloner.INSTANCE);
            WorldDownloader downloader = new WorldDownloader(store, cache, PlainFileCloner.INSTANCE);

            assertThatNullPointerException()
                    .isThrownBy(() -> new WorldDownloader(null, cache, PlainFileCloner.INSTANCE));
            assertThatNullPointerException()
                    .isThrownBy(() -> new WorldDownloader(store, null, PlainFileCloner.INSTANCE));
            assertThatNullPointerException().isThrownBy(() -> new WorldDownloader(store, cache, null));

            WorldId worldId = WorldId.random();
            Manifest emptyManifest = new Manifest(worldId, 1L, 1, 3953, "1.21.4", Instant.now(), Map.of());
            assertThatNullPointerException().isThrownBy(() -> downloader.materialize(null, tempDir));
            assertThatNullPointerException().isThrownBy(() -> downloader.materialize(emptyManifest, null));

            // Path traversal in entry
            Manifest traversalManifest = new Manifest(
                    worldId,
                    1L,
                    1,
                    3953,
                    "1.21.4",
                    Instant.now(),
                    Map.of(
                            "../../escaped.txt",
                            new ManifestEntry(
                                    "../../escaped.txt",
                                    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                                    10,
                                    1000L)));

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> downloader.materialize(traversalManifest, tempDir.resolve("scratch")));
        }
    }
}
