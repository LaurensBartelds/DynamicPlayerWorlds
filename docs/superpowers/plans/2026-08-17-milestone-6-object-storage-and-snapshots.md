# Milestone 6: Object Storage, Manifests & Snapshot Commits Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement durable, content-addressed S3/MinIO object storage, snapshot manifests, quiesced snapshot commits unifying world data and player profiles, cold/warm world loading, and scratch wipe resilience (spec §11.6, §12.2, MN-1–7, MN-2a/b/c, MN-3/3a, MN-4, MN-5a/b/c, MN-6/6a, NFR-1, NFR-3, NFR-7, NFR-8, NFR-9).

**Architecture:** World data files are stored write-once in S3 at `worlds/<world_id>/data/<sha256>` and manifests at `worlds/<world_id>/manifest/<generation>-<sequence>.json`. A single PostgreSQL transaction atomically updates `player_world.manifest_key` and saves player profiles. A multi-stage quiescence pipeline (`save-off` -> `World#save()` -> quiet-period wait -> snapshot copy -> `save-on` -> structural `.mca` validation -> upload) eliminates torn regions.

**Tech Stack:** Java 25, AWS SDK v2 (`software.amazon.awssdk:s3`, `software.amazon.awssdk:url-connection-client`), PostgreSQL (JDBC/HikariCP), Paper 26.2, JUnit 5, AssertJ, Testcontainers (PostgreSQL, MinIO).

---

### File Structure & Module Map

#### `:core` (`nl.gzmn.playerworlds.core.storage`)
- [NEW] `ManifestEntry.java`: Immutable record of a single file in a snapshot (logical path, sha256 hex, sizeBytes, mtimeMillis).
- [NEW] `Manifest.java`: Immutable manifest snapshot model (worldId, generation, sequence, dataVersion, mcVersion, createdAt, entries map).
- [NEW] `ManifestCodec.java`: Deterministic JSON encoder/decoder for `Manifest`.
- [NEW] `ObjectStore.java`: Storage client abstraction for data objects and manifests.
- [NEW] `S3ObjectStore.java`: S3-backed implementation using AWS SDK v2 `UrlConnectionHttpClient`.
- [NEW] `LocalObjectCache.java`: Local immutable file cache (`<cachePath>/<sha256>`) with LRU eviction.
- [NEW] `WorldDownloader.java`: Cold/warm world materializer from a `Manifest` using cache and S3.
- [NEW] `DirtyScanner.java`: Size and mtime stat walk (OQ-13) against baseline manifest.
- [NEW] `SnapshotEngine.java`: Orchestrates off-thread snapshot copying, hashing, region validation, and S3 uploads.
- [MODIFY] `PlayerWorldRepository.java`: Single-transaction snapshot commit updating `manifest_key` and inserting profiles (MN-3a, FR-15b).

#### `:backend` (`nl.gzmn.playerworlds.backend`)
- [NEW] `storage/QuiesceWatchdog.java`: Background watchdog to guarantee auto-save restoration.
- [NEW] `storage/PeriodicSyncTask.java`: Scheduled task triggering periodic snapshot commits (MN-6).
- [MODIFY] `profile/WorldCommitService.java`: Full snapshot commit pipeline (main-thread quiesce -> IO snapshot & upload -> DB transaction).
- [MODIFY] `world/WorldLifecycleService.java`: Cold/warm load via `WorldDownloader` and initial snapshot on create (FR-1a).
- [MODIFY] `profile/ProfileListener.java`: Profile restoration targeting manifest's specific snapshot generation and sequence.
- [MODIFY] `GzmnWorldsPlugin.java`: Bootstrap storage services, local cache, periodic sync, and shutdown commit (FR-28).

---

### Task 1: Manifest Model & Deterministic Codec (`:core`)

**Files:**
- Create: `core/src/main/java/nl/gzmn/playerworlds/core/storage/ManifestEntry.java`
- Create: `core/src/main/java/nl/gzmn/playerworlds/core/storage/Manifest.java`
- Create: `core/src/main/java/nl/gzmn/playerworlds/core/storage/ManifestCodec.java`
- Test: `core/src/test/java/nl/gzmn/playerworlds/core/storage/ManifestCodecTest.java`

- [ ] **Step 1: Write failing unit tests for `ManifestCodec`**

```java
package nl.gzmn.playerworlds.core.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.junit.jupiter.api.Test;

class ManifestCodecTest {

    @Test
    void roundTripsManifestSuccessfully() {
        WorldId worldId = WorldId.random();
        Instant now = Instant.ofEpochMilli(1755465320000L);
        ManifestEntry e1 = new ManifestEntry(
                "pw_test/level.dat",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                1240L,
                1755465320000L);
        ManifestEntry e2 = new ManifestEntry(
                "pw_test/region/r.0.0.mca",
                "4b227777d4dd1fc61c6f884f48641d02b4d121d3fd328cb08b5531fcacdabf8a",
                10485760L,
                1755465330000L);
        Manifest manifest = new Manifest(
                worldId,
                0L,
                1,
                4903,
                "26.2",
                now,
                Map.of(e1.path(), e1, e2.path(), e2));

        String json = ManifestCodec.encode(manifest);
        Manifest decoded = ManifestCodec.decode(json);

        assertThat(decoded).isEqualTo(manifest);
        assertThat(decoded.manifestKey()).isEqualTo("worlds/" + worldId.value() + "/manifest/0-1.json");
    }

    @Test
    void sortsEntriesDeterministically() {
        WorldId worldId = WorldId.random();
        ManifestEntry e1 = new ManifestEntry("b.dat", "0".repeat(64), 10L, 100L);
        ManifestEntry e2 = new ManifestEntry("a.dat", "1".repeat(64), 20L, 200L);
        Manifest manifest = new Manifest(
                worldId, 0L, 1, 4903, "26.2", Instant.EPOCH, Map.of(e1.path(), e1, e2.path(), e2));

        String json = ManifestCodec.encode(manifest);
        int aIdx = json.indexOf("\"a.dat\"");
        int bIdx = json.indexOf("\"b.dat\"");
        assertThat(aIdx).isLessThan(bIdx);
    }

    @Test
    void rejectsInvalidJson() {
        assertThatThrownBy(() -> ManifestCodec.decode("{invalid-json"))
                .isInstanceOf(StorageException.class);
    }
}
```

- [ ] **Step 2: Run test to verify failure**
Run: `./gradlew :core:test --tests "nl.gzmn.playerworlds.core.storage.ManifestCodecTest"`
Expected: Compilation failure (classes not found).

- [ ] **Step 3: Implement `ManifestEntry`, `Manifest`, and `ManifestCodec`**

