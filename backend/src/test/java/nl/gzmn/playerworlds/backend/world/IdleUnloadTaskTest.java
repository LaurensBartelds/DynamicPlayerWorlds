package nl.gzmn.playerworlds.backend.world;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Sweep arithmetic for the FR-25 grace period and the FR-25a retry wait. */
class IdleUnloadTaskTest {

    @Test
    @DisplayName("the default grace period is thirty sweeps of the default interval")
    void defaultsConvertAsExpected() {
        assertThat(IdleUnloadTask.sweepsIn(Duration.ofMinutes(10))).isEqualTo(30);
        assertThat(IdleUnloadTask.sweepsIn(Duration.ofMinutes(2))).isEqualTo(6);
    }

    @Test
    @DisplayName("a period shorter than one sweep still costs one sweep, never zero")
    void roundsUpAndNeverReachesZero() {
        // Rounding down would make the world unload on the same sweep that first
        // observed it go quiet, which is not a grace period at all.
        assertThat(IdleUnloadTask.sweepsIn(Duration.ofSeconds(1))).isEqualTo(1);
        assertThat(IdleUnloadTask.sweepsIn(Duration.ZERO)).isEqualTo(1);
        assertThat(IdleUnloadTask.sweepsIn(Duration.ofSeconds(21))).isEqualTo(2);
    }

    @Test
    @DisplayName("an absurd period does not overflow into a negative count")
    void hugePeriodsSaturate() {
        assertThat(IdleUnloadTask.sweepsIn(Duration.ofDays(365 * 1000L))).isPositive();
    }
}
