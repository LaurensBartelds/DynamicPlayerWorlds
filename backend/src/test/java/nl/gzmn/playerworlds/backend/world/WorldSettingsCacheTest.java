package nl.gzmn.playerworlds.backend.world;

import static org.assertj.core.api.Assertions.assertThat;

import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.model.WorldSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WorldSettingsCacheTest {

    @Test
    @DisplayName("returns defaults for uncached world")
    void returnsDefaultsForUncached() {
        WorldSettingsCache cache = new WorldSettingsCache();
        WorldId id = WorldId.random();

        assertThat(cache.get(id)).isEqualTo(WorldSettings.defaults());
    }

    @Test
    @DisplayName("stores and retrieves custom settings")
    void storesAndRetrieves() {
        WorldSettingsCache cache = new WorldSettingsCache();
        WorldId id = WorldId.random();
        WorldSettings custom = new WorldSettings(true, true, false, false);

        cache.put(id, custom);
        assertThat(cache.get(id)).isEqualTo(custom);

        cache.invalidate(id);
        assertThat(cache.get(id)).isEqualTo(WorldSettings.defaults());
    }
}
