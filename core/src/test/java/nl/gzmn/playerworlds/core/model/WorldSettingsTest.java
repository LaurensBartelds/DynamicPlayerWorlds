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
    @DisplayName("FR-9i additions default to vanilla's own defaults")
    void fr9iDefaultsMatchVanilla() {
        WorldSettings settings = WorldSettings.defaults();
        assertThat(settings.keepInventory()).isFalse();
        assertThat(settings.fallDamage()).isTrue();
        assertThat(settings.fireDamage()).isTrue();
        assertThat(settings.freezeDamage()).isTrue();
        assertThat(settings.drowningDamage()).isTrue();
        assertThat(settings.advanceTime()).isTrue();
        assertThat(settings.advanceWeather()).isTrue();
        assertThat(settings.spawnPhantoms()).isTrue();
        assertThat(settings.immediateRespawn()).isFalse();
        assertThat(settings.naturalHealthRegeneration()).isTrue();
        assertThat(settings.playersSleepingPercentage()).isEqualTo(100);
        assertThat(settings.maxEntityCramming()).isEqualTo(24);
        assertThat(settings.respawnRadius()).isEqualTo(10);
        assertThat(settings.maxSnowAccumulationHeight()).isEqualTo(1);
    }

    @Test
    @DisplayName("toJson and fromJson round-trip accurately")
    void roundTripJson() {
        WorldSettings custom = WorldSettings.defaults()
                .withPvp(true)
                .withVisitorsMayOpenContainers(true)
                .withVisitorsMayInteract(false)
                .withMobGriefing(false);
        String json = custom.toJson();
        WorldSettings parsed = WorldSettings.fromJson(json);

        assertThat(parsed).isEqualTo(custom);
    }

    @Test
    @DisplayName("FR-9i settings round-trip through toJson/fromJson")
    void roundTripJsonFr9i() {
        WorldSettings custom = WorldSettings.defaults()
                .withKeepInventory(true)
                .withFallDamage(false)
                .withFireDamage(false)
                .withFreezeDamage(false)
                .withDrowningDamage(false)
                .withAdvanceTime(false)
                .withAdvanceWeather(false)
                .withSpawnPhantoms(false)
                .withImmediateRespawn(true)
                .withNaturalHealthRegeneration(false)
                .withPlayersSleepingPercentage(50)
                .withMaxEntityCramming(12)
                .withRespawnRadius(3)
                .withMaxSnowAccumulationHeight(7);

        WorldSettings parsed = WorldSettings.fromJson(custom.toJson());

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
        assertThat(partial.keepInventory())
                .as("fields the JSON does not mention fall back to the FR-9i default")
                .isFalse();
        assertThat(partial.playersSleepingPercentage()).isEqualTo(100);
    }

    @Test
    @DisplayName("fromJson handles a partial FR-9i boolean and ranged int gracefully")
    void fromJsonHandlesFr9iFallbacks() {
        WorldSettings keepInventoryOn = WorldSettings.fromJson("{\"keepInventory\": true}");
        assertThat(keepInventoryOn.keepInventory()).isTrue();
        assertThat(keepInventoryOn.pvp())
                .as("unrelated fields still fall back to their defaults")
                .isTrue();

        WorldSettings sleepPercentage = WorldSettings.fromJson("{\"playersSleepingPercentage\": 50}");
        assertThat(sleepPercentage.playersSleepingPercentage()).isEqualTo(50);
    }
}
