package nl.gzmn.playerworlds.core.profile;

/**
 * A profile payload that cannot be read (FR-16).
 *
 * <p>Unchecked, because there is exactly one correct response and it is the same
 * everywhere: refuse the load and send the player to lobby with an error. FR-16
 * is explicit that granting an empty inventory instead is not acceptable — it is
 * indistinguishable from a wipe, and the player would spend it before anyone
 * realised.
 *
 * <p>The repair path is FR-16a's admin rollback to a retained snapshot, which is
 * why FR-15c keeps more than one.
 */
public class ProfileFormatException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ProfileFormatException(String message) {
        super(message);
    }

    public ProfileFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