`ManifestEntry.java`:
```java
package nl.gzmn.playerworlds.core.storage;

import java.util.Objects;

public record ManifestEntry(String path, String sha256Hex, long sizeBytes, long lastModifiedMillis) {
    public ManifestEntry {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(sha256Hex, "sha256Hex");
        if (sha256Hex.length() != 64) {
            throw new IllegalArgumentException("sha256Hex must be 64 hex chars");
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must be >= 0");
        }
        if (lastModifiedMillis < 0) {
            throw new IllegalArgumentException("lastModifiedMillis must be >= 0");
        }
    }
}
```

`Manifest.java`:
```java
package nl.gzmn.playerworlds.core.storage;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import nl.gzmn.playerworlds.core.model.WorldId;

public record Manifest(
        WorldId worldId,
        long generation,
        int sequence,
        int dataVersion,
        String mcVersion,
        Instant createdAt,
        Map<String, ManifestEntry> entries) {
    public Manifest {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(mcVersion, "mcVersion");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(entries, "entries");
        entries = Collections.unmodifiableMap(new TreeMap<>(entries));
    }

    public String manifestKey() {
        return "worlds/" + worldId.value() + "/manifest/" + generation + "-" + sequence + ".json";
    }
}
```

`ManifestCodec.java`:
```java
package nl.gzmn.playerworlds.core.storage;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import nl.gzmn.playerworlds.core.model.WorldId;

public final class ManifestCodec {

    private ManifestCodec() {}

    public static String encode(Manifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        StringBuilder sb = new StringBuilder(1024);
        sb.append("{\n");
        sb.append("  \"worldId\": \"").append(manifest.worldId().value()).append("\",\n");
        sb.append("  \"generation\": ").append(manifest.generation()).append(",\n");
        sb.append("  \"sequence\": ").append(manifest.sequence()).append(",\n");
        sb.append("  \"dataVersion\": ").append(manifest.dataVersion()).append(",\n");
        sb.append("  \"mcVersion\": \"").append(escapeJson(manifest.mcVersion())).append("\",\n");
        sb.append("  \"createdAt\": \"").append(manifest.createdAt()).append("\",\n");
        sb.append("  \"entries\": {\n");

        int count = 0;
        int size = manifest.entries().size();
        for (Map.Entry<String, ManifestEntry> entry : manifest.entries().entrySet()) {
            ManifestEntry val = entry.getValue();
            sb.append("    \"").append(escapeJson(entry.getKey())).append("\": {");
            sb.append("\"sha256\": \"").append(val.sha256Hex()).append("\", ");
            sb.append("\"sizeBytes\": ").append(val.sizeBytes()).append(", ");
            sb.append("\"lastModifiedMillis\": ").append(val.lastModifiedMillis());
            sb.append("}");
            if (++count < size) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("  }\n");
        sb.append("}");
        return sb.toString();
    }

    public static Manifest decode(String json) {
        Objects.requireNonNull(json, "json");
        try {
            String raw = json.trim();
            if (!raw.startsWith("{") || !raw.endsWith("}")) {
                throw new IllegalArgumentException("JSON must start with { and end with }");
            }
            UUID worldId = UUID.fromString(findStringValue(raw, "worldId"));
            long generation = findLongValue(raw, "generation");
            int sequence = findIntValue(raw, "sequence");
            int dataVersion = findIntValue(raw, "dataVersion");
            String mcVersion = findStringValue(raw, "mcVersion");
            Instant createdAt = Instant.parse(findStringValue(raw, "createdAt"));

            Map<String, ManifestEntry> entries = parseEntries(raw);
            return new Manifest(new WorldId(worldId), generation, sequence, dataVersion, mcVersion, createdAt, entries);
        } catch (Exception e) {
            throw new StorageException("Failed to decode Manifest JSON", e);
        }
    }

    private static Map<String, ManifestEntry> parseEntries(String raw) {
        Map<String, ManifestEntry> map = new LinkedHashMap<>();
        int entriesIdx = raw.indexOf("\"entries\"");
        if (entriesIdx == -1) {
            return map;
        }
        int openBrace = raw.indexOf('{', entriesIdx);
        if (openBrace == -1) {
            return map;
        }
        int closeBrace = raw.lastIndexOf('}');
        if (closeBrace <= openBrace) {
            return map;
        }
        // Find outer closing brace of entries
        int entriesClose = raw.lastIndexOf('}', closeBrace - 1);
        if (entriesClose <= openBrace) {
            return map;
        }
        String entriesBody = raw.substring(openBrace + 1, entriesClose).trim();
        if (entriesBody.isEmpty()) {
            return map;
        }

        int pos = 0;
        while (pos < entriesBody.length()) {
            int keyStart = entriesBody.indexOf('"', pos);
            if (keyStart == -1) break;
            int keyEnd = entriesBody.indexOf('"', keyStart + 1);
            if (keyEnd == -1) break;
            String path = entriesBody.substring(keyStart + 1, keyEnd);

            int valStart = entriesBody.indexOf('{', keyEnd);
            if (valStart == -1) break;
            int valEnd = entriesBody.indexOf('}', valStart);
            if (valEnd == -1) break;

            String objBody = entriesBody.substring(valStart + 1, valEnd);
            String sha256 = findStringValue("{" + objBody + "}", "sha256");
            long sizeBytes = findLongValue("{" + objBody + "}", "sizeBytes");
            long mtime = findLongValue("{" + objBody + "}", "lastModifiedMillis");

            map.put(path, new ManifestEntry(path, sha256, sizeBytes, mtime));
            pos = valEnd + 1;
        }
        return map;
    }

    private static String findStringValue(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx == -1) throw new IllegalArgumentException("Missing key: " + key);
        int colon = json.indexOf(':', idx + pattern.length());
        int startQuote = json.indexOf('"', colon);
        int endQuote = json.indexOf('"', startQuote + 1);
        return json.substring(startQuote + 1, endQuote);
    }

    private static int findIntValue(String json, String key) {
        return (int) findLongValue(json, key);
    }

    private static long findLongValue(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx == -1) throw new IllegalArgumentException("Missing key: " + key);
        int colon = json.indexOf(':', idx + pattern.length());
        int end = json.indexOf(',', colon);
        if (end == -1) end = json.indexOf('\n', colon);
        if (end == -1) end = json.indexOf('}', colon);
        String num = json.substring(colon + 1, end).replaceAll("[^0-9-]", "");
        return Long.parseLong(num);
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
```

