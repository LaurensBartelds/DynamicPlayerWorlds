package nl.gzmn.playerworlds.proxy.control;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import nl.gzmn.playerworlds.core.control.CommandHandler;
import nl.gzmn.playerworlds.core.control.CommandResult;
import nl.gzmn.playerworlds.core.control.EjectPayload;
import nl.gzmn.playerworlds.core.control.NodeCommand;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles {@link nl.gzmn.playerworlds.core.control.CommandKind#EJECT_PLAYER} on the Velocity proxy.
 *
 * <p>Transfers the player back to the configured lobby server.
 */
public final class ProxyEjectHandler implements CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(ProxyEjectHandler.class);

    private final @Nullable ProxyServer proxy;
    private final Supplier<String> lobbyServerSupplier;

    public ProxyEjectHandler(@Nullable ProxyServer proxy, Supplier<String> lobbyServerSupplier) {
        this.proxy = proxy;
        this.lobbyServerSupplier = Objects.requireNonNull(lobbyServerSupplier, "lobbyServerSupplier");
    }

    @Override
    public CommandResult handle(NodeCommand command) {
        Optional<EjectPayload> parsed = EjectPayload.parse(command.payloadJson());
        if (parsed.isEmpty()) {
            return CommandResult.ok();
        }
        if (proxy == null) {
            return CommandResult.ok();
        }

        UUID targetUuid = parsed.get().playerUuid();
        Optional<Player> player = proxy.getPlayer(targetUuid);
        if (player.isEmpty()) {
            return CommandResult.ok();
        }

        String lobbyName = lobbyServerSupplier.get();
        if (lobbyName == null || lobbyName.isBlank()) {
            log.warn("cannot eject player {}: lobby server not configured", targetUuid);
            return CommandResult.error("lobby server not configured");
        }

        Optional<RegisteredServer> lobby = proxy.getServer(lobbyName);
        if (lobby.isEmpty()) {
            log.warn("cannot eject player {}: lobby server '{}' not registered", targetUuid, lobbyName);
            return CommandResult.error("lobby server '" + lobbyName + "' not registered");
        }

        player.get().createConnectionRequest(lobby.get()).fireAndForget();
        log.info("routed player {} back to lobby '{}'", targetUuid, lobbyName);
        return CommandResult.ok();
    }
}
