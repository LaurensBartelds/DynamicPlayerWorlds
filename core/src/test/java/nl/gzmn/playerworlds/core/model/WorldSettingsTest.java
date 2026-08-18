package nl.gzmn.playerworlds.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WorldSettingsTest {

    @Test
    @DisplayName("defaults match the specification safe defaults (FR-9e)")
    void defaultsMatchSpec() {
        WorldSettings settings = WorldSettings.defaults();
        assertThat(settings.pvp()).isFalse();
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

        WorldSettings partial = WorldSettings.fromJson("{\"pvp\": true}");
        assertThat(partial.pvp()).isTrue();
        assertThat(partial.visitorsMayOpenContainers()).isFalse();
        assertThat(partial.visitorsMayInteract()).isTrue();
        assertThat(partial.mobGriefing()).isTrue();
    }
}
