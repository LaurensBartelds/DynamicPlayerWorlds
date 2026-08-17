package nl.gzmn.playerworlds.core.db;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import nl.gzmn.playerworlds.core.model.WorldId;

/**
 * {@code pending_transfer} — the proxy's handoff into a node (FR-10, FR-11).
 *
 * <p>A row here says "this player is on their way to this world, on this node,
 * against this lease generation". The node reads it when the player arrives and
 * decides where to put them.
 *
 * <p>The generation is the whole reason the row carries more than a world id.
 * FR-11 requires the node reject a transfer whose {@code generation} does not
 * match the lease it holds, because the world may have moved between the proxy
 * routing the player and the player arriving — and a node that honoured a stale
 * route would load a world another node now owns.
 *
 * <p>Expiry is evaluated in database time, in the predicate, for the same reason
 * every other expiry in this schema is (CONTRIBUTING rule 5).
 */
public final class PendingTransferRepository extends Repository {

    public PendingTransferRepository(Database database) {
        super(database);
    }

    /** A routed handoff waiting to be claimed. */
    public record PendingTransfer(UUID uuid, WorldId worldId, String nodeId, long generation) {
        public PendingTransfer {
            Objects.requireNonNull(uuid, "uuid");
            Objects.requireNonNull(worldId, "worldId");
            Objects.requireNonNull(nodeId, "nodeId");
        }
    }

    /**
     * Routes a player to a world on a node.
     *
     * <p>Upserts, because a player who runs {@code /world join} twice, or joins a
     * different world before the first transfer is consumed, should end up going
     * where they asked most recently rather than being refused over a row they
     * cannot see.
     */
    public void route(UUID uuid, WorldId worldId, String nodeId, long generation) throws SQLException {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(nodeId, "nodeId");
        database.inTransaction(connection -> execute(connection, """
                INSERT INTO pending_transfer (uuid, world_id, node_id, generation)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (uuid) DO UPDATE
                  SET world_id = excluded.world_id,
                      node_id = excluded.node_id,
                      generation = excluded.generation,
                      created_at = now()
                """, statement -> {
            statement.setObject(1, uuid);
            statement.setObject(2, worldId.value());
            statement.setString(3, nodeId);
            statement.setLong(4, generation);
        }));
    }

    /**
     * Takes the player's unexpired transfer, removing it (FR-11).
     *
     * <p>A single statement that reads and deletes, so a transfer cannot be
     * consumed twice — by a reconnect racing the first join, or by two handlers
     * on the same node. Returning empty is the "row is missing or expired" branch
     * FR-11 sends to lobby.
     *
     * @param expiry {@code transfers.expiry-seconds}
     */
    public Optional<PendingTransfer> claim(UUID uuid, Duration expiry) throws SQLException {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(expiry, "expiry");
        return database.inTransaction(connection -> queryOne(
                connection,
                """
                DELETE FROM pending_transfer
                 WHERE uuid = ?
                   AND created_at > now() - (? * INTERVAL '1 second')
                RETURNING uuid, world_id, node_id, generation
                """,
                statement -> {
                    statement.setObject(1, uuid);
                    statement.setDouble(2, expiry.toMillis() / 1000.0);
                },
                row -> new PendingTransfer(
                        Objects.requireNonNull(row.getObject("uuid", UUID.class), "uuid"),
                        new WorldId(Objects.requireNonNull(row.getObject("world_id", UUID.class), "world_id")),
                        Objects.requireNonNull(row.getString("node_id"), "node_id"),
                        row.getLong("generation"))));
    }

    /** Drops a routed transfer that will not be used, so it cannot be claimed later. */
    public boolean cancel(UUID uuid) throws SQLException {
        Objects.requireNonNull(uuid, "uuid");
        return database.inTransaction(connection -> execute(
                        connection,
                        "DELETE FROM pending_transfer WHERE uuid = ?",
                        statement -> statement.setObject(1, uuid))
                == 1);
    }

    /**
     * Removes every expired row (FR-40's sweep).
     *
     * <p>Claim already ignores expired rows, so this is hygiene rather than
     * correctness — but an unswept table grows for as long as players change
     * their minds.
     */
    public int sweepExpired(Duration expiry) throws SQLException {
        Objects.requireNonNull(expiry, "expiry");
        return database.inTransaction(connection -> execute(
                connection,
                "DELETE FROM pending_transfer WHERE created_at <= now() - (? * INTERVAL '1 second')",
                statement -> statement.setDouble(1, expiry.toMillis() / 1000.0)));
    }

    /** Records where a player was, for FR-13's resume prompt. */
    public void rememberLastWorld(UUID uuid, WorldId worldId) throws SQLException {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(worldId, "worldId");
        database.inTransaction(connection -> execute(connection, """
                INSERT INTO player_last_world (uuid, world_id, left_at)
                VALUES (?, ?, now())
                ON CONFLICT (uuid) DO UPDATE
                  SET world_id = excluded.world_id,
                      left_at = now()
                """, statement -> {
            statement.setObject(1, uuid);
            statement.setObject(2, worldId.value());
        }));
    }

    /** The world this player last left, for FR-13's resume prompt. */
    public Optional<WorldId> lastWorld(UUID uuid) throws SQLException {
        Objects.requireNonNull(uuid, "uuid");
        return database.withConnection(connection -> queryOne(
                connection,
                "SELECT world_id FROM player_last_world WHERE uuid = ?",
                statement -> statement.setObject(1, uuid),
                row -> new WorldId(Objects.requireNonNull(row.getObject("world_id", UUID.class), "world_id"))));
    }
}
