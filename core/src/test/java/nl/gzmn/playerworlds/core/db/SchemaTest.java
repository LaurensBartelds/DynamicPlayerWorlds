package nl.gzmn.playerworlds.core.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The V1 baseline and the schema-version guard, against a real PostgreSQL.
 *
 * <p>These run against a container rather than an in-memory database on purpose.
 * The baseline uses {@code JSONB}, partial indexes, {@code TIMESTAMPTZ} defaults
 * of {@code now()} and cascading foreign keys, and an emulation that accepts the
 * DDL proves nothing about whether PostgreSQL will.
 */
class SchemaTest {

    private Database database;

    @BeforeEach
    void openDatabase() throws SQLException {
        database = TestPostgres.freshDatabase();
    }

    @AfterEach
    void closeDatabase() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    @DisplayName("migrations apply to an empty database")
    void migrationsApplyToAnEmptyDatabase() throws Exception {
        int version = Schema.migrate(database);

        assertThat(version).isEqualTo(Schema.MAX_SUPPORTED);
    }

    @Test
    @DisplayName("migrating twice is idempotent")
    void migratingTwiceIsIdempotent() throws Exception {
        int first = Schema.migrate(database);
        List<String> tablesAfterFirst = tableNames();

        int second = Schema.migrate(database);

        assertThat(second).isEqualTo(first);
        assertThat(tableNames()).isEqualTo(tablesAfterFirst);
    }

    @Test
    @DisplayName("the baseline defines every table the specification requires")
    void baselineDefinesEveryTableTheSpecificationRequires() throws Exception {
        Schema.migrate(database);

        // Specification section 4, plus the two the plan adds: player_world_report
        // (FR-39 requires a table staff can read and section 4 never defines one)
        // and network_setting (plan section 8.1). Asserted as an exact set so that
        // a table quietly dropped from the migration fails here rather than at the
        // first query in a later milestone.
        assertThat(tableNames())
                .containsExactlyInAnyOrder(
                        "flyway_schema_history",
                        "network_setting",
                        "node_command",
                        "pending_transfer",
                        "player_last_world",
                        // V5: FR-34 warns an owner before their world is archived, and
                        // that owner is offline by definition — inactivity is what
                        // earned the warning — so the message has to wait for a login.
                        "player_notice",
                        // V2: the username cache section 4 never defined, which every
                        // section 6 command needs to turn its first argument into a UUID.
                        "player_name",
                        "player_world",
                        "player_world_archive",
                        "player_world_ban",
                        "player_world_invite",
                        "player_world_member",
                        "player_world_ownership_log",
                        "player_world_profile",
                        "player_world_report",
                        "player_world_transfer_request",
                        "worlds_node");
    }

    @Test
    @DisplayName("a schema newer than this build refuses to start (plan section 6)")
    void aSchemaNewerThanThisBuildRefusesToStart() throws Exception {
        Schema.migrate(database);
        recordFutureMigration(Schema.MAX_SUPPORTED + 1);

        assertThatThrownBy(() -> Schema.migrate(database))
                .isInstanceOf(SchemaVersionException.class)
                .hasMessageContaining("V" + (Schema.MAX_SUPPORTED + 1))
                .hasMessageContaining("supports at most");
    }

    @Test
    @DisplayName("deleting a world cascades to its child rows (FR-27)")
    void deletingAWorldCascadesToItsChildRows() throws Exception {
        Schema.migrate(database);

        database.inTransaction(connection -> {
            execute(connection, """
                    INSERT INTO player_world (id, owner_uuid, name, folder, seed, state)
                    VALUES ('11111111-1111-1111-1111-111111111111',
                            '22222222-2222-2222-2222-222222222222',
                            'test', 'pw_test', 42, 'READY')
                    """);
            execute(connection, """
                    INSERT INTO player_world_member (world_id, uuid, role)
                    VALUES ('11111111-1111-1111-1111-111111111111',
                            '22222222-2222-2222-2222-222222222222', 'OWNER')
                    """);
            execute(connection, """
                    INSERT INTO player_world_report (world_id, reporter_uuid, target_uuid, reason)
                    VALUES ('11111111-1111-1111-1111-111111111111',
                            '22222222-2222-2222-2222-222222222222',
                            '33333333-3333-3333-3333-333333333333', 'griefing')
                    """);
            return null;
        });

        database.inTransaction(connection -> {
            execute(connection, "DELETE FROM player_world WHERE id = '11111111-1111-1111-1111-111111111111'");
            return null;
        });

        assertThat(rowCount("player_world_member")).isZero();
        assertThat(rowCount("player_world_report")).isZero();
    }

    @Test
    @DisplayName("a report is handled by somebody or by nobody, never half of each")
    void aReportIsHandledBySomebodyOrNobody() throws Exception {
        Schema.migrate(database);

        database.inTransaction(connection -> {
            execute(connection, """
                    INSERT INTO player_world (id, owner_uuid, name, folder, seed, state)
                    VALUES ('44444444-4444-4444-4444-444444444444',
                            '55555555-5555-5555-5555-555555555555',
                            'w', 'pw_w', 1, 'READY')
                    """);
            return null;
        });

        assertThatThrownBy(() -> database.inTransaction(connection -> {
                    execute(connection, """
                            INSERT INTO player_world_report
                                (world_id, reporter_uuid, target_uuid, reason, handled_at)
                            VALUES ('44444444-4444-4444-4444-444444444444',
                                    '55555555-5555-5555-5555-555555555555',
                                    '66666666-6666-6666-6666-666666666666', 'spam', now())
                            """);
                    return null;
                }))
                .isInstanceOf(SQLException.class);
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.execute();
        }
    }

    private int rowCount(String table) throws SQLException {
        return database.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT count(*) FROM " + table);
                    ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        });
    }

    private List<String> tableNames() throws SQLException {
        return database.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                            SELECT table_name FROM information_schema.tables
                            WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                            ORDER BY table_name
                            """);
                    ResultSet rows = statement.executeQuery()) {
                List<String> names = new ArrayList<>();
                while (rows.next()) {
                    names.add(rows.getString(1));
                }
                return names;
            }
        });
    }

    /**
     * Fakes a migration applied by a node running newer code, which is the real
     * scenario the guard exists for: a rolling deploy where one node has already
     * migrated ahead of this one.
     */
    private void recordFutureMigration(int version) throws SQLException {
        database.inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO flyway_schema_history
                        (installed_rank, version, description, type, script,
                         checksum, installed_by, execution_time, success)
                    VALUES (?, ?, 'from a newer node', 'SQL', ?, NULL, current_user, 0, true)
                    """)) {
                statement.setInt(1, version);
                statement.setString(2, String.valueOf(version));
                statement.setString(3, "V" + version + "__from_a_newer_node.sql");
                statement.execute();
            }
            return null;
        });
    }
}