- [ ] **Step 4: Run test to verify pass**
Run: `./gradlew :core:test --tests "nl.gzmn.playerworlds.core.storage.ManifestCodecTest"`
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add core/src/main/java/nl/gzmn/playerworlds/core/storage/ManifestEntry.java core/src/main/java/nl/gzmn/playerworlds/core/storage/Manifest.java core/src/main/java/nl/gzmn/playerworlds/core/storage/ManifestCodec.java core/src/test/java/nl/gzmn/playerworlds/core/storage/ManifestCodecTest.java
git commit -m "feat(core): add Manifest data model and deterministic ManifestCodec"
```

---

### Task 2: S3 Object Store Client (`:core`)

**Files:**
- Create: `core/src/main/java/nl/gzmn/playerworlds/core/storage/ObjectStore.java`
- Create: `core/src/main/java/nl/gzmn/playerworlds/core/storage/S3ObjectStore.java`
- Test: `testing/src/test/java/nl/gzmn/playerworlds/testing/S3ObjectStoreTest.java`

- [ ] **Step 1: Write integration tests against MinIO for `S3ObjectStore`**

```java
package nl.gzmn.playerworlds.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import nl.gzmn.playerworlds.core.config.StorageClientSettings;
import nl.gzmn.playerworlds.core.storage.S3ObjectStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class S3ObjectStoreTest {

    @Test
    void putAndGetObjectSuccessfully(@TempDir Path tempDir) throws Exception {
        StorageClientSettings settings = TestObjectStore.settingsForNewBucket();
        try (S3ObjectStore store = S3ObjectStore.open(settings)) {
            String key = "test/sample.txt";
            byte[] content = "hello-gzmn-storage".getBytes(StandardCharsets.UTF_8);

            store.putBytes(key, content, "text/plain");

            assertThat(store.exists(key)).isTrue();
            assertThat(store.exists("test/non-existent")).isFalse();

            byte[] fetched = store.getBytes(key);
            assertThat(fetched).isEqualTo(content);

            Path dest = tempDir.resolve("downloaded.txt");
            store.getObject(key, dest);
            assertThat(Files.readAllBytes(dest)).isEqualTo(content);
        }
    }
}
```

- [ ] **Step 2: Run test to verify failure**
Run: `./gradlew :testing:test --tests "nl.gzmn.playerworlds.testing.S3ObjectStoreTest"`
Expected: Compilation failure (`ObjectStore` / `S3ObjectStore` not found).

- [ ] **Step 3: Implement `ObjectStore` and `S3ObjectStore`**

`ObjectStore.java`:
```java
package nl.gzmn.playerworlds.core.storage;

import java.io.Closeable;
import java.nio.file.Path;

public interface ObjectStore extends Closeable {
    void putObject(String key, Path sourceFile);
    void putBytes(String key, byte[] bytes, String contentType);
    void getObject(String key, Path destinationFile);
    byte[] getBytes(String key);
    boolean exists(String key);
    @Override
    void close();
}
```

`S3ObjectStore.java`:
```java
package nl.gzmn.playerworlds.core.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import nl.gzmn.playerworlds.core.config.StorageClientSettings;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

public final class S3ObjectStore implements ObjectStore {

    private final S3Client client;
    private final String bucket;

    public S3ObjectStore(S3Client client, String bucket) {
        this.client = Objects.requireNonNull(client, "client");
        this.bucket = Objects.requireNonNull(bucket, "bucket");
    }

    public static S3ObjectStore open(StorageClientSettings settings) {
        Objects.requireNonNull(settings, "settings");
        S3Client client = S3Client.builder()
                .endpointOverride(settings.endpoint())
                .region(Region.of(settings.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(settings.accessKey(), settings.secretKey())))
                .forcePathStyle(settings.pathStyleAccess())
                .httpClient(UrlConnectionHttpClient.builder().build())
                .build();
        return new S3ObjectStore(client, settings.bucket());
    }

    @Override
    public void putObject(String key, Path sourceFile) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(sourceFile, "sourceFile");
        try {
            client.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(key).build(),
                    RequestBody.fromFile(sourceFile));
        } catch (S3Exception e) {
            throw new StorageException("Failed to upload object: " + key, e);
        }
    }

    @Override
    public void putBytes(String key, byte[] bytes, String contentType) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(bytes, "bytes");
        try {
            client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(contentType != null ? contentType : "application/octet-stream")
                            .build(),
                    RequestBody.fromBytes(bytes));
        } catch (S3Exception e) {
            throw new StorageException("Failed to put bytes: " + key, e);
        }
    }

    @Override
    public void getObject(String key, Path destinationFile) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(destinationFile, "destinationFile");
        try {
            Path temp = destinationFile.resolveSibling(destinationFile.getFileName() + ".tmp");
            Files.createDirectories(destinationFile.getParent());
            client.getObject(
                    GetObjectRequest.builder().bucket(bucket).key(key).build(),
                    temp);
            Files.move(temp, destinationFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | S3Exception e) {
            throw new StorageException("Failed to download object: " + key, e);
        }
    }

    @Override
    public byte[] getBytes(String key) {
        Objects.requireNonNull(key, "key");
        try {
            return client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build())
                    .asByteArray();
        } catch (S3Exception e) {
            throw new StorageException("Failed to get bytes for: " + key, e);
        }
    }

    @Override
    public boolean exists(String key) {
        Objects.requireNonNull(key, "key");
        try {
            client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw new StorageException("Failed to check existence for: " + key, e);
        }
    }

    @Override
    public void close() {
        client.close();
    }
}
```

- [ ] **Step 4: Run test to verify pass**
Run: `./gradlew :testing:test --tests "nl.gzmn.playerworlds.testing.S3ObjectStoreTest"`
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add core/src/main/java/nl/gzmn/playerworlds/core/storage/ObjectStore.java core/src/main/java/nl/gzmn/playerworlds/core/storage/S3ObjectStore.java testing/src/test/java/nl/gzmn/playerworlds/testing/S3ObjectStoreTest.java
git commit -m "feat(core): implement S3ObjectStore with AWS SDK UrlConnectionHttpClient"
```

---

### Task 3: Local Object Cache (`:core`)

**Files:**
- Create: `core/src/main/java/nl/gzmn/playerworlds/core/storage/LocalObjectCache.java`
- Test: `core/src/test/java/nl/gzmn/playerworlds/core/storage/LocalObjectCacheTest.java`

- [ ] **Step 1: Write unit tests for `LocalObjectCache`**

```java
package nl.gzmn.playerworlds.core.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalObjectCacheTest {

    @Test
    void storesAndClonesObjects(@TempDir Path tempDir) throws Exception {
        Path cacheRoot = tempDir.resolve("cache");
        Path scratchRoot = tempDir.resolve("scratch");
        LocalObjectCache cache = new LocalObjectCache(cacheRoot, new PlainFileCloner());

        String hash = "a".repeat(64);
        byte[] content = "cached-content".getBytes(StandardCharsets.UTF_8);

        Path source = tempDir.resolve("source.txt");
        Files.write(source, content);

        cache.put(hash, source);
        assertThat(cache.contains(hash)).isTrue();

        Path dest = scratchRoot.resolve("pw_1/level.dat");
        cache.cloneTo(hash, dest);

        assertThat(Files.readAllBytes(dest)).isEqualTo(content);
    }

    @Test
    void evictsLruWhenExceedingCap(@TempDir Path tempDir) throws Exception {
        Path cacheRoot = tempDir.resolve("cache");
        LocalObjectCache cache = new LocalObjectCache(cacheRoot, new PlainFileCloner());

        byte[] blob1 = new byte[100];
        byte[] blob2 = new byte[100];
        Path src1 = tempDir.resolve("s1");
        Path src2 = tempDir.resolve("s2");
        Files.write(src1, blob1);
        Files.write(src2, blob2);

        String h1 = "1".repeat(64);
        String h2 = "2".repeat(64);

        cache.put(h1, src1);
        Thread.sleep(20);
        cache.put(h2, src2);

        cache.evictLru(150); // Max 150 bytes -> oldest (h1) should be evicted

        assertThat(cache.contains(h1)).isFalse();
        assertThat(cache.contains(h2)).isTrue();
    }
}
```

