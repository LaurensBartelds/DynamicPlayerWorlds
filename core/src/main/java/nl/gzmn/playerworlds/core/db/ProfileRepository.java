package nl.gzmn.playerworlds.core.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import nl.gzmn.playerworlds.core.model.WorldId;

/**
 * {@code player_world_profile} (FR-14 to FR-17).
 *
 * <p>There is deliberately no "save one profile" entry point that opens its own
 * transaction. FR-15 requires profiles be persisted <em>only</em> as part of a
 * world snapshot commit, and FR-16 requires that commit be atomic across every
 * player in the world, so the write takes a {@link Connection} and the caller
 * owns the transaction — the same one that will carry the manifest pointer once
 * milestone 6 adds it.
 *
 * <p>FR-15a is the reason, and it is worth restating because this API shape is
 * the only thing enforcing it: profiles and world data live in different storage
 * systems, and any skew between their durability points is an item duplication
 * bug in one direction and an item destruction bug in the other. Committing both
 * through one transaction removes the window rather than narrowing it.
 */
public final class ProfileRepository extends Repository {

    public ProfileRepository(Database database) {
        super(database);
    }

    /** Which snapshot a set of profiles belongs to (FR-15b). */
    public record Snapshot(long generation, int sequence) implements Comparable<Snapshot> {
        @Override
        public int compareTo(Snapshot other) {
            int byGeneration = Long.compare(generation, other.generation);
            return byGeneration != 0 ? byGeneration : Integer.compare(sequence, other.sequence);
        }
    }

    /** A stored payload and the version tag needed to read it (FR-17). */
    // An opaque payload; there is nothing to wrap it in that would mean more,
    // and nothing compares two of these for equality.
    @SuppressWarnings("ArrayRecordComponent")
    public record StoredProfile(Snapshot snapshot, int formatVersion, byte[] data) {
        public StoredProfile {
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(data, "data");
        }
    }

    /**
     * Writes every player's profile for one snapshot, inside the caller's
     * transaction (FR-15, FR-16).
     *
     * @param payloads uuid to encoded envelope; an empty map is a valid commit
     *     for a world nobody was in
     * @return rows written
     */
    public int saveAll(
            Connection connection, WorldId worldId, Snapshot snapshot, int formatVersion, Map<UUID, byte[]> payloads)
            throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(payloads, "payloads");

