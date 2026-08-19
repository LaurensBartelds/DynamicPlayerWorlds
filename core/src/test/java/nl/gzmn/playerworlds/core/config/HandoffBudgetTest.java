package nl.gzmn.playerworlds.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CP-5: a give-up command may not outlive the claim that authorises it. */
class HandoffBudgetTest {

    @Test
    @DisplayName("the budget is the countdown plus one commit budget plus slack")
    void budgetIsCountdownPlusCommitPlusMargin() {
        NetworkPolicy policy = NetworkPolicy.defaults(); // commit 15s, claim timeout 60s

        assertThat(HandoffBudget.forCountdown(policy, 10)).isEqualTo(Duration.ofSeconds(10 + 15 + 5));
        assertThat(HandoffBudget.isClamped(policy, 10)).isFalse();
    }

    @Test
    @DisplayName("a countdown that would outlive the claim window is clamped (CP-5)")
    void aCountdownThatOutlivesTheClaimIsClamped() {
        NetworkPolicy policy = NetworkPolicy.defaults(); // claim timeout 60s

        // MigratePayload allows up to 120s, so this is reachable rather than
        // hypothetical: 120 + 15 + 5 is more than twice the claim window, and an
        // unclamped wait lets a second poller start the same migration.
        assertThat(HandoffBudget.forCountdown(policy, 120)).isEqualTo(Duration.ofSeconds(59));
        assertThat(HandoffBudget.isClamped(policy, 120)).isTrue();
    }

    @Test
    @DisplayName("a longer claim timeout raises the ceiling rather than the budget")
    void ceilingFollowsTheClaimTimeout() {
        NetworkPolicy policy = NetworkPolicy.fromRaw(Map.of(NetworkPolicy.KEY_CONTROL_CLAIM_TIMEOUT_SECONDS, "180"));

        assertThat(HandoffBudget.forCountdown(policy, 120)).isEqualTo(Duration.ofSeconds(140));
        assertThat(HandoffBudget.isClamped(policy, 120)).isFalse();
    }
}
