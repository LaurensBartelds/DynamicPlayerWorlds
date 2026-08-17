package nl.gzmn.playerworlds.core.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import nl.gzmn.playerworlds.core.db.ProfileRepository.Snapshot;
import nl.gzmn.playerworlds.core.db.ProfileRepository.StoredProfile;
import nl.gzmn.playerworlds.core.model.Visibility;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@code player_world_profile} against a real PostgreSQL (FR-14 to FR-17). */
class ProfileRepositoryTest {

    private Database database;
    private ProfileRepository profiles;
    private WorldId worldId;
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @BeforeEach
    void openDatabase() throws Exception {
        database = TestPostgres.freshDatabase();
        Schema.migrate(database);
        profiles = new ProfileRepository(database);
        PlayerWorldRepository worlds = new PlayerWorldRepository(database);
        worldId = worlds.create(WorldId.random(), UUID.randomUUID(), "home", 1L, 5000, Visibility.PRIVATE)
                .id();
    }

    @AfterEach
    void closeDatabase() {
        if (database != null) {
            database.close();
        }
    }

    private void commit(Snapshot snapshot, Map<UUID, byte[]> payloads) throws Exception {
        database.inTransaction(connection -> profiles.saveAll(connection, worldId, snapshot, 1, payloads));
    }

    @Test
    @DisplayName("a commit writes every player in the world atomically (FR-16)")
    void commitWritesEveryPlayer() throws Exception {
        Snapshot snapshot = new Snapshot(0L, 0);

        commit(snapshot, Map.of(alice, new byte[] {1, 2, 3}, bob, new byte[] {4, 5}));

        assertThat(profiles.load(worldId, alice, snapshot).orElseThrow().data()).containsExactly(1, 2, 3);
        assertThat(profiles.load(worldId, bob, snapshot).orElseThrow().data()).containsExactly(4, 5);
    }

    @Test
    @DisplayName("a rolled-back commit leaves no partial profile (FR-16)")
    void aFailedCommitWritesNothing() throws Exception {
        // FR-16: "A failed write must not leave a partially serialised profile."
        // The transaction is what guarantees it, so the test kills one mid-way.
        try {
            database.inTransaction(connection -> {
                profiles.saveAll(connection, worldId, new Snapshot(0L, 0), 1, Map.of(alice, new byte[] {1}));
                throw new IllegalStateException("storage failed after the profile write");
            });
        } catch (IllegalStateException expected) {
            // The commit aborts; nothing should have landed.
        }

        assertThat(profiles.load(worldId, alice, new Snapshot(0L, 0))).isEmpty();
        assertThat(profiles.latestSnapshot(worldId)).isEmpty();
    }

    @Test
    @DisplayName("sequences advance per generation, so two commits never collide")
    void sequencesAdvance() throws Exception {
        int first = database.inTransaction(connection -> profiles.nextSequence(connection, worldId, 0L));
        commit(new Snapshot(0L, first), Map.of(alice, new byte[] {1}));
        int second = database.inTransaction(connection -> profiles.nextSequence(connection, worldId, 0L));
        commit(new Snapshot(0L, second), Map.of(alice, new byte[] {2}));

        assertThat(first).isZero();
        assertThat(second).isEqualTo(1);
        assertThat(profiles.latestSnapshot(worldId)).contains(new Snapshot(0L, 1));
    }

    @Test
    @DisplayName("a later generation outranks a higher sequence in an earlier one")
    void generationOutranksSequence() throws Exception {
        commit(new Snapshot(0L, 9), Map.of(alice, new byte[] {1}));
        commit(new Snapshot(1L, 0), Map.of(alice, new byte[] {2}));

        assertThat(profiles.latestSnapshot(worldId)).contains(new Snapshot(1L, 0));
        assertThat(new Snapshot(1L, 0)).isGreaterThan(new Snapshot(0L, 9));
    }

