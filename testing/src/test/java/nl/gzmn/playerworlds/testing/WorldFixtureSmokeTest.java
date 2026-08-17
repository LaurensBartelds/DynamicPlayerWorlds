package nl.gzmn.playerworlds.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit-layer smoke for the synthetic world fixture (plan section 11).
 *
 * <p>No container, no server — only that the MN-2a path set is present and the
 * node-local excludes stay out of the synced path list.
 */
class WorldFixtureSmokeTest {

    @TempDir
    Path scratch;

    @Test
    @DisplayName("materialise writes the MN-2a path set for all three dimensions")
    void materialiseWritesTheMn2aPathSet() throws Exception {
        WorldId id = WorldId.parse("11111111-1111-1111-1111-111111111111");
        Path overworld = WorldFixture.materialize(scratch, id, WorldFixture.DimensionSet.ALL_THREE);

        assertThat(overworld).isEqualTo(scratch.resolve(id.folder()));
        assertThat(overworld.resolve("level.dat")).exists().isRegularFile();
        assertThat(overworld.resolve("region/r.0.0.mca")).exists();
        assertThat(overworld.resolve("entities/r.0.0.mca")).exists();
        assertThat(overworld.resolve("poi/r.0.0.mca")).exists();
        assertThat(overworld.resolve("data/raids.dat")).exists();

        Path nether = scratch.resolve(id.folder() + "_nether");
        assertThat(nether.resolve("level.dat")).exists();
        assertThat(nether.resolve("DIM-1/region/r.0.0.mca")).exists();

        Path end = scratch.resolve(id.folder() + "_the_end");
        assertThat(end.resolve("level.dat")).exists();
        assertThat(end.resolve("DIM1/region/r.0.0.mca")).exists();
    }

    @Test
    @DisplayName("synced paths omit session.lock and uid.dat (MN-2a)")
    void syncedPathsOmitNodeLocalFiles() throws Exception {
        WorldId id = WorldFixture.materialize(scratch);
        List<String> synced = WorldFixture.syncedRelativePaths(scratch, id);

        assertThat(synced).isNotEmpty();
        assertThat(synced).noneMatch(path -> path.endsWith("session.lock") || path.endsWith("uid.dat"));
        assertThat(synced).anyMatch(path -> path.endsWith("level.dat"));
        assertThat(synced).anyMatch(path -> path.contains("/region/"));
        assertThat(synced).anyMatch(path -> path.contains("_nether/"));
        assertThat(synced).anyMatch(path -> path.contains("_the_end/"));

        // The excludes exist on disk; they are only filtered from the sync set.
        assertThat(Files.exists(scratch.resolve(id.folder()).resolve("session.lock")))
                .isTrue();
    }
}
