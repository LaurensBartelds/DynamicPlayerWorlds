package nl.gzmn.playerworlds.core.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One {@code player_world} row.
 *
 * <p>A value object, not a handle: nothing here changes when the database does,
 * and a caller holding one is holding a snapshot of the row as it was read. That
 * is deliberate — the fields that move under a caller's feet ({@code generation},
 * {@code lease_expires}) are exactly the fields whose staleness MN-3a and MN-8
 * detect through a conditional {@code UPDATE} rather than by re-reading.
 *
 * <p>{@code owner_uuid} is authoritative for ownership. The {@code OWNER} value
 * in {@code player_world_member.role} is a denormalised convenience and loses
 * every disagreement (FR-31a).
 *
 * @param id world identity; also the source of {@link WorldId#folder()} (FR-2a)
 * @param ownerUuid authoritative owner (FR-31a)
 * @param name free text, shown to players and used by {@code /world join}
 * @param folder live world folder name, derived from {@code id}, never from
 *     {@code name} (FR-2a)
 * @param seed shared by all three dimensions, so a dimension materialised later
 *     is identical to one created up front (FR-2)
 * @param borderRadius overworld and end radius in blocks; the nether uses this
 *     divided by {@code worlds.nether-border-divisor} (FR-3)
 * @param visibility FR-9a
 * @param description one line shown in {@code /world browse}; {@code null} when unset
 * @param settingsJson per-world owner settings as JSONB text (FR-9e)
 * @param assignedNode node holding the lease, {@code null} when unleased (MN-8)
 * @param leaseExpires database time the lease lapses, {@code null} when unleased
 * @param generation bumped on every lease acquisition; the fencing token (MN-3a)
 * @param manifestKey current committed snapshot, {@code null} before the first
 *     upload (MN-3)
 * @param dataVersion chunk {@code DataVersion} of the last commit, {@code null}
 *     before the first upload. A node whose own data version is lower must
 *     refuse this world (MN-26)
 * @param mcVersion display only; never compared, because version strings do not
 *     order reliably (MN-27)
 * @param createdAt database time
 * @param lastPlayed database time of the last session, {@code null} if never played
 * @param state FR-35 / FR-36 lifecycle state
 * @param storageBytes size on the owner's allowance: live snapshot bytes while the world is
 *     READY, archive bytes once it is ARCHIVED (§4)
 */
public record PlayerWorld(
        WorldId id,
        UUID ownerUuid,
        String name,
        String folder,
        long seed,
        int borderRadius,
        Visibility visibility,
        @Nullable String description,
        String settingsJson,
        @Nullable String assignedNode,
        @Nullable Instant leaseExpires,
        long generation,
        @Nullable String manifestKey,
        @Nullable Integer dataVersion,
        @Nullable String mcVersion,
        Instant createdAt,
        @Nullable Instant lastPlayed,
        WorldState state,
        long storageBytes) {

    /** Empty per-world settings, as the schema default writes them. */
    public static final String EMPTY_SETTINGS = "{}";

    public PlayerWorld {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(folder, "folder");
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(settingsJson, "settingsJson");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(state, "state");

        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (borderRadius < 1) {
            throw new IllegalArgumentException("borderRadius must be at least 1, was: " + borderRadius);
        }
        if (generation < 0) {
            throw new IllegalArgumentException("generation must not be negative, was: " + generation);
        }
        if (storageBytes < 0) {
            throw new IllegalArgumentException("storageBytes must not be negative, was: " + storageBytes);
        }
        if (!folder.equals(id.folder())) {
            // FR-2a is not advice. A folder that does not follow from the id means
            // either player text reached a filesystem path or two worlds can
            // collide through a sibling dimension folder, and the UNIQUE constraint
            // on `folder` catches neither.
            throw new IllegalArgumentException(
                    "folder must be derived from the world id (FR-2a): expected " + id.folder() + ", was " + folder);
        }
    }

    /**
     * Whether a node at {@code nodeDataVersion} may open this world (MN-26).
     *
     * <p>A world with no committed snapshot has no data version yet and is
     * openable by any node. Once it has one, chunk {@code DataVersion} only moves
     * forward — a world last saved by a newer server cannot be opened by an older
     * one, and Minecraft has no supported chunk downgrade, so the gate turns
     * corruption into a clean refusal.
     */
    public boolean isOpenableBy(int nodeDataVersion) {
        Integer worldVersion = dataVersion;
        return worldVersion == null || worldVersion <= nodeDataVersion;
    }

    public PlayerWorld withLease(String node, Instant expires, long gen) {
        return new PlayerWorld(
                id,
                ownerUuid,
                name,
                folder,
                seed,
                borderRadius,
                visibility,
                description,
                settingsJson,
                node,
                expires,
                gen,
                manifestKey,
                dataVersion,
                mcVersion,
                createdAt,
                lastPlayed,
                state,
                0L);
    }
}
