package nl.gzmn.playerworlds.proxy.world;

import static org.assertj.core.api.Assertions.assertThat;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.UUID;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WorldPresenceTest {

    private final WorldPresence presence = new WorldPresence();

    @Test
    @DisplayName("answers with the world the node reported")
    void answersWithTheReportedWorld() {
        UUID uuid = UUID.randomUUID();
        WorldId world = WorldId.random();
        presence.entered(uuid, "node-1", world);

        assertThat(presence.worldOf(playerOn(uuid, "node-1"))).contains(world);
    }

    @Test
    @DisplayName("says nothing about a player no node has reported")
    void saysNothingWithoutAReport() {
        assertThat(presence.worldOf(playerOn(UUID.randomUUID(), "node-1"))).isEmpty();
    }

    @Test
    @DisplayName("a report from a node the player has since left is not an answer")
    void ignoresAReportFromAnotherNode() {
        UUID uuid = UUID.randomUUID();
        presence.entered(uuid, "node-1", WorldId.random());

        // The lobby runs no worlds and so reports nothing. Without the node
        // check this would still answer with the world they walked out of.
        assertThat(presence.worldOf(playerOn(uuid, "lobby"))).isEmpty();
    }

    @Test
    @DisplayName("a player with no server connection has no world")
    void ignoresADisconnectedPlayer() {
        UUID uuid = UUID.randomUUID();
        presence.entered(uuid, "node-1", WorldId.random());

        assertThat(presence.worldOf(playerOn(uuid, null))).isEmpty();
    }

    @Test
    @DisplayName("a report of no world clears the entry")
    void noWorldClearsTheEntry() {
        UUID uuid = UUID.randomUUID();
        presence.entered(uuid, "node-1", WorldId.random());
        presence.entered(uuid, "node-1", null);

        assertThat(presence.worldOf(playerOn(uuid, "node-1"))).isEmpty();
        assertThat(presence.size()).isZero();
    }

    @Test
    @DisplayName("forgetting a player drops the entry")
    void forgettingDropsTheEntry() {
        UUID uuid = UUID.randomUUID();
        presence.entered(uuid, "node-1", WorldId.random());
        presence.forget(uuid);

        assertThat(presence.size()).isZero();
    }

    private Player playerOn(UUID uuid, @Nullable String serverName) {
        Optional<ServerConnection> connection = serverName == null
                ? Optional.empty()
                : Optional.of(mockConnection(
                        new ServerInfo(serverName, new InetSocketAddress(InetAddress.getLoopbackAddress(), 25566))));
        return (Player) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {Player.class},
                (proxyObj, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "getCurrentServer" -> connection;
                    default -> null;
                });
    }

    private ServerConnection mockConnection(ServerInfo info) {
        return (ServerConnection) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {ServerConnection.class},
                (proxyObj, method, args) -> method.getName().equals("getServerInfo") ? info : null);
    }
}
