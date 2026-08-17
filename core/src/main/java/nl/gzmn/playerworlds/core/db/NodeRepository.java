package nl.gzmn.playerworlds.core.db;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * {@code worlds_node} — the heartbeat every node publishes (MN-17, MN-18).
 *
 * <p>{@code last_seen} is always database {@code now()}, never a value the node
 * supplies. Liveness is compared across nodes whose clocks drift independently,
 * and a node with a fast clock would otherwise look alive after it died
 * (CONTRIBUTING rule 5).
 *
 * <p>MN-18's ordering constraint — {@code nodes.dead-after-seconds} strictly
 * below {@code nodes.lease-seconds} — is enforced at startup by
 * {@code ConfigValidator}, not here. This class only reports who has beaten
 * recently; takeover eligibility is governed solely by lease expiry (MN-8).
 */
public final class NodeRepository extends Repository {

    public NodeRepository(Database database) {
        super(database);
    }

    /**
     * One node's heartbeat row (MN-18).
     *
     * @param dataVersion the chunk {@code DataVersion} placement filters on
     *     before any other term is evaluated (MN-15, MN-28)
     */
    public record NodeStatus(
            String nodeId,
            String address,
            int loadedWorlds,
            int onlinePlayers,
            /** Absent until the node reports one; MN-15 excludes on it only when present. */
            @Nullable Integer heapPercent,
            @Nullable Double tps,
            boolean draining,
            int dataVersion,
            String mcVersion,
            Instant lastSeen) {

        public NodeStatus {
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(address, "address");
            Objects.requireNonNull(mcVersion, "mcVersion");
            Objects.requireNonNull(lastSeen, "lastSeen");
        }
    }

    /**
     * Publishes this node's heartbeat, stamped with database time.
     *
     * <p>Called on the node's own schedule ({@code node.heartbeat-seconds}). The
     * first call registers the node; every later one refreshes it, which is why
     * this is an upsert rather than a separate register and update.
     */
    public void heartbeat(
            String nodeId,
            String address,
            int loadedWorlds,
            int onlinePlayers,
            @Nullable Integer heapPercent,
            @Nullable Double tps,
            boolean draining,
            int dataVersion,
            String mcVersion)
            throws SQLException {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(mcVersion, "mcVersion");
        database.inTransaction(connection -> execute(connection, """
                INSERT INTO worlds_node (
                  node_id, address, loaded_worlds, online_players, heap_percent, tps,
                  draining, data_version, mc_version, last_seen
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (node_id) DO UPDATE
                  SET address = excluded.address,
                      loaded_worlds = excluded.loaded_worlds,
                      online_players = excluded.online_players,
                      heap_percent = excluded.heap_percent,
                      tps = excluded.tps,
                      draining = excluded.draining,
                      data_version = excluded.data_version,
                      mc_version = excluded.mc_version,
                      last_seen = now()
                """, statement -> {
            statement.setString(1, nodeId);
            statement.setString(2, address);
            statement.setInt(3, loadedWorlds);
            statement.setInt(4, onlinePlayers);
            if (heapPercent == null) {
                statement.setNull(5, java.sql.Types.INTEGER);
            } else {
                statement.setInt(5, heapPercent);
            }
            if (tps == null) {
                statement.setNull(6, java.sql.Types.NUMERIC);
            } else {
                statement.setDouble(6, tps);
            }
            statement.setBoolean(7, draining);
            statement.setInt(8, dataVersion);
            statement.setString(9, mcVersion);
        }));
    }

    /**
     * Nodes that have beaten within {@code deadAfter} and are not draining.
     *
     * <p>The candidate set MN-14's placement chooses from. Liveness is evaluated
     * in the SQL, in database time, for the reason above.
     */
    public List<NodeStatus> aliveNodes(Duration deadAfter) throws SQLException {
        Objects.requireNonNull(deadAfter, "deadAfter");
        return database.withConnection(connection -> queryList(
                connection,
                """
                SELECT node_id, address, loaded_worlds, online_players, heap_percent, tps,
                       draining, data_version, mc_version, last_seen
                  FROM worlds_node
                 WHERE draining = false
                   AND last_seen > now() - (? * INTERVAL '1 second')
                 ORDER BY loaded_worlds, online_players
                """,
                statement -> statement.setDouble(1, deadAfter.toMillis() / 1000.0),
                NodeRepository::mapNode));
    }

    /** One node by id, alive or not. */
    public Optional<NodeStatus> find(String nodeId) throws SQLException {
        Objects.requireNonNull(nodeId, "nodeId");
        return database.withConnection(connection ->
                queryOne(connection, """
                SELECT node_id, address, loaded_worlds, online_players, heap_percent, tps,
                       draining, data_version, mc_version, last_seen
                  FROM worlds_node
                 WHERE node_id = ?
                """, statement -> statement.setString(1, nodeId), NodeRepository::mapNode));
    }

    /** Every registered node, for {@code /world admin list} and the proxy's registration sweep. */
    public List<NodeStatus> allNodes() throws SQLException {
        return database.withConnection(
                connection -> queryList(connection, """
                SELECT node_id, address, loaded_worlds, online_players, heap_percent, tps,
                       draining, data_version, mc_version, last_seen
                  FROM worlds_node
                 ORDER BY node_id
                """, StatementBinder.NONE, NodeRepository::mapNode));
    }

    /**
     * Marks a node draining, so placement stops choosing it (MN-20).
     *
     * <p>Deliberately not a delete. A draining node is still holding worlds and
     * still heartbeating; what changes is only that it takes no new ones.
     */
    public boolean setDraining(String nodeId, boolean draining) throws SQLException {
        Objects.requireNonNull(nodeId, "nodeId");
        return database.inTransaction(connection ->
                execute(connection, "UPDATE worlds_node SET draining = ? WHERE node_id = ?", statement -> {
                            statement.setBoolean(1, draining);
                            statement.setString(2, nodeId);
                        })
                        == 1);
    }

    /** Removes a node's registration, on a clean shutdown (MN-17). */
    public boolean deregister(String nodeId) throws SQLException {
        Objects.requireNonNull(nodeId, "nodeId");
        return database.inTransaction(connection -> execute(
                        connection,
                        "DELETE FROM worlds_node WHERE node_id = ?",
                        statement -> statement.setString(1, nodeId))
                == 1);
    }

    private static NodeStatus mapNode(ResultSet row) throws SQLException {
        int heap = row.getInt("heap_percent");
        Integer heapPercent = row.wasNull() ? null : heap;
        double tpsValue = row.getDouble("tps");
        Double tps = row.wasNull() ? null : tpsValue;
        OffsetDateTime lastSeen = row.getObject("last_seen", OffsetDateTime.class);
        if (lastSeen == null) {
            throw new SQLException("last_seen was NULL");
        }
        return new NodeStatus(
                Objects.requireNonNull(row.getString("node_id"), "node_id"),
                Objects.requireNonNull(row.getString("address"), "address"),
                row.getInt("loaded_worlds"),
                row.getInt("online_players"),
                heapPercent,
                tps,
                row.getBoolean("draining"),
                row.getInt("data_version"),
                Objects.requireNonNull(row.getString("mc_version"), "mc_version"),
                lastSeen.toInstant());
    }
}
