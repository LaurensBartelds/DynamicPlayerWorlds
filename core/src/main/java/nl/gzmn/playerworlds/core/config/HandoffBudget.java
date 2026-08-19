package nl.gzmn.playerworlds.core.config;

import java.time.Duration;
import java.util.Objects;

/**
 * How long a command that gives a world up may wait for it, and why that has a
 * ceiling.
 *
 * <p>{@code MIGRATE_WORLD} and {@code DRAIN_NODE} both wait for the same
 * sequence — countdown, eject, commit, unload, release — so they want the same
 * budget: the countdown the operator asked for, plus one
 * {@code storage.commit-timeout-seconds}, plus slack for the unload and the
 * lease release after it.
 *
 * <p>The ceiling is CP-5's. A claimed {@code node_command} row becomes claimable
 * again after {@code control.claim-timeout-seconds}, so a handler that waits
 * longer than that is still running when a second poller starts the same
 * migration or drain from the beginning. Both handlers argued this in their own
 * comments and only one of them applied it; this is the one place that does.
 */
public final class HandoffBudget {

    /**
     * Slack after the commit for the unload and the lease release. Both are short
     * and neither touches object storage; this is not a target.
     */
    public static final Duration MARGIN = Duration.ofSeconds(5);

    /**
     * Kept clear of the claim timeout, so the budget expires and the handler
     * reports a failure a moment <em>before</em> a second claimer could start.
     */
    public static final Duration CLAIM_HEADROOM = Duration.ofSeconds(1);

    private HandoffBudget() {}

    /**
     * The budget for a give-up with this countdown, clamped to fit inside one
     * claim window.
     *
     * <p>A countdown large enough to be clamped away is a configuration the
     * operator should see rather than one to silently honour halfway: it means
     * the handler will give up while players are still being counted down at.
     * {@code MigratePayload.MAX_COUNTDOWN_SECONDS} is 120 and the default claim
     * timeout is 60, so it is reachable.
     *
     * @param policy current network policy
     * @param countdownSeconds MN-21's visible warning, from the command payload
     */
    public static Duration forCountdown(NetworkPolicy policy, int countdownSeconds) {
        Objects.requireNonNull(policy, "policy");
        if (countdownSeconds < 0) {
            throw new IllegalArgumentException("countdownSeconds must not be negative, was: " + countdownSeconds);
        }
        Duration wanted = Duration.ofSeconds(countdownSeconds)
                .plus(policy.commitTimeout())
                .plus(MARGIN);
        Duration ceiling = policy.controlClaimTimeout().minus(CLAIM_HEADROOM);
        if (ceiling.isNegative() || ceiling.isZero()) {
            // A claim timeout under a second is refused by ConfigValidator; if one
            // reached here anyway, an unclamped budget is less wrong than a
            // non-positive one.
            return wanted;
        }
        return wanted.compareTo(ceiling) > 0 ? ceiling : wanted;
    }

    /** Whether {@link #forCountdown} had to clamp, so the caller can say so once. */
    public static boolean isClamped(NetworkPolicy policy, int countdownSeconds) {
        Objects.requireNonNull(policy, "policy");
        Duration wanted = Duration.ofSeconds(countdownSeconds)
                .plus(policy.commitTimeout())
                .plus(MARGIN);
        return wanted.compareTo(forCountdown(policy, countdownSeconds)) > 0;
    }
}
