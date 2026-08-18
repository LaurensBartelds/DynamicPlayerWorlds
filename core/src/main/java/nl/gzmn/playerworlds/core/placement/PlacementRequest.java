package nl.gzmn.playerworlds.core.placement;

import java.util.Objects;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository.PlacementContext;
import nl.gzmn.playerworlds.core.model.Visibility;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.jspecify.annotations.Nullable;

/**
 * The world a placement decision is being taken for.
 *
 * @param worldId for logging and metrics; placement never keys on it
 * @param dataVersion the world's committed chunk {@code DataVersion}, or
 *     {@code null} when it has never been committed and every node may take it
 *     (MN-26, MN-28)
 * @param visibility MN-15a's public/private separation term
 * @param leaseHolder the node whose lease is live now, in database time, or
 *     {@code null}. Present means MN-14 routes there and nothing is scored.
 * @param warmNode the node that wrote the current manifest and most likely still
 *     holds the files (MN-15a, MN-5), or {@code null}
 */
public record PlacementRequest(
        WorldId worldId,
        @Nullable Integer dataVersion,
        Visibility visibility,
        @Nullable String leaseHolder,
        @Nullable String warmNode) {

    public PlacementRequest {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(visibility, "visibility");
    }

    /** From the row placement was going to read anyway. */
    public static PlacementRequest of(WorldId worldId, PlacementContext context) {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(context, "context");
        return new PlacementRequest(
                worldId, context.dataVersion(), context.visibility(), context.leaseHolder(), context.warmNode());
    }

    /**
     * A world that does not exist yet (FR-1a).
     *
     * <p>No committed data version, so MN-28 excludes nothing; no lease and no
     * warm copy, so the whole decision is MN-15's scoring. The visibility is the
     * one the world is about to be created with, so MN-15a's separation applies
     * from the first placement rather than from the first migration.
     */
    public static PlacementRequest forNewWorld(WorldId worldId, Visibility visibility) {
        return new PlacementRequest(worldId, null, visibility, null, null);
    }
}
