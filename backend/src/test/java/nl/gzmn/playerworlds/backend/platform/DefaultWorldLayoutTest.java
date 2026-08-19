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
    @DisplayName("paper-world.yml marks a materialised Paper 26 dimension folder")
    void paperWorldYmlIsRequired() {
        assertThat(layout.worldRootFiles()).contains("paper-world.yml");
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
    @DisplayName("region data sits at the Bukkit world root for every dimension (Paper 26)")
    void dimensionDataRelativePaths() {
        assertThat(layout.dimensionDataRelativePath(DimensionKind.OVERWORLD)).isEqualTo(Path.of(""));
        assertThat(layout.dimensionDataRelativePath(DimensionKind.NETHER)).isEqualTo(Path.of(""));
        assertThat(layout.dimensionDataRelativePath(DimensionKind.END)).isEqualTo(Path.of(""));
    }

    @Test
    @DisplayName("absolute paths nest under <level>/dimensions/minecraft/<bukkitName> (Paper 26)")
    void absolutePathsUnderScratch() {
        Path scratch = Path.of("/data/scratch");
        assertThat(layout.dimensionDataRoot(scratch, "world", "pw_abc", DimensionKind.OVERWORLD))
                .isEqualTo(scratch.resolve("world/dimensions/minecraft/pw_abc"));
        assertThat(layout.dimensionDataRoot(scratch, "world", "pw_abc", DimensionKind.NETHER))
                .isEqualTo(scratch.resolve("world/dimensions/minecraft/pw_abc_nether"));
        assertThat(layout.dimensionDataRoot(scratch, "world", "pw_abc", DimensionKind.END))
                .isEqualTo(scratch.resolve("world/dimensions/minecraft/pw_abc_the_end"));
        assertThat(layout.bukkitWorldFolder(scratch, "world", "pw_abc", DimensionKind.NETHER)
                        .resolve("paper-world.yml"))
                .isEqualTo(scratch.resolve("world/dimensions/minecraft/pw_abc_nether/paper-world.yml"));
    }
}