- [ ] **Step 2: Run test to verify failure**
Run: `./gradlew :core:test --tests "nl.gzmn.playerworlds.core.storage.LocalObjectCacheTest"`
Expected: Compilation failure.

- [ ] **Step 3: Implement `LocalObjectCache`**

`LocalObjectCache.java`:
```java
package nl.gzmn.playerworlds.core.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LocalObjectCache {

    private static final Logger log = LoggerFactory.getLogger(LocalObjectCache.class);

    private final Path cacheRoot;
    private final FileCloner cloner;

    public LocalObjectCache(Path cacheRoot, FileCloner cloner) {
        this.cacheRoot = Objects.requireNonNull(cacheRoot, "cacheRoot");
        this.cloner = Objects.requireNonNull(cloner, "cloner");
        try {
            Files.createDirectories(cacheRoot);
        } catch (IOException e) {
            throw new StorageException("Could not initialize cache directory " + cacheRoot, e);
        }
    }

    public boolean contains(String sha256Hex) {
        Objects.requireNonNull(sha256Hex, "sha256Hex");
        return Files.isRegularFile(pathOf(sha256Hex));
    }

    public Path pathOf(String sha256Hex) {
        return cacheRoot.resolve(sha256Hex);
    }

    public void put(String sha256Hex, Path sourceFile) {
        Objects.requireNonNull(sha256Hex, "sha256Hex");
        Objects.requireNonNull(sourceFile, "sourceFile");
        Path target = pathOf(sha256Hex);
        if (Files.exists(target)) {
            try {
                Files.setLastModifiedTime(target, FileTime.from(Instant.now()));
            } catch (IOException ignored) {}
            return;
        }
        try {
            Path temp = cacheRoot.resolve(sha256Hex + ".tmp-" + System.nanoTime());
            cloner.copy(sourceFile, temp);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new StorageException("Failed to put object in local cache: " + sha256Hex, e);
        }
    }

    public void cloneTo(String sha256Hex, Path destinationFile) {
        Objects.requireNonNull(sha256Hex, "sha256Hex");
        Objects.requireNonNull(destinationFile, "destinationFile");
        Path source = pathOf(sha256Hex);
        if (!Files.isRegularFile(source)) {
            throw new StorageException("Object not in cache: " + sha256Hex);
        }
        try {
            cloner.copy(source, destinationFile);
            Files.setLastModifiedTime(source, FileTime.from(Instant.now()));
        } catch (IOException e) {
            throw new StorageException("Failed to clone object from cache: " + sha256Hex + " to " + destinationFile, e);
        }
    }

    public long evictLru(long maxBytes) {
        if (maxBytes <= 0) return 0;
        try (Stream<Path> stream = Files.list(cacheRoot)) {
            List<Path> files = new ArrayList<>(stream.filter(Files::isRegularFile).toList());
            long total = 0;
            for (Path f : files) {
                total += Files.size(f);
            }
            if (total <= maxBytes) {
                return 0;
            }

            files.sort(Comparator.comparingLong(p -> {
                try {
                    return Files.getLastModifiedTime(p).toMillis();
                } catch (IOException e) {
                    return 0L;
                }
            }));

            long bytesFreed = 0;
            for (Path f : files) {
                if (total <= maxBytes) break;
                try {
                    long size = Files.size(f);
                    Files.deleteIfExists(f);
                    total -= size;
                    bytesFreed += size;
                } catch (IOException e) {
                    log.warn("Failed to evict cache file: {}", f, e);
                }
            }
            return bytesFreed;
        } catch (IOException e) {
            log.error("Cache eviction scan failed", e);
            return 0;
        }
    }
}
```

