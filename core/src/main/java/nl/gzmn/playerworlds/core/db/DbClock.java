package nl.gzmn.playerworlds.core.db;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;

/**
 * The only sanctioned source of "now" for anything a lease decision depends on
 * (MN-10b, CONTRIBUTING.md rule 5).
 *
 * <p>Every safety property in specification section 12.3 is a timestamp
 * comparison, and node clocks drift. Two nodes disagreeing by a minute about
 * what time it is means one of them can believe a lease has expired while the
 * holder still believes it is valid — and both are then writing the same world.
 * PostgreSQL is already the single linearization point (MN-3a), so it is also the
 * only clock the whole network agrees on.
 *
 * <p>{@code ArchitectureTest} forbids {@code System.currentTimeMillis()},
 * {@code Instant.now()} and {@code LocalDateTime.now()} in {@code core.db},
 * {@code core.lease} and {@code core.storage}, so this interface is the way out
 * of that restriction rather than a convenience wrapper around it.
 *
 * <h2>What this is not for</h2>
 *
 * <p>A lease deadline must <em>not</em> be evaluated by calling {@link #now()} in
 * a loop: the database is exactly what is unreachable in the failure this guards
 * against (MN-10b). Take the {@code lease_expires} value the database issued at
 * the last successful heartbeat and measure elapsed time against it locally with
 * {@link System#nanoTime()}, which is monotonic and immune to the wall clock
 * being stepped. {@link #elapsedSince} exists for exactly that.
 */
public interface DbClock {

    /**
     * Database time, as one round trip.
     *
     * <p>This is a query, so it costs a connection and must never run on the main
     * thread. Callers that need a timestamp inside a statement they are already
     * running should write {@code now()} into the SQL instead — that is both
     * cheaper and strictly more correct, because it is evaluated in the same
     * transaction as the rows it is compared against.
     */
    Instant now() throws SQLException;

    /**
     * How far the local monotonic clock has advanced since a {@code nanoTime}
     * reading, for deriving a deadline from a database-issued instant without
     * asking the database again.
     *
     * <p>Deliberately not {@code Instant.now()} arithmetic: the wall clock can be
     * stepped by NTP mid-outage, and a deadline that jumps backwards keeps a
     * fenced node playing.
     */
    static Duration elapsedSince(long startNanoTime) {
        return Duration.ofNanos(System.nanoTime() - startNanoTime);
    }
}
