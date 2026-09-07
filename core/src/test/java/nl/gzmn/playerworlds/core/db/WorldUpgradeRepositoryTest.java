package nl.gzmn.playerworlds.core.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import nl.gzmn.playerworlds.core.model.UpgradeKind;
import nl.gzmn.playerworlds.core.model.Visibility;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.model.WorldUpgrade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@code world_upgrade} against a real PostgreSQL (CONTRIBUTING.md, Tests). */
class WorldUpgradeRepositoryTest {

    private Database database;
    private WorldUpgradeRepository upgrades;
    private PlayerWorldRepository worlds;

    @BeforeEach
    void openDatabase() throws Exception {
        database = TestPostgres.freshDatabase();
        Schema.migrate(database);
        upgrades = new WorldUpgradeRepository(database);
        worlds = new PlayerWorldRepository(database);
    }

    @AfterEach
    void closeDatabase() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    @DisplayName("a grant is idempotent on its reference, and a repeat cannot change it_FR44")
    void grantIsIdempotentOnReference() throws Exception {
        UUID owner = UUID.randomUUID();

        WorldUpgradeRepository.Grant first = upgrades.grant(owner, UpgradeKind.STORAGE, 2048L, "tebex-1");
        assertThat(first.created()).isTrue();

        // The webstore retries the same delivery, and gets it wrong the second time.
        WorldUpgradeRepository.Grant repeat = upgrades.grant(owner, UpgradeKind.STORAGE, 999_999L, "tebex-1");

        assertThat(repeat.created())
                .as("a retried delivery grants nothing new and says so")
                .isFalse();
        assertThat(repeat.upgrade().id()).isEqualTo(first.upgrade().id());
        assertThat(repeat.upgrade().amount())
                .as("the second delivery of a transaction must not be able to rewrite the first")
                .isEqualTo(2048L);
        assertThat(upgrades.listOwnedBy(owner)).hasSize(1);
    }

    @Test
    @DisplayName("an unredeemed upgrade counts for nothing until it is spent on a world_FR45")
    void unredeemedUpgradeAddsNothing() throws Exception {
        UUID owner = UUID.randomUUID();
        WorldUpgradeRepository.Grant grant = upgrades.grant(owner, UpgradeKind.STORAGE, 4096L, "tebex-2");

        assertThat(grant.upgrade().unredeemed()).isTrue();
        assertThat(upgrades.bonusStorageBytes(owner)).isZero();

        WorldId world = createWorld(owner, "home");
        assertThat(upgrades.redeem(grant.upgrade().id(), owner, world)).isPresent();

        assertThat(upgrades.bonusStorageBytes(owner)).isEqualTo(4096L);
    }

    @Test
    @DisplayName("two clients redeeming one upgrade at once produce exactly one redemption_FR45")
    void concurrentRedeemProducesOneRedemption() throws Exception {
        UUID owner = UUID.randomUUID();
        WorldUpgradeRepository.Grant grant = upgrades.grant(owner, UpgradeKind.BORDER, 2500L, "tebex-3");
        WorldId one = createWorld(owner, "one");
        WorldId two = createWorld(owner, "two");
        UUID id = grant.upgrade().id();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Optional<WorldUpgrade>>> both =
                    List.of(() -> upgrades.redeem(id, owner, one), () -> upgrades.redeem(id, owner, two));
            List<Future<Optional<WorldUpgrade>>> results = pool.invokeAll(both);

            long redeemed = 0;
            for (Future<Optional<WorldUpgrade>> result : results) {
                if (result.get(30, TimeUnit.SECONDS).isPresent()) {
                    redeemed++;
                }
            }
            assertThat(redeemed)
                    .as("the conditional UPDATE is what makes a double click spend one upgrade")
                    .isEqualTo(1L);
        } finally {
            pool.shutdownNow();
        }

