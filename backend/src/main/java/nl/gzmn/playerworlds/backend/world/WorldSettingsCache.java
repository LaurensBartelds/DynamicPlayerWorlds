package nl.gzmn.playerworlds.backend.world;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.model.WorldSettings;

/**
 * In-memory cache of per-world settings (FR-9e).
 *
 * <p>Read synchronously on the Bukkit tick thread by {@link RoleEnforcementListener}.
 * Updated when a world loads and invalidated via {@code INVALIDATE_CACHE}.
 */
public final class WorldSettingsCache {

    private final Map<WorldId, WorldSettings> cache = new ConcurrentHashMap<>();

    public WorldSettings get(WorldId worldId) {
        Objects.requireNonNull(worldId, "worldId");
        return cache.getOrDefault(worldId, WorldSettings.defaults());
    }

    public void put(WorldId worldId, WorldSettings settings) {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(settings, "settings");
        cache.put(worldId, settings);
    }

    public void invalidate(WorldId worldId) {
        Objects.requireNonNull(worldId, "worldId");
        cache.remove(worldId);
    }

    public void clear() {
        cache.clear();
    }
}
