package nl.gzmn.playerworlds.core.db;

import java.sql.ResultSet;
import java.sql.SQLException;

/** Maps the current row of a {@link ResultSet} to a value. */
@FunctionalInterface
public interface RowMapper<T> {

    /**
     * Reads the row the result set is positioned on. Implementations must not call
     * {@link ResultSet#next()}; the caller owns cursor movement.
     */
    T map(ResultSet row) throws SQLException;
}
