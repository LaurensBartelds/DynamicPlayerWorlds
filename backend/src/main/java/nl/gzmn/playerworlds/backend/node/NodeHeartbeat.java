package nl.gzmn.playerworlds.backend.node;

import java.sql.SQLException;
import java.util.Objects;
import java.util.function.IntSupplier;
import nl.gzmn.playerworlds.backend.platform.ServerIdentity;
import nl.gzmn.playerworlds.core.config.NodeConfig;
import nl.gzmn.playerworlds.core.db.NodeRepository;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes this node's heartbeat row (MN-17, MN-18).
 *
 * <p>The row is how the proxy learns a node exists and how placement decides
 * whether it is a candidate. It carries the node's chunk {@code DataVersion},
 * because MN-15 filters on that before evaluating any other term (MN-28) — a
 * node that cannot open a world should never be scored against one that can.
 *
 * <p>Runs on the scheduled pool, never the tick thread: it is a database write.
 * The counts it reports are read from suppliers the caller wires to whatever
 * owns them, so this class does not need to know about worlds or players.
 */
public final class NodeHeartbeat implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(NodeHeartbeat.class);

    private final NodeRepository nodes;
    private final NodeConfig config;
    private final ServerIdentity identity;
    private final IntSupplier loadedWorlds;
    private final IntSupplier onlinePlayers;

    private volatile boolean draining;

    /** Set once a failure has been logged, so an outage does not fill the log. */
    private volatile boolean failing;

    public NodeHeartbeat(
            NodeRepository nodes,
            NodeConfig config,
            ServerIdentity identity,
            IntSupplier loadedWorlds,
            IntSupplier onlinePlayers) {
        this.nodes = Objects.requireNonNull(nodes, "nodes");
        this.config = Objects.requireNonNull(config, "config");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.loadedWorlds = Objects.requireNonNull(loadedWorlds, "loadedWorlds");
        this.onlinePlayers = Objects.requireNonNull(onlinePlayers, "onlinePlayers");
    }

    @Override
    public void run() {
        try {
            nodes.heartbeat(
                    config.nodeId(),
                    config.address(),
                    loadedWorlds.getAsInt(),
                    onlinePlayers.getAsInt(),
                    heapPercent(),
                    null,
                    draining,
                    identity.dataVersion(),
                    identity.minecraftVersion());
            if (failing) {
                failing = false;
                log.info("heartbeat restored for node {}", config.nodeId());
            }
        } catch (SQLException e) {
            // Missing a heartbeat is not itself dangerous — MN-18 only excludes
            // this node from placement, and takeover is governed by lease expiry
            // rather than by liveness (MN-8). Logged once so a database outage
            // does not bury the log it is being diagnosed from.
            if (!failing) {
                failing = true;
                log.error("could not publish the heartbeat for node {}", config.nodeId(), e);
            }
        } catch (RuntimeException e) {
            log.error("heartbeat failed unexpectedly for node {}", config.nodeId(), e);
        }
    }

    /** Stops this node being chosen for new placements (MN-20). */
    public void setDraining(boolean draining) {
        this.draining = draining;
    }

    public boolean isDraining() {
        return draining;
    }

    /**
     * Removes the registration on a clean shutdown (MN-17).
     *
     * <p>Best effort. A node that dies without doing this simply stops beating
     * and ages out of the alive set, which is the same outcome a moment later.
     */
    public void deregister() {
        try {
            nodes.deregister(config.nodeId());
        } catch (SQLException e) {
            log.warn("could not deregister node {}; it will age out instead", config.nodeId(), e);
        }
    }

    /** Heap in use as a percentage of the maximum, for MN-15's exclusion term. */
    private static @Nullable Integer heapPercent() {
        Runtime runtime = Runtime.getRuntime();
        long max = runtime.maxMemory();
        if (max <= 0) {
            return null;
        }
        long used = runtime.totalMemory() - runtime.freeMemory();
        return (int) Math.min(100L, used * 100L / max);
    }
}
