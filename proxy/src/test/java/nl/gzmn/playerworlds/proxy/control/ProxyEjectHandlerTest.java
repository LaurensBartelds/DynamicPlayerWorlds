package nl.gzmn.playerworlds.proxy.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import nl.gzmn.playerworlds.core.control.CommandKind;
import nl.gzmn.playerworlds.core.control.CommandResult;
import nl.gzmn.playerworlds.core.control.EjectPayload;
import nl.gzmn.playerworlds.core.control.NodeCommand;
import org.junit.jupiter.api.Test;

class ProxyEjectHandlerTest {

    @Test
    void skipsWhenPayloadInvalid() {
        ProxyEjectHandler handler = new ProxyEjectHandler(null, () -> "lobby");
        NodeCommand command = new NodeCommand(
                1L,
                "proxy",
                null,
                null,
                CommandKind.EJECT_PLAYER.name(),
                "invalid json",
                Instant.now(),
                Instant.now().plusSeconds(60),
                null,
                null,
                0,
                null);

        CommandResult result = handler.handle(command);
        assertTrue(result.isOk());
    }

    @Test
    void skipsWhenPlayerMissing() {
        UUID targetUuid = UUID.randomUUID();
        ProxyServer proxy = mockProxy(Map.of(), Map.of());
        ProxyEjectHandler handler = new ProxyEjectHandler(proxy, () -> "lobby");

        NodeCommand command = new NodeCommand(
                2L,
                "proxy",
                null,
                null,
                CommandKind.EJECT_PLAYER.name(),
                EjectPayload.format(targetUuid, "reason"),
                Instant.now(),
                Instant.now().plusSeconds(60),
                null,
                null,
                0,
                null);

        CommandResult result = handler.handle(command);
        assertTrue(result.isOk());
    }

    @Test
    void errorsWhenLobbyServerNotFound() {
        UUID targetUuid = UUID.randomUUID();
        AtomicReference<RegisteredServer> connectedTo = new AtomicReference<>();
        Player player = mockPlayer(targetUuid, connectedTo);
        ProxyServer proxy = mockProxy(Map.of(targetUuid, player), Map.of());
        ProxyEjectHandler handler = new ProxyEjectHandler(proxy, () -> "lobby");

        NodeCommand command = new NodeCommand(
                3L,
                "proxy",
                null,
                null,
                CommandKind.EJECT_PLAYER.name(),
                EjectPayload.format(targetUuid, "reason"),
                Instant.now(),
                Instant.now().plusSeconds(60),
                null,
                null,
                0,
                null);

        CommandResult result = handler.handle(command);
        assertFalse(result.isOk());
        assertThat(result.wire()).contains("lobby");
        assertThat(connectedTo.get()).isNull();
    }

    @Test
    void requestsConnectionWhenPlayerAndLobbyPresent() {
        UUID targetUuid = UUID.randomUUID();
        AtomicReference<RegisteredServer> connectedTo = new AtomicReference<>();
        Player player = mockPlayer(targetUuid, connectedTo);
        RegisteredServer lobby = mockServer("lobby");
        ProxyServer proxy = mockProxy(Map.of(targetUuid, player), Map.of("lobby", lobby));
        ProxyEjectHandler handler = new ProxyEjectHandler(proxy, () -> "lobby");

        NodeCommand command = new NodeCommand(
                4L,
                "proxy",
                null,
                null,
                CommandKind.EJECT_PLAYER.name(),
                EjectPayload.format(targetUuid, "reason"),
                Instant.now(),
                Instant.now().plusSeconds(60),
                null,
                null,
                0,
                null);

        CommandResult result = handler.handle(command);
        assertTrue(result.isOk());
        assertThat(connectedTo.get()).isSameAs(lobby);
    }

    @SuppressWarnings("unchecked")
    private ProxyServer mockProxy(Map<UUID, Player> players, Map<String, RegisteredServer> servers) {
        return (ProxyServer) Proxy.newProxyInstance(
                ProxyServer.class.getClassLoader(), new Class<?>[] {ProxyServer.class}, (proxy, method, args) -> {
                    if ("getPlayer".equals(method.getName())
                            && args != null
                            && args.length == 1
                            && args[0] instanceof UUID uuid) {
                        return Optional.ofNullable(players.get(uuid));
                    }
                    if ("getServer".equals(method.getName())
                            && args != null
                            && args.length == 1
                            && args[0] instanceof String name) {
                        return Optional.ofNullable(servers.get(name));
                    }
                    if ("toString".equals(method.getName())) {
                        return "MockProxyServer";
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private Player mockPlayer(UUID uuid, AtomicReference<RegisteredServer> connectedTo) {
        ConnectionRequestBuilder reqBuilder = (ConnectionRequestBuilder) Proxy.newProxyInstance(
                ConnectionRequestBuilder.class.getClassLoader(),
                new Class<?>[] {ConnectionRequestBuilder.class},
                (proxy, method, args) -> {
                    if ("fireAndForget".equals(method.getName())) {
                        return null;
                    }
                    return defaultValue(method.getReturnType());
                });

        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(), new Class<?>[] {Player.class}, (proxy, method, args) -> {
                    if ("getUniqueId".equals(method.getName())) {
                        return uuid;
                    }
                    if ("createConnectionRequest".equals(method.getName())
                            && args != null
                            && args.length == 1
                            && args[0] instanceof RegisteredServer target) {
                        connectedTo.set(target);
                        return reqBuilder;
                    }
                    if ("toString".equals(method.getName())) {
                        return "MockPlayer[" + uuid + "]";
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private RegisteredServer mockServer(String name) {
        return (RegisteredServer) Proxy.newProxyInstance(
                RegisteredServer.class.getClassLoader(),
                new Class<?>[] {RegisteredServer.class},
                (proxy, method, args) -> {
                    if ("toString".equals(method.getName())) {
                        return "MockServer[" + name + "]";
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == void.class) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == byte.class || returnType == short.class || returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0.0f;
        }
        if (returnType == double.class) {
            return 0.0d;
        }
        if (returnType == char.class) {
            return '\0';
        }
        if (returnType == Optional.class) {
            return Optional.empty();
        }
        return null;
    }
}
