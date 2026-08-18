package nl.gzmn.playerworlds.core.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import nl.gzmn.playerworlds.core.model.Visibility;
import nl.gzmn.playerworlds.core.model.WorldArchive;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ArchiveRepositoryTest {

    private Database database;
    private ArchiveRepository archives;
    private PlayerWorldRepository worlds;

    @BeforeEach
    void setUp() throws Exception {
        database = TestPostgres.freshDatabase();
        Schema.migrate(database);
        archives = new ArchiveRepository(database);
        worlds = new PlayerWorldRepository(database);
    }

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    @DisplayName("records and finds latest archive, and increments restore count")
    void recordsAndFindsLatestArchive() throws SQLException {
        UUID owner = UUID.randomUUID();
        WorldId worldId = WorldId.random();
        worlds.create(worldId, owner, "archive-test", 12345L, 5000, Visibility.PRIVATE);

        WorldArchive created = archives.recordArchive(
                worldId, "worlds/" + worldId + "/archive/test.tar.zst", 1048576L, "abcdef123456", 3953);

        assertThat(created.worldId()).isEqualTo(worldId);
        assertThat(created.objectKey()).isEqualTo("worlds/" + worldId + "/archive/test.tar.zst");
        assertThat(created.sizeBytes()).isEqualTo(1048576L);
        assertThat(created.checksum()).isEqualTo("abcdef123456");
        assertThat(created.dataVersion()).isEqualTo(3953);
        assertThat(created.restoreCount()).isZero();
        assertThat(created.archivedAt()).isNotNull();

        Optional<WorldArchive> found = archives.findLatestByWorld(worldId);
        assertThat(found).isPresent();
        assertThat(found.get().objectKey()).isEqualTo("worlds/" + worldId + "/archive/test.tar.zst");
        assertThat(found.get().checksum()).isEqualTo("abcdef123456");
        assertThat(found.get().sizeBytes()).isEqualTo(1048576L);

        boolean incremented = archives.incrementRestoreCount(worldId, created.archivedAt());
        assertThat(incremented).isTrue();

        Optional<WorldArchive> updated = archives.findLatestByWorld(worldId);
        assertThat(updated).isPresent();
        assertThat(updated.get().restoreCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("findAllByWorld returns all archives newest first")
    void findAllByWorldReturnsAllArchives() throws SQLException, InterruptedException {
        UUID owner = UUID.randomUUID();
        WorldId worldId = WorldId.random();
        worlds.create(worldId, owner, "multi-archive", 12345L, 5000, Visibility.PRIVATE);

        WorldArchive first =
                archives.recordArchive(worldId, "worlds/" + worldId + "/archive/1.tar.zst", 1000L, "hash1", 3953);
        Thread.sleep(10);
        WorldArchive second =
                archives.recordArchive(worldId, "worlds/" + worldId + "/archive/2.tar.zst", 2000L, "hash2", 3953);

        List<WorldArchive> all = archives.findAllByWorld(worldId);
        assertThat(all).hasSize(2);
        assertThat(all.get(0).objectKey()).isEqualTo(second.objectKey());
        assertThat(all.get(1).objectKey()).isEqualTo(first.objectKey());
    }

    @Test
    @DisplayName("deleteArchive removes specific archive entry")
    void deleteArchiveRemovesEntry() throws SQLException {
        UUID owner = UUID.randomUUID();
        WorldId worldId = WorldId.random();
        worlds.create(worldId, owner, "delete-archive", 12345L, 5000, Visibility.PRIVATE);

        WorldArchive created =
                archives.recordArchive(worldId, "worlds/" + worldId + "/archive/del.tar.zst", 1000L, "hashdel", 3953);

        assertThat(archives.findLatestByWorld(worldId)).isPresent();

        boolean deleted = archives.deleteArchive(worldId, created.archivedAt());
        assertThat(deleted).isTrue();

        assertThat(archives.findLatestByWorld(worldId)).isEmpty();
    }
}
