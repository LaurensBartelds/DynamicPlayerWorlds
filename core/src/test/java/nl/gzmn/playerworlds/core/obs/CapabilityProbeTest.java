package nl.gzmn.playerworlds.core.obs;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import nl.gzmn.playerworlds.core.db.Database;
import nl.gzmn.playerworlds.core.db.Schema;
import nl.gzmn.playerworlds.core.db.TestPostgres;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CapabilityProbeTest {

    @TempDir
    Path temp;

    @Test
    @DisplayName("filesystem probe reports type, free space and a reflink verdict")
    void filesystemProbe() throws Exception {
        Path scratch = temp.resolve("scratch");
        Files.createDirectories(scratch);

        CapabilityReport report = CapabilityProbe.run(
                CapabilityProbe.Request.filesystemOnly(scratch, 0).withMinecraft("26.2", 4903));

        assertThat(report.safeToEnable()).isTrue();
        assertThat(report.filesystemType()).isNotBlank();
        assertThat(report.freeBytes()).isPositive();
        assertThat(report.reflink()).isIn(ReflinkVerdict.REFLINK, ReflinkVerdict.FULL_COPY, ReflinkVerdict.UNKNOWN);
        assertThat(report.minecraftVersion()).isEqualTo("26.2");
        assertThat(report.dataVersion()).isEqualTo(4903);
        assertThat(report.databaseChecked()).isFalse();
        assertThat(report.storageChecked()).isFalse();

        // Acceptance: the log path does not throw and includes the verdict token.
        CapabilityProbe.log(report);
        assertThat(report.reflink().wire()).isIn("reflink", "full-copy", "unknown");
    }

    @Test
    @DisplayName("free space below the floor refuses enable (NFR-3)")
    void freeSpaceFloorFails() throws Exception {
        Path scratch = temp.resolve("scratch-low");
        Files.createDirectories(scratch);
        long ridiculousFloor = Long.MAX_VALUE / 2;

        CapabilityReport report = CapabilityProbe.run(CapabilityProbe.Request.filesystemOnly(scratch, ridiculousFloor));

        assertThat(report.safeToEnable()).isFalse();
        assertThat(report.freeSpaceOk()).isFalse();
        assertThat(report.failures()).anyMatch(f -> f.contains("NFR-3"));
    }

    @Test
    @DisplayName("database round trip and schema version are reported when supplied")
    void databaseProbe() throws Exception {
        Path scratch = temp.resolve("scratch-db");
        Files.createDirectories(scratch);

        try (Database database = TestPostgres.freshDatabase()) {
            Schema.migrate(database);
            CapabilityReport report = CapabilityProbe.run(
                    CapabilityProbe.Request.filesystemOnly(scratch, 0).withDatabase(database));

            assertThat(report.safeToEnable()).isTrue();
            assertThat(report.databaseChecked()).isTrue();
            assertThat(report.databaseReachable()).isTrue();
            assertThat(report.schemaVersion()).isEqualTo(Schema.MAX_SUPPORTED);
            assertThat(report.schemaOk()).isTrue();
        }
    }

    @Test
    @DisplayName("storage health failure refuses enable")
    void storageFailure() throws Exception {
        Path scratch = temp.resolve("scratch-s3");
        Files.createDirectories(scratch);

        CapabilityReport report = CapabilityProbe.run(
                CapabilityProbe.Request.filesystemOnly(scratch, 0).withStorage(() -> {
                    throw new SQLException("connection refused");
                }));

        assertThat(report.safeToEnable()).isFalse();
        assertThat(report.storageChecked()).isTrue();
        assertThat(report.storageReachable()).isFalse();
        assertThat(report.failures()).anyMatch(f -> f.contains("object storage"));
    }
}
