package nl.gzmn.playerworlds.core.menu;

/**
 * Thrown when encoding or decoding a menu protocol message fails due to corrupt,
 * truncated, or invalid payload data.
 */
public class MenuCodecException extends IllegalArgumentException {

    public MenuCodecException(String message) {
        super(message);
    }

    public MenuCodecException(String message, Throwable cause) {
        super(message, cause);
    }
}
