package nl.gzmn.playerworlds.core.db;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.model.WorldReport;

/**
 * {@code player_world_report} (FR-39).
 *
 * <p>In-world player reports with captured chat log for staff review.
 */
public final class ReportRepository extends Repository {

    public ReportRepository(Database database) {
        super(database);
    }

    /**
     * Creates a new moderation report.
     *
     * @param worldId world in which the report was filed
     * @param reporter player filing the report
     * @param target player reported
     * @param reason report explanation
     * @param chatLogJson group chat log around the incident formatted as JSON
     * @return the created report
     */
    public WorldReport createReport(WorldId worldId, UUID reporter, UUID target, String reason, String chatLogJson)
            throws SQLException {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(reporter, "reporter");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(chatLogJson, "chatLogJson");

        return database.inTransaction(connection -> queryOne(
                        connection,
                        """
                        INSERT INTO player_world_report (world_id, reporter_uuid, target_uuid, reason, chat_log)
                        VALUES (?, ?, ?, ?, ?::jsonb)
                        RETURNING id, world_id, reporter_uuid, target_uuid, reason, chat_log::text AS chat_log_json, created_at, handled_at, handled_by
                        """,
                        statement -> {
                            statement.setObject(1, worldId.value());
                            statement.setObject(2, reporter);
                            statement.setObject(3, target);
                            statement.setString(4, reason);
                            statement.setString(5, chatLogJson);
                        },
                        ReportRepository::mapReport)
                .orElseThrow(() -> new SQLException("INSERT player_world_report RETURNING produced no row")));
    }

    /** Lists open reports, oldest first (the staff queue). */
    public List<WorldReport> listOpenReports() throws SQLException {
        return database.withConnection(
                connection -> queryList(connection, """
                SELECT id, world_id, reporter_uuid, target_uuid, reason, chat_log::text AS chat_log_json, created_at, handled_at, handled_by
                  FROM player_world_report
                 WHERE handled_at IS NULL
                 ORDER BY created_at ASC
                """, StatementBinder.NONE, ReportRepository::mapReport));
    }

    /** Finds a report by id. */
    public Optional<WorldReport> findReport(long id) throws SQLException {
        return database.withConnection(connection ->
                queryOne(connection, """
                SELECT id, world_id, reporter_uuid, target_uuid, reason, chat_log::text AS chat_log_json, created_at, handled_at, handled_by
                  FROM player_world_report
                 WHERE id = ?
                """, statement -> statement.setLong(1, id), ReportRepository::mapReport));
    }

    /** Marks a report as handled by a staff member. */
    public boolean markHandled(long id, UUID handledBy) throws SQLException {
        Objects.requireNonNull(handledBy, "handledBy");

        return database.inTransaction(connection -> execute(connection, """
                        UPDATE player_world_report
                           SET handled_at = now(), handled_by = ?
                         WHERE id = ? AND handled_at IS NULL
                        """, statement -> {
                    statement.setObject(1, handledBy);
                    statement.setLong(2, id);
                })
                == 1);
    }

    private static WorldReport mapReport(ResultSet row) throws SQLException {
        OffsetDateTime createdAt = row.getObject("created_at", OffsetDateTime.class);
        if (createdAt == null) {
            throw new SQLException("created_at was NULL");
        }
        OffsetDateTime handledAt = row.getObject("handled_at", OffsetDateTime.class);
        UUID handledBy = row.getObject("handled_by", UUID.class);

        return new WorldReport(
                row.getLong("id"),
                new WorldId(Objects.requireNonNull(row.getObject("world_id", UUID.class), "world_id")),
                Objects.requireNonNull(row.getObject("reporter_uuid", UUID.class), "reporter_uuid"),
                Objects.requireNonNull(row.getObject("target_uuid", UUID.class), "target_uuid"),
                Objects.requireNonNull(row.getString("reason"), "reason"),
                Objects.requireNonNull(row.getString("chat_log_json"), "chat_log_json"),
                createdAt.toInstant(),
                handledAt == null ? null : handledAt.toInstant(),
                handledBy);
    }
}
