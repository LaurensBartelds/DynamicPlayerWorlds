package nl.gzmn.playerworlds.backend.world;

import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import nl.gzmn.playerworlds.core.db.MembershipRepository;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.model.WorldSettings;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fills the two tick-thread caches from the authoritative rows (FR-9, FR-9e,
 * FR-31a).
 *
 * <p>Role enforcement and the per-world settings are read on the main thread and
 * so cannot query (NFR-2). That is why {@link MembershipCache} and
 * {@link WorldSettingsCache} exist. What was missing was the other half: a way to
 * <em>fill</em> them outside the load path.
 *
 * <p>Without it, {@code INVALIDATE_CACHE} could only evict, and both caches
 * answer a miss with a value rather than an absence — {@code effectiveRole}
 * returns {@link nl.gzmn.playerworlds.core.model.Role#VISITOR} and
 * {@link WorldSettingsCache#get} returns {@link WorldSettings#defaults()}. On a
 * world nobody has loaded that is the safe direction to be wrong in. On a world
 * that is loaded and being played in it means every membership change demoted
 * the owner in their own world and reset the container rule to its default until
 * the world unloaded. Evicting a cache whose miss is a wrong answer is not a
 * refresh, and the six producers that send {@code INVALIDATE_CACHE} all wanted
 * a refresh.
 *
 * <p>One implementation, used by the load path and by the control plane, because
 * FR-31a's precedence — {@code player_world.owner_uuid} wins over the
 * {@code OWNER} role value — is a rule that must not exist twice.
 */
public final class WorldCacheLoader {

    private static final Logger log = LoggerFactory.getLogger(WorldCacheLoader.class);

    private final PlayerWorldRepository worlds;
    private final MembershipRepository membership;
    private final MembershipCache membershipCache;
    private final @Nullable WorldSettingsCache settingsCache;

    public WorldCacheLoader(
            PlayerWorldRepository worlds,
            MembershipRepository membership,
            MembershipCache membershipCache,
            @Nullable WorldSettingsCache settingsCache) {
        this.worlds = Objects.requireNonNull(worlds, "worlds");
        this.membership = Objects.requireNonNull(membership, "membership");
        this.membershipCache = Objects.requireNonNull(membershipCache, "membershipCache");
        this.settingsCache = settingsCache;
    }

    /**
     * Re-reads one world's membership and settings and publishes both.
     *
     * <p>Blocking JDBC: call it on {@link nl.gzmn.playerworlds.core.concurrent.PluginExecutors#db()}.
     *
     * @return false when the world no longer exists, in which case both caches
     *     are dropped for it rather than left holding a row that is gone
     */
    public boolean refresh(WorldId worldId) throws SQLException {
        Objects.requireNonNull(worldId, "worldId");
        Optional<PlayerWorld> found = worlds.findById(worldId);
        if (found.isEmpty()) {
            membershipCache.invalidate(worldId);
            if (settingsCache != null) {
                settingsCache.invalidate(worldId);
            }
            return false;
        }
        publish(found.get());
        return true;
    }

    /**
     * Publishes from a row the caller already holds, saving the re-read on the
     * load path where the row was just fetched.
     */
    public void publish(PlayerWorld row) throws SQLException {
        Objects.requireNonNull(row, "row");
        // owner_uuid and the role map are published together, from the same row,
        // so the FR-31a precedence the cache applies is never mixed across reads.
        membershipCache.put(row.id(), row.ownerUuid(), membership.rolesIn(row.id()));
        if (settingsCache != null) {
            settingsCache.put(row.id(), WorldSettings.fromJson(row.settingsJson()));
        }
    }

    /**
     * Refreshes without propagating, for callers that cannot act on a failure.
     *
     * <p>Failure is not fatal but it is not silent either: an empty membership
     * cache makes every player a visitor, which is the safe direction and a very
     * visible one (FR-9).
     */
    public void refreshQuietly(WorldId worldId) {
        try {
            refresh(worldId);
        } catch (SQLException e) {
            log.error(
                    "could not refresh the caches for world {}; until it reloads every player there is "
                            + "treated as a visitor and its settings read as defaults (FR-9, FR-9e)",
                    worldId,
                    e);
        }
    }
}
