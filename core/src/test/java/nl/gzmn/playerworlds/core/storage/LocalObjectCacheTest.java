package nl.gzmn.playerworlds.core.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalObjectCacheTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("contains returns false when object does not exist in cache")
    void containsReturnsFalseWhenMissing() {
        Path cacheRoot = tempDir.resolve("cache");
        LocalObjectCache cache = new LocalObjectCache(cacheRoot, PlainFileCloner.INSTANCE);

        assertThat(cache.contains("a".repeat(64))).isFalse();
        assertThat(cache.pathOf("a".repeat(64))).isEqualTo(cacheRoot.resolve("a".repeat(64)));
    }

    @Test
    @DisplayName("put stores file atomically and contains returns true")
    void storesAndContainsObject() throws Exception {
        Path cacheRoot = tempDir.resolve("cache");
        LocalObjectCache cache = new LocalObjectCache(cacheRoot, PlainFileCloner.INSTANCE);

        String hash = "a".repeat(64);
        byte[] content = "cached-content-1234".getBytes(StandardCharsets.UTF_8);

        Path source = tempDir.resolve("source.txt");
        Files.write(source, content);

        cache.put(hash, source);

        assertThat(cache.contains(hash)).isTrue();
        assertThat(cache.pathOf(hash)).isEqualTo(cacheRoot.resolve(hash));
        assertThat(Files.readAllBytes(cache.pathOf(hash))).isEqualTo(content);
    }

    @Test
    @DisplayName("put on existing object updates last modified time without modifying content")
    void putExistingObjectUpdatesMtime() throws Exception {
        Path cacheRoot = tempDir.resolve("cache");
        LocalObjectCache cache = new LocalObjectCache(cacheRoot, PlainFileCloner.INSTANCE);

        String hash = "b".repeat(64);
        Path source1 = tempDir.resolve("source1.txt");
        Files.writeString(source1, "original-content", StandardCharsets.UTF_8);

        cache.put(hash, source1);

        Path cachedPath = cache.pathOf(hash);
        FileTime oldTime = FileTime.from(Instant.now().minus(10, ChronoUnit.MINUTES));
        Files.setLastModifiedTime(cachedPath, oldTime);

        Path source2 = tempDir.resolve("source2.txt");
        Files.writeString(source2, "modified-content", StandardCharsets.UTF_8);

        cache.put(hash, source2);

        // Content remains immutable (original content)
        assertThat(Files.readString(cachedPath, StandardCharsets.UTF_8)).isEqualTo("original-content");
        // But mtime is updated to recent
        assertThat(Files.getLastModifiedTime(cachedPath).toInstant()).isAfter(oldTime.toInstant());
    }

    @Test
    @DisplayName("cloneTo copies content to target, creates parent dirs, and touches cached mtime")
    void cloneToCopiesAndTouchesCache() throws Exception {
        Path cacheRoot = tempDir.resolve("cache");
        Path scratchRoot = tempDir.resolve("scratch");
        LocalObjectCache cache = new LocalObjectCache(cacheRoot, PlainFileCloner.INSTANCE);

        String hash = "c".repeat(64);
        byte[] content = "level.dat bytes content".getBytes(StandardCharsets.UTF_8);

        Path source = tempDir.resolve("source.txt");
        Files.write(source, content);
        cache.put(hash, source);

        Path cachedPath = cache.pathOf(hash);
        FileTime oldTime = FileTime.from(Instant.now().minus(5, ChronoUnit.MINUTES));
        Files.setLastModifiedTime(cachedPath, oldTime);

        Path dest = scratchRoot.resolve("nested/dir/level.dat");
        cache.cloneTo(hash, dest);

        assertThat(Files.exists(dest)).isTrue();
        assertThat(Files.readAllBytes(dest)).isEqualTo(content);
        assertThat(Files.getLastModifiedTime(cachedPath).toInstant()).isAfter(oldTime.toInstant());
    }

    @Test
    @DisplayName("cloneTo throws StorageException if object is missing from cache")
    void cloneToThrowsWhenMissing() {
        Path cacheRoot = tempDir.resolve("cache");
        LocalObjectCache cache = new LocalObjectCache(cacheRoot, PlainFileCloner.INSTANCE);

        Path dest = tempDir.resolve("out.dat");
        assertThatThrownBy(() -> cache.cloneTo("non-existent-hash", dest))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("not in cache");
    }

    @Test
    @DisplayName("evictLru returns 0 when cache size is within maxBytes")
    void evictLruNoOpWhenUnderLimit() throws Exception {
        Path cacheRoot = tempDir.resolve("cache");
        LocalObjectCache cache = new LocalObjectCache(cacheRoot, PlainFileCloner.INSTANCE);

        Path s1 = tempDir.resolve("s1");
        Files.write(s1, new byte[50]);
        cache.put("1".repeat(64), s1);

        long freed = cache.evictLru(100);
        assertThat(freed).isZero();
        assertThat(cache.contains("1".repeat(64))).isTrue();
    }

    @Test
    @DisplayName("evictLru deletes oldest files first until total size <= maxBytes")
    void evictsLruOldestFirst() throws Exception {
        Path cacheRoot = tempDir.resolve("cache");
        LocalObjectCache cache = new LocalObjectCache(cacheRoot, PlainFileCloner.INSTANCE);

        Path src1 = tempDir.resolve("s1");
        Path src2 = tempDir.resolve("s2");
        Path src3 = tempDir.resolve("s3");
        Files.write(src1, new byte[100]);
        Files.write(src2, new byte[100]);
        Files.write(src3, new byte[100]);

        String h1 = "1".repeat(64);
        String h2 = "2".repeat(64);
        String h3 = "3".repeat(64);

        cache.put(h1, src1);
        cache.put(h2, src2);
        cache.put(h3, src3);

        Instant now = Instant.now();
        Files.setLastModifiedTime(cache.pathOf(h1), FileTime.from(now.minus(30, ChronoUnit.MINUTES)));
        Files.setLastModifiedTime(cache.pathOf(h2), FileTime.from(now.minus(20, ChronoUnit.MINUTES)));
        Files.setLastModifiedTime(cache.pathOf(h3), FileTime.from(now.minus(10, ChronoUnit.MINUTES)));

        // Total size = 300 bytes. Limit = 150 bytes.
        // Should evict h1 (oldest, 100 bytes freed, total 200 > 150), then h2 (100 bytes freed, total 100 <= 150).
        // Total bytes freed = 200.
        long freed = cache.evictLru(150);

        assertThat(freed).isEqualTo(200);
        assertThat(cache.contains(h1)).isFalse();
        assertThat(cache.contains(h2)).isFalse();
        assertThat(cache.contains(h3)).isTrue();
    }

    @Test
    @DisplayName("evictLru with 0 maxBytes evicts all files")
    void evictsLruAllWhenZeroCap() throws Exception {
        Path cacheRoot = tempDir.resolve("cache");
        LocalObjectCache cache = new LocalObjectCache(cacheRoot, PlainFileCloner.INSTANCE);

        Path src1 = tempDir.resolve("s1");
        Files.write(src1, new byte[100]);
        String h1 = "1".repeat(64);
        cache.put(h1, src1);

        long freed = cache.evictLru(0);
        assertThat(freed).isEqualTo(100);
        assertThat(cache.contains(h1)).isFalse();
    }

    @Test
    @DisplayName("put throws StorageException if source file does not exist")
    void putThrowsIfSourceMissing() {
        Path cacheRoot = tempDir.resolve("cache");
        LocalObjectCache cache = new LocalObjectCache(cacheRoot, PlainFileCloner.INSTANCE);

        Path nonExistent = tempDir.resolve("missing.dat");
        assertThatThrownBy(() -> cache.put("1".repeat(64), nonExistent)).isInstanceOf(StorageException.class);
    }

    @Test
    @DisplayName("constructor and methods reject null arguments")
    void nullChecks() {
        Path cacheRoot = tempDir.resolve("cache");
        assertThatNullPointerException().isThrownBy(() -> new LocalObjectCache(null, PlainFileCloner.INSTANCE));
        assertThatNullPointerException().isThrownBy(() -> new LocalObjectCache(cacheRoot, null));

        LocalObjectCache cache = new LocalObjectCache(cacheRoot, PlainFileCloner.INSTANCE);
        assertThatNullPointerException().isThrownBy(() -> cache.contains(null));
        assertThatNullPointerException().isThrownBy(() -> cache.pathOf(null));
        assertThatNullPointerException().isThrownBy(() -> cache.put(null, tempDir.resolve("s")));
        assertThatNullPointerException().isThrownBy(() -> cache.put("a", null));
        assertThatNullPointerException().isThrownBy(() -> cache.cloneTo(null, tempDir.resolve("d")));
        assertThatNullPointerException().isThrownBy(() -> cache.cloneTo("a", null));
    }
}
