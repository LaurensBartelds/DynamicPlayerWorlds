package nl.gzmn.playerworlds.core.control;

import static org.assertj.core.api.Assertions.assertThat;

import nl.gzmn.playerworlds.core.model.WorldState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** FR-37: the state a deletion was confirmed against travels with the command. */
class DeletePayloadTest {

    @Test
    @DisplayName("round-trips every state a deletion can be confirmed against")
    void roundTripsEveryState() {
        for (WorldState state : WorldState.values()) {
            assertThat(DeletePayload.parse(DeletePayload.format(state)))
                    .as("state %s", state)
                    .contains(new DeletePayload(state));
        }
    }

    @Test
    @DisplayName("tolerates the whitespace jsonb adds when it re-serialises the column")
    void toleratesJsonbSpacing() {
        assertThat(DeletePayload.parse("{\"expectedState\": \"READY\"}")).contains(new DeletePayload(WorldState.READY));
    }

    @Test
    @DisplayName("an absent expectation is a valid instruction, not a malformed one")
    void absentExpectationParsesToEmpty() {
        // A DELETE_WORLD enqueued before FR-37 carried the state still has to run.
        assertThat(DeletePayload.parse(null)).contains(DeletePayload.EMPTY);
        assertThat(DeletePayload.parse("  ")).contains(DeletePayload.EMPTY);
        assertThat(DeletePayload.parse("{}")).contains(DeletePayload.EMPTY);
        assertThat(DeletePayload.format(null)).isEqualTo("{}");
    }

    @Test
    @DisplayName("a state this build does not know is refused rather than guessed")
    void unknownStateIsRefused() {
        // The schema having moved ahead of the code is exactly when guessing which state was
        // meant would delete the wrong thing.
        assertThat(DeletePayload.parse("{\"expectedState\":\"VITRIFIED\"}")).isEmpty();
        assertThat(DeletePayload.parse("not json")).isEmpty();
        assertThat(DeletePayload.parse("{\"expectedState\"}")).isEmpty();
    }
}
