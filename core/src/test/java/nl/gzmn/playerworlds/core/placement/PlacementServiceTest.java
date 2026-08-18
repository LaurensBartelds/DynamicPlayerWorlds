package nl.gzmn.playerworlds.core.placement;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.db.NodeRepository.NodeStatus;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository.NodeOccupancy;
import nl.gzmn.playerworlds.core.model.Visibility;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MN-14 to MN-16 and MN-28, against the pool shapes the specification describes.
 *
 * <p>The version cases are milestone 8's acceptance criterion from specification
 * section 11: "run the pair at different Minecraft versions, open a world on the
 * newer one, and assert the older one cannot acquire it and is excluded from
 * placement for it". The half that lives in the database — MN-26's predicate on
 * the acquiring {@code UPDATE} — is asserted in {@code PlayerWorldRepositoryTest};
 * this is the placement half.
 */
class PlacementServiceTest {

    private static final NetworkPolicy POLICY = NetworkPolicy.defaults();

    /** The build's own data version at the time of writing (Paper 26.2-112). */
    private static final int NEW = 4903;

    private static final int OLD = 4189;

    @Test
    @DisplayName("a live lease wins outright, even against a better-scoring node (MN-14, MN-16)")
    void aLiveLeaseWinsOutright() {
        NodeStatus busy = node("node-a", 4, 40, NEW);
        NodeStatus idle = node("node-b", 0, 0, NEW);

        PlacementDecision decision = PlacementService.select(
                new PlacementRequest(WorldId.random(), NEW, Visibility.PRIVATE, "node-a", "node-b"),
                List.of(busy, idle),
                Map.of(),
                POLICY);

        // node-b is emptier and holds the warm copy, and still loses: MN-16 says
        // every member resolves to the same node, and only the holder can open it.
        assertThat(decision).isEqualTo(new PlacementDecision.Held("node-a"));
    }

    @Test
    @DisplayName("a node older than the world is excluded before anything else is scored (MN-28)")
    void anOlderNodeIsExcludedFromPlacement() {
        NodeStatus older = node("node-old", 0, 0, OLD);
        NodeStatus newer = node("node-new", 4, 30, NEW);

        PlacementDecision decision = PlacementService.select(
                new PlacementRequest(WorldId.random(), NEW, Visibility.PRIVATE, null, "node-old"),
                List.of(older, newer),
                Map.of(),
                POLICY);

        // node-old is empty *and* holds the warm copy, which is the strongest
        // preference there is. MN-28 is a hard constraint and outranks both.
        assertThat(decision).isInstanceOf(PlacementDecision.Selected.class);
        assertThat(((PlacementDecision.Selected) decision).node().nodeId()).isEqualTo("node-new");
    }

    @Test
    @DisplayName("a world newer than every node is stranded, and says so (MN-28, section 12.7)")
    void aWorldNewerThanEveryNodeIsStranded() {
        PlacementDecision decision = PlacementService.select(
                new PlacementRequest(WorldId.random(), NEW, Visibility.PRIVATE, null, null),
                List.of(node("node-old", 0, 0, OLD), node("node-older", 0, 0, OLD - 1)),
                Map.of(),
                POLICY);

        assertThat(decision).isEqualTo(new PlacementDecision.NoNodeNewEnough(NEW, OLD));
    }

    @Test
    @DisplayName("a world with no committed snapshot may go to any node (MN-26)")
    void anUncommittedWorldMayGoAnywhere() {
        PlacementDecision decision = PlacementService.select(
                PlacementRequest.forNewWorld(WorldId.random(), Visibility.PRIVATE),
                List.of(node("node-old", 0, 0, OLD)),
                Map.of(),
                POLICY);

        assertThat(decision).isInstanceOf(PlacementDecision.Selected.class);
    }

    @Test
    @DisplayName("a node at nodes.max-worlds is excluded (MN-15, FR-26)")
    void aFullNodeIsExcluded() {
        NodeStatus full = node("node-full", POLICY.maxWorldsPerNode(), 0, NEW);
        NodeStatus room = node("node-room", POLICY.maxWorldsPerNode() - 1, 90, NEW);

        PlacementDecision decision = PlacementService.select(
                new PlacementRequest(WorldId.random(), NEW, Visibility.PRIVATE, null, null),
                List.of(full, room),
                Map.of(),
                POLICY);

        assertThat(decision).isInstanceOf(PlacementDecision.Selected.class);
        assertThat(((PlacementDecision.Selected) decision).node().nodeId()).isEqualTo("node-room");
    }

    @Test
    @DisplayName("a node over the heap or TPS threshold is excluded (MN-15)")
    void anUnhealthyNodeIsExcluded() {
        NodeStatus hot = new NodeStatus(
                "node-hot", "hot:25565", 0, 0, POLICY.maxHeapPercent() + 1, 20.0, false, NEW, "26.2", Instant.EPOCH);
        NodeStatus slow = new NodeStatus(
                "node-slow", "slow:25565", 0, 0, 10, POLICY.minTps() - 1, false, NEW, "26.2", Instant.EPOCH);

        PlacementDecision decision = PlacementService.select(
                new PlacementRequest(WorldId.random(), NEW, Visibility.PRIVATE, null, null),
                List.of(hot, slow),
                Map.of(),
                POLICY);

        assertThat(decision).isEqualTo(new PlacementDecision.NoCapacity(2));
    }