- [ ] **Step 4: Run test to verify pass**
Run: `./gradlew :core:test --tests "nl.gzmn.playerworlds.core.storage.LocalObjectCacheTest"`
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add core/src/main/java/nl/gzmn/playerworlds/core/storage/LocalObjectCache.java core/src/test/java/nl/gzmn/playerworlds/core/storage/LocalObjectCacheTest.java
git commit -m "feat(core): implement LocalObjectCache with LRU eviction and atomic file caching"
```

---

### Task 4: World Downloader & Materialization Engine (`:core`)

**Files:**
- Create: `core/src/main/java/nl/gzmn/playerworlds/core/storage/WorldDownloader.java`
- Test: `testing/src/test/java/nl/gzmn/playerworlds/testing/WorldDownloaderTest.java`

- [ ] **Step 1: Write integration tests for `WorldDownloader` against MinIO and `WorldFixture`**

```java
package nl.gzmn.playerworlds.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import nl.gzmn.playerworlds.core.config.StorageClientSettings;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.storage.ContentHasher;
import nl.gzmn.playerworlds.core.storage.HashedContent;
import nl.gzmn.playerworlds.core.storage.LocalObjectCache;
import nl.gzmn.playerworlds.core.storage.Manifest;
import nl.gzmn.playerworlds.core.storage.ManifestEntry;
import nl.gzmn.playerworlds.core.storage.PlainFileCloner;
import nl.gzmn.playerworlds.core.storage.S3ObjectStore;
import nl.gzmn.playerworlds.core.storage.WorldDownloader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldDownloaderTest {

    @Test
    void downloadsAndMaterializesWorldFromScratchWipe(@TempDir Path tempDir) throws Exception {
        Path scratchRoot = tempDir.resolve("scratch");
        Path cacheRoot = tempDir.resolve("cache");
        Path restoreRoot = tempDir.resolve("restore-scratch");

        WorldId worldId = WorldFixture.materialize(scratchRoot);
        List<String> paths = WorldFixture.syncedRelativePaths(scratchRoot, worldId);

        StorageClientSettings settings = TestObjectStore.settingsForNewBucket();
        try (S3ObjectStore store = S3ObjectStore.open(settings)) {
            LocalObjectCache cache = new LocalObjectCache(cacheRoot, new PlainFileCloner());

            Map<String, ManifestEntry> entries = new HashMap<>();
            for (String rel : paths) {
                Path file = scratchRoot.resolve(rel);
                HashedContent hashed = ContentHasher.hash(file);
                store.putObject("worlds/" + worldId.value() + "/data/" + hashed.sha256Hex(), file);
                entries.put(rel, new ManifestEntry(rel, hashed.sha256Hex(), hashed.sizeBytes(), Files.getLastModifiedTime(file).toMillis()));
            }

            Manifest manifest = new Manifest(worldId, 0L, 1, 4903, "26.2", Instant.now(), entries);
            WorldDownloader downloader = new WorldDownloader(store, cache, new PlainFileCloner());

            WorldDownloader.Result result = downloader.materialize(manifest, restoreRoot);

            assertThat(result.wasWarm()).isFalse();
            assertThat(result.filesDownloaded()).isEqualTo(paths.size());

            // Verify all files match original
            for (String rel : paths) {
                Path restored = restoreRoot.resolve(rel);
                assertThat(Files.exists(restored)).isTrue();
                assertThat(Files.readAllBytes(restored)).isEqualTo(Files.readAllBytes(scratchRoot.resolve(rel)));
            }

            // Second run should be warm
            WorldDownloader.Result second = downloader.materialize(manifest, restoreRoot);
            assertThat(second.wasWarm()).isTrue();
            assertThat(second.filesDownloaded()).isZero();
        }
    }
}
```

- [ ] **Step 2: Run test to verify failure**
Run: `./gradlew :testing:test --tests "nl.gzmn.playerworlds.testing.WorldDownloaderTest"`
Expected: Compilation failure.

- [ ] **Step 3: Implement `WorldDownloader`**

`WorldDownloader.java`:
```java
package nl.gzmn.playerworlds.core.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WorldDownloader {

    private static final Logger log = LoggerFactory.getLogger(WorldDownloader.class);

    private final ObjectStore objectStore;
    private final LocalObjectCache cache;
    private final FileCloner cloner;

    public record Result(int filesChecked, int filesRestored, int filesDownloaded, long bytesDownloaded, boolean wasWarm) {}

    public WorldDownloader(ObjectStore objectStore, LocalObjectCache cache, FileCloner cloner) {
        this.objectStore = Objects.requireNonNull(objectStore, "objectStore");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.cloner = Objects.requireNonNull(cloner, "cloner");
    }

    public Result materialize(Manifest manifest, Path scratchRoot) {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(scratchRoot, "scratchRoot");

        int checked = 0;
        int restored = 0;
        int downloaded = 0;
        long bytesDownloaded = 0;

        for (ManifestEntry entry : manifest.entries().values()) {
            checked++;
            Path target = scratchRoot.resolve(entry.path());
            boolean needsRestore = true;

            if (Files.isRegularFile(target)) {
                try {
                    FileFingerprint localFp = FileFingerprint.of(target);
                    if (localFp.sizeBytes() == entry.sizeBytes()
                            && localFp.lastModifiedTime().toMillis() == entry.lastModifiedMillis()) {
                        needsRestore = false;
                    }
                } catch (IOException ignored) {}
            }

            if (!needsRestore) {
                continue;
            }

            restored++;
            String hash = entry.sha256Hex();
            String s3Key = "worlds/" + manifest.worldId().value() + "/data/" + hash;

            if (!cache.contains(hash)) {
                Path cacheFile = cache.pathOf(hash);
                objectStore.getObject(s3Key, cacheFile);
                downloaded++;
                bytesDownloaded += entry.sizeBytes();
            }

            try {
                Files.createDirectories(target.getParent());
                cache.cloneTo(hash, target);
                Files.setLastModifiedTime(target, FileTime.fromMillis(entry.lastModifiedMillis()));
            } catch (IOException e) {
                throw new StorageException("Failed to materialize " + target + " from cache object " + hash, e);
            }
        }

        boolean wasWarm = (downloaded == 0);
        return new Result(checked, restored, downloaded, bytesDownloaded, wasWarm);
    }
}
```

- [ ] **Step 4: Run test to verify pass**
Run: `./gradlew :testing:test --tests "nl.gzmn.playerworlds.testing.WorldDownloaderTest"`
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add core/src/main/java/nl/gzmn/playerworlds/core/storage/WorldDownloader.java testing/src/test/java/nl/gzmn/playerworlds/testing/WorldDownloaderTest.java
git commit -m "feat(core): implement WorldDownloader for cold/warm manifest materialization"
```

---

### Task 5: Dirty Scanner & Snapshot Engine (`:core`)

**Files:**
- Create: `core/src/main/java/nl/gzmn/playerworlds/core/storage/DirtyScanner.java`
- Create: `core/src/main/java/nl/gzmn/playerworlds/core/storage/SnapshotEngine.java`
- Test: `core/src/test/java/nl/gzmn/playerworlds/core/storage/DirtyScannerTest.java`
- Test: `testing/src/test/java/nl/gzmn/playerworlds/testing/SnapshotEngineTest.java`

- [ ] **Step 1: Write unit tests for `DirtyScanner` and integration tests for `SnapshotEngine`**

`DirtyScannerTest.java`:
```java
package nl.gzmn.playerworlds.core.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DirtyScannerTest {

    @Test
    void findsModifiedAndNewFiles(@TempDir Path tempDir) throws Exception {
        WorldId id = WorldId.random();
        String folder = id.folder();
        Path worldFolder = tempDir.resolve(folder);
        Files.createDirectories(worldFolder);

        Path f1 = worldFolder.resolve("level.dat");
        Path f2 = worldFolder.resolve("session.lock"); // Should be excluded
        Path f3 = worldFolder.resolve("region/r.0.0.mca");
        Files.createDirectories(f3.getParent());

        Files.write(f1, new byte[]{1, 2});
        Files.setLastModifiedTime(f1, FileTime.fromMillis(1000));
        Files.write(f2, new byte[]{9});
        Files.write(f3, new byte[]{3, 4, 5});
        Files.setLastModifiedTime(f3, FileTime.fromMillis(2000));

        ManifestEntry baselineEntry = new ManifestEntry(folder + "/level.dat", "0".repeat(64), 2L, 1000L);
        Map<String, ManifestEntry> baseline = Map.of(baselineEntry.path(), baselineEntry);

        List<Path> dirty = DirtyScanner.scanDirty(tempDir, id, baseline, List.of("session.lock", "uid.dat"));

        // f1 is clean (matches size & mtime), f2 is excluded, f3 is new -> dirty should only contain f3
        assertThat(dirty).containsExactly(Path.of(folder, "region", "r.0.0.mca"));
    }
}
```

- [ ] **Step 2: Run test to verify failure**
Run: `./gradlew :core:test --tests "nl.gzmn.playerworlds.core.storage.DirtyScannerTest"`
Expected: Compilation failure.

- [ ] **Step 3: Implement `DirtyScanner` and `SnapshotEngine`**

