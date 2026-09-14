package nl.gzmn.playerworlds.core.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import nl.gzmn.playerworlds.core.model.UpgradeKind;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.model.WorldUpgrade;
import org.jspecify.annotations.Nullable;

/**
 * {@code world_upgrade}: one-time purchased upgrades (FR-44, FR-45, FR-47).
 *
 * <p>Subscriptions are not here and must not be. They are permissions, granted and revoked
 * by whatever the network already uses for ranks, and a copy of "is this player still
 * subscribed" in this table would be a second answer to a question the permission backend
 * is authoritative about — the copy being the one that goes stale after a chargeback
 * (FR-41).
 */
public final class WorldUpgradeRepository extends Repository {

    public WorldUpgradeRepository(Database database) {
        super(database);
    }

    /** A grant, and whether this call is what created it. */
    public record Grant(WorldUpgrade upgrade, boolean created) {
        public Grant {
            Objects.requireNonNull(upgrade, "upgrade");
        }
    }

    /**
     * Records a purchase, idempotently on {@code reference} (FR-44, CONTRIBUTING rule 7).
     *
     * <p>A webstore that retries a delivery, or a staff member who runs the command twice
     * because the first appeared to hang, grants one upgrade. The repeat is reported rather
     * than thrown, because "you have already delivered this" is an outcome the caller has
     * something to say about, not a failure it has to interpret from a constraint name.
     *
     * <p>{@code ON CONFLICT DO NOTHING} followed by a read rather than {@code DO UPDATE}:
     * the second delivery of a transaction must not be able to change the first, least of
     * all its amount.
     *
     * @param ownerUuid who bought it
     * @param kind what it raises
     * @param amount bytes or blocks, positive
     * @param reference the grantor's transaction id
     * @return the stored upgrade, and whether this call created it
     */
    public Grant grant(UUID ownerUuid, UpgradeKind kind, long amount, String reference) throws SQLException {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(reference, "reference");
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive: " + amount);
        }