        int written = 0;
        for (Map.Entry<UUID, byte[]> entry : payloads.entrySet()) {
            written += execute(connection, """
                    INSERT INTO player_world_profile (
                      world_id, uuid, generation, sequence, format_version, data
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (world_id, uuid, generation, sequence) DO UPDATE
                      SET format_version = excluded.format_version,
                          data = excluded.data,
                          updated_at = now()
                    """, statement -> {
                statement.setObject(1, worldId.value());
                statement.setObject(2, entry.getKey());
                statement.setLong(3, snapshot.generation());
                statement.setInt(4, snapshot.sequence());
                statement.setInt(5, formatVersion);
                statement.setBytes(6, entry.getValue());
            });
        }
        return written;
    }

    /**
     * One whole commit: pick the next sequence and write every payload, in a
     * single transaction (FR-15, FR-16).
     *
     * <p>The transaction-owning entry point, so a caller outside {@code :core}
     * never holds a {@link Connection}. It is also where milestone 6's manifest
     * pointer joins, which is the entire point of FR-15a — world data and
     * profiles reaching their durability point together rather than minutes
     * apart.
     *
     * @return the snapshot the payloads were written as
     */
    public Snapshot commit(WorldId worldId, long generation, int formatVersion, Map<UUID, byte[]> payloads)
            throws SQLException {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(payloads, "payloads");
        return database.inTransaction(connection -> {
            Snapshot snapshot = new Snapshot(generation, nextSequence(connection, worldId, generation));
            saveAll(connection, worldId, snapshot, formatVersion, payloads);
            return snapshot;
        });
    }

    /**
     * The newest snapshot this world has profiles for.
     *
     * <p>Milestone 6 replaces this with "the snapshot named by
     * {@code player_world.manifest_key}", which is what FR-15b actually specifies
     * — world and player state coming back from the same instant. Until a
     * manifest exists there is nothing to name it with, and the newest is the
     * only snapshot there is.
     */
    public Optional<Snapshot> latestSnapshot(WorldId worldId) throws SQLException {
        Objects.requireNonNull(worldId, "worldId");
        return database.withConnection(connection -> latestSnapshot(connection, worldId));
    }

    public Optional<Snapshot> latestSnapshot(Connection connection, WorldId worldId) throws SQLException {
        return queryOne(
                connection,
                """
                SELECT generation, sequence
                  FROM player_world_profile
                 WHERE world_id = ?
                 ORDER BY generation DESC, sequence DESC
                 LIMIT 1
                """,
                statement -> statement.setObject(1, worldId.value()),
                row -> new Snapshot(row.getLong("generation"), row.getInt("sequence")));
    }

    /**
     * The sequence the next commit in this generation should use.
     *
     * <p>Read inside the committing transaction so two commits cannot pick the
     * same one. The per-world single-flight queue already serialises commits on
     * one node; this is what holds once a second node can hold the lease.
     */
    public int nextSequence(Connection connection, WorldId worldId, long generation) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(worldId, "worldId");
        return queryOne(
                        connection,
                        """
                        SELECT coalesce(max(sequence), -1) + 1 AS next
                          FROM player_world_profile
                         WHERE world_id = ? AND generation = ?
                        """,
                        statement -> {
                            statement.setObject(1, worldId.value());
                            statement.setLong(2, generation);
                        },
                        row -> row.getInt("next"))
                .orElse(0);
    }

    /** One player's profile from a named snapshot (FR-15b). */
    public Optional<StoredProfile> load(WorldId worldId, UUID uuid, Snapshot snapshot) throws SQLException {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(snapshot, "snapshot");
        return database.withConnection(connection -> queryOne(
                connection,
                """
                SELECT generation, sequence, format_version, data
                  FROM player_world_profile
                 WHERE world_id = ? AND uuid = ? AND generation = ? AND sequence = ?
                """,
                statement -> {
                    statement.setObject(1, worldId.value());
                    statement.setObject(2, uuid);
                    statement.setLong(3, snapshot.generation());
                    statement.setInt(4, snapshot.sequence());
                },
                ProfileRepository::mapProfile));
    }

    /**
     * Every retained snapshot for one player, newest first.
     *
     * <p>This is what FR-16a's {@code /world admin profile} lists, and the reason
     * FR-15c retains more than one: a profile that cannot be deserialised fails
     * identically on every attempt, so the repair has to be a rollback to an
     * earlier snapshot rather than a wipe.
     */
    public List<StoredProfile> listSnapshots(WorldId worldId, UUID uuid) throws SQLException {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(uuid, "uuid");
        return database.withConnection(connection -> queryList(
                connection,
                """
                SELECT generation, sequence, format_version, data
                  FROM player_world_profile
                 WHERE world_id = ? AND uuid = ?
                 ORDER BY generation DESC, sequence DESC
                """,
                statement -> {
                    statement.setObject(1, worldId.value());
                    statement.setObject(2, uuid);
                },
                ProfileRepository::mapProfile));
    }

    /**
     * Drops all but the newest {@code keep} snapshots of a world (FR-15c).
     *
     * <p>Manifests are pruned to the same count by the same job, because they are
     * one key: pruning manifests faster than profiles makes a load find a
     * {@code manifest_key} whose profiles are gone and issue every player a fresh
     * inventory under FR-15b — silent, total loss for that world (ADR 0007).
     *
     * @return rows removed
     */
    public int pruneToLatest(WorldId worldId, int keep) throws SQLException {
        Objects.requireNonNull(worldId, "worldId");
        if (keep < 1) {
            throw new IllegalArgumentException("keep must be at least 1, was: " + keep);
        }
        return database.inTransaction(connection -> execute(connection, """
                DELETE FROM player_world_profile
                 WHERE world_id = ?
                   AND (generation, sequence) NOT IN (
                     SELECT generation, sequence
                       FROM player_world_profile
                      WHERE world_id = ?
                      GROUP BY generation, sequence
                      ORDER BY generation DESC, sequence DESC
                      LIMIT ?
                   )
                """, statement -> {
            statement.setObject(1, worldId.value());
            statement.setObject(2, worldId.value());
            statement.setInt(3, keep);
        }));
    }

    private static StoredProfile mapProfile(ResultSet row) throws SQLException {
        return new StoredProfile(
                new Snapshot(row.getLong("generation"), row.getInt("sequence")),
                row.getInt("format_version"),
                Objects.requireNonNull(row.getBytes("data"), "data"));
    }
}