        // And it went to exactly one of the two worlds, not both.
        long onBoth = upgrades.borderBonusBlocks(one) + upgrades.borderBonusBlocks(two);
        assertThat(onBoth).isEqualTo(2500L);
    }

    @Test
    @DisplayName("an upgrade cannot be redeemed by anyone but the player who bought it_FR45")
    void redeemRefusesSomebodyElsesUpgrade() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        WorldUpgradeRepository.Grant grant = upgrades.grant(owner, UpgradeKind.STORAGE, 1024L, "tebex-4");
        WorldId theirs = createWorld(stranger, "theirs");

        assertThat(upgrades.redeem(grant.upgrade().id(), stranger, theirs)).isEmpty();
        assertThat(upgrades.bonusStorageBytes(owner)).isZero();
        assertThat(upgrades.bonusStorageBytes(stranger)).isZero();
    }

    @Test
    @DisplayName("deleting a world releases the upgrade spent on it rather than deleting it_FR47")
    void deletingAWorldReleasesItsUpgrade() throws Exception {
        UUID owner = UUID.randomUUID();
        WorldUpgradeRepository.Grant grant = upgrades.grant(owner, UpgradeKind.BORDER, 5000L, "tebex-5");
        WorldId world = createWorld(owner, "doomed");
        assertThat(upgrades.redeem(grant.upgrade().id(), owner, world)).isPresent();
        assertThat(upgrades.borderBonusBlocks(world)).isEqualTo(5000L);

        // FR-27's hard delete. Every other child of player_world cascades; this one must not,
        // or the player loses what they paid for at the moment they delete the world.
        assertThat(worlds.deleteHard(world)).isTrue();

        Optional<WorldUpgrade> released = upgrades.findById(grant.upgrade().id());
        assertThat(released)
                .as("the purchase survives the world it was spent on")
                .isPresent();
        assertThat(released.get().unredeemed())
                .as("and comes back unredeemed, so it can be spent again")
                .isTrue();
        assertThat(released.get().redeemedAt()).isNull();
    }

    @Test
    @DisplayName("revoking refuses an upgrade that has already been spent")
    void revokeRefusesRedeemed() throws Exception {
        UUID owner = UUID.randomUUID();
        WorldUpgradeRepository.Grant unspent = upgrades.grant(owner, UpgradeKind.STORAGE, 1024L, "tebex-6");
        WorldUpgradeRepository.Grant spent = upgrades.grant(owner, UpgradeKind.STORAGE, 2048L, "tebex-7");
        WorldId world = createWorld(owner, "home");
        assertThat(upgrades.redeem(spent.upgrade().id(), owner, world)).isPresent();

        assertThat(upgrades.revoke("tebex-6")).isTrue();
        assertThat(upgrades.revoke("tebex-7"))
                .as("clawing back a spent border upgrade would leave the world over its allowance,"
                        + " and FR-3c forbids shrinking it back")
                .isFalse();

        assertThat(upgrades.listOwnedBy(owner))
                .extracting(WorldUpgrade::reference)
                .containsExactly("tebex-7");
    }

    @Test
    @DisplayName("storage and border upgrades are counted separately")
    void kindsAreCountedSeparately() throws Exception {
        UUID owner = UUID.randomUUID();
        WorldId world = createWorld(owner, "home");

        WorldUpgradeRepository.Grant storage = upgrades.grant(owner, UpgradeKind.STORAGE, 8192L, "tebex-8");
        WorldUpgradeRepository.Grant border = upgrades.grant(owner, UpgradeKind.BORDER, 1000L, "tebex-9");
        assertThat(upgrades.redeem(storage.upgrade().id(), owner, world)).isPresent();
        assertThat(upgrades.redeem(border.upgrade().id(), owner, world)).isPresent();

        assertThat(upgrades.bonusStorageBytes(owner)).isEqualTo(8192L);
        assertThat(upgrades.borderBonusBlocks(world)).isEqualTo(1000L);
    }

    private WorldId createWorld(UUID owner, String name) throws SQLException {
        WorldId id = WorldId.random();
        database.inTransaction((Connection connection) ->
                worlds.insertCreating(connection, id, owner, name, id.folder(), 1234L, 5000, Visibility.PRIVATE));
        return id;
    }
}
