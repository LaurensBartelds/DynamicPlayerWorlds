package nl.gzmn.playerworlds.backend.world;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import nl.gzmn.playerworlds.backend.platform.DimensionKind;
import nl.gzmn.playerworlds.backend.world.LoadedWorld.IdleDecision;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The FR-25 / FR-25a idle state machine, tested as the pure function it is.
 *
 * <p>This is why the grace period counts sweeps rather than reading a clock: the
 * behaviour that matters — unload after the period, reset on a join, retry the
 * whole world after a refusal — is checkable without a server and without
 * sleeping.
 */
class LoadedWorldTest {

    private static final int IDLE_THRESHOLD = 3;

    private LoadedWorld world() {
        return new LoadedWorld(WorldId.random(), UUID.randomUUID(), "home", 42L, 5000);
    }

    @Test
    @DisplayName("a world unloads only after the full grace period (FR-25)")
    void unloadsAfterTheGracePeriod() {
        LoadedWorld world = world();

        assertThat(world.onSweep(false, IDLE_THRESHOLD)).isEqualTo(IdleDecision.WAIT);
        assertThat(world.onSweep(false, IDLE_THRESHOLD)).isEqualTo(IdleDecision.WAIT);
        assertThat(world.onSweep(false, IDLE_THRESHOLD)).isEqualTo(IdleDecision.UNLOAD);
    }

    @Test
    @DisplayName("any player resets the grace period (FR-25)")
    void aPlayerResetsTheTimer() {
        LoadedWorld world = world();
        world.onSweep(false, IDLE_THRESHOLD);
        world.onSweep(false, IDLE_THRESHOLD);

        assertThat(world.onSweep(true, IDLE_THRESHOLD)).isEqualTo(IdleDecision.WAIT);
        assertThat(world.idleSweeps()).isZero();

        // Back to a full period, not one sweep short of one.
        assertThat(world.onSweep(false, IDLE_THRESHOLD)).isEqualTo(IdleDecision.WAIT);
        assertThat(world.onSweep(false, IDLE_THRESHOLD)).isEqualTo(IdleDecision.WAIT);
        assertThat(world.onSweep(false, IDLE_THRESHOLD)).isEqualTo(IdleDecision.UNLOAD);
    }

    @Test
    @DisplayName("a blocked unload waits the retry period, then re-attempts (FR-25a)")
    void blockedUnloadRetriesAfterTheWait() {
        LoadedWorld world = world();
        for (int i = 0; i < IDLE_THRESHOLD; i++) {
            world.onSweep(false, IDLE_THRESHOLD);
        }
        world.unloadDeferred(2);

        assertThat(world.onSweep(false, IDLE_THRESHOLD)).isEqualTo(IdleDecision.WAIT);
        assertThat(world.onSweep(false, IDLE_THRESHOLD)).isEqualTo(IdleDecision.WAIT);
        // The idle counter stayed at its threshold, so the retry fires as soon as
        // the wait elapses rather than serving a second full grace period.
        assertThat(world.onSweep(false, IDLE_THRESHOLD)).isEqualTo(IdleDecision.UNLOAD);
    }

    @Test
    @DisplayName("a rejoin cancels a pending retry, not just the grace period (FR-25)")
    void rejoinCancelsAPendingRetry() {
        LoadedWorld world = world();
        for (int i = 0; i < IDLE_THRESHOLD; i++) {
            world.onSweep(false, IDLE_THRESHOLD);
        }
        world.unloadDeferred(5);

        // "Any join into any dimension of that world resets the timer and cancels
        // the pending unload" — a world somebody is standing in must not be taken
        // from under them when the retry wait expires.
        assertThat(world.onSweep(true, IDLE_THRESHOLD)).isEqualTo(IdleDecision.WAIT);
        assertThat(world.retryWaitSweeps()).isZero();
        assertThat(world.idleSweeps()).isZero();
        assertThat(world.onSweep(false, IDLE_THRESHOLD)).isEqualTo(IdleDecision.WAIT);
    }

    @Test
    @DisplayName("the idle counter does not run away while a retry is pending")
    void idleCounterIsBounded() {
        LoadedWorld world = world();
        for (int i = 0; i < 100; i++) {
            world.onSweep(false, IDLE_THRESHOLD);
        }

        assertThat(world.idleSweeps()).isEqualTo(IDLE_THRESHOLD);
    }

    @Test
    @DisplayName("materialised dimensions are tracked and both marks are idempotent")
    void materialisedTracksDimensions() {
        LoadedWorld world = world();

        assertThat(world.materialised()).isEmpty();
        assertThat(world.isMaterialised(DimensionKind.OVERWORLD)).isFalse();

        world.markMaterialised(DimensionKind.OVERWORLD);
        world.markMaterialised(DimensionKind.OVERWORLD);
        world.markMaterialised(DimensionKind.NETHER);

        assertThat(world.materialised()).containsExactlyInAnyOrder(DimensionKind.OVERWORLD, DimensionKind.NETHER);

        world.markUnloaded(DimensionKind.NETHER);
        world.markUnloaded(DimensionKind.NETHER);
        world.markUnloaded(DimensionKind.END);

        assertThat(world.materialised()).containsExactly(DimensionKind.OVERWORLD);
    }

    @Test
    @DisplayName("the seed the portal path needs is carried on the node, not re-read (FR-2, NFR-2)")
    void seedAndBorderAreCarried() {
        LoadedWorld world = world();

        assertThat(world.seed()).isEqualTo(42L);
        assertThat(world.borderRadius()).isEqualTo(5000);
    }
}
