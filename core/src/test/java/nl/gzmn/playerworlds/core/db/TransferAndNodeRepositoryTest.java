package nl.gzmn.playerworlds.core.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import nl.gzmn.playerworlds.core.db.PendingTransferRepository.PendingTransfer;
import nl.gzmn.playerworlds.core.model.Visibility;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The handoff and the node heartbeat (FR-10, FR-11, FR-13, MN-17, MN-18). */
class TransferAndNodeRepositoryTest {

    private Database database;
    private PendingTransferRepository transfers;
    private NodeRepository nodes;
    private WorldId worldId;
    private final UUID player = UUID.randomUUID();

    @BeforeEach
    void openDatabase() throws Exception {
        database = TestPostgres.freshDatabase();
        Schema.migrate(database);
        transfers = new PendingTransferRepository(database);
        nodes = new NodeRepository(database);
        worldId = new PlayerWorldRepository(database)
                .create(WorldId.random(), UUID.randomUUID(), "home", 1L, 5000, Visibility.PRIVATE)
                .id();
    }

    @AfterEach
    void closeDatabase() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    @DisplayName("a routed transfer is claimed exactly once (FR-11)")
    void aTransferIsClaimedOnce() throws Exception {
        transfers.route(player, worldId, "worlds-1", 0L);

        // Reading and deleting in one statement is what stops a reconnect racing
        // the first join from consuming the same route twice.
        assertThat(transfers.claim(player, Duration.ofSeconds(60)))
                .contains(new PendingTransfer(player, worldId, "worlds-1", 0L));
        assertThat(transfers.claim(player, Duration.ofSeconds(60))).isEmpty();
    }

    @Test
    @DisplayName("an expired transfer is not claimable, and expiry is the database's call")
    void expiredTransfersAreRefused() throws Exception {
        transfers.route(player, worldId, "worlds-1", 0L);
        database.inTransaction(connection -> {
            try (var statement = connection.prepareStatement(
                    "UPDATE pending_transfer SET created_at = now() - INTERVAL '1 hour' WHERE uuid = ?")) {
                statement.setObject(1, player);
                return statement.executeUpdate();
            }
        });

        // FR-11 sends this player to lobby with an explanation.
        assertThat(transfers.claim(player, Duration.ofSeconds(60))).isEmpty();
    }

    @Test
    @DisplayName("re-routing replaces rather than refusing")
    void rerouteReplaces() throws Exception {
        WorldId other = new PlayerWorldRepository(database)
                .create(WorldId.random(), UUID.randomUUID(), "other", 2L, 5000, Visibility.PRIVATE)
                .id();
        transfers.route(player, worldId, "worlds-1", 0L);
        transfers.route(player, other, "worlds-2", 3L);

        PendingTransfer claimed =
                transfers.claim(player, Duration.ofSeconds(60)).orElseThrow();

        assertThat(claimed.worldId()).isEqualTo(other);
        assertThat(claimed.nodeId()).isEqualTo("worlds-2");
        assertThat(claimed.generation()).isEqualTo(3L);
    }

    @Test
    @DisplayName("a cancelled transfer cannot be claimed")
    void cancelRemovesTheRoute() throws Exception {
        transfers.route(player, worldId, "worlds-1", 0L);

        assertThat(transfers.cancel(player)).isTrue();
        assertThat(transfers.claim(player, Duration.ofSeconds(60))).isEmpty();
    }

    @Test
    @DisplayName("the sweep removes expired rows and leaves live ones")
    void sweepIsScopedToExpiredRows() throws Exception {
        transfers.route(player, worldId, "worlds-1", 0L);

        assertThat(transfers.sweepExpired(Duration.ofSeconds(60))).isZero();
        assertThat(transfers.claim(player, Duration.ofSeconds(60))).isPresent();
    }

    @Test
    @DisplayName("the last world is remembered for the FR-13 resume prompt")
    void lastWorldIsRemembered() throws Exception {
        assertThat(transfers.lastWorld(player)).isEmpty();

        transfers.rememberLastWorld(player, worldId);

        assertThat(transfers.lastWorld(player)).contains(worldId);
    }

    @Test
    @DisplayName("a heartbeat registers a node and then refreshes it (MN-17, MN-18)")
    void heartbeatUpserts() throws Exception {
        nodes.heartbeat("worlds-1", "127.0.0.1:25566", 0, 0, 40, 20.0, false, 4903, "26.2");
        nodes.heartbeat("worlds-1", "127.0.0.1:25566", 2, 5, 55, 19.5, false, 4903, "26.2");

        var status = nodes.find("worlds-1").orElseThrow();

        assertThat(status.loadedWorlds()).isEqualTo(2);
        assertThat(status.onlinePlayers()).isEqualTo(5);
        assertThat(status.dataVersion()).isEqualTo(4903);
        assertThat(nodes.allNodes()).hasSize(1);
    }

    @Test
    @DisplayName("a node that stopped beating drops out of the alive set (MN-18)")
    void staleNodesAreNotAlive() throws Exception {
        nodes.heartbeat("worlds-1", "a:1", 0, 0, null, null, false, 4903, "26.2");
        database.inTransaction(connection -> {
            try (var statement =
                    connection.prepareStatement("UPDATE worlds_node SET last_seen = now() - INTERVAL '5 minutes'")) {
                return statement.executeUpdate();
            }
        });

        assertThat(nodes.aliveNodes(Duration.ofSeconds(60))).isEmpty();
        // Still registered, just not a placement candidate.
        assertThat(nodes.find("worlds-1")).isPresent();
    }

    @Test
    @DisplayName("a draining node keeps its worlds but takes no new ones (MN-20)")
    void drainingNodesAreExcludedFromPlacement() throws Exception {
        nodes.heartbeat("worlds-1", "a:1", 1, 1, null, null, false, 4903, "26.2");

        assertThat(nodes.aliveNodes(Duration.ofSeconds(60))).hasSize(1);

        assertThat(nodes.setDraining("worlds-1", true)).isTrue();

        assertThat(nodes.aliveNodes(Duration.ofSeconds(60))).isEmpty();
        assertThat(nodes.find("worlds-1").orElseThrow().draining()).isTrue();
    }

    @Test
    @DisplayName("a clean shutdown deregisters the node (MN-17)")
    void deregisterRemovesTheNode() throws Exception {
        nodes.heartbeat("worlds-1", "a:1", 0, 0, null, null, false, 4903, "26.2");

        assertThat(nodes.deregister("worlds-1")).isTrue();
        assertThat(nodes.find("worlds-1")).isEmpty();
    }
}
