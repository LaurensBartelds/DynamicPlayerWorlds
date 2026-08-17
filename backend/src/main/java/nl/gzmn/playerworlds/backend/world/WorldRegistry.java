package nl.gzmn.playerworlds.backend.world;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import nl.gzmn.playerworlds.core.model.WorldId;

/**
 * The worlds this node currently holds.
 *
 * <p>Authoritative for "is this world loaded here", which the schema deliberately
 * does not store: specification section 4 derives loadedness from the lease
 * precisely so a crashed node cannot leave a stale flag behind. On one node that
 * derivation is this map.
 *
 * <p>Concurrent because the create and load paths finish on the database
 * executor while the portal handler and the idle sweep read it on the main
 * thread. The per-world counters inside {@link LoadedWorld} stay main-thread
 * work; only membership crosses threads.
 */
public final class WorldRegistry {

    private final ConcurrentMap<WorldId, LoadedWorld> worlds = new ConcurrentHashMap<>();

    /**
     * Adds a world, or returns the one already registered.
     *
     * <p>Returning the incumbent rather than replacing it is what makes two
     * overlapping loads of the same world safe: the second caller gets the state
     * the first is populating instead of a fresh object whose materialised set is
     * empty.
     */
    public LoadedWorld register(LoadedWorld world) {
        Objects.requireNonNull(world, "world");
        LoadedWorld existing = worlds.putIfAbsent(world.id(), world);
        return existing != null ? existing : world;
    }

    /** Removes a world once every dimension is down. */
    public Optional<LoadedWorld> unregister(WorldId id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(worlds.remove(id));
    }

    public Optional<LoadedWorld> find(WorldId id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(worlds.get(id));
    }

    public boolean isLoaded(WorldId id) {
        Objects.requireNonNull(id, "id");
        return worlds.containsKey(id);
    }

    /**
     * Every loaded world, as a snapshot.
     *
     * <p>A copy, because the idle sweep unregisters while it iterates and the
     * whole point of FR-25a is that an unload can fail part-way and leave the map
     * changing under a caller.
     */
    public List<LoadedWorld> all() {
        Collection<LoadedWorld> values = worlds.values();
        return List.copyOf(values);
    }

    /** Worlds held here, for {@code worlds_loaded} and the FR-26 cap. */
    public int size() {
        return worlds.size();
    }

    /** Drops every entry. Used by the shutdown path once all worlds are down. */
    public void clear() {
        worlds.clear();
    }
}
