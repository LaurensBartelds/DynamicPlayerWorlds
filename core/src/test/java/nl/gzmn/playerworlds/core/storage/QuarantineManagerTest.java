package nl.gzmn.playerworlds.core.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuarantineManagerTest {

    @TempDir
    private Path tempDir;

    private Path scratchRoot;
    private Path quarantineRoot;

    @BeforeEach
    void setup() throws IOException {
        scratchRoot = tempDir.resolve("scratch");
        quarantineRoot = tempDir.resolve("quarantine");
        Files.createDirectories(scratchRoot);
        Files.createDirectories(quarantineRoot);
    }

    /** Paper 26's nesting, which the caller owns now — this stands in for WorldLayout. */
    private Path dimensionFolder(String bukkitWorldName) {
        return scratchRoot
                .resolve("world")
                .resolve("dimensions")
                .resolve("minecraft")
                .resolve(bukkitWorldName);
    }

    private Path dimensionsRoot() {
        return scratchRoot.resolve("world").resolve("dimensions").resolve("minecraft");
    }

    /** The three flat folder names of one world, as the layout would give them. */
    private List<Path> dimensionFolders(WorldId worldId) {
        String base = worldId.folder();
        return List.of(dimensionFolder(base), dimensionFolder(base + "_nether"), dimensionFolder(base + "_the_end"));
    }

    /** Resolves a folder name back to its world, the inverse WorldFolders supplies. */
    private static Function<String, Optional<WorldId>> resolverFor(WorldId... known) {
        return name -> {
            for (WorldId id : known) {
                String base = id.folder();
                if (name.equals(base) || name.equals(base + "_nether") || name.equals(base + "_the_end")) {
                    return Optional.of(id);
                }
            }
            return Optional.empty();
        };
    }

    private QuarantineManager.StartupSweep sweep(
            Function<String, Optional<WorldId>> resolver,
            Set<WorldId> leasedHere,
            Function<WorldId, Optional<String>> manifestKeys) {
        return new QuarantineManager.StartupSweep(
                scratchRoot, dimensionsRoot(), quarantineRoot, resolver, leasedHere, manifestKeys, "tag");
    }

    private Path materialiseWorld(WorldId worldId) throws IOException {
        for (Path folder : dimensionFolders(worldId)) {
            Files.createDirectories(folder);
        }
        Path overworld = dimensionFolder(worldId.folder());
        Files.writeString(overworld.resolve("paper-world.yml"), "world data");
        return overworld;
    }

    @Test
    @DisplayName("quarantineWorld moves every dimension folder it is given, and drops the marker")
    void quarantineWorldMovesAllDimensionFolders() throws IOException {
        WorldId worldId = WorldId.random();
        materialiseWorld(worldId);
        CleanUnloadMarker.write(scratchRoot, worldId, "worlds/x/manifest/1-1.json");

        List<Path> quarantined =
                QuarantineManager.quarantineWorld(scratchRoot, quarantineRoot, worldId, dimensionFolders(worldId));

        assertThat(quarantined).hasSize(3);
        for (Path folder : dimensionFolders(worldId)) {
            assertThat(Files.exists(folder)).isFalse();
        }
        for (Path q : quarantined) {
            assertThat(Files.exists(q)).isTrue();
            assertThat(q.getParent()).isEqualTo(quarantineRoot);
        }
        // The marker vouched for files that are no longer there.
        assertThat(CleanUnloadMarker.read(scratchRoot, worldId)).isEmpty();
    }

    @Test
    @DisplayName("a cleanly unloaded world survives a restart as a warm cache (MN-5, MN-4)")
    void aCleanlyUnloadedWorldSurvivesRestartAsAWarmCache_MN5() throws IOException {
        WorldId world = WorldId.random();
        Path overworld = materialiseWorld(world);
        String manifest = "worlds/" + world.value() + "/manifest/3-2.json";
        // What a clean unload leaves behind: the commit landed, then the marker,
        // then the lease was released — so at startup nothing is leased here.
        CleanUnloadMarker.write(scratchRoot, world, manifest);

        List<Path> quarantined =
                QuarantineManager.sweepStartup(sweep(resolverFor(world), Set.of(), id -> Optional.of(manifest)));

        assertThat(quarantined).isEmpty();
        assertThat(Files.exists(overworld)).isTrue();
        assertThat(Files.readString(overworld.resolve("paper-world.yml"))).isEqualTo("world data");
        // Still vouched for: nothing has touched it, so the next load is warm.
        assertThat(CleanUnloadMarker.read(scratchRoot, world)).contains(manifest);
    }

    @Test
    @DisplayName("a world with no marker is quarantined as crash debris (MN-13)")
    void aWorldWithNoMarkerIsQuarantined_MN13() throws IOException {
        WorldId world = WorldId.random();
        Path overworld = materialiseWorld(world);
        String manifest = "worlds/" + world.value() + "/manifest/3-2.json";
        // kill -9 while loaded: the load cleared the marker and nothing wrote one.

        List<Path> quarantined =
                QuarantineManager.sweepStartup(sweep(resolverFor(world), Set.of(), id -> Optional.of(manifest)));

        assertThat(quarantined).hasSize(3);
        assertThat(Files.exists(overworld)).isFalse();
    }

    @Test
    @DisplayName("a marker naming an older manifest is debris, not a warm cache (D18)")
    void aMarkerNamingADifferentManifestIsDebris() throws IOException {
        WorldId world = WorldId.random();
        Path overworld = materialiseWorld(world);
        CleanUnloadMarker.write(scratchRoot, world, "worlds/" + world.value() + "/manifest/3-1.json");

        // Another node has taken the world and committed since.
        List<Path> quarantined = QuarantineManager.sweepStartup(sweep(
                resolverFor(world), Set.of(), id -> Optional.of("worlds/" + world.value() + "/manifest/4-1.json")));

        assertThat(quarantined).hasSize(3);
        assertThat(Files.exists(overworld)).isFalse();
        assertThat(CleanUnloadMarker.read(scratchRoot, world)).isEmpty();
    }

    @Test
    @DisplayName("a world still leased to this node at startup is debris whatever its marker says")
    void aWorldStillLeasedHereIsDebris() throws IOException {
        WorldId world = WorldId.random();
        Path overworld = materialiseWorld(world);
        String manifest = "worlds/" + world.value() + "/manifest/3-2.json";
        CleanUnloadMarker.write(scratchRoot, world, manifest);

        // A live lease at startup means the previous process died holding it; a
        // clean shutdown releases every lease before it exits (FR-28).
        List<Path> quarantined =
                QuarantineManager.sweepStartup(sweep(resolverFor(world), Set.of(world), id -> Optional.of(manifest)));

        assertThat(quarantined).hasSize(3);
        assertThat(Files.exists(overworld)).isFalse();
    }

    @Test
    @DisplayName("sweepStartup deletes leftover snapshot directories and leaves foreign folders alone (MN-5a)")
    void sweepStartupDeletesSnapshotsAndIgnoresForeignFolders() throws IOException {
        WorldId world = WorldId.random();
        materialiseWorld(world);
        String manifest = "worlds/" + world.value() + "/manifest/1-1.json";
        CleanUnloadMarker.write(scratchRoot, world, manifest);

        // The lobby, sitting in the same directory. Nothing of ours.
        Path lobby = dimensionFolder("overworld");
        Files.createDirectories(lobby);

        Path snapshotTemp = scratchRoot.resolve("_snapshot_123");
        Path snapshotsDir = scratchRoot.resolve(".snapshots");
        Files.createDirectories(snapshotTemp);
        Files.createDirectories(snapshotsDir);

        List<Path> quarantined =
                QuarantineManager.sweepStartup(sweep(resolverFor(world), Set.of(), id -> Optional.of(manifest)));

        assertThat(quarantined).isEmpty();
        assertThat(Files.exists(lobby)).isTrue();
        assertThat(Files.exists(snapshotTemp)).isFalse();
        assertThat(Files.exists(snapshotsDir)).isFalse();
    }

    @Test
    @DisplayName("a world whose row is gone is not kept on the strength of a marker")
    void aWorldWithNoCurrentManifestIsDebris() throws IOException {
        WorldId world = WorldId.random();
        Path overworld = materialiseWorld(world);
        CleanUnloadMarker.write(scratchRoot, world, CleanUnloadMarker.NO_MANIFEST);

        // Hard-deleted elsewhere (FR-37), so there is no row and no manifest.
        List<Path> quarantined =
                QuarantineManager.sweepStartup(sweep(resolverFor(world), Set.of(), id -> Optional.empty()));

        assertThat(quarantined).hasSize(3);
        assertThat(Files.exists(overworld)).isFalse();
    }

    @Test
    @DisplayName("prune cleans up expired quarantine folders and enforces max bytes budget")
    void pruneEnforcesRetentionAndSize() throws IOException {
        // Create 3 quarantine folders
        Path folderOld = quarantineRoot.resolve("old_world");
        Path folderRecent1 = quarantineRoot.resolve("recent_1");
        Path folderRecent2 = quarantineRoot.resolve("recent_2");

        Files.createDirectories(folderOld);
        Files.writeString(folderOld.resolve("large.bin"), "0".repeat(5000));
        Files.setLastModifiedTime(folderOld, FileTime.from(Instant.now().minus(10, ChronoUnit.DAYS)));

        Files.createDirectories(folderRecent1);
        Files.writeString(folderRecent1.resolve("file.bin"), "1".repeat(3000));
        Files.setLastModifiedTime(folderRecent1, FileTime.from(Instant.now().minus(2, ChronoUnit.DAYS)));

        Files.createDirectories(folderRecent2);
        Files.writeString(folderRecent2.resolve("file.bin"), "2".repeat(3000));
        Files.setLastModifiedTime(folderRecent2, FileTime.from(Instant.now().minus(1, ChronoUnit.DAYS)));

        // Prune with retainDays = 7 and maxBytes = 4000
        QuarantineManager.prune(quarantineRoot, 4000, 7, Instant.now());

        // folderOld (> 7 days) should be deleted
        assertThat(Files.exists(folderOld)).isFalse();

        // Between recent_1 (older) and recent_2 (newer), recent_1 should be pruned to satisfy 4000 byte budget
        assertThat(Files.exists(folderRecent1)).isFalse();
        assertThat(Files.exists(folderRecent2)).isTrue();
    }
}