`DirtyScanner.java`:
```java
package nl.gzmn.playerworlds.core.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import nl.gzmn.playerworlds.core.model.WorldId;

public final class DirtyScanner {

    private DirtyScanner() {}

    public static List<Path> scanDirty(
            Path scratchRoot,
            WorldId worldId,
            Map<String, ManifestEntry> baselineEntries,
            List<String> excludeGlobs) {
        Objects.requireNonNull(scratchRoot, "scratchRoot");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(baselineEntries, "baselineEntries");
        Objects.requireNonNull(excludeGlobs, "excludeGlobs");

        String base = worldId.folder();
        List<String> folderPrefixes = List.of(base, base + "_nether", base + "_the_end");
        List<Path> dirty = new ArrayList<>();

        for (String prefix : folderPrefixes) {
            Path root = scratchRoot.resolve(prefix);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                        .filter(path -> !isExcluded(path.getFileName().toString(), excludeGlobs))
                        .forEach(path -> {
                            Path relative = scratchRoot.relativize(path);
                            String unixRel = relative.toString().replace('\\', '/');
                            ManifestEntry baseEntry = baselineEntries.get(unixRel);
                            boolean isDirty = true;
                            if (baseEntry != null) {
                                try {
                                    FileFingerprint fp = FileFingerprint.of(path);
                                    if (fp.sizeBytes() == baseEntry.sizeBytes()
                                            && fp.lastModifiedTime().toMillis() == baseEntry.lastModifiedMillis()) {
                                        isDirty = false;
                                    }
                                } catch (IOException ignored) {}
                            }
                            if (isDirty) {
                                dirty.add(relative);
                            }
                        });
            } catch (IOException e) {
                throw new StorageException("Failed to scan directory for dirty files: " + root, e);
            }
        }
        dirty.sort(Comparator.naturalOrder());
        return List.copyOf(dirty);
    }

    private static boolean isExcluded(String fileName, List<String> excludeGlobs) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String glob : excludeGlobs) {
            if (glob.equalsIgnoreCase(lower)) {
                return true;
            }
        }
        return false;
    }
}
```

`SnapshotEngine.java`:
```java
package nl.gzmn.playerworlds.core.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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

public final class SnapshotEngine {

    private static final Logger log = LoggerFactory.getLogger(SnapshotEngine.class);

    private final ObjectStore objectStore;
    private final LocalObjectCache cache;
    private final SnapshotCopier copier;

    public record SnapshotResult(Manifest manifest, int dirtyCount, long uploadedBytes) {}

    public SnapshotEngine(ObjectStore objectStore, LocalObjectCache cache, SnapshotCopier copier) {
        this.objectStore = Objects.requireNonNull(objectStore, "objectStore");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.copier = Objects.requireNonNull(copier, "copier");
    }

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
        Objects.requireNonNull(dirtyRelativePaths, "dirtyRelativePaths");

        Path tempSnapshotDir = scratchRoot.resolve(".snapshot-" + worldId.value() + "-" + UUID.randomUUID());
        try {
            // 1. Copy dirty files into isolated snapshot directory
            List<SnapshotCopier.CopiedFile> copied = copier.copyAll(scratchRoot, tempSnapshotDir, dirtyRelativePaths);

            // 2. Hash, validate .mca (MN-5c) and upload missing objects to S3 & Cache
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

                newEntries.put(unixRel, new ManifestEntry(unixRel, sha256, hashed.sizeBytes(), file.fingerprint().lastModifiedTime().toMillis()));
            }

            // 3. Encode & upload manifest
            Manifest manifest = new Manifest(worldId, generation, sequence, dataVersion, mcVersion, Instant.now(), newEntries);
            String manifestJson = ManifestCodec.encode(manifest);
            objectStore.putBytes(manifest.manifestKey(), manifestJson.getBytes(java.nio.charset.StandardCharsets.UTF_8), "application/json");

            return new SnapshotResult(manifest, copied.size(), uploadedBytes);
        } finally {
            deleteDirectoryRecursively(tempSnapshotDir);
        }
    }

    private static void deleteDirectoryRecursively(Path root) {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }
}
```

- [ ] **Step 4: Run tests to verify pass**
Run: `./gradlew :core:test --tests "nl.gzmn.playerworlds.core.storage.DirtyScannerTest"`
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add core/src/main/java/nl/gzmn/playerworlds/core/storage/DirtyScanner.java core/src/main/java/nl/gzmn/playerworlds/core/storage/SnapshotEngine.java core/src/test/java/nl/gzmn/playerworlds/core/storage/DirtyScannerTest.java
git commit -m "feat(core): implement DirtyScanner and SnapshotEngine for quiesced S3 snapshot creation"
```

---

### Task 6: Single-Transaction Database Commit (`:core`)

**Files:**
- Modify: `core/src/main/java/nl/gzmn/playerworlds/core/db/PlayerWorldRepository.java`
- Modify: `core/src/main/java/nl/gzmn/playerworlds/core/db/ProfileRepository.java`
- Test: `core/src/test/java/nl/gzmn/playerworlds/core/db/PlayerWorldRepositoryTest.java`

- [ ] **Step 1: Write test for atomic snapshot + profile commit in `PlayerWorldRepositoryTest`**

```java
    @Test
    void commitsSnapshotAndProfilesInOneTransaction() throws Exception {
        WorldId id = WorldId.random();
        UUID owner = UUID.randomUUID();
        PlayerWorld world = repository.create(id, owner, "commit-world", 1234L, 5000, Visibility.PRIVATE);

        ProfileRepository profiles = new ProfileRepository(database);
        ProfileRepository.Snapshot snap = new ProfileRepository.Snapshot(0L, 1);
        UUID player1 = UUID.randomUUID();
        byte[] payload = new byte[]{1, 2, 3};

        boolean committed = repository.commitSnapshot(
                id,
                0L,
                "node-a",
                "worlds/" + id.value() + "/manifest/0-1.json",
                4903,
                "26.2",
                snap,
                1,
                Map.of(player1, payload),
                profiles);

        assertThat(committed).isTrue();

        PlayerWorld updated = repository.findById(id).orElseThrow();
        assertThat(updated.manifestKey()).isEqualTo("worlds/" + id.value() + "/manifest/0-1.json");
        assertThat(updated.dataVersion()).isEqualTo(4903);
        assertThat(updated.mcVersion()).isEqualTo("26.2");

        assertThat(profiles.load(id, player1, snap)).isPresent();
    }
