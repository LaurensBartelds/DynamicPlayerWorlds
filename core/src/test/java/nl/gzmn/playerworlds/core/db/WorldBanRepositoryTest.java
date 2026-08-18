package nl.gzmn.playerworlds.core.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.Visibility;
import nl.gzmn.playerworlds.core.model.WorldBan;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WorldBanRepositoryTest {

    private Database database;
    private PlayerWorldRepository worlds;
    private WorldBanRepository bans;
    private UUID owner;
    private WorldId worldId;

    @BeforeEach
    void openDatabase() throws Exception {
        database = TestPostgres.freshDatabase();
        Schema.migrate(database);
        worlds = new PlayerWorldRepository(database);
        bans = new WorldBanRepository(database);
        owner = UUID.randomUUID();
        PlayerWorld world = worlds.create(WorldId.random(), owner, "home", 1L, 5000, Visibility.PUBLIC);
        worldId = world.id();
    }

    @AfterEach
    void closeDatabase() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    @DisplayName("banning a player persists the ban with reason (FR-9d)")
    void banPlayer() throws Exception {
        UUID target = UUID.randomUUID();
        WorldBan ban = bans.ban(worldId, target, owner, "Griefing");

        assertThat(ban.worldId()).isEqualTo(worldId);
        assertThat(ban.uuid()).isEqualTo(target);
        assertThat(ban.bannedBy()).isEqualTo(owner);
        assertThat(ban.reason()).isEqualTo("Griefing");
        assertThat(ban.bannedAt()).isNotNull();

        assertThat(bans.isBanned(worldId, target)).isTrue();
        assertThat(bans.findBan(worldId, target)).isPresent();
    }

    @Test
    @DisplayName("unbanning a player removes the ban row")
    void unbanPlayer() throws Exception {
        UUID target = UUID.randomUUID();
        bans.ban(worldId, target, owner, "Misbehavior");
        assertThat(bans.isBanned(worldId, target)).isTrue();

        boolean removed = bans.unban(worldId, target);
        assertThat(removed).isTrue();
        assertThat(bans.isBanned(worldId, target)).isFalse();
        assertThat(bans.findBan(worldId, target)).isEmpty();
    }

    @Test
    @DisplayName("listBans returns bans in newest-first order")
    void listBans() throws Exception {
        UUID target1 = UUID.randomUUID();
        UUID target2 = UUID.randomUUID();

        bans.ban(worldId, target1, owner, "Reason 1");
        bans.ban(worldId, target2, owner, "Reason 2");

        List<WorldBan> list = bans.listBans(worldId);
        assertThat(list).hasSize(2);
        assertThat(list.stream().map(WorldBan::uuid)).containsExactlyInAnyOrder(target1, target2);
    }
}
