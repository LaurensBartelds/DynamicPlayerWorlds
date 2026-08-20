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
import nl.gzmn.playerworlds.core.storage.DirtyScanner;
import nl.gzmn.playerworlds.core.storage.FileFingerprint;
import nl.gzmn.playerworlds.core.storage.HashedContent;
import nl.gzmn.playerworlds.core.storage.LocalObjectCache;
import nl.gzmn.playerworlds.core.storage.Manifest;
import nl.gzmn.playerworlds.core.storage.ManifestEntry;
import nl.gzmn.playerworlds.core.storage.PlainFileCloner;
import nl.gzmn.playerworlds.core.storage.S3ObjectStore;
import nl.gzmn.playerworlds.core.storage.SnapshotCopier;
import nl.gzmn.playerworlds.core.storage.SnapshotEngine;
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
            WorldDownloader.Result coldResult = downloader.materialize(
                    manifest,
                    targetScratch,
                    WorldFixture.relativeDimensionFolders(worldId),
                    WorldDownloader.Verification.FINGERPRINT);

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
            WorldDownloader.Result warmResult = downloader.materialize(
                    manifest,
                    targetScratch,
                    WorldFixture.relativeDimensionFolders(worldId),
                    WorldDownloader.Verification.FINGERPRINT);

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
            WorldDownloader.Result firstResult = downloader.materialize(
                    manifest,
                    targetScratch1,
                    WorldFixture.relativeDimensionFolders(worldId),
                    WorldDownloader.Verification.FINGERPRINT);
            assertThat(firstResult.filesDownloaded()).isEqualTo(entries.size());
            assertThat(firstResult.wasWarm()).isFalse();

            // Materialize second scratch from warm cache
            WorldDownloader.Result secondResult = downloader.materialize(
                    manifest,
                    targetScratch2,
                    WorldFixture.relativeDimensionFolders(worldId),
                    WorldDownloader.Verification.FINGERPRINT);
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
            downloader.materialize(
                    manifest,
                    targetScratch,
                    WorldFixture.relativeDimensionFolders(worldId),
                    WorldDownloader.Verification.FINGERPRINT);

            // Mutate one file's content and mtime
            String mutatedPath = relativePaths.get(0);
            Path mutatedFile = targetScratch.resolve(mutatedPath);
            Files.write(mutatedFile, new byte[] {0x42, 0x43});
            Files.setLastModifiedTime(mutatedFile, FileTime.fromMillis(12345L));

            WorldDownloader.Result result = downloader.materialize(
                    manifest,
                    targetScratch,
                    WorldFixture.relativeDimensionFolders(worldId),
                    WorldDownloader.Verification.FINGERPRINT);
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
            assertThatNullPointerException()
                    .isThrownBy(() ->
                            downloader.materialize(null, tempDir, List.of(), WorldDownloader.Verification.FINGERPRINT));
            assertThatNullPointerException()
                    .isThrownBy(() -> downloader.materialize(
                            emptyManifest, null, List.of(), WorldDownloader.Verification.FINGERPRINT));

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
                    .isThrownBy(() -> downloader.materialize(
                            traversalManifest,
                            tempDir.resolve("scratch"),
                            List.of(),
                            WorldDownloader.Verification.FINGERPRINT));
        }
    }

    @Test
    @DisplayName("materialise removes files the manifest does not list (MN-4, D16)")
    void materialiseRemovesFilesTheManifestDoesNotList_MN4(@TempDir Path tempDir) throws Exception {
        Path sourceScratch = tempDir.resolve("source");
        Path targetScratch = tempDir.resolve("target");
        Path engineCacheRoot = tempDir.resolve("engine-cache");
        Path downloaderCacheRoot = tempDir.resolve("downloader-cache");

        WorldId worldId = WorldId.random();
        WorldFixture.materialize(sourceScratch, worldId, WorldFixture.DimensionSet.ALL_THREE);

        StorageClientSettings settings = TestObjectStore.settingsForNewBucket();
        try (S3ObjectStore store = S3ObjectStore.open(settings)) {
            LocalObjectCache engineCache = new LocalObjectCache(engineCacheRoot, PlainFileCloner.INSTANCE);
            SnapshotEngine engine =
                    new SnapshotEngine(store, engineCache, new SnapshotCopier(PlainFileCloner.INSTANCE));
            List<Path> roots = WorldFixture.relativeDimensionFolders(worldId);
            Manifest manifest = engine.executeSnapshot(
                            sourceScratch,
                            worldId,
                            0L,
                            1,
                            4903,
                            "26.2",
                            Map.of(),
                            DirtyScanner.scan(sourceScratch, roots, Map.of(), List.of("session.lock", "uid.dat")),
                            true)
                    .manifest();

            LocalObjectCache downloaderCache = new LocalObjectCache(downloaderCacheRoot, PlainFileCloner.INSTANCE);
            WorldDownloader downloader = new WorldDownloader(store, downloaderCache, PlainFileCloner.INSTANCE);
            downloader.materialize(manifest, targetScratch, roots, WorldDownloader.Verification.FINGERPRINT);

            // Debris the manifest does not know about: a region file from an
            // earlier generation, left behind by a crash. Merging rather than
            // mirroring keeps it, and the next snapshot uploads it as though the
            // world still contained it.
            Path stale =
                    targetScratch.resolve("world/dimensions/minecraft/" + worldId.folder() + "/region/r.99.99.mca");
            Files.createDirectories(stale.getParent());
            Files.write(stale, new byte[] {1, 2, 3});
            // And one outside the world's folders, which must be left alone.
            Path unrelated = targetScratch.resolve("world/dimensions/minecraft/somebody_elses/level.dat");
            Files.createDirectories(unrelated.getParent());
            Files.write(unrelated, new byte[] {4});

            WorldDownloader.Result result =
                    downloader.materialize(manifest, targetScratch, roots, WorldDownloader.Verification.FINGERPRINT);

            assertThat(Files.exists(stale)).isFalse();
            assertThat(result.filesRemoved()).isEqualTo(1);
            assertThat(Files.exists(unrelated)).isTrue();
            // Every file the manifest does list is still there.
            for (ManifestEntry entry : manifest.entries().values()) {
                assertThat(Files.isRegularFile(targetScratch.resolve(entry.path())))
                        .as("manifest entry must survive the prune: %s", entry.path())
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("REHASH catches a file that matches on size and mtime but not on content (MN-4)")
    void rehashCatchesAFileThatMatchesOnSizeAndMtime_MN4(@TempDir Path tempDir) throws Exception {
        Path sourceScratch = tempDir.resolve("source");
        Path targetScratch = tempDir.resolve("target");

        WorldId worldId = WorldId.random();
        WorldFixture.materialize(sourceScratch, worldId, WorldFixture.DimensionSet.OVERWORLD_ONLY);
        List<Path> roots = WorldFixture.relativeDimensionFolders(worldId);

        StorageClientSettings settings = TestObjectStore.settingsForNewBucket();
        try (S3ObjectStore store = S3ObjectStore.open(settings)) {
            LocalObjectCache engineCache =
                    new LocalObjectCache(tempDir.resolve("engine-cache"), PlainFileCloner.INSTANCE);
            SnapshotEngine engine =
                    new SnapshotEngine(store, engineCache, new SnapshotCopier(PlainFileCloner.INSTANCE));
            Manifest manifest = engine.executeSnapshot(
                            sourceScratch,
                            worldId,
                            0L,
                            1,
                            4903,
                            "26.2",
                            Map.of(),
                            DirtyScanner.scan(sourceScratch, roots, Map.of(), List.of("session.lock", "uid.dat")),
                            true)
                    .manifest();

            LocalObjectCache downloaderCache =
                    new LocalObjectCache(tempDir.resolve("downloader-cache"), PlainFileCloner.INSTANCE);
            WorldDownloader downloader = new WorldDownloader(store, downloaderCache, PlainFileCloner.INSTANCE);
            downloader.materialize(manifest, targetScratch, roots, WorldDownloader.Verification.FINGERPRINT);

            // A file a crash left half-written: the same length and the same
            // mtime, different bytes. This is exactly what MN-4's "a world whose
            // marker is absent is fully rehashed before use" is for — size and
            // mtime are what an interrupted write preserves.
            ManifestEntry entry = manifest.entries().values().stream()
                    .filter(e -> e.sizeBytes() > 4)
                    .findFirst()
                    .orElseThrow();
            Path corrupted = targetScratch.resolve(entry.path());
            byte[] good = Files.readAllBytes(corrupted);
            byte[] torn = good.clone();
            torn[torn.length - 1] = (byte) (torn[torn.length - 1] ^ 0xFF);
            Files.write(corrupted, torn);
            Files.setLastModifiedTime(corrupted, FileTime.fromMillis(entry.lastModifiedMillis()));

            // Fingerprint mode believes it, which is the trade MN-4 makes on the
            // join path NFR-1 budgets.
            WorldDownloader.Result trusting =
                    downloader.materialize(manifest, targetScratch, roots, WorldDownloader.Verification.FINGERPRINT);
            assertThat(trusting.filesRestored()).isZero();
            assertThat(Files.readAllBytes(corrupted)).isEqualTo(torn);

            // Rehash does not.
            WorldDownloader.Result verifying =
                    downloader.materialize(manifest, targetScratch, roots, WorldDownloader.Verification.REHASH);
            assertThat(verifying.filesRestored()).isEqualTo(1);
            assertThat(Files.readAllBytes(corrupted)).isEqualTo(good);
        }
    }
}
