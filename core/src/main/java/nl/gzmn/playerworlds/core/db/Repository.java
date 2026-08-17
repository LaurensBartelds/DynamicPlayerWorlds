package nl.gzmn.playerworlds.core.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The shape every repository lands in: a little plumbing, and nothing that hides
 * the SQL.
 *
 * <p>Deliberately not an ORM and deliberately not a query builder. Every statement
 * in this system that matters is a hand-shaped conditional {@code UPDATE} whose
 * exact predicate <em>is</em> the correctness argument — MN-8's lease acquisition,
 * MN-3a's fenced commit, MN-26's version gate. A reviewer has to be able to read
 * that predicate as SQL, in the source, next to the requirement id it implements.
 * What is worth sharing is only the boilerplate around it: bind, iterate, close,
 * and make the affected row count impossible to ignore.
 *
 * <p>Subclasses take a {@link Connection} in their methods rather than reaching
 * for one themselves, so that a caller can compose several repository calls into
 * one transaction. MN-3a requires exactly that: the manifest pointer and its
 * profiles commit together or not at all.
 */
public abstract class Repository {

    protected final Database database;

    protected Repository(Database database) {
        this.database = database;
    }

    /** Runs a query and maps every row. */
    protected static <T> List<T> queryList(
            Connection connection, String sql, StatementBinder binder, RowMapper<T> mapper) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet rows = statement.executeQuery()) {
                List<T> results = new ArrayList<>();
                while (rows.next()) {
                    results.add(mapper.map(rows));
                }
                return List.copyOf(results);
            }
        }
    }

    /**
     * Runs a query expected to match at most one row.
     *
     * <p>Throws if it matches more than one. A silent {@code LIMIT 1} would turn a
     * broken uniqueness assumption into an arbitrary choice between two rows,
     * which is the kind of bug that shows up months later as "sometimes the wrong
     * world loads".
     */
    protected static <T> Optional<T> queryOne(
            Connection connection, String sql, StatementBinder binder, RowMapper<T> mapper) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                T result = mapper.map(rows);
                if (rows.next()) {
                    throw new SQLException("expected at most one row from: " + sql);
                }
                return Optional.of(result);
            }
        }
    }

    /**
     * Runs a statement and returns how many rows it affected.
     *
     * <p>The return value is the answer, not a diagnostic. Zero rows affected is
     * how MN-8 reports "another node holds the lease", how MN-3a reports "this
     * commit has been fenced" and how MN-26 reports "this world needs a newer
     * server" — all of them expected outcomes with their own handling, none of
     * them exceptions.
     */
    protected static int execute(Connection connection, String sql, StatementBinder binder) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            return statement.executeUpdate();
        }
    }

    /** Binds the parameters of a prepared statement. */
    @FunctionalInterface
    protected interface StatementBinder {

        /** A binder for a statement with no parameters. */
        StatementBinder NONE = statement -> {};

        void bind(PreparedStatement statement) throws SQLException;
    }
}
