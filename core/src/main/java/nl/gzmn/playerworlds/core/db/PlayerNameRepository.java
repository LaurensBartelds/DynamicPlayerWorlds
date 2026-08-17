package nl.gzmn.playerworlds.core.db;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@code player_name} cache (V2).
 *
 * <p>Every management command in specification section 6 takes a player name and
 * every table in section 4 stores a UUID; this bridges the two. The proxy writes
 * it on login, because the proxy is the one component that sees every login on
 * the network.
 *
 * <p>Treated as a cache throughout. A miss degrades a display name to a UUID
 * rather than failing the operation, and no other table references it.
 */
public final class PlayerNameRepository extends Repository {

    public PlayerNameRepository(Database database) {
        super(database);
    }

    /**
     * Records that a UUID is currently using this name.
     *
     * <p>Upserts on both keys. A name change moves the name to the new UUID's row
     * and, because the folded name is unique, has to displace whatever row held
     * it — an account that renamed away is stale by definition, and keeping its
     * old row would make a name lookup ambiguous.
     */
    public void remember(UUID uuid, String name) throws SQLException {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        database.inTransaction(connection -> {
            execute(connection, "DELETE FROM player_name WHERE lower(name) = lower(?) AND uuid <> ?", statement -> {
                statement.setString(1, name);
                statement.setObject(2, uuid);
            });
            return execute(connection, """
                    INSERT INTO player_name (uuid, name)
                    VALUES (?, ?)
                    ON CONFLICT (uuid) DO UPDATE
                      SET name = excluded.name,
                          updated_at = now()
                    """, statement -> {
                statement.setObject(1, uuid);
                statement.setString(2, name);
            });
        });
    }

    /** The UUID currently known by this name, case-insensitively. */
    public Optional<UUID> uuidOf(String name) throws SQLException {
        Objects.requireNonNull(name, "name");
        return database.withConnection(connection -> queryOne(
                connection,
                "SELECT uuid FROM player_name WHERE lower(name) = lower(?)",
                statement -> statement.setString(1, name),
                row -> Objects.requireNonNull(row.getObject("uuid", UUID.class), "uuid")));
    }

    /** The name last seen for this UUID. */
    public Optional<String> nameOf(UUID uuid) throws SQLException {
        Objects.requireNonNull(uuid, "uuid");
        return database.withConnection(connection -> queryOne(
                connection,
                "SELECT name FROM player_name WHERE uuid = ?",
                statement -> statement.setObject(1, uuid),
                row -> Objects.requireNonNull(row.getString("name"), "name")));
    }

    /**
     * Names for a set of UUIDs, in one query.
     *
     * <p>One statement rather than one per member: {@code /world members} renders
     * a whole list, and a query per row is how a cheap command becomes a slow one
     * on the world with the most people in it.
     *
     * <p>UUIDs with no cached name are simply absent; callers render those as the
     * UUID rather than treating it as an error.
     */
    public Map<UUID, String> namesOf(List<UUID> uuids) throws SQLException {
        Objects.requireNonNull(uuids, "uuids");
        if (uuids.isEmpty()) {
            return Map.of();
        }
        UUID[] array = uuids.toArray(new UUID[0]);
        return database.withConnection(connection -> {
            Map<UUID, String> names = new LinkedHashMap<>(array.length);
            for (Map.Entry<UUID, String> entry : queryList(
                    connection,
                    "SELECT uuid, name FROM player_name WHERE uuid = ANY (?)",
                    statement -> statement.setArray(1, connection.createArrayOf("uuid", array)),
                    row -> Map.entry(
                            Objects.requireNonNull(row.getObject("uuid", UUID.class), "uuid"),
                            Objects.requireNonNull(row.getString("name"), "name")))) {
                names.put(entry.getKey(), entry.getValue());
            }
            return Map.copyOf(names);
        });
    }
}
