package nl.gzmn.playerworlds.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WorldIdTest {

    @Test
    @DisplayName("folder is derived from the id and carries no player text (FR-2a)")
    void folderIsDerivedFromTheId() {
        WorldId id = WorldId.parse("3f2504e0-4f89-11d3-9a0c-0305e82c3301");

        assertThat(id.folder()).isEqualTo("pw_3f2504e04f8911d39a0c0305e82c3301");
    }

    @Test
    @DisplayName("folder never contains a hyphen, so sibling dimension folders cannot collide")
    void folderHasNoSeparators() {
        assertThat(WorldId.random().folder()).doesNotContain("-").startsWith("pw_");
    }

    @Test
    @DisplayName("distinct ids produce distinct folders")
    void foldersAreUnique() {
        assertThat(WorldId.random().folder()).isNotEqualTo(WorldId.random().folder());
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> new WorldId(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void roundTripsThroughText() {
        UUID raw = UUID.randomUUID();

        assertThat(WorldId.parse(raw.toString())).isEqualTo(new WorldId(raw));
    }

    @Test
    @DisplayName("a folder round-trips back to the id that produced it")
    void folderRoundTripsBackToTheId() {
        WorldId id = WorldId.random();

        assertThat(WorldId.fromFolder(id.folder())).contains(id);
    }

    @Test
    @DisplayName("a folder that is not ours is rejected rather than guessed at")
    void foreignFoldersAreRejected() {
        // The lobby, the server's own worlds, and anything another plugin made
        // all reach this method on every portal transit and every join.
        assertThat(WorldId.fromFolder("world")).isEmpty();
        assertThat(WorldId.fromFolder("world_nether")).isEmpty();
        assertThat(WorldId.fromFolder("lobby")).isEmpty();
        assertThat(WorldId.fromFolder("pw_")).isEmpty();
        assertThat(WorldId.fromFolder("pw_short")).isEmpty();
        assertThat(WorldId.fromFolder("pw_" + "z".repeat(32))).isEmpty();
    }

    @Test
    @DisplayName("a folder that parses to a different id is rejected, not silently accepted")
    void nearlyValidFoldersDoNotRoundTrip() {
        // UUID.fromString pads short groups, so a 32-character string of hex can
        // parse to a UUID whose own folder is a different string. Comparing back
        // is what makes this a parse rather than a guess.
        String uppercase = WorldId.random().folder().toUpperCase(java.util.Locale.ROOT);

        assertThat(WorldId.fromFolder(uppercase)).isEmpty();
    }
}
