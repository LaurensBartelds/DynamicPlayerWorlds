package nl.gzmn.playerworlds.core.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One {@code player_world_invite} row (FR-6).
 *
 * <p>Whether an invite has expired is never decided by reading this record's
 * {@code expiresAt} against a local clock. Expiry is a predicate in the SQL that
 * reads the row, evaluated in database time, because node clocks drift and an
 * invite that looks live on one node and dead on another is the same class of
 * bug as a lease decided locally (CONTRIBUTING rule 5).
 *
 * @param worldId the world being offered
 * @param uuid the invitee
 * @param invitedBy the owner who sent it
 * @param expiresAt database time the invite lapses ({@code invites.expiry-minutes})
 */
public record WorldInvite(WorldId worldId, UUID uuid, UUID invitedBy, Instant expiresAt) {

    public WorldInvite {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(invitedBy, "invitedBy");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