    @Test
    @DisplayName("a player with no row for the snapshot gets nothing back (FR-15b)")
    void aPlayerWhoNeverPlayedHasNoProfile() throws Exception {
        commit(new Snapshot(0L, 0), Map.of(alice, new byte[] {1}));

        // FR-15b: they have never played in the world and get a fresh profile per
        // FR-5 — which is the caller's job, not this repository's.
        assertThat(profiles.load(worldId, bob, new Snapshot(0L, 0))).isEmpty();
    }

    @Test
    @DisplayName("retained snapshots are listed newest first, for the FR-16a repair path")
    void snapshotsAreListedForRollback() throws Exception {
        commit(new Snapshot(0L, 0), Map.of(alice, new byte[] {1}));
        commit(new Snapshot(0L, 1), Map.of(alice, new byte[] {2}));
        commit(new Snapshot(0L, 2), Map.of(alice, new byte[] {3}));

        List<StoredProfile> history = profiles.listSnapshots(worldId, alice);

        assertThat(history).hasSize(3);
        assertThat(history.getFirst().snapshot()).isEqualTo(new Snapshot(0L, 2));
        assertThat(history.getLast().snapshot()).isEqualTo(new Snapshot(0L, 0));
    }

    @Test
    @DisplayName("pruning keeps the newest snapshots and drops the rest (FR-15c)")
    void pruningKeepsTheNewest() throws Exception {
        for (int sequence = 0; sequence < 5; sequence++) {
            commit(new Snapshot(0L, sequence), Map.of(alice, new byte[] {(byte) sequence}, bob, new byte[] {9}));
        }

        int removed = profiles.pruneToLatest(worldId, 3);

        // Two snapshots gone, and both players' rows within them.
        assertThat(removed).isEqualTo(4);
        assertThat(profiles.listSnapshots(worldId, alice))
                .extracting(StoredProfile::snapshot)
                .containsExactly(new Snapshot(0L, 4), new Snapshot(0L, 3), new Snapshot(0L, 2));
    }

    @Test
    @DisplayName("re-committing the same snapshot replaces rather than duplicates")
    void reCommittingASnapshotReplaces() throws Exception {
        Snapshot snapshot = new Snapshot(0L, 0);
        commit(snapshot, Map.of(alice, new byte[] {1}));
        commit(snapshot, Map.of(alice, new byte[] {2}));

        assertThat(profiles.load(worldId, alice, snapshot).orElseThrow().data()).containsExactly(2);
        assertThat(profiles.listSnapshots(worldId, alice)).hasSize(1);
    }

    @Test
    @DisplayName("the format version is stored beside the payload, not inside it (FR-17)")
    void formatVersionIsStoredSeparately() throws Exception {
        Snapshot snapshot = new Snapshot(0L, 0);
        database.inTransaction(
                connection -> profiles.saveAll(connection, worldId, snapshot, 7, Map.of(alice, new byte[] {1})));

        // A payload that cannot be parsed at all can still be identified and
        // migrated, which is the whole reason it is a column.
        assertThat(profiles.load(worldId, alice, snapshot).orElseThrow().formatVersion())
                .isEqualTo(7);
    }

    @Test
    @DisplayName("deleting a world takes its profiles with it")
    void profilesCascadeFromTheWorld() throws Exception {
        commit(new Snapshot(0L, 0), Map.of(alice, new byte[] {1}));
        PlayerWorldRepository worlds = new PlayerWorldRepository(database);

        database.inTransaction(connection -> worlds.deleteIfCreating(connection, worldId));

        assertThat(profiles.listSnapshots(worldId, alice)).isEmpty();
    }

    @Test
    @DisplayName("a commit for a world nobody was in is still a valid commit")
    void anEmptyCommitIsValid() throws Exception {
        int written = database.inTransaction(
                connection -> profiles.saveAll(connection, worldId, new Snapshot(0L, 0), 1, Map.of()));

        assertThat(written).isZero();
    }
}
