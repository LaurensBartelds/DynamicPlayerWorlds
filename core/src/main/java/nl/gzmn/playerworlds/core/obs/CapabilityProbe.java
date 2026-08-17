package nl.gzmn.playerworlds.core.obs;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import nl.gzmn.playerworlds.core.db.Database;
import nl.gzmn.playerworlds.core.db.Schema;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Startup capability probe (plan section 10.4).
 *
 * <p>Runs once at enable and logs every result on a single loud block so an
 * operator reading the boot log sees filesystem type, the reflink verdict, free
 * space against the NFR-3 floor, Minecraft identity, schema version, and
 * database / object-storage reachability. A failure that violates a safety
 * property makes {@link CapabilityReport#safeToEnable()} false; the platform
 * entry point refuses enable.
 */
public final class CapabilityProbe {

    private static final Logger log = LoggerFactory.getLogger(CapabilityProbe.class);

    private CapabilityProbe() {}

    /**
     * Inputs for one probe run. Optional dependencies are skipped (and logged as
     * unchecked) when null — the foundation enables before the database is fully
     * wired, and the probe must still report the filesystem verdict.
     */
    public record Request(
            Path scratchPath,
            long minFreeBytes,
            @Nullable String minecraftVersion,
            @Nullable Integer dataVersion,
            @Nullable Database database,
            @Nullable StorageHealthCheck storage) {

        public Request {
            Objects.requireNonNull(scratchPath, "scratchPath");
            if (minFreeBytes < 0) {
                throw new IllegalArgumentException("minFreeBytes must not be negative, was: " + minFreeBytes);
            }
        }

        public static Request filesystemOnly(Path scratchPath, long minFreeBytes) {
            return new Request(scratchPath, minFreeBytes, null, null, null, null);
        }

        public Request withMinecraft(@Nullable String version, @Nullable Integer dataVersion) {
            return new Request(scratchPath, minFreeBytes, version, dataVersion, database, storage);
        }

        public Request withDatabase(@Nullable Database database) {
            return new Request(scratchPath, minFreeBytes, minecraftVersion, dataVersion, database, storage);
        }

        public Request withStorage(@Nullable StorageHealthCheck storage) {
            return new Request(scratchPath, minFreeBytes, minecraftVersion, dataVersion, database, storage);
        }
    }

    public static CapabilityReport run(Request request) {
        Objects.requireNonNull(request, "request");
        CapabilityReport.Builder report = CapabilityReport.builder()
                .minecraftVersion(request.minecraftVersion())
                .dataVersion(request.dataVersion())
                .minFreeBytes(request.minFreeBytes())
                .schemaRange(Schema.MIN_SUPPORTED, Schema.MAX_SUPPORTED, true);

        probeFilesystem(request, report);
        probeDatabase(request, report);
        probeStorage(request, report);
        return report.build();
    }

    /**
     * Logs the report as a contiguous block at INFO, with WARN/ERROR for the
     * lines that need an operator's attention.
     */
    public static void log(CapabilityReport report) {
        Objects.requireNonNull(report, "report");
        log.info("=== gzmn-worlds capability probe ===");
        log.info(
                "filesystem: type={} reflink={}",
                report.filesystemType(),
                report.reflink().wire());
        if (!report.reflink().isCheap()) {
            log.warn(
                    "filesystem: reflink verdict is {}; MN-5a snapshots will full-copy the dirty set; budget free space and IO accordingly (ext4 is the usual cause)",
                    report.reflink().wire());
        }
        log.info(
                "filesystem: freeBytes={} minFreeBytes={} ok={}",
                report.freeBytes(),
                report.minFreeBytes(),
                report.freeSpaceOk());
        if (report.minecraftVersion() != null || report.dataVersion() != null) {
            log.info(
                    "minecraft: version={} dataVersion={}",
                    report.minecraftVersion() == null ? "-" : report.minecraftVersion(),
                    report.dataVersion() == null ? "-" : report.dataVersion());
        } else {
            log.info("minecraft: (not supplied to probe)");
        }
        if (report.databaseChecked()) {
            if (report.databaseReachable()) {
                log.info(
                        "database: reachable schema=V{} supported=V{}..V{} ok={}",
                        report.schemaVersion() == null ? "?" : report.schemaVersion(),
                        report.schemaMinSupported(),
                        report.schemaMaxSupported(),
                        report.schemaOk());
            } else {
                log.error("database: UNREACHABLE: {}", report.databaseError());
            }
        } else {
            log.info("database: (not checked; no Database supplied)");
        }
        if (report.storageChecked()) {
            if (report.storageReachable()) {
                log.info("storage: reachable");
            } else {
                log.error("storage: UNREACHABLE: {}", report.storageError());
            }
        } else {
            log.info("storage: (not checked; no StorageHealthCheck supplied)");
        }
        if (report.safeToEnable()) {
            log.info("capability probe: OK; safe to enable");
        } else {
            for (String failure : report.failures()) {
                log.error("capability probe: FAIL: {}", failure);
            }
            int n = report.failures().size();
            log.error("capability probe: refusing enable ({} failure(s))", n);
        }
        log.info("=== end capability probe ===");
    }

    private static void probeFilesystem(Request request, CapabilityReport.Builder report) {
        Path scratch = request.scratchPath();
        try {
            if (!Files.isDirectory(scratch)) {
                Files.createDirectories(scratch);
            }
            FileStore store = Files.getFileStore(scratch);
            report.filesystemType(store.type());
            long free = store.getUsableSpace();
            report.freeBytes(free);
            boolean freeOk = request.minFreeBytes() <= 0 || free >= request.minFreeBytes();
            report.freeSpaceOk(freeOk);
            if (!freeOk) {
                report.failure(freeSpaceFailure(free, request.minFreeBytes()));
            }
        } catch (IOException e) {
            report.filesystemType("unreadable");
            report.freeSpaceOk(false);
            report.failure(filesystemReadFailure(scratch, e));
        }

        ReflinkVerdict verdict = ReflinkProbe.probe(scratch);
        report.reflink(verdict);
        // UNKNOWN is not a hard enable failure: the node can still run, it just
        // must not pretend snapshots are free. FULL_COPY is the expected common
        // case on ext4 and is likewise not a refusal.
    }

    private static void probeDatabase(Request request, CapabilityReport.Builder report) {
        Database database = request.database();
        if (database == null) {
            report.databaseChecked(false);
            return;
        }
        report.databaseChecked(true);
        try {
            database.now();
            report.databaseReachable(true);
            int version = Schema.appliedVersion(database);
            report.schemaVersion(version);
            boolean inRange = version == 0 || (version >= Schema.MIN_SUPPORTED && version <= Schema.MAX_SUPPORTED);
            // version 0 = empty database; migrate will bring it up. Still "ok" for
            // enable as long as the round trip worked.
            report.schemaRange(Schema.MIN_SUPPORTED, Schema.MAX_SUPPORTED, inRange);
            if (!inRange) {
                report.failure(schemaRangeFailure(version));
            }
        } catch (Exception e) {
            report.databaseReachable(false);
            report.databaseError(e.toString());
            report.failure(databaseUnreachableFailure(e));
        }
    }

    private static void probeStorage(Request request, CapabilityReport.Builder report) {
        StorageHealthCheck storage = request.storage();
        if (storage == null) {
            report.storageChecked(false);
            return;
        }
        report.storageChecked(true);
        try {
            storage.ping();
            report.storageReachable(true);
        } catch (Exception e) {
            report.storageReachable(false);
            report.storageError(e.toString());
            report.failure(storageUnreachableFailure(e));
        }
    }

    private static String freeSpaceFailure(long free, long minFree) {
        StringBuilder message = new StringBuilder(96);
        message.append("scratch free space ");
        message.append(free);
        message.append(" bytes is below floor ");
        message.append(minFree);
        message.append(" (NFR-3)");
        return message.toString();
    }

    private static String filesystemReadFailure(Path scratch, IOException e) {
        StringBuilder message = new StringBuilder(96);
        message.append("could not read scratch filesystem at ");
        message.append(scratch);
        message.append(": ");
        message.append(e.getMessage());
        return message.toString();
    }

    private static String schemaRangeFailure(int version) {
        StringBuilder message = new StringBuilder(96);
        message.append("database schema V");
        message.append(version);
        message.append(" outside supported range V");
        message.append(Schema.MIN_SUPPORTED);
        message.append("..V");
        message.append(Schema.MAX_SUPPORTED);
        return message.toString();
    }

    private static String databaseUnreachableFailure(Exception e) {
        StringBuilder message = new StringBuilder(64);
        message.append("database unreachable: ");
        message.append(e.getMessage());
        return message.toString();
    }

    private static String storageUnreachableFailure(Exception e) {
        StringBuilder message = new StringBuilder(64);
        message.append("object storage unreachable: ");
        message.append(e.getMessage());
        return message.toString();
    }
}
