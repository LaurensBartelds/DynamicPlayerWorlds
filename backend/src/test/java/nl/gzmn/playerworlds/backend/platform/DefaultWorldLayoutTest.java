package nl.gzmn.playerworlds.backend.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MN-2a path set. Omitting any of these is silent data loss, so the required
 * directories are asserted by name rather than only by count.
 */
class DefaultWorldLayoutTest {

    private final WorldLayout layout = DefaultWorldLayout.INSTANCE;

    @Test
    @DisplayName("required dimension directories include region, entities, poi and data (MN-2a)")
    void requiredDimensionDirectories_MN2a() {
        assertThat(layout.dimensionContentDirectories()).containsExactlyInAnyOrder("region", "entities", "poi", "data");
    }

    @Test
    @DisplayName("level.dat is a required world-root file (FR-3b dragon state lives there)")
    void levelDatIsRequired() {
        assertThat(layout.worldRootFiles()).contains("level.dat");
    }

    @Test
    @DisplayName("default exclude globs drop session.lock and uid.dat (MN-2a)")
    void defaultExcludes() {
        assertThat(layout.defaultExcludeGlobs()).containsExactlyInAnyOrder("session.lock", "uid.dat");
    }

    @Test
    @DisplayName("Bukkit world names use _nether and _the_end suffixes (MN-2a)")
    void bukkitWorldNames() {
        assertThat(layout.bukkitWorldName("pw_abc", DimensionKind.OVERWORLD)).isEqualTo("pw_abc");
        assertThat(layout.bukkitWorldName("pw_abc", DimensionKind.NETHER)).isEqualTo("pw_abc_nether");
        assertThat(layout.bukkitWorldName("pw_abc", DimensionKind.END)).isEqualTo("pw_abc_the_end");
    }

    @Test
    @DisplayName("nether and end region roots sit under DIM-1 and DIM1 (MN-2a)")
    void dimensionDataRelativePaths() {
        assertThat(layout.dimensionDataRelativePath(DimensionKind.OVERWORLD)).isEqualTo(Path.of(""));
        assertThat(layout.dimensionDataRelativePath(DimensionKind.NETHER)).isEqualTo(Path.of("DIM-1"));
        assertThat(layout.dimensionDataRelativePath(DimensionKind.END)).isEqualTo(Path.of("DIM1"));
    }

    @Test
    @DisplayName("absolute paths under scratch resolve Bukkit folder plus DIM segment")
    void absolutePathsUnderScratch() {
        Path scratch = Path.of("/data/scratch");
        assertThat(layout.dimensionDataRoot(scratch, "pw_abc", DimensionKind.OVERWORLD))
                .isEqualTo(scratch.resolve("pw_abc"));
        assertThat(layout.dimensionDataRoot(scratch, "pw_abc", DimensionKind.NETHER))
                .isEqualTo(scratch.resolve("pw_abc_nether").resolve("DIM-1"));
        assertThat(layout.dimensionDataRoot(scratch, "pw_abc", DimensionKind.END))
                .isEqualTo(scratch.resolve("pw_abc_the_end").resolve("DIM1"));
        assertThat(layout.bukkitWorldFolder(scratch, "pw_abc", DimensionKind.NETHER)
                        .resolve("level.dat"))
                .isEqualTo(scratch.resolve("pw_abc_nether").resolve("level.dat"));
    }
}
