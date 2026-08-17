package nl.gzmn.playerworlds.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import nl.gzmn.playerworlds.core.db.Database;
import nl.gzmn.playerworlds.core.db.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Database-layer smoke through the shared {@link TestDatabase} factory (plan
 * section 11).
 *
 * <p>Proves the harness can hand a migrated PostgreSQL to a consumer outside
 * {@code :core}. Deeper schema and lease tests stay in {@code :core}.
 */
class TestDatabaseSmokeTest {

    @Test
    @DisplayName("openMigrated applies the baseline and answers SELECT 1")
    void openMigratedAppliesTheBaseline() throws Exception {
        try (Database database = TestDatabase.openMigrated()) {
            assertThat(Schema.appliedVersion(database)).isEqualTo(Schema.MAX_SUPPORTED);

            int one = database.withConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement("SELECT 1");
                        ResultSet rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    return rows.getInt(1);
                }
            });
            assertThat(one).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("openFresh resets schema so a second open is empty again")
    void openFreshResetsSchema() throws Exception {
        try (Database first = TestDatabase.openMigrated()) {
            assertThat(Schema.appliedVersion(first)).isEqualTo(Schema.MAX_SUPPORTED);
        }
        try (Database second = TestDatabase.openFresh()) {
            assertThat(Schema.appliedVersion(second)).isZero();
        }
    }
}
