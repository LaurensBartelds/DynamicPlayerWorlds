package nl.gzmn.playerworlds.core.concurrent;

/**
 * Work ran on the wrong thread for this system's rules (main vs off-main).
 *
 * <p>Distinct from {@link java.lang.WrongThreadException}, which is the JDK's
 * structured-concurrency signal. Unchecked so a missed
 * {@link MainThread#assertOff()} fails loudly at the boundary rather than
 * forcing every caller to declare {@code throws}. A JDBC or object-storage call
 * that reaches the main thread is a defect (NFR-2, NFR-7), not a recoverable
 * condition.
 */
@SuppressWarnings("AvoidCommonTypeNames")
public final class WrongThreadException extends RuntimeException {

    public WrongThreadException(String message) {
        super(message);
    }
}
