package nl.gzmn.playerworlds.core.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import nl.gzmn.playerworlds.core.model.Role;
import nl.gzmn.playerworlds.core.model.TransferRequest;
import nl.gzmn.playerworlds.core.model.Visibility;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TransferRequestRepositoryTest {

    private Database database;
    private TransferRequestRepository requests;
    private PlayerWorldRepository worlds;
    private MembershipRepository membership;

    @BeforeEach
    void openDatabase() throws Exception {
        database = TestPostgres.freshDatabase();
        Schema.migrate(database);
        requests = new TransferRequestRepository(database);
        worlds = new PlayerWorldRepository(database);
        membership = new MembershipRepository(database);
    }

    @AfterEach
    void closeDatabase() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    @DisplayName("creates and finds live transfer request and deletes it")
    void createsAndFindsLiveTransferRequest() throws SQLException {
        UUID owner = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        WorldId worldId = WorldId.random();
        worlds.create(worldId, owner, "transfer-test", 12345L, 5000, Visibility.PRIVATE);
        database.inTransaction(connection -> membership.insertMember(connection, worldId, target, Role.BUILDER, owner));

        TransferRequest req = requests.requestTransfer(worldId, target, owner, Duration.ofDays(7));
        assertThat(req.worldId()).isEqualTo(worldId);
        assertThat(req.toUuid()).isEqualTo(target);
        assertThat(req.fromUuid()).isEqualTo(owner);
        assertThat(req.expiresAt()).isNotNull();
        assertThat(req.createdAt()).isNotNull();

        Optional<TransferRequest> found = requests.findLiveRequest(worldId, target);
        assertThat(found).isPresent();
        assertThat(found.get().worldId()).isEqualTo(worldId);
        assertThat(found.get().toUuid()).isEqualTo(target);
        assertThat(found.get().fromUuid()).isEqualTo(owner);

        List<TransferRequest> forTarget = requests.findLiveRequestsFor(target);
        assertThat(forTarget).hasSize(1);
        assertThat(forTarget.getFirst().worldId()).isEqualTo(worldId);

        boolean deleted = requests.deleteRequest(worldId, target);
        assertThat(deleted).isTrue();
        assertThat(requests.findLiveRequest(worldId, target)).isEmpty();
    }

    @Test
    @DisplayName("requestTransfer upserts on conflict, updating expiry and timestamps")
    void requestTransferUpsertsOnConflict() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID newOwner = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        WorldId worldId = WorldId.random();
        worlds.create(worldId, owner, "upsert-test", 12345L, 5000, Visibility.PRIVATE);

        TransferRequest first = requests.requestTransfer(worldId, target, owner, Duration.ofDays(1));
        TransferRequest second = requests.requestTransfer(worldId, target, newOwner, Duration.ofDays(7));

        assertThat(second.worldId()).isEqualTo(worldId);
        assertThat(second.toUuid()).isEqualTo(target);
        assertThat(second.fromUuid()).isEqualTo(newOwner);
        assertThat(second.expiresAt()).isAfter(first.expiresAt());

        List<TransferRequest> liveRequests = requests.findLiveRequestsFor(target);
        assertThat(liveRequests).hasSize(1);
        assertThat(liveRequests.getFirst().fromUuid()).isEqualTo(newOwner);
    }

    @Test
    @DisplayName("expired transfer requests are not returned by findLiveRequest or findLiveRequestsFor")
    void expiredRequestsAreNotReturned() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        WorldId worldId = WorldId.random();
        worlds.create(worldId, owner, "expire-test", 12345L, 5000, Visibility.PRIVATE);

        requests.requestTransfer(worldId, target, owner, Duration.ofDays(7));
        expireRequest(worldId, target);

        assertThat(requests.findLiveRequest(worldId, target)).isEmpty();
        assertThat(requests.findLiveRequestsFor(target)).isEmpty();
    }

    @Test
    @DisplayName("deleteExpired removes only expired requests and returns count")
    void deleteExpiredRemovesOnlyExpiredRequests() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID target1 = UUID.randomUUID();
        UUID target2 = UUID.randomUUID();
        WorldId worldId = WorldId.random();
        worlds.create(worldId, owner, "sweep-test", 12345L, 5000, Visibility.PRIVATE);

        requests.requestTransfer(worldId, target1, owner, Duration.ofDays(7));
        requests.requestTransfer(worldId, target2, owner, Duration.ofDays(7));
        expireRequest(worldId, target1);

        int deleted = requests.deleteExpired();
        assertThat(deleted).isEqualTo(1);

        assertThat(requests.findLiveRequest(worldId, target1)).isEmpty();
        assertThat(requests.findLiveRequest(worldId, target2)).isPresent();
    }

    @Test
    @DisplayName("deleteRequest returns false when deleting non-existent request")
    void deleteRequestReturnsFalseWhenNotFound() throws Exception {
        WorldId worldId = WorldId.random();
        UUID target = UUID.randomUUID();
        assertThat(requests.deleteRequest(worldId, target)).isFalse();
    }

    @Test
    @DisplayName("deleting a world cascades and removes its transfer requests")
    void worldDeletionCascadesTransferRequests() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        WorldId worldId = WorldId.random();
        worlds.create(worldId, owner, "cascade-test", 12345L, 5000, Visibility.PRIVATE);

        requests.requestTransfer(worldId, target, owner, Duration.ofDays(7));
        assertThat(requests.findLiveRequest(worldId, target)).isPresent();

        database.inTransaction(connection -> worlds.deleteIfCreating(connection, worldId));

        assertThat(requests.findLiveRequestsFor(target)).isEmpty();
    }

    private void expireRequest(WorldId worldId, UUID target) throws SQLException {
        database.inTransaction(connection -> {
            try (var statement = connection.prepareStatement(
                    "UPDATE player_world_transfer_request SET expires_at = now() - INTERVAL '1 second' "
                            + "WHERE world_id = ? AND to_uuid = ?")) {
                statement.setObject(1, worldId.value());
                statement.setObject(2, target);
                return statement.executeUpdate();
            }
        });
    }
}
