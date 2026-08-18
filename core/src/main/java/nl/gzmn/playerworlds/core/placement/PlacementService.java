package nl.gzmn.playerworlds.core.placement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.db.NodeRepository.NodeStatus;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository.NodeOccupancy;
import nl.gzmn.playerworlds.core.model.Visibility;

/**
 * MN-14's placement service: which node should open this world.
 *
 * <p>A pure function. Everything it needs — the candidate nodes, the world's
 * lease and version, who holds a warm copy, what each node is already holding —
 * is passed in, so the decision can be unit-tested against the exact pool shapes
 * MN-15 and MN-15a describe rather than only against whatever a live network
 * happens to look like.
 *
 * <h2>The order, which is the specification's and not a convenience</h2>
 *
 * <ol>
 *   <li><b>A live lease wins outright</b> (MN-14, MN-16). Not a scoring term:
 *       every member of a world must resolve to the same node, and a world with a
 *       live lease can only be opened by its holder.
 *   <li><b>MN-28's version filter</b>, "evaluated before the scoring terms in
 *       MN-15a, not a preference". A node older than the world cannot open it at
 *       all (MN-26), so scoring it would be scoring a node that is about to
 *       refuse.
 *   <li><b>MN-15's exclusions</b> — loaded worlds against {@code nodes.max-worlds}
 *       (FR-26), heap against {@code nodes.max-heap-percent}, TPS against
 *       {@code nodes.min-tps}. Hard: "a node above a configured threshold on any
 *       of these is excluded from new placements".
 *   <li><b>MN-15a's preferences</b> — warm copy, then public/private separation,
 *       then load. Preferences, so a pool with one usable node still places.
 * </ol>
 *
 * <p>Draining nodes (MN-20, MN-22) never reach step 2: they are excluded by the
 * query that produces the candidate list, and excluded again here so a caller
 * passing its own list cannot lose the rule.
 */
public final class PlacementService {

    /**
     * A warm copy is worth more than any amount of load balancing.
     *
     * <p>MN-15a calls it "the single largest lever on join latency", and NFR-1's
     * cold-load budget is measured in tens of seconds against a warm load's
     * fraction of one. Sending a join to an emptier node that has to download the
     * whole world first is a worse answer for the player and a worse one for the
     * network, which pays the transfer.
     */
    private static final int WARM_COPY_BONUS = 10_000;

    /**
     * What one world of the opposite kind on a candidate costs it (MN-15a).
     *
     * <p>Large enough to decide between two otherwise comparable nodes, small
     * enough that a warm copy still wins and that a node with free capacity is
     * still chosen over no placement at all. MN-15a is explicit that this is "a
     * preference, not a partition, so that a small pool still functions".
     */
    private static final int MIXED_VISIBILITY_PENALTY = 100;

    /** One free world slot is worth more than one absent player, and both are tie-breaks. */
    private static final int FREE_SLOT_BONUS = 10;

    private PlacementService() {}

    /**
     * Chooses a node (MN-14).
     *
     * @param candidates nodes the caller believes are alive; typically
     *     {@code NodeRepository.aliveNodes(policy.deadAfter())}
     * @param occupancy live-leased worlds per node, split by visibility, for
     *     MN-15a's separation term. A node absent from the map is holding nothing.
     */
    public static PlacementDecision select(
            PlacementRequest request,
            List<NodeStatus> candidates,
            Map<String, NodeOccupancy> occupancy,
            NetworkPolicy policy) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(occupancy, "occupancy");
        Objects.requireNonNull(policy, "policy");

        // MN-14, MN-16. Before anything else, because a live lease is not a
        // preference and the node holding it is not a candidate to be scored.
        String holder = request.leaseHolder();
        if (holder != null) {
            return new PlacementDecision.Held(holder);
        }

        List<NodeStatus> alive =
                candidates.stream().filter(node -> !node.draining()).toList();
        if (alive.isEmpty()) {
            return new PlacementDecision.NoNodesAlive();
        }

        // MN-28, evaluated first.
        Integer worldVersion = request.dataVersion();
        List<NodeStatus> newEnough = worldVersion == null
                ? alive
                : alive.stream()
                        .filter(node -> node.dataVersion() >= worldVersion)
                        .toList();
        if (newEnough.isEmpty()) {
            int newest = alive.stream()
                    .mapToInt(NodeStatus::dataVersion)
                    .max()
                    .orElse(0);
            return new PlacementDecision.NoNodeNewEnough(worldVersion == null ? 0 : worldVersion, newest);
        }

        // MN-15's hard exclusions.
        List<NodeStatus> usable = new ArrayList<>(newEnough.size());
        for (NodeStatus node : newEnough) {
            if (!withinThresholds(node, policy)) {
                continue;
            }
            usable.add(node);
        }
        if (usable.isEmpty()) {
            return new PlacementDecision.NoCapacity(newEnough.size());
        }

        // MN-15a's preferences. Sorted rather than reduced so the tie-break is
        // visible: equal scores fall back to node id, which makes the choice
        // reproducible across proxies and across restarts.
        NodeStatus best = usable.stream()
                .max(Comparator.comparingInt((NodeStatus node) -> score(node, request, occupancy, policy))
                        .thenComparing(Comparator.comparing(NodeStatus::nodeId).reversed()))
                .orElseThrow();
        return new PlacementDecision.Selected(best, score(best, request, occupancy, policy));
    }

    /**
     * MN-15's exclusion terms.
     *
     * <p>An absent heap or TPS reading does not exclude: the heartbeat's columns
     * are nullable and a node that has not reported one yet is newly started,
     * which is the state placement most wants to use. Excluding on missing data
     * would empty the pool during a rolling restart.
     */
    private static boolean withinThresholds(NodeStatus node, NetworkPolicy policy) {
        if (node.loadedWorlds() >= policy.maxWorldsPerNode()) {
            return false;
        }
        Integer heap = node.heapPercent();
        if (heap != null && heap > policy.maxHeapPercent()) {
            return false;
        }
        Double tps = node.tps();
        return tps == null || tps >= policy.minTps();
    }

    /** MN-15a's two preferences, then MN-15's load terms as the tie-break. */
    private static int score(
            NodeStatus node, PlacementRequest request, Map<String, NodeOccupancy> occupancy, NetworkPolicy policy) {
        int score = 0;

        if (node.nodeId().equals(request.warmNode())) {
            score += WARM_COPY_BONUS;
        }

        NodeOccupancy holding = occupancy.getOrDefault(node.nodeId(), NodeOccupancy.EMPTY);
        int opposite = request.visibility() == Visibility.PUBLIC ? holding.privateWorlds() : holding.publicWorlds();
        score -= MIXED_VISIBILITY_PENALTY * opposite;

        score += FREE_SLOT_BONUS * (policy.maxWorldsPerNode() - node.loadedWorlds());
        score -= node.onlinePlayers();
        return score;
    }
}