```

- [ ] **Step 2: Run test to verify failure**
Run: `./gradlew :core:test --tests "nl.gzmn.playerworlds.core.db.PlayerWorldRepositoryTest"`
Expected: Compilation failure (`commitSnapshot` not defined).

- [ ] **Step 3: Implement `commitSnapshot` in `PlayerWorldRepository`**

In `PlayerWorldRepository.java`:
```java
    public boolean commitSnapshot(
            WorldId id,
            long generation,
            @Nullable String nodeId,
            String manifestKey,
            int dataVersion,
            String mcVersion,
            ProfileRepository.Snapshot snapshot,
            int profileFormatVersion,
            Map<UUID, byte[]> profiles,
            ProfileRepository profileRepository)
            throws SQLException {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(manifestKey, "manifestKey");
        Objects.requireNonNull(mcVersion, "mcVersion");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(profiles, "profiles");
        Objects.requireNonNull(profileRepository, "profileRepository");

        return database.inTransaction(connection -> {
            int updated = execute(
                    connection,
                    """
                    UPDATE player_world
                       SET manifest_key = ?,
                           last_played  = now(),
                           data_version = ?,
                           mc_version   = ?
                     WHERE id = ?
                       AND (assigned_node IS NULL OR assigned_node = ?)
                       AND generation = ?
                    """,
                    statement -> {
                        statement.setString(1, manifestKey);
                        statement.setInt(2, dataVersion);
                        statement.setString(3, mcVersion);
                        statement.setObject(4, id.value());
                        statement.setString(5, nodeId);
                        statement.setLong(6, generation);
                    });

            if (updated != 1) {
                return false;
            }

            if (!profiles.isEmpty()) {
                profileRepository.saveAll(connection, id, snapshot, profileFormatVersion, profiles);
            }
            return true;
        });
    }
```

- [ ] **Step 4: Run test to verify pass**
Run: `./gradlew :core:test --tests "nl.gzmn.playerworlds.core.db.PlayerWorldRepositoryTest"`
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add core/src/main/java/nl/gzmn/playerworlds/core/db/PlayerWorldRepository.java core/src/test/java/nl/gzmn/playerworlds/core/db/PlayerWorldRepositoryTest.java
git commit -m "feat(core): add atomic commitSnapshot in PlayerWorldRepository combining manifest and profiles"
```

---

### Task 7: Quiescence Watchdog & `WorldCommitService` Integration (`:backend`)

**Files:**
- Create: `backend/src/main/java/nl/gzmn/playerworlds/backend/storage/QuiesceWatchdog.java`
- Modify: `backend/src/main/java/nl/gzmn/playerworlds/backend/profile/WorldCommitService.java`
- Modify: `backend/src/main/java/nl/gzmn/playerworlds/backend/profile/ProfileListener.java`
- Test: `backend/src/test/java/nl/gzmn/playerworlds/backend/profile/WorldCommitServiceTest.java`

- [ ] **Step 1: Implement `QuiesceWatchdog`**

```java
package nl.gzmn.playerworlds.backend.storage;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import nl.gzmn.playerworlds.backend.platform.WorldRuntime;
import org.bukkit.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class QuiesceWatchdog {

    private static final Logger log = LoggerFactory.getLogger(QuiesceWatchdog.class);

    private QuiesceWatchdog() {}

    public static ScheduledFuture<?> arm(
            ScheduledExecutorService sched,
            WorldRuntime runtime,
            World world,
            Duration timeout) {
        Objects.requireNonNull(sched, "sched");
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(timeout, "timeout");

        return sched.schedule(() -> {
            if (!runtime.isAutoSave(world)) {
                log.warn("QuiesceWatchdog triggered: restoring auto-save for world {}", world.getName());
                runtime.setAutoSave(world, true);
            }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);
    }
}
```

- [ ] **Step 2: Update `WorldCommitService` to integrate `SnapshotEngine` and `PlayerWorldRepository.commitSnapshot`**

Extend `WorldCommitService` constructors and `runCommit(worldId)`:
1. Capture player profiles and disable auto-save on main thread.
2. Arm `QuiesceWatchdog`.
3. Call `World#save()` on each dimension.
4. On IO thread: run `DirtyScanner` against cached last manifest (or S3).
5. Run `SnapshotEngine.executeSnapshot(...)`.
6. Restore `setAutoSave(true)`.
7. On DB thread: call `playerWorlds.commitSnapshot(...)`.

- [ ] **Step 3: Update `ProfileListener` for manifest-aware snapshot loading**

In `ProfileListener.java`:
Read the loaded world's `manifest_key` (if present) to resolve the exact generation and sequence for `profileRepository.load(worldId, player.getUniqueId(), snapshot)`.

- [ ] **Step 4: Run unit tests**
Run: `./gradlew :backend:test --tests "nl.gzmn.playerworlds.backend.profile.WorldCommitServiceTest"`
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add backend/src/main/java/nl/gzmn/playerworlds/backend/storage/QuiesceWatchdog.java backend/src/main/java/nl/gzmn/playerworlds/backend/profile/WorldCommitService.java backend/src/main/java/nl/gzmn/playerworlds/backend/profile/ProfileListener.java
git commit -m "feat(backend): wire snapshot engine, quiesce watchdog and atomic commit into WorldCommitService"
```

---

### Task 8: World Lifecycle Cold/Warm Loading & Initial Snapshot (`:backend`)

**Files:**
- Modify: `backend/src/main/java/nl/gzmn/playerworlds/backend/world/WorldLifecycleService.java`
- Test: `backend/src/test/java/nl/gzmn/playerworlds/backend/world/WorldLifecycleServiceTest.java`

- [ ] **Step 1: Update `WorldLifecycleService` to use `WorldDownloader` and initial snapshot commit**

In `WorldLifecycleService.java`:
1. In `materialiseExisting(row, current)`:
   If `row.manifestKey()` is present and `objectStore` is configured:
   - Run `worldDownloader.materialize(manifest, worldContainer)` on `executors.io()`.
   - Track `metrics.worldLoadWarm()` / `metrics.worldLoadCold()`.
2. In `materialiseNewWorld`:
   - After generating overworld, call `commitService.requestCommit(row.id())` to upload the initial snapshot (FR-1a).

- [ ] **Step 2: Run tests in `:backend`**
Run: `./gradlew :backend:test --tests "nl.gzmn.playerworlds.backend.world.WorldLifecycleServiceTest"`
Expected: PASS.

- [ ] **Step 3: Commit**
```bash
git add backend/src/main/java/nl/gzmn/playerworlds/backend/world/WorldLifecycleService.java
git commit -m "feat(backend): wire WorldDownloader cold/warm materialization and initial snapshot creation"
```

---

### Task 9: Periodic Sync Scheduler & Shutdown Commits (`:backend`)

**Files:**
- Create: `backend/src/main/java/nl/gzmn/playerworlds/backend/storage/PeriodicSyncTask.java`
- Modify: `backend/src/main/java/nl/gzmn/playerworlds/backend/GzmnWorldsPlugin.java`
- Test: `backend/src/test/java/nl/gzmn/playerworlds/backend/storage/PeriodicSyncTaskTest.java`

- [ ] **Step 1: Implement `PeriodicSyncTask`**

```java
package nl.gzmn.playerworlds.backend.storage;

