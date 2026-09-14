package nl.gzmn.playerworlds.core.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One {@code world_upgrade} row: a one-time purchase, redeemed or not (FR-44, FR-45).
 *
 * <p>A value object, like {@link PlayerWorld}: a caller holding one holds the row as it was
 * read. Redemption is a conditional {@code UPDATE} in the repository rather than a setter
 * here, so two clients redeeming the same upgrade at once produce one redemption.
 *
 * @param id the upgrade's own identity
 * @param ownerUuid who bought it. Never changes — not even when the world it is redeemed
 *     against is transferred to somebody else under FR-31, because the purchase was theirs
 *     and the world was only where they spent it
 * @param worldId the world it is redeemed against, or {@code null} when it is still
 *     unredeemed. Cleared again if that world is deleted (FR-47)
 * @param kind what it raises (FR-45)
 * @param amount bytes for {@link UpgradeKind#STORAGE}, blocks of radius for
 *     {@link UpgradeKind#BORDER}. Always positive; the database says so too
 * @param reference the grantor's transaction id, unique across the table. This is the whole
 *     of what makes granting idempotent (FR-44, CONTRIBUTING rule 7)
 * @param grantedAt database time the grant landed
 * @param redeemedAt database time it was redeemed, {@code null} together with
 *     {@code worldId}
 */
public record WorldUpgrade(
        UUID id,
        UUID ownerUuid,
        @Nullable WorldId worldId,
        UpgradeKind kind,
        long amount,
        String reference,
        Instant grantedAt,
        @Nullable Instant redeemedAt) {

    public WorldUpgrade {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(grantedAt, "grantedAt");
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive: " + amount);
        }
        if (reference.isBlank()) {
            throw new IllegalArgumentException("reference must not be blank");
        }
        // The two redemption columns are set and cleared together, and the database has the
        // same CHECK. Half a redemption would leave every reader to decide for itself which
        // column means redeemed.
        if ((worldId == null) != (redeemedAt == null)) {
            throw new IllegalArgumentException("worldId and redeemedAt must be set together, was worldId=" + worldId
                    + " redeemedAt=" + redeemedAt);
        }
    }

    /** Whether this upgrade is still waiting to be spent on a world. */
    public boolean unredeemed() {
        return worldId == null;
    }
}
