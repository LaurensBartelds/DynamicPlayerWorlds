package nl.gzmn.playerworlds.backend.world;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import nl.gzmn.playerworlds.core.model.Role;
import nl.gzmn.playerworlds.core.model.WorldId;

/**
 * Roles for the worlds this node holds, readable from the tick thread.
 *
 * <p>Role enforcement happens in {@code BlockBreakEvent} and its neighbours,
 * which run on the main thread and therefore cannot query (NFR-2). The whole
 * membership of a world is small — a handful of players — so a node reads it
 * once when the world loads and keeps it until membership changes.
 *
 * <p>This is the shape plan 00's Q3 predicted for the proxy's tab completion,
 * arriving first on the node: a cache filled on load and invalidated over the
 * control plane. Until milestone 5 wires {@code INVALIDATE_CACHE}, a membership
 * change made on the proxy reaches a loaded world on its next load — which is
 * stated in the milestone notes rather than hidden here.
 *
 * <p>Ownership is stored separately from the roles rather than being read out of
 * them, because {@code player_world.owner_uuid} is authoritative and the
 * {@code OWNER} role value is a denormalised convenience that loses every
 * disagreement (FR-31a).
 */
public final class MembershipCache {

    private final ConcurrentMap<WorldId, Entry> byWorld = new ConcurrentHashMap<>();

    /** Replaces the cached membership for one world. */
    public void put(WorldId worldId, UUID ownerUuid, Map<UUID, Role> roles) {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(roles, "roles");
        byWorld.put(worldId, new Entry(ownerUuid, Map.copyOf(roles)));
    }

    /** Drops a world's membership, when it unloads or when it changes. */
    public void invalidate(WorldId worldId) {
        Objects.requireNonNull(worldId, "worldId");
        byWorld.remove(worldId);
    }

    public void clear() {
        byWorld.clear();
    }

    /** Whether this node has membership for the world at all. */
    public boolean isCached(WorldId worldId) {
        Objects.requireNonNull(worldId, "worldId");
        return byWorld.containsKey(worldId);
    }

    /**
     * The player's role in this world.
     *
     * <p>Empty means "not a member", which callers treat as the least-privileged
     * answer rather than as an error. Empty is also what an uncached world
     * returns, and that is deliberate: enforcing as if the player had no rights
     * is the safe direction to fail in, and the alternative — assuming they may
     * build — hands a stranger a world on a cache miss.
     */
    public Optional<Role> roleOf(WorldId worldId, UUID uuid) {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(uuid, "uuid");
        Entry entry = byWorld.get(worldId);
        if (entry == null) {
            return Optional.empty();
        }
        // owner_uuid wins over the role map (FR-31a). If the two ever disagree —
        // a half-applied transfer, a hand-edited row — the world still answers to
        // the player the world table says owns it.
        if (entry.ownerUuid().equals(uuid)) {
            return Optional.of(Role.OWNER);
        }
        return Optional.ofNullable(entry.roles().get(uuid));
    }

    /**
     * The effective role for enforcement, treating a non-member as a visitor.
     *
     * <p>A player standing in a world they are not a member of should not be
     * possible once milestone 3's isolation lands. Until then, and afterwards for
     * anything that slips through, they get VISITOR — which under FR-9 cannot
     * build and cannot open containers unless the owner allowed it.
     */
    public Role effectiveRole(WorldId worldId, UUID uuid) {
        return roleOf(worldId, uuid).orElse(Role.VISITOR);
    }

    /** Cached worlds, for meters and tests. */
    public int size() {
        return byWorld.size();
    }

    private record Entry(UUID ownerUuid, Map<UUID, Role> roles) {}
}
