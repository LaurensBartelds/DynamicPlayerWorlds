package nl.gzmn.playerworlds.core.control;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CommandKindTest {

    @Test
    @DisplayName("every v1 kind parses from its stored name (CP-6)")
    void everyV1KindParses() {
        for (CommandKind kind : CommandKind.values()) {
            assertThat(CommandKind.parse(kind.name())).contains(kind);
        }
    }

    @Test
    @DisplayName("unknown names are empty so the dispatcher can complete with error")
    void unknownNamesAreEmpty() {
        assertThat(CommandKind.parse("FUTURE_COMMAND")).isEmpty();
        assertThat(CommandKind.parse("")).isEmpty();
        assertThat(CommandKind.parse("   ")).isEmpty();
    }

    @Test
    @DisplayName("node channel names are stable and proxy is a fixed shared channel")
    void channelNames() {
        assertThat(ControlChannels.forNode("worlds-1")).isEqualTo("gzmn_node_worlds-1");
        assertThat(ControlChannels.PROXY).isEqualTo("gzmn_proxy");
        assertThat(ControlChannels.isProxy(ControlChannels.PROXY)).isTrue();
        assertThat(ControlChannels.isProxy(ControlChannels.forNode("x"))).isFalse();
    }

    @Test
    @DisplayName("result wire forms stay short and stable for operators")
    void resultWireForms() {
        assertThat(CommandResult.ok().wire()).isEqualTo("OK");
        assertThat(CommandResult.staleGeneration().wire()).isEqualTo("STALE_GENERATION");
        assertThat(CommandResult.unknownCommand("X").wire()).isEqualTo("UNKNOWN_COMMAND:X");
        assertThat(CommandResult.error("boom").wire()).isEqualTo("ERROR:boom");
    }
}