        return database.inTransaction(connection -> {
            Optional<WorldUpgrade> inserted = queryOne(
                    connection,
                    """
                    INSERT INTO world_upgrade (id, owner_uuid, kind, amount, reference)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (reference) DO NOTHING
                    RETURNING id, owner_uuid, world_id, kind, amount, reference, granted_at, redeemed_at
                    """,
                    statement -> {
                        statement.setObject(1, UUID.randomUUID());
                        statement.setObject(2, ownerUuid);
                        statement.setString(3, kind.name());
                        statement.setLong(4, amount);
                        statement.setString(5, reference);
                    },
                    WorldUpgradeRepository::mapUpgrade);
            if (inserted.isPresent()) {
                return new Grant(inserted.get(), true);
            }
            return new Grant(
                    findByReference(connection, reference)
                            .orElseThrow(() -> new SQLException(
                                    "world_upgrade reference '" + reference + "' conflicted but could not be read")),
                    false);
        });
    }

    /**
     * Spends an unredeemed upgrade on a world (FR-45).
     *
     * <p>Conditional on the upgrade still being unredeemed and still belonging to the
     * caller, so two clicks on the same button redeem once and a player cannot spend
     * somebody else's purchase. Empty means the condition did not hold — already redeemed,
     * or not theirs — and the caller says which by re-reading.
     *
     * @param id the upgrade to redeem
     * @param ownerUuid the player redeeming it, which must be its owner
     * @param worldId the world to spend it on
     * @return the redeemed upgrade, or empty when nothing was redeemed
     */
    public Optional<WorldUpgrade> redeem(UUID id, UUID ownerUuid, WorldId worldId) throws SQLException {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(worldId, "worldId");

        return database.inTransaction(connection -> queryOne(
                connection,
                """
                UPDATE world_upgrade
                   SET world_id = ?, redeemed_at = now()
                 WHERE id = ?
                   AND owner_uuid = ?
                   AND world_id IS NULL
                   AND redeemed_at IS NULL
                RETURNING id, owner_uuid, world_id, kind, amount, reference, granted_at, redeemed_at
                """,
                statement -> {
                    statement.setObject(1, worldId.value());
                    statement.setObject(2, id);
                    statement.setObject(3, ownerUuid);
                },
                WorldUpgradeRepository::mapUpgrade));
    }

    /**
     * Removes a grant by reference — a refund, or a mis-delivery.
     *
     * <p>Refuses a redeemed upgrade rather than clawing it back. Revoking one that has been
     * spent on a border would leave that world enlarged past what its owner is entitled to,
     * and FR-3c forbids the only correction that would fix it.
     *
     * @param reference the grantor's transaction id
     * @return true when a row was removed
     */
    public boolean revoke(String reference) throws SQLException {
        Objects.requireNonNull(reference, "reference");
        return database.inTransaction(connection -> execute(
                        connection,
                        "DELETE FROM world_upgrade WHERE reference = ? AND world_id IS NULL",
                        statement -> statement.setString(1, reference))
                > 0);
    }

    /**
     * Every upgrade a player holds, newest grant first.
     *
     * @param ownerUuid the player
     * @return their upgrades, redeemed and not
     */
    public List<WorldUpgrade> listOwnedBy(UUID ownerUuid) throws SQLException {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        return database.withConnection(connection -> queryList(
                connection, """
                SELECT id, owner_uuid, world_id, kind, amount, reference, granted_at, redeemed_at
                  FROM world_upgrade
                 WHERE owner_uuid = ?
                 ORDER BY granted_at DESC, id
                """, statement -> statement.setObject(1, ownerUuid), WorldUpgradeRepository::mapUpgrade));
    }

    /**
     * A player's unspent upgrades, oldest grant first (FR-45).
     *
     * <p>Oldest first because that is the order they should be spent in: an upgrade bought in
     * March and one bought in June are interchangeable, and spending the older one leaves the
     * player holding the one whose purchase they are likelier to still remember. It also gives
     * the menu a deterministic "redeem one of these" without putting an id in a button.
     *
     * @param ownerUuid the player
     * @return their unspent upgrades, oldest first
     */
    public List<WorldUpgrade> listUnredeemed(UUID ownerUuid) throws SQLException {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        return database.withConnection(connection -> queryList(
                connection, """
                SELECT id, owner_uuid, world_id, kind, amount, reference, granted_at, redeemed_at
                  FROM world_upgrade
                 WHERE owner_uuid = ?
                   AND world_id IS NULL
                 ORDER BY granted_at, id
                """, statement -> statement.setObject(1, ownerUuid), WorldUpgradeRepository::mapUpgrade));
    }

    /**
     * The bytes a player's redeemed {@code STORAGE} upgrades add to their allowance (FR-45).
     *
     * <p>Unredeemed upgrades count for nothing: an upgrade does nothing until it is spent,
     * which is what gives the player a choice about where it goes.
     *
     * @param ownerUuid the player
     * @return bytes to add to the network allowance, zero when they have none
     */
    public long bonusStorageBytes(UUID ownerUuid) throws SQLException {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        return database.withConnection(
                connection -> sumAmount(connection, """
                SELECT COALESCE(SUM(amount), 0) AS total
                  FROM world_upgrade
                 WHERE owner_uuid = ?
                   AND kind = 'STORAGE'
                   AND world_id IS NOT NULL
                """, statement -> statement.setObject(1, ownerUuid)));
    }

    /**
     * The blocks a world's redeemed {@code BORDER} upgrades add to its permitted radius.
     *
     * @param worldId the world
     * @return blocks of radius above the player's subscription allowance, zero when none
     */
    public long borderBonusBlocks(WorldId worldId) throws SQLException {
        Objects.requireNonNull(worldId, "worldId");
        return database.withConnection(connection -> borderBonusBlocks(connection, worldId));
    }

    /** As {@link #borderBonusBlocks(WorldId)}, inside a caller's transaction. */
    public long borderBonusBlocks(Connection connection, WorldId worldId) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(worldId, "worldId");
        return sumAmount(connection, """
                SELECT COALESCE(SUM(amount), 0) AS total
                  FROM world_upgrade
                 WHERE world_id = ?
                   AND kind = 'BORDER'
                """, statement -> statement.setObject(1, worldId.value()));
    }

    /**
     * One upgrade by id.
     *
     * @param id the upgrade
     * @return it, or empty when there is no such row
     */
    public Optional<WorldUpgrade> findById(UUID id) throws SQLException {
        Objects.requireNonNull(id, "id");
        return database.withConnection(connection ->
                queryOne(connection, """
                SELECT id, owner_uuid, world_id, kind, amount, reference, granted_at, redeemed_at
                  FROM world_upgrade
                 WHERE id = ?
                """, statement -> statement.setObject(1, id), WorldUpgradeRepository::mapUpgrade));
    }

    private static Optional<WorldUpgrade> findByReference(Connection connection, String reference) throws SQLException {
        return queryOne(
                connection, """
                SELECT id, owner_uuid, world_id, kind, amount, reference, granted_at, redeemed_at
                  FROM world_upgrade
                 WHERE reference = ?
                """, statement -> statement.setString(1, reference), WorldUpgradeRepository::mapUpgrade);
    }

    private static long sumAmount(Connection connection, String sql, StatementBinder binder) throws SQLException {
        return queryOne(connection, sql, binder, rows -> rows.getLong("total")).orElse(0L);
    }

    private static WorldUpgrade mapUpgrade(ResultSet rows) throws SQLException {
        UUID worldUuid = rows.getObject("world_id", UUID.class);
        String kind = rows.getString("kind");
        return new WorldUpgrade(
                Objects.requireNonNull(rows.getObject("id", UUID.class), "id"),
                Objects.requireNonNull(rows.getObject("owner_uuid", UUID.class), "owner_uuid"),
                worldUuid == null ? null : new WorldId(worldUuid),
                UpgradeKind.parse(kind).orElseThrow(() -> new SQLException("unknown world_upgrade.kind: " + kind)),
                rows.getLong("amount"),
                rows.getString("reference"),
                instant(rows.getObject("granted_at", OffsetDateTime.class)),
                nullableInstant(rows.getObject("redeemed_at", OffsetDateTime.class)));
    }

    private static Instant instant(@Nullable OffsetDateTime value) throws SQLException {
        if (value == null) {
            throw new SQLException("expected a non-null timestamp");
        }
        return value.toInstant();
    }

    private static @Nullable Instant nullableInstant(@Nullable OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
