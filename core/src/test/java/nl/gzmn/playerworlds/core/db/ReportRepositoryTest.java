package nl.gzmn.playerworlds.core.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.Visibility;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.model.WorldReport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReportRepositoryTest {

    private Database database;
    private PlayerWorldRepository worlds;
    private ReportRepository reports;
    private UUID owner;
    private WorldId worldId;

    @BeforeEach
    void openDatabase() throws Exception {
        database = TestPostgres.freshDatabase();
        Schema.migrate(database);
        worlds = new PlayerWorldRepository(database);
        reports = new ReportRepository(database);
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
    @DisplayName("createReport inserts open report with chat log (FR-39)")
    void createAndListOpenReport() throws Exception {
        UUID reporter = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        String chatLog = "[{\"sender\":\"Bob\",\"message\":\"bad words\"}]";

        WorldReport report = reports.createReport(worldId, reporter, target, "Harassment", chatLog);

        assertThat(report.id()).isPositive();
        assertThat(report.worldId()).isEqualTo(worldId);
        assertThat(report.reporterUuid()).isEqualTo(reporter);
        assertThat(report.targetUuid()).isEqualTo(target);
        assertThat(report.reason()).isEqualTo("Harassment");
        assertThat(report.chatLogJson()).contains("bad words").contains("Bob");
        assertThat(report.handledAt()).isNull();
        assertThat(report.handledBy()).isNull();

        List<WorldReport> open = reports.listOpenReports();
        assertThat(open).hasSize(1);
        assertThat(open.getFirst().id()).isEqualTo(report.id());
    }

    @Test
    @DisplayName("markHandled completes report and removes from open queue")
    void markReportHandled() throws Exception {
        UUID reporter = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID staff = UUID.randomUUID();

        WorldReport report = reports.createReport(worldId, reporter, target, "Spam", "[]");
        assertThat(reports.listOpenReports()).hasSize(1);

        boolean handled = reports.markHandled(report.id(), staff);
        assertThat(handled).isTrue();

        assertThat(reports.listOpenReports()).isEmpty();
        Optional<WorldReport> found = reports.findReport(report.id());
        assertThat(found).isPresent();
        assertThat(found.get().handledAt()).isNotNull();
        assertThat(found.get().handledBy()).isEqualTo(staff);
    }
}
