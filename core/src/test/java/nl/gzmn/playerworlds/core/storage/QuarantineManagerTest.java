package nl.gzmn.playerworlds.core.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
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

    @Test
    @DisplayName("quarantineWorld moves overworld, nether, and the_end folders to quarantine")
    void quarantineWorldMovesAllDimensionFolders() throws IOException {
        WorldId worldId = WorldId.random();
        String base = worldId.folder();

        Path overworld = scratchRoot.resolve(base);
        Path nether = scratchRoot.resolve(base + "_nether");
        Path end = scratchRoot.resolve(base + "_the_end");

        Files.createDirectories(overworld);
        Files.writeString(overworld.resolve("level.dat"), "test data");
        Files.createDirectories(nether);
        Files.createDirectories(end);

        List<Path> quarantined = QuarantineManager.quarantineWorld(scratchRoot, quarantineRoot, worldId);

        assertThat(quarantined).hasSize(3);
        assertThat(Files.exists(overworld)).isFalse();
        assertThat(Files.exists(nether)).isFalse();
        assertThat(Files.exists(end)).isFalse();

        for (Path q : quarantined) {
            assertThat(Files.exists(q)).isTrue();
            assertThat(q.getParent()).isEqualTo(quarantineRoot);
        }
    }

    @Test
    @DisplayName("sweepStartup deletes snapshot directories and quarantines unleased world folders")
    void sweepStartupDeletesSnapshotsAndQuarantinesUnleased() throws IOException {
        WorldId leasedWorld = WorldId.random();
        WorldId unleasedWorld = WorldId.random();

        // Leased world in scratch (should be left untouched)
        Path leasedFolder = scratchRoot.resolve(leasedWorld.folder());
        Files.createDirectories(leasedFolder);

        // Unleased world in scratch (should be moved to quarantine)
        Path unleasedFolder = scratchRoot.resolve(unleasedWorld.folder());
        Files.createDirectories(unleasedFolder);
        Files.writeString(unleasedFolder.resolve("data.txt"), "crash debris");

        // Snapshot folders (should be deleted outright under MN-5a)
        Path snapshotTemp = scratchRoot.resolve("_snapshot_123");
        Path snapshotsDir = scratchRoot.resolve(".snapshots");
        Files.createDirectories(snapshotTemp);
        Files.createDirectories(snapshotsDir);

        List<Path> quarantined = QuarantineManager.sweepStartup(scratchRoot, quarantineRoot, Set.of(leasedWorld));

        assertThat(Files.exists(leasedFolder)).isTrue();
        assertThat(Files.exists(unleasedFolder)).isFalse();
        assertThat(Files.exists(snapshotTemp)).isFalse();
        assertThat(Files.exists(snapshotsDir)).isFalse();

        assertThat(quarantined).hasSize(1);
        assertThat(Files.exists(quarantined.getFirst())).isTrue();
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
