package nl.gzmn.playerworlds.backend.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import nl.gzmn.playerworlds.backend.platform.DefaultWorldLayout;
import nl.gzmn.playerworlds.backend.platform.DimensionKind;
import nl.gzmn.playerworlds.backend.platform.WorldLayout;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The inverse of {@link WorldLayout#bukkitWorldName}. Worth its own tests
 * because a forward mapping without a matching inverse is how a portal lands in
 * the wrong world (FR-3a) — the failure is silent and looks like a routing bug.
 */
class WorldFoldersTest {

    private final WorldFolders folders = new WorldFolders(DefaultWorldLayout.INSTANCE);

    @Test
    @DisplayName("every dimension name maps back to its own world and dimension")
    void everyDimensionInverts() {
        WorldId id = WorldId.random();

        for (DimensionKind dimension : DimensionKind.values()) {
            String name = folders.bukkitWorldName(id, dimension);

            assertThat(folders.resolve(name))
                    .as("resolve(%s)", name)
                    .contains(new WorldFolders.PlayerWorldDimension(id, dimension));
        }
    }

    @Test
    @DisplayName("the end is matched before the overworld, whose suffix is empty")
    void longestSuffixWins() {
        WorldId id = WorldId.random();

        assertThat(folders.resolve(id.folder() + "_the_end").orElseThrow().dimension())
                .isEqualTo(DimensionKind.END);
        assertThat(folders.resolve(id.folder() + "_nether").orElseThrow().dimension())
                .isEqualTo(DimensionKind.NETHER);
        assertThat(folders.resolve(id.folder()).orElseThrow().dimension()).isEqualTo(DimensionKind.OVERWORLD);
    }

    @Test
    @DisplayName("worlds that are not ours are left alone")
    void foreignWorldsResolveToNothing() {
        assertThat(folders.resolve("world")).isEmpty();
        assertThat(folders.resolve("world_nether")).isEmpty();
        assertThat(folders.resolve("lobby")).isEmpty();
        assertThat(folders.isPlayerWorld("world")).isFalse();
        assertThat(folders.isPlayerWorld(WorldId.random().folder())).isTrue();
    }

    @Test
    @DisplayName("a layout whose names are not suffixes is refused rather than silently inverted wrong")
    void nonSuffixLayoutIsRefused() {
        WorldLayout prefixing = new WorldLayout() {
            @Override
            public String id() {
                return "prefixing-test-layout";
            }

            @Override
            public int minDataVersion() {
                return 0;
            }

            @Override
            public String bukkitWorldName(String baseFolder, DimensionKind dimension) {
                return dimension == DimensionKind.OVERWORLD ? baseFolder : dimension + "_" + baseFolder;
            }

            @Override
            public Path dimensionDataRelativePath(DimensionKind dimension) {
                return Path.of("");
            }

            @Override
            public Path relativeWorldFolder(String primaryLevelName, String baseFolder, DimensionKind dimension) {
                return Path.of(bukkitWorldName(baseFolder, dimension));
            }

            @Override
            public List<String> worldRootFiles() {
                return List.of("level.dat");
            }

            @Override
            public List<String> dimensionContentDirectories() {
                return List.of("region");
            }

            @Override
            public List<String> defaultExcludeGlobs() {
                return List.of();
            }
        };

        assertThatThrownBy(() -> new WorldFolders(prefixing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prefixing-test-layout");
    }
}