    @Test
    @DisplayName("a node that has not reported heap or TPS yet is still a candidate (MN-15)")
    void aNodeWithNoReadingsIsStillACandidate() {
        // Nullable columns, and a node that has just started has not filled them.
        // Excluding on absence empties the pool during a rolling restart.
        PlacementDecision decision = PlacementService.select(
                new PlacementRequest(WorldId.random(), NEW, Visibility.PRIVATE, null, null),
                List.of(new NodeStatus(
                        "node-fresh", "fresh:25565", 0, 0, null, null, false, NEW, "26.2", Instant.EPOCH)),
                Map.of(),
                POLICY);

        assertThat(decision).isInstanceOf(PlacementDecision.Selected.class);
    }

    @Test
    @DisplayName("a warm copy outweighs an emptier node (MN-15a)")
    void aWarmCopyOutweighsAnEmptierNode() {
        NodeStatus warm = node("node-warm", POLICY.maxWorldsPerNode() - 1, 50, NEW);
        NodeStatus empty = node("node-empty", 0, 0, NEW);

        PlacementDecision decision = PlacementService.select(
                new PlacementRequest(WorldId.random(), NEW, Visibility.PRIVATE, null, "node-warm"),
                List.of(warm, empty),
                Map.of(),
                POLICY);

        assertThat(((PlacementDecision.Selected) decision).node().nodeId()).isEqualTo("node-warm");
    }

    @Test
    @DisplayName("a public world prefers a node holding no private ones (MN-15a)")
    void aPublicWorldPrefersANodeHoldingNoPrivateWorlds() {
        NodeStatus mixed = node("node-mixed", 2, 4, NEW);
        NodeStatus clean = node("node-clean", 2, 4, NEW);

        PlacementDecision decision = PlacementService.select(
                new PlacementRequest(WorldId.random(), NEW, Visibility.PUBLIC, null, null),
                List.of(mixed, clean),
                Map.of("node-mixed", new NodeOccupancy(0, 2), "node-clean", new NodeOccupancy(2, 0)),
                POLICY);

        assertThat(((PlacementDecision.Selected) decision).node().nodeId()).isEqualTo("node-clean");
    }

    @Test
    @DisplayName("separation is a preference, not a partition: one usable node still places (MN-15a)")
    void separationDoesNotEmptyASmallPool() {
        PlacementDecision decision = PlacementService.select(
                new PlacementRequest(WorldId.random(), NEW, Visibility.PUBLIC, null, null),
                List.of(node("node-only", 1, 3, NEW)),
                Map.of("node-only", new NodeOccupancy(0, 4)),
                POLICY);

        assertThat(((PlacementDecision.Selected) decision).node().nodeId()).isEqualTo("node-only");
    }

    @Test
    @DisplayName("a draining node takes no new worlds (MN-20, MN-22)")
    void aDrainingNodeTakesNoNewWorlds() {
        NodeStatus draining =
                new NodeStatus("node-drain", "drain:25565", 0, 0, 10, 20.0, true, NEW, "26.2", Instant.EPOCH);

        PlacementDecision decision = PlacementService.select(
                new PlacementRequest(WorldId.random(), NEW, Visibility.PRIVATE, null, "node-drain"),
                List.of(draining),
                Map.of(),
                POLICY);

        assertThat(decision).isEqualTo(new PlacementDecision.NoNodesAlive());
    }

    @Test
    @DisplayName("equal candidates resolve the same way every time")
    void tiesAreBrokenDeterministically() {
        NodeStatus a = node("node-a", 1, 5, NEW);
        NodeStatus b = node("node-b", 1, 5, NEW);
        PlacementRequest request = new PlacementRequest(WorldId.random(), NEW, Visibility.PRIVATE, null, null);

        // Two proxies reading the same table in a different order must place the
        // same way, or MN-16 holds only by luck between the read and the acquire.
        PlacementDecision first = PlacementService.select(request, List.of(a, b), Map.of(), POLICY);
        PlacementDecision second = PlacementService.select(request, List.of(b, a), Map.of(), POLICY);

        assertThat(first).isEqualTo(second);
        assertThat(((PlacementDecision.Selected) first).node().nodeId()).isEqualTo("node-a");
    }

    @Test
    @DisplayName("an empty pool is reported as an empty pool")
    void anEmptyPoolIsReported() {
        PlacementDecision decision = PlacementService.select(
                PlacementRequest.forNewWorld(WorldId.random(), Visibility.PRIVATE), List.of(), Map.of(), POLICY);

        assertThat(decision).isEqualTo(new PlacementDecision.NoNodesAlive());
    }

    private static NodeStatus node(String id, int loadedWorlds, int players, int dataVersion) {
        return status(id, loadedWorlds, players, 10, 20.0, dataVersion);
    }

    private static NodeStatus status(
            String id, int loadedWorlds, int players, @Nullable Integer heap, @Nullable Double tps, int dataVersion) {
        return new NodeStatus(
                id, id + ":25565", loadedWorlds, players, heap, tps, false, dataVersion, "26.2", Instant.EPOCH);
    }
}
