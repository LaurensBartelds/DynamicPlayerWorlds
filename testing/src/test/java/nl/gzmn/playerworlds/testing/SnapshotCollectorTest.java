package nl.gzmn.playerworlds.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import nl.gzmn.playerworlds.core.config.StorageClientSettings;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.storage.DirtyScanner;
import nl.gzmn.playerworlds.core.storage.LocalObjectCache;
import nl.gzmn.playerworlds.core.storage.Manifest;
import nl.gzmn.playerworlds.core.storage.ManifestEntry;
import nl.gzmn.playerworlds.core.storage.PlainFileCloner;
import nl.gzmn.playerworlds.core.storage.S3ObjectStore;
import nl.gzmn.playerworlds.core.storage.SnapshotCollector;
import nl.gzmn.playerworlds.core.storage.SnapshotCopier;
import nl.gzmn.playerworlds.core.storage.SnapshotEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** MN-2b: objects no retained manifest references are reclaimed. */
class SnapshotCollectorTest {

    private static final List<String> EXCLUDES = List.of("session.lock", "uid.dat");

    @Test
    @DisplayName("a world that has churned its files reclaims storage once its manifests age out (MN-2b)")
    void aChurnedWorldReclaimsStorage_MN2b(@TempDir Path tempDir) throws Exception {
        Path scratch = tempDir.resolve("scratch");
        StorageClientSettings settings = TestObjectStore.settingsForNewBucket();
        try (S3ObjectStore store = S3ObjectStore.open(settings)) {
            LocalObjectCache cache = new LocalObjectCache(tempDir.resolve("cache"), PlainFileCloner.INSTANCE);
            SnapshotEngine engine = new SnapshotEngine(store, cache, new SnapshotCopier(PlainFileCloner.INSTANCE));

            WorldId worldId = WorldFixture.materialize(scratch);
            List<Path> roots = WorldFixture.relativeDimensionFolders(worldId);
            Path churned = scratch.resolve("world/dimensions/minecraft/" + worldId.folder() + "/paper-world.yml");

            // Four snapshots, rewriting one file each time. Each rewrite leaves the
            // previous content behind as an object: immutable and content-addressed,
            // so no writer can ever delete it (MN-2b).
            Manifest latest = null;
            for (int sequence = 1; sequence <= 4; sequence++) {
                if (sequence > 1) {
                    Files.writeString(churned, "revision " + sequence);
                }
                Map<String, ManifestEntry> baseline = latest == null ? Map.of() : latest.entries();
                latest = engine.executeSnapshot(
                                scratch,
                                worldId,
                                0L,
                                sequence,
                                4903,
                                "26.2",
                                baseline,
                                DirtyScanner.scan(scratch, roots, baseline, EXCLUDES),
                                true)
                        .manifest();
            }

            String dataPrefix = "worlds/" + worldId.value() + "/data/";
            assertThat(store.listKeys("worlds/" + worldId.value() + "/manifest/"))
                    .hasSize(4);
            int objectsBefore = store.listKeys(dataPrefix).size();

            // Retain two: the newest two manifests, one of which is the current one.
            SnapshotCollector collector = new SnapshotCollector(store);
            SnapshotCollector.Collected collected = collector.collect(worldId, latest.manifestKey(), 2);

            assertThat(collected.manifestsDeleted()).isEqualTo(2);
            assertThat(collected.dataObjectsDeleted()).isPositive();
            assertThat(store.listKeys(dataPrefix)).hasSize(objectsBefore - collected.dataObjectsDeleted());

            // Everything the current manifest names is still there, which is the
            // only property that matters: the world must still load.
            for (ManifestEntry entry : latest.entries().values()) {
                assertThat(store.exists(dataPrefix + entry.sha256Hex()))
                        .as("the current manifest still names %s", entry.path())
                        .isTrue();
            }
            assertThat(store.exists(latest.manifestKey())).isTrue();
        }
    }

