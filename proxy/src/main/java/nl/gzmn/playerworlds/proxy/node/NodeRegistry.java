package nl.gzmn.playerworlds.proxy.node;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.net.InetSocketAddress;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import nl.gzmn.playerworlds.core.db.NodeRepository;
import nl.gzmn.playerworlds.core.db.NodeRepository.NodeStatus;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps Velocity's registered servers in step with the heartbeat table (MN-17).
 *
 * <p>MN-17 is explicit that {@code velocity.toml} is not edited to add capacity:
 * nodes register themselves and are started and stopped through Pelican. So the
 * proxy discovers them from {@code worlds_node} and calls
 * {@code registerServer} / {@code unregisterServer} to match.
 *
 * <p>Runs on a schedule rather than reacting to an event, because the source of
 * truth is a table several nodes write to independently and there is nothing to
 * subscribe to. The sweep is idempotent: it registers what is missing,
 * unregisters what has gone, and leaves everything else alone.
 */
public final class NodeRegistry {

    private static final Logger log = LoggerFactory.getLogger(NodeRegistry.class);

    private final ProxyServer proxy;
    private final NodeRepository nodes;

    /** Names this class registered, so it never unregisters a server from velocity.toml. */
    private final Set<String> ours = new HashSet<>();

    public NodeRegistry(ProxyServer proxy, NodeRepository nodes) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.nodes = Objects.requireNonNull(nodes, "nodes");
    }

    /**
     * Brings Velocity's server list in line with the alive nodes.
     *
     * @param deadAfter {@code nodes.dead-after-seconds}
     */
    public synchronized void sync(Duration deadAfter) {
        final List<NodeStatus> alive;
        try {
            alive = nodes.aliveNodes(deadAfter);
        } catch (SQLException e) {
            // Keep whatever is registered. Unregistering everything because the
            // database blinked would disconnect routing for nodes that are fine.
            log.warn("could not read the node table; leaving server registrations as they are: {}", e.getMessage());
            return;
        }

        Set<String> seen = new HashSet<>(alive.size());
        for (NodeStatus node : alive) {
            seen.add(node.nodeId());
            register(node);
        }

        for (String registered : Set.copyOf(ours)) {
            if (!seen.contains(registered)) {
                proxy.getServer(registered).ifPresent(server -> {
                    proxy.unregisterServer(server.getServerInfo());
                    log.info("unregistered node {} (no longer alive)", registered);
                });
                ours.remove(registered);
            }
        }
    }

    private void register(NodeStatus node) {
        Optional<RegisteredServer> existing = proxy.getServer(node.nodeId());
        InetSocketAddress address;
        try {
            address = parseAddress(node.address());
        } catch (IllegalArgumentException e) {
            log.error("node {} advertises an unusable address '{}'; not registering", node.nodeId(), node.address());
            return;
        }

        if (existing.isPresent()) {
            if (existing.get().getServerInfo().getAddress().equals(address)) {
                return;
            }
            // A node that moved. Re-register, but only if we own the name —
            // a server declared in velocity.toml is the operator's, not ours.
            if (!ours.contains(node.nodeId())) {
                log.warn(
                        "node {} advertises {} but a server of that name from velocity.toml points elsewhere; "
                                + "leaving the operator's entry alone (MN-17 says capacity is not added by editing it)",
                        node.nodeId(),
                        node.address());
                return;
            }
            proxy.unregisterServer(existing.get().getServerInfo());
        }

        proxy.registerServer(new ServerInfo(node.nodeId(), address));
        ours.add(node.nodeId());
        log.info("registered node {} at {}", node.nodeId(), node.address());
    }

    /** Whether a node is currently routable. */
    public Optional<RegisteredServer> server(String nodeId) {
        return proxy.getServer(nodeId);
    }

    /**
     * Picks a node for a world (MN-14's placement, minus the scoring).
     *
     * <p>Milestone 8 replaces the body with MN-15's real selection: version
     * filtering first, then load, heap and TPS exclusions, then the warm-copy and
     * public-separation preferences in MN-15a. Until a second node exists there is
     * nothing to score, and the honest version of "choose a node" is "the one that
     * is alive and least loaded" — which is the order {@code aliveNodes} already
     * returns.
     *
     * @param worldDataVersion the world's committed chunk data version, or
     *     {@code null} when it has never been committed and any node may take it
     */
    public Optional<NodeStatus> selectNode(WorldId worldId, @Nullable Integer worldDataVersion, Duration deadAfter) {
        Objects.requireNonNull(worldId, "worldId");
        try {
            for (NodeStatus candidate : nodes.aliveNodes(deadAfter)) {
                // MN-28's version predicate, evaluated before anything else.
                if (worldDataVersion == null || candidate.dataVersion() >= worldDataVersion) {
                    return Optional.of(candidate);
                }
            }
            return Optional.empty();
        } catch (SQLException e) {
            log.error("could not select a node for world {}", worldId, e);
            return Optional.empty();
        }
    }

    /** Nodes currently considered alive (MN-14). */
    public List<NodeStatus> aliveNodes(Duration deadAfter) throws SQLException {
        Objects.requireNonNull(deadAfter, "deadAfter");
        return nodes.aliveNodes(deadAfter);
    }

    /** Unregisters everything this class registered, on proxy shutdown. */
    public synchronized void unregisterAll() {
        for (String registered : Set.copyOf(ours)) {
            proxy.getServer(registered).ifPresent(server -> proxy.unregisterServer(server.getServerInfo()));
        }
        ours.clear();
    }

    /** {@code host:port} as Velocity wants it. */
    static InetSocketAddress parseAddress(String address) {
        int colon = address.lastIndexOf(':');
        if (colon <= 0 || colon == address.length() - 1) {
            throw new IllegalArgumentException("address must be host:port, was: " + address);
        }
        String host = address.substring(0, colon);
        final int port;
        try {
            port = Integer.parseInt(address.substring(colon + 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("address port is not a number: " + address, e);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("address port is out of range: " + address);
        }
        return new InetSocketAddress(host, port);
    }
}
