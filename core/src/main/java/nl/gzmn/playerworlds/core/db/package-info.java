/**
 * Database access: the connection pool, migrations, the sanctioned clock and the
 * repository seam every later milestone writes its statements into.
 *
 * <p>This is the only package permitted to touch {@code java.sql},
 * {@code javax.sql} or HikariCP, and {@code ArchitectureTest} enforces that. The
 * reason is not layering taste: it is that "no blocking JDBC on the main thread"
 * (NFR-2, CONTRIBUTING.md rule 3) has to be a property of the structure rather
 * than of every caller remembering, and a single package is small enough to
 * audit by eye.
 *
 * <p>Data access is plain JDBC behind a thin repository layer, not an ORM.
 * Every statement in this system that matters is a hand-shaped conditional
 * {@code UPDATE} whose exact predicate <em>is</em> the correctness argument
 * (MN-3a, MN-8, MN-26). Those must be readable as SQL in the source, not
 * assembled by a framework.
 *
 * <p>Nothing here reads a local clock. See {@link
 * nl.gzmn.playerworlds.core.db.DbClock}.
 *
 * <p>The control plane's SQL and LISTEN connection live here
 * ({@link nl.gzmn.playerworlds.core.db.NodeCommandRepository},
 * {@link nl.gzmn.playerworlds.core.db.PgNotificationListener}); protocol types
 * and orchestration are in {@code core.control}.
 */
@NullMarked
package nl.gzmn.playerworlds.core.db;

import org.jspecify.annotations.NullMarked;
