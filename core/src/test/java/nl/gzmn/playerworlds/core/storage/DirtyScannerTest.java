package nl.gzmn.playerworlds.core.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DirtyScannerTest {

    @Test
    @DisplayName("finds modified and new files while ignoring clean and excluded files")
    void findsModifiedAndNewFiles(@TempDir Path tempDir) throws Exception {
        WorldId id = WorldId.random();
        String folder = id.folder();
        Path worldFolder = tempDir.resolve(folder);
        Files.createDirectories(worldFolder);

        Path f1Clean = worldFolder.resolve("level.dat");
        Path f2Excluded = worldFolder.resolve("session.lock");
        Path f3New = worldFolder.resolve("region/r.0.0.mca");
        Path f4Modified = worldFolder.resolve("entities/r.0.0.mca");
        Files.createDirectories(f3New.getParent());
        Files.createDirectories(f4Modified.getParent());

        Files.write(f1Clean, new byte[] {1, 2});
        Files.setLastModifiedTime(f1Clean, FileTime.fromMillis(1000));

        Files.write(f2Excluded, new byte[] {9});

        Files.write(f3New, new byte[] {3, 4, 5});
        Files.setLastModifiedTime(f3New, FileTime.fromMillis(2000));

        Files.write(f4Modified, new byte[] {7, 8, 9, 10});
        Files.setLastModifiedTime(f4Modified, FileTime.fromMillis(3000));

        ManifestEntry baselineClean = new ManifestEntry(folder + "/level.dat", "0".repeat(64), 2L, 1000L);
        ManifestEntry baselineOldModified =
                new ManifestEntry(folder + "/entities/r.0.0.mca", "1".repeat(64), 2L, 1500L);

        Map<String, ManifestEntry> baseline = Map.of(
                baselineClean.path(), baselineClean,
                baselineOldModified.path(), baselineOldModified);

        List<Path> dirty = DirtyScanner.scanDirty(tempDir, id, baseline, List.of("session.lock", "uid.dat"));

        // f1 is clean, f2 is excluded, f3 is new, f4 is modified
        assertThat(dirty)
                .containsExactly(Path.of(folder, "entities", "r.0.0.mca"), Path.of(folder, "region", "r.0.0.mca"));
    }

    @Test
    @DisplayName("scans overworld, nether, and end dimension folders")
    void scansAllThreeDimensions(@TempDir Path tempDir) throws Exception {
        WorldId id = WorldId.random();
        String base = id.folder();

        Path overworld = tempDir.resolve(base);
        Path nether = tempDir.resolve(base + "_nether");
        Path end = tempDir.resolve(base + "_the_end");

        Files.createDirectories(overworld);
        Files.createDirectories(nether.resolve("DIM-1"));
        Files.createDirectories(end.resolve("DIM1"));

        Path overworldFile = overworld.resolve("level.dat");
        Path netherFile = nether.resolve("DIM-1/r.0.0.mca");
        Path endFile = end.resolve("DIM1/r.0.0.mca");

        Files.write(overworldFile, new byte[] {1});
        Files.write(netherFile, new byte[] {2});
        Files.write(endFile, new byte[] {3});

        List<Path> dirty = DirtyScanner.scanDirty(tempDir, id, Map.of(), List.of("session.lock", "uid.dat"));

        assertThat(dirty)
                .containsExactly(
                        Path.of(base, "level.dat"),
                        Path.of(base + "_nether", "DIM-1", "r.0.0.mca"),
                        Path.of(base + "_the_end", "DIM1", "r.0.0.mca"));
    }

    @Test
    @DisplayName("returns empty list when all files match baseline or are excluded")
    void returnsEmptyWhenAllClean(@TempDir Path tempDir) throws Exception {
        WorldId id = WorldId.random();
        String folder = id.folder();
        Path worldFolder = tempDir.resolve(folder);
        Files.createDirectories(worldFolder);

        Path f1 = worldFolder.resolve("level.dat");
        Path f2 = worldFolder.resolve("session.lock");
        Files.write(f1, new byte[] {1, 2, 3});
        Files.setLastModifiedTime(f1, FileTime.fromMillis(5000));
        Files.write(f2, new byte[] {4, 5});

        ManifestEntry entry = new ManifestEntry(folder + "/level.dat", "a".repeat(64), 3L, 5000L);
        Map<String, ManifestEntry> baseline = Map.of(entry.path(), entry);

        List<Path> dirty = DirtyScanner.scanDirty(tempDir, id, baseline, List.of("session.lock", "uid.dat"));

        assertThat(dirty).isEmpty();
    }

    @Test
    @DisplayName("validates non-null arguments")
    void validatesArguments(@TempDir Path tempDir) {
        WorldId id = WorldId.random();
        Map<String, ManifestEntry> baseline = Map.of();
        List<String> excludes = List.of();

        assertThatNullPointerException().isThrownBy(() -> DirtyScanner.scanDirty(null, id, baseline, excludes));
        assertThatNullPointerException()
                .isThrownBy(() -> DirtyScanner.scanDirty(tempDir, (WorldId) null, baseline, excludes));
        assertThatNullPointerException().isThrownBy(() -> DirtyScanner.scanDirty(tempDir, id, null, excludes));
        assertThatNullPointerException().isThrownBy(() -> DirtyScanner.scanDirty(tempDir, id, baseline, null));
    }
}
