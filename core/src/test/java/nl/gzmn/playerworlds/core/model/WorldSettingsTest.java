package nl.gzmn.playerworlds.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WorldSettingsTest {

    @Test
    @DisplayName("PVP is on by default and the visitor limits stay closed (FR-9e)")
    void defaultsMatchSpec() {
        WorldSettings settings = WorldSettings.defaults();
        assertThat(settings.pvp()).isTrue();
        assertThat(settings.visitorsMayOpenContainers()).isFalse();
        assertThat(settings.visitorsMayInteract()).isTrue();
        assertThat(settings.mobGriefing()).isTrue();
    }

    @Test
    @DisplayName("toJson and fromJson round-trip accurately")
    void roundTripJson() {
        WorldSettings custom = new WorldSettings(true, true, false, false);
        String json = custom.toJson();
        WorldSettings parsed = WorldSettings.fromJson(json);

        assertThat(parsed).isEqualTo(custom);
    }

    @Test
    @DisplayName("fromJson handles null, empty, and partial JSON gracefully")
    void fromJsonHandlesFallbacks() {
        assertThat(WorldSettings.fromJson(null)).isEqualTo(WorldSettings.defaults());
        assertThat(WorldSettings.fromJson("")).isEqualTo(WorldSettings.defaults());
        assertThat(WorldSettings.fromJson("{}")).isEqualTo(WorldSettings.defaults());

        WorldSettings offExplicitly = WorldSettings.fromJson("{\"pvp\": false}");
        assertThat(offExplicitly.pvp())
                .as("an owner who turned PVP off keeps it off (FR-9e)")
                .isFalse();

        WorldSettings partial = WorldSettings.fromJson("{\"pvp\": true}");
        assertThat(partial.pvp()).isTrue();
        assertThat(partial.visitorsMayOpenContainers()).isFalse();
        assertThat(partial.visitorsMayInteract()).isTrue();
        assertThat(partial.mobGriefing()).isTrue();
    }
}
