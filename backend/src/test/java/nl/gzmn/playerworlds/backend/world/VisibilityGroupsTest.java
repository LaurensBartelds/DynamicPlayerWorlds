package nl.gzmn.playerworlds.backend.world;

import static org.assertj.core.api.Assertions.assertThat;

import nl.gzmn.playerworlds.backend.platform.DefaultWorldLayout;
import nl.gzmn.playerworlds.backend.platform.DimensionKind;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The visibility rule (§5.5, FR-18).
 *
 * <p>Worth testing as a pure function because its failure mode is silent:
 * nothing breaks, a player just sees somebody they should not.
 */
class VisibilityGroupsTest {

    private final WorldFolders folders = new WorldFolders(DefaultWorldLayout.INSTANCE);
    private final VisibilityGroups groups = new VisibilityGroups(folders);

    private final WorldId first = WorldId.random();
    private final WorldId second = WorldId.random();

    private String dimension(WorldId id, DimensionKind kind) {
        return folders.bukkitWorldName(id, kind);
    }

    @Test
    @DisplayName("all three dimensions of a world are one group (§5.5)")
    void dimensionsAreOneGroup() {
        // "Moving between overworld, nether and end must not change who a player
        // can see."
        for (DimensionKind a : DimensionKind.values()) {
            for (DimensionKind b : DimensionKind.values()) {
                assertThat(groups.sameGroup(dimension(first, a), dimension(first, b)))
                        .as("%s and %s of one world", a, b)
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("two player worlds are never the same group")
    void differentWorldsAreDifferentGroups() {
        for (DimensionKind a : DimensionKind.values()) {
            for (DimensionKind b : DimensionKind.values()) {
                assertThat(groups.sameGroup(dimension(first, a), dimension(second, b)))
                        .as("%s of one world and %s of another", a, b)
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("a player outside a player world is a group of one (FR-11)")
    void outsideAPlayerWorldIsAlone() {
        // The holding area "is not a world they can interact with or see anyone
        // else from". Grouping everyone outside a player world together would put
        // two mid-join strangers in each other's tab list.
        assertThat(groups.sameGroup("world", "world")).isFalse();
        assertThat(groups.sameGroup("world", "lobby")).isFalse();
        assertThat(groups.sameGroup("world", dimension(first, DimensionKind.OVERWORLD)))
                .isFalse();
        assertThat(groups.groupOf("world")).isEmpty();
    }

    @Test
    @DisplayName("a player world resolves to its own id, from any dimension")
    void groupOfResolvesTheWorldId() {
        for (DimensionKind kind : DimensionKind.values()) {
            assertThat(groups.groupOf(dimension(first, kind))).contains(first);
        }
    }
}