    @Test
    @DisplayName("an orphan a fenced node left behind is reclaimed (MN-2b)")
    void anOrphanFromAFencedNodeIsReclaimed(@TempDir Path tempDir) throws Exception {
        Path scratch = tempDir.resolve("scratch");
        StorageClientSettings settings = TestObjectStore.settingsForNewBucket();
        try (S3ObjectStore store = S3ObjectStore.open(settings)) {
            LocalObjectCache cache = new LocalObjectCache(tempDir.resolve("cache"), PlainFileCloner.INSTANCE);
            SnapshotEngine engine = new SnapshotEngine(store, cache, new SnapshotCopier(PlainFileCloner.INSTANCE));

            WorldId worldId = WorldFixture.materialize(scratch);
            List<Path> roots = WorldFixture.relativeDimensionFolders(worldId);
            Manifest manifest = engine.executeSnapshot(
                            scratch,
                            worldId,
                            0L,
                            1,
                            4903,
                            "26.2",
                            Map.of(),
                            DirtyScanner.scan(scratch, roots, Map.of(), EXCLUDES),
                            true)
                    .manifest();

            // What a fenced node's uploads become: objects for a snapshot whose
            // manifest never committed. MN-2b calls these expected.
            String orphanKey = "worlds/" + worldId.value() + "/data/" + "f".repeat(64);
            store.putBytes(orphanKey, new byte[] {1, 2, 3}, "application/octet-stream");

            SnapshotCollector.Collected collected =
                    new SnapshotCollector(store).collect(worldId, manifest.manifestKey(), 3);

            assertThat(collected.dataObjectsDeleted()).isEqualTo(1);
            assertThat(store.exists(orphanKey)).isFalse();
            // The one committed manifest is inside the retention count, so nothing
            // else moved.
            assertThat(collected.manifestsDeleted()).isZero();
            for (ManifestEntry entry : manifest.entries().values()) {
                assertThat(store.exists("worlds/" + worldId.value() + "/data/" + entry.sha256Hex()))
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("the current manifest is retained however old it sorts (MN-2b)")
    void theCurrentManifestIsAlwaysRetained(@TempDir Path tempDir) throws Exception {
        Path scratch = tempDir.resolve("scratch");
        StorageClientSettings settings = TestObjectStore.settingsForNewBucket();
        try (S3ObjectStore store = S3ObjectStore.open(settings)) {
            LocalObjectCache cache = new LocalObjectCache(tempDir.resolve("cache"), PlainFileCloner.INSTANCE);
            SnapshotEngine engine = new SnapshotEngine(store, cache, new SnapshotCopier(PlainFileCloner.INSTANCE));

            WorldId worldId = WorldFixture.materialize(scratch);
            List<Path> roots = WorldFixture.relativeDimensionFolders(worldId);
            DirtyScanner.Scan scan = DirtyScanner.scan(scratch, roots, Map.of(), EXCLUDES);

            // Generation 9 first, then 10: as strings "10-1.json" sorts before
            // "9-1.json", so a collector ordering by key would keep the wrong one
            // and delete the world's current snapshot.
            Manifest older = engine.executeSnapshot(scratch, worldId, 9L, 1, 4903, "26.2", Map.of(), scan, true)
                    .manifest();
            Manifest newer = engine.executeSnapshot(scratch, worldId, 10L, 1, 4903, "26.2", Map.of(), scan, true)
                    .manifest();

            // Retain one, and say the older generation is the current one — which is
            // what a world restored from an archive looks like mid-flight.
            SnapshotCollector.Collected collected =
                    new SnapshotCollector(store).collect(worldId, older.manifestKey(), 1);

            assertThat(store.exists(older.manifestKey()))
                    .as("the current manifest survives its own collection")
                    .isTrue();
            assertThat(store.exists(newer.manifestKey()))
                    .as("the newest is retained by the count")
                    .isTrue();
            assertThat(collected.manifestsDeleted()).isZero();
            // Both manifests describe the same files, so nothing is orphaned.
            assertThat(collected.dataObjectsDeleted()).isZero();
        }
    }
}
