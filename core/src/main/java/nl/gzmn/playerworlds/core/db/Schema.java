package nl.gzmn.playerworlds.core.db;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies migrations and decides whether this build may talk to the schema it
 * finds.
 *
 * <p>Who runs migrations is a decision the specification does not make, and it
 * matters for 24/7 operation: if every node migrates on startup then a rolling
 * restart has N nodes racing. Two rules settle it.
 *
 * <ol>
 *   <li><b>Migrations run under the FR-40 advisory lock.</b> Flyway takes a lock
 *       of its own, so concurrent migration is already safe; sharing the
 *       maintenance lock additionally stops a schema change from landing on top
 *       of a maintenance sweep that is midway through the shape being changed.
 *   <li><b>Every node checks the version before and after.</b> A schema newer
 *       than this build supports is refused outright — and refused <em>before</em>
 *       migrating, because the point is not to touch it at all.
 * </ol>
 */
public final class Schema {

    private static final Logger log = LoggerFactory.getLogger(Schema.class);

    private static final String MIGRATION_LOCATION = "classpath:db/migration";

    /**
     * The oldest schema this build can work against. Raise it only when a
     * migration removes or reshapes something the code no longer handles — never
     * as routine housekeeping, because raising it turns older nodes that were
     * running fine into nodes that refuse to start.
     */
    public static final int MIN_SUPPORTED = 1;

    /**
     * The newest schema this build knows about, which is the highest {@code V<n>}
     * migration shipped in {@code db/migration}. Adding a migration means bumping
     * this in the same commit; a mismatch means every node refuses to start,
     * which is the loudest possible reminder.
     */
    public static final int MAX_SUPPORTED = 5;

    /** How long a starting node waits for the migration lock before giving up. */
    private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(60);

    private Schema() {}

    /**
     * Brings the schema up to date and returns the version now in place.
     *
     * @throws SchemaVersionException if the schema is newer than this build
     *     supports, or if the migration lock could not be taken
     */
    public static int migrate(Database database) throws SQLException, InterruptedException {
        Optional<AdvisoryLock> lock = AdvisoryLock.tryAcquire(database, AdvisoryLock.MAINTENANCE_KEY, LOCK_TIMEOUT);
        if (lock.isEmpty()) {
            throw new SchemaVersionException("could not acquire the migration lock within " + LOCK_TIMEOUT
                    + "; another node is migrating or a maintenance job is running long. "
                    + "Refusing to start rather than migrating concurrently.");
        }
        // try/finally rather than try-with-resources: the lock is held for its
        // side effect on other processes and is never touched inside the body, and
        // an unreferenced resource variable is exactly what -Xlint:try flags.
        AdvisoryLock held = lock.get();
        try {
            return migrateHoldingLock(database);
        } finally {
            held.close();
        }
    }

    private static int migrateHoldingLock(Database database) {
        Flyway flyway = Flyway.configure(Schema.class.getClassLoader())
                .dataSource(database.dataSource())
                .locations(MIGRATION_LOCATION)
                // Forward-only and immutable (CONTRIBUTING.md rule 6). Checksum
                // validation is what turns an edited migration into a refusal to
                // start instead of a database that silently disagrees with its
                // own history.
                .validateOnMigrate(true)
                .cleanDisabled(true)
                .load();

        int before = currentVersion(flyway);
        if (before > MAX_SUPPORTED) {
            throw new SchemaVersionException("database schema is at V" + before + " but this build supports at most V"
                    + MAX_SUPPORTED + ". Another node has already migrated ahead of this one; "
                    + "deploy the newer build here before starting it.");
        }

        flyway.migrate();

        int after = currentVersion(flyway);
        if (after < MIN_SUPPORTED || after > MAX_SUPPORTED) {
            throw new SchemaVersionException("schema is at V" + after
                    + " after migrating, outside the supported range V" + MIN_SUPPORTED + "..V" + MAX_SUPPORTED);
        }

        if (after == before) {
            log.info("database schema already at V{}", after);
        } else {
            log.info("database schema migrated V{} -> V{}", before, after);
        }
        return after;
    }

    /**
     * Reads the applied schema version without migrating. Used by the startup
     * capability probe (plan section 10.4) so enable can log the version and
     * refuse a schema outside {@link #MIN_SUPPORTED}..{@link #MAX_SUPPORTED}.
     *
     * @return applied major version, or {@code 0} when no migration has run yet
     */
    public static int appliedVersion(Database database) {
        Flyway flyway = Flyway.configure(Schema.class.getClassLoader())
                .dataSource(database.dataSource())
                .locations(MIGRATION_LOCATION)
                .load();
        return currentVersion(flyway);
    }

    /**
     * The applied schema version, or {@code 0} for a database with no migrations
     * yet. Only the major component is used: migrations here are numbered
     * {@code V1}, {@code V2}, ... and a dotted version would mean the numbering
     * convention had drifted.
     */
    private static int currentVersion(Flyway flyway) {
        MigrationInfo current = flyway.info().current();
        if (current == null) {
            return 0;
        }
        MigrationVersion version = current.getVersion();
        if (version == null) {
            // A repeatable migration or a baseline marker. Neither is used here.
            return 0;
        }
        return Integer.parseInt(version.getVersion().split("\\.", 2)[0]);
    }
}
