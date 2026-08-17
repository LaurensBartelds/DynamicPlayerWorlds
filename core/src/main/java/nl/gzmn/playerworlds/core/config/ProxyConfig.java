package nl.gzmn.playerworlds.core.config;

import java.util.Objects;
import nl.gzmn.playerworlds.core.db.DatabaseSettings;

/**
 * Node-local configuration for the Velocity proxy plugin.
 *
 * <p>Deliberately small. Specification section 7's proxy {@code config.toml} listed
 * database credentials, a lobby name and a transfer expiry; the expiry is network
 * policy and belongs in {@link NetworkPolicy} ({@code transfers.expiry-seconds}),
 * enforced here from the database so the proxy and every backend agree. There is
 * no {@code worlds} server name: nodes register themselves dynamically (MN-17).
 *
 * @param database connection settings
 * @param lobbyServer the Velocity registered-server name players return to
 */
public record ProxyConfig(DatabaseSettings database, String lobbyServer) {

    public ProxyConfig {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(lobbyServer, "lobbyServer");
        if (lobbyServer.isBlank()) {
            throw new ConfigException("lobby server name must not be blank");
        }
    }
}