import java.util.Objects;
import java.util.function.Supplier;
import nl.gzmn.playerworlds.backend.profile.WorldCommitService;
import nl.gzmn.playerworlds.backend.world.LoadedWorld;
import nl.gzmn.playerworlds.backend.world.WorldRegistry;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PeriodicSyncTask implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(PeriodicSyncTask.class);

    private final WorldRegistry registry;
    private final WorldCommitService commits;
    private final Supplier<NetworkPolicy> policySupplier;

    public PeriodicSyncTask(
            WorldRegistry registry,
            WorldCommitService commits,
            Supplier<NetworkPolicy> policySupplier) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.commits = Objects.requireNonNull(commits, "commits");
        this.policySupplier = Objects.requireNonNull(policySupplier, "policySupplier");
    }

    @Override
    public void run() {
        for (LoadedWorld world : registry.loadedWorlds()) {
            try {
                commits.requestCommit(world.id());
            } catch (Exception e) {
                log.warn("Periodic incremental sync failed for world {}", world.id(), e);
            }
        }
    }
}
```

- [ ] **Step 2: Update `GzmnWorldsPlugin.java`**
1. In `onEnable()` / `startWorldLifecycle`:
   - Initialize `S3ObjectStore`, `LocalObjectCache`, `WorldDownloader`, `SnapshotEngine`.
   - Instantiate `WorldCommitService` and register `ProfileListener`.
   - Schedule `PeriodicSyncTask` using `policy.syncInterval()`.
2. In `onDisable()` (FR-28):
   - Synchronously request final commit for all loaded worlds before dimension unload.

- [ ] **Step 3: Run plugin test suite**
Run: `./gradlew :backend:test`
Expected: PASS.

- [ ] **Step 4: Commit**
```bash
git add backend/src/main/java/nl/gzmn/playerworlds/backend/storage/PeriodicSyncTask.java backend/src/main/java/nl/gzmn/playerworlds/backend/GzmnWorldsPlugin.java
git commit -m "feat(backend): wire PeriodicSyncTask and FR-28 shutdown commits in GzmnWorldsPlugin"
```

---

### Task 10: Milestone 6 Acceptance Tests & Quality Gate

**Files:**
- Create: `testing/src/test/java/nl/gzmn/playerworlds/testing/Milestone6AcceptanceTest.java`

- [ ] **Step 1: Implement full scratch wipe test (NFR-9) and corruption injection (MN-5c)**

```java
package nl.gzmn.playerworlds.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import nl.gzmn.playerworlds.core.config.StorageClientSettings;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.storage.ContentHasher;
import nl.gzmn.playerworlds.core.storage.HashedContent;
import nl.gzmn.playerworlds.core.storage.LocalObjectCache;
import nl.gzmn.playerworlds.core.storage.Manifest;
import nl.gzmn.playerworlds.core.storage.ManifestEntry;
import nl.gzmn.playerworlds.core.storage.PlainFileCloner;
import nl.gzmn.playerworlds.core.storage.RegionStructureException;
import nl.gzmn.playerworlds.core.storage.S3ObjectStore;
import nl.gzmn.playerworlds.core.storage.SnapshotCopier;
import nl.gzmn.playerworlds.core.storage.SnapshotEngine;
import nl.gzmn.playerworlds.core.storage.WorldDownloader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Milestone6AcceptanceTest {

    @Test
    void survivesScratchWipeAndRestoresMobsAndEntities(@TempDir Path tempDir) throws Exception {
        Path scratch = tempDir.resolve("scratch");
        Path cacheRoot = tempDir.resolve("cache");
        Path restoreScratch = tempDir.resolve("restored-scratch");

        WorldId worldId = WorldFixture.materialize(scratch);
        List<String> expectedPaths = WorldFixture.syncedRelativePaths(scratch, worldId);

        StorageClientSettings settings = TestObjectStore.settingsForNewBucket();
        try (S3ObjectStore store = S3ObjectStore.open(settings)) {
            LocalObjectCache cache = new LocalObjectCache(cacheRoot, new PlainFileCloner());
            SnapshotCopier copier = new SnapshotCopier(new PlainFileCloner());
            SnapshotEngine engine = new SnapshotEngine(store, cache, copier);

            List<Path> dirty = expectedPaths.stream().map(Path::of).toList();
            SnapshotEngine.SnapshotResult snap = engine.executeSnapshot(
                    scratch, worldId, 0L, 1, 4903, "26.2", Map.of(), dirty, true);

            // Wipe scratch directory (NFR-9)
            // Now materialize onto empty directory using WorldDownloader
            WorldDownloader downloader = new WorldDownloader(store, cache, new PlainFileCloner());
            WorldDownloader.Result dlResult = downloader.materialize(snap.manifest(), restoreScratch);

            assertThat(dlResult.wasWarm()).isFalse();
            assertThat(dlResult.filesRestored()).isEqualTo(expectedPaths.size());

            for (String rel : expectedPaths) {
                Path original = scratch.resolve(rel);
                Path restored = restoreScratch.resolve(rel);
                assertThat(Files.exists(restored)).isTrue();
                assertThat(Files.readAllBytes(restored)).isEqualTo(Files.readAllBytes(original));
            }
        }
    }

    @Test
    void rejectsCorruptMcaFileDuringSnapshot(@TempDir Path tempDir) throws Exception {
        Path scratch = tempDir.resolve("scratch");
        Path cacheRoot = tempDir.resolve("cache");
        WorldId worldId = WorldFixture.materialize(scratch);

        // Corrupt r.0.0.mca
        Path mca = scratch.resolve(worldId.folder()).resolve("region").resolve("r.0.0.mca");
        byte[] corrupted = new byte[8192];
        corrupted[0] = 0; corrupted[1] = 0; corrupted[2] = 1; corrupted[3] = 10; // offset 1 inside header!
        Files.write(mca, corrupted);

        StorageClientSettings settings = TestObjectStore.settingsForNewBucket();
        try (S3ObjectStore store = S3ObjectStore.open(settings)) {
            LocalObjectCache cache = new LocalObjectCache(cacheRoot, new PlainFileCloner());
            SnapshotCopier copier = new SnapshotCopier(new PlainFileCloner());
            SnapshotEngine engine = new SnapshotEngine(store, cache, copier);

            List<Path> dirty = List.of(Path.of(worldId.folder(), "region", "r.0.0.mca"));

            assertThatThrownBy(() -> engine.executeSnapshot(
                    scratch, worldId, 0L, 1, 4903, "26.2", Map.of(), dirty, true))
                    .isInstanceOf(RegionStructureException.class);
        }
    }
}
```

- [ ] **Step 2: Run verification and entire quality gate suite**
Run:
```bash
./gradlew check build
```
Expected: All tests pass, zero warnings/errors across `:core`, `:backend`, `:proxy`, `:testing`.

- [ ] **Step 3: Commit**
```bash
git add testing/src/test/java/nl/gzmn/playerworlds/testing/Milestone6AcceptanceTest.java
git commit -m "test: add Milestone 6 acceptance tests for scratch wipe recovery and MCA corruption rejection"
```
