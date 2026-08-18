package nl.gzmn.playerworlds.proxy.node;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.db.NodeRepository;
import nl.gzmn.playerworlds.core.db.NodeRepository.NodeStatus;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository.NodeOccupancy;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository.PlacementContext;
import nl.gzmn.playerworlds.core.model.Visibility;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.placement.PlacementDecision;
import nl.gzmn.playerworlds.core.placement.PlacementRequest;
import nl.gzmn.playerworlds.core.placement.PlacementService;

/**
 * Reads what {@link PlacementService} needs and asks it (MN-14).
 *
 * <p>The split is deliberate: the decision is a pure function in {@code :core},
 * tested against the pool shapes the specification describes; this class is the
 * three queries that feed it. Nothing here decides anything, so there is no
 * second copy of MN-15's ordering to drift out of step with the first.
 *
 * <p>Every method throws {@link SQLException} rather than swallowing it. A
 * placement that silently answered "no node available" because the database
 * blinked would tell a player their world is gone.
 */
public final class Placement {

    private final NodeRepository nodes;
    private final PlayerWorldRepository worlds;

    public Placement(NodeRepository nodes, PlayerWorldRepository worlds) {
        this.nodes = Objects.requireNonNull(nodes, "nodes");
        this.worlds = Objects.requireNonNull(worlds, "worlds");
    }

    /**
     * Where an existing world should be opened.
     *
     * <p>Reads the lease in database time, so a world that is loaded goes back to
     * the node holding it (MN-14, MN-16) rather than to whichever node happens to
     * score best at this instant.
     */
    public PlacementDecision forExistingWorld(WorldId worldId, NetworkPolicy policy) throws SQLException {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(policy, "policy");
        PlacementContext context =
                worlds.placementContext(worldId).orElseThrow(() -> new SQLException("no such world: " + worldId));
        return decide(PlacementRequest.of(worldId, context), policy);
    }

    /**
     * Where a world that does not exist yet should be created (FR-1a).
     *
     * <p>No committed data version, no lease and no warm copy, so this is MN-15's
     * scoring alone — but MN-15a's public/private separation still applies, from
     * the world's first placement rather than from its first migration.
     */
    public PlacementDecision forNewWorld(WorldId worldId, Visibility visibility, NetworkPolicy policy)
            throws SQLException {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(policy, "policy");
        return decide(PlacementRequest.forNewWorld(worldId, visibility), policy);
    }

    /**
     * Whether a named node could take a world, for {@code /world admin migrate}.
     *
     * <p>MN-21 is a manual override of MN-15's scoring, not of MN-28's version
     * predicate: an operator can move a world to a busier node, and cannot move it
     * to one too old to open it, because that node would simply refuse the lease
     * (MN-26) and the world would end up nowhere.
     */
    public MigrationCheck canTake(String nodeId, WorldId worldId, NetworkPolicy policy) throws SQLException {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(policy, "policy");

        PlacementContext context =
                worlds.placementContext(worldId).orElseThrow(() -> new SQLException("no such world: " + worldId));
        java.util.Optional<NodeStatus> found = nodes.find(nodeId);
        if (found.isEmpty()) {
            return new MigrationCheck.UnknownNode();
        }
        NodeStatus target = found.get();
        boolean alive = nodes.aliveNodes(policy.deadAfter()).stream()
                .anyMatch(node -> node.nodeId().equals(nodeId));
        if (!alive) {
            return new MigrationCheck.NotAvailable(target.draining());
        }
        Integer worldVersion = context.dataVersion();
        if (worldVersion != null && target.dataVersion() < worldVersion) {
            return new MigrationCheck.TooOld(worldVersion, target.dataVersion());
        }
        return new MigrationCheck.Ready(target, context);
    }

    /** The answer to "can this node take this world" (MN-21, MN-26, MN-28). */
    public sealed interface MigrationCheck {

        /** No heartbeat row of that name has ever existed. */
        record UnknownNode() implements MigrationCheck {}

        /** Registered, but not beating within {@code nodes.dead-after-seconds}, or draining. */
        record NotAvailable(boolean draining) implements MigrationCheck {}

        /** The node's chunk data version is below the world's (MN-26, MN-28). */
        record TooOld(int worldDataVersion, int nodeDataVersion) implements MigrationCheck {}

        /** The move can proceed. */
        record Ready(NodeStatus node, PlacementContext world) implements MigrationCheck {}
    }

    /** Nodes considered alive, for {@code /world admin list}. */
    public List<NodeStatus> aliveNodes(Duration deadAfter) throws SQLException {
        return nodes.aliveNodes(deadAfter);
    }

    /** Every registered node, alive or not, for {@code /world admin list}. */
    public List<NodeStatus> allNodes() throws SQLException {
        return nodes.allNodes();
    }

    private PlacementDecision decide(PlacementRequest request, NetworkPolicy policy) throws SQLException {
        List<NodeStatus> candidates = nodes.aliveNodes(policy.deadAfter());
        // Skipped when a lease is live: the decision is already made, and MN-15a's
        // separation term costs a group-by over every leased world in the network.
        Map<String, NodeOccupancy> occupancy = request.leaseHolder() != null ? Map.of() : worlds.liveLeaseOccupancy();
        return PlacementService.select(request, candidates, occupancy, policy);
    }
}
