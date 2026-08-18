package nl.gzmn.playerworlds.core.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The MN-19 / MN-22 payload, which is stored text and therefore has to round-trip. */
class MigratePayloadTest {

    @Test
    @DisplayName("a move round-trips through the stored form")
    void moveRoundTrips() {
        MigratePayload payload = MigratePayload.to("node-b", 15);

        assertThat(MigratePayload.parse(payload.format())).contains(payload);
    }

    @Test
    @DisplayName("a drain round-trips with no destination (MN-22)")
    void drainRoundTrips() {
        MigratePayload payload = MigratePayload.drain(0);

        assertThat(payload.format()).doesNotContain("targetNode");
        assertThat(MigratePayload.parse(payload.format())).contains(payload);
    }

    @Test
    @DisplayName("a node id with a quote in it round-trips rather than truncating")
    void escapingRoundTrips() {
        // node.id comes from a config file an operator edits. A payload that
        // truncated here would migrate the world to a node named by the prefix.
        MigratePayload payload = MigratePayload.to("node\"b\\c", 5);

        assertThat(MigratePayload.parse(payload.format())).contains(payload);
    }

    @Test
    @DisplayName("a payload with no countdown takes MN-21's default")
    void missingCountdownDefaults() {
        assertThat(MigratePayload.parse("{\"targetNode\":\"node-b\"}"))
                .contains(MigratePayload.to("node-b", MigratePayload.DEFAULT_COUNTDOWN_SECONDS));
    }

    @Test
    @DisplayName("malformed text is refused rather than defaulted")
    void malformedIsRefused() {
        // Refusing beats defaulting: a migration that ran against an unreadable
        // payload would move a world somewhere nobody asked for.
        assertThat(MigratePayload.parse("")).isEmpty();
        assertThat(MigratePayload.parse("not json")).isEmpty();
        assertThat(MigratePayload.parse("{\"targetNode\":\"\"}")).isEmpty();
        assertThat(MigratePayload.parse("{\"countdownSeconds\":-1}")).isEmpty();
        assertThat(MigratePayload.parse("{\"countdownSeconds\":9999}")).isEmpty();
    }

    @Test
    @DisplayName("a resume round-trips and is off by default (MN-20)")
    void resumeRoundTrips() {
        assertThat(MigratePayload.parse(MigratePayload.resumeDrain().format())).contains(MigratePayload.resumeDrain());
        assertThat(MigratePayload.parse(MigratePayload.drain(0).format())
                        .orElseThrow()
                        .resume())
                .isFalse();
    }

    @Test
    @DisplayName("an out-of-range countdown is refused at construction too")
    void countdownIsBounded() {
        assertThatThrownBy(() -> new MigratePayload("node-b", -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MigratePayload("node-b", MigratePayload.MAX_COUNTDOWN_SECONDS + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
