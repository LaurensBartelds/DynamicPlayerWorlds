package nl.gzmn.playerworlds.core.placement;

import java.util.Objects;
import nl.gzmn.playerworlds.core.db.NodeRepository.NodeStatus;

/**
 * What {@link PlacementService} concluded for one world (MN-14, MN-15, MN-28).
 *
 * <p>A sealed result rather than an {@code Optional<NodeStatus>}, because the
 * three ways of having no node are three different messages to the player and
 * three different operator problems. "No node is new enough" (MN-28) means an
 * upgrade was rolled back and the world is stranded until a newer node returns;
 * "every node is full" means the pool needs capacity; "no node is alive" means
 * the pool is down. Collapsing them into an empty optional is how an operator
 * ends up reading logs to find out which.
 */
public sealed interface PlacementDecision {

    /**
     * The world already holds a live lease, so it goes back to that node (MN-14).
     *
     * <p>Not scored, and not overridable by a better candidate: MN-16 requires
     * that every member of a world resolves to the same node, and a world with a
     * live lease can only be opened by the node holding it. A holder that is over
     * MN-15's thresholds still wins — the alternative is a second copy of a loaded
     * world, which the lease exists to prevent.
     *
     * @param nodeId the lease holder, which may not be registered with the proxy
     *     if it has just died; the caller checks routability
     */
    record Held(String nodeId) implements PlacementDecision {
        public Held {
            Objects.requireNonNull(nodeId, "nodeId");
        }
    }

    /** A node was chosen and the caller should now acquire the lease on it (MN-14). */
    record Selected(NodeStatus node, int score) implements PlacementDecision {
        public Selected {
            Objects.requireNonNull(node, "node");
        }
    }

    /** No node in the pool is alive and not draining (MN-18, MN-20). */
    record NoNodesAlive() implements PlacementDecision {}

    /**
     * Nodes are alive, but every one of them is older than the world (MN-28).
     *
     * <p>Section 12.7's "a world is newer than every available node": the pool was
     * rolled back after an upgrade. The world is safe and unreachable, and saying
     * so is the whole of the correct handling.
     */
    record NoNodeNewEnough(int worldDataVersion, int newestNodeDataVersion) implements PlacementDecision {}

    /** Nodes are alive and new enough, but all are over an MN-15 threshold. */
    record NoCapacity(int candidates) implements PlacementDecision {}
}
