package nl.gzmn.playerworlds.core.obs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class MdcContextTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("open puts keys and close restores prior values")
    @SuppressWarnings("try")
    void restoresPreviousValues() {
        MDC.put(MdcKeys.NODE_ID, "outer");
        try (MdcContext ignored = MdcContext.open().nodeId("inner").op("sync")) {
            assertThat(MDC.get(MdcKeys.NODE_ID)).isEqualTo("inner");
            assertThat(MDC.get(MdcKeys.OP)).isEqualTo("sync");
        }
        assertThat(MDC.get(MdcKeys.NODE_ID)).isEqualTo("outer");
        assertThat(MDC.get(MdcKeys.OP)).isNull();
    }

    @Test
    @DisplayName("world and player helpers write stable string forms")
    @SuppressWarnings("try")
    void typedHelpers() {
        UUID world = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID player = UUID.fromString("22222222-2222-2222-2222-222222222222");
        try (MdcContext ignored = MdcContext.open()
                .worldId(new WorldId(world))
                .generation(7L)
                .playerUuid(player)
                .event(LogEvent.WORLD_JOIN)) {
            assertThat(MDC.get(MdcKeys.WORLD_ID)).isEqualTo(world.toString());
            assertThat(MDC.get(MdcKeys.GENERATION)).isEqualTo("7");
            assertThat(MDC.get(MdcKeys.PLAYER_UUID)).isEqualTo(player.toString());
            assertThat(MDC.get(MdcKeys.EVENT)).isEqualTo("world.join");
        }
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }
}
