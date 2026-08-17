package nl.gzmn.playerworlds.core.db;

/**
 * Thrown when the database schema is outside the range this build supports, so
 * the node must refuse to enable rather than run against a shape its statements
 * were not written for.
 *
 * <p>This is the database counterpart of the Minecraft version gate in ADR 0001,
 * and exists for the same reason: during a rolling deploy the node pool is
 * <em>not</em> interchangeable, and a node running older code against a newer
 * schema is the mis-sequenced restart that quietly corrupts something. Making the
 * mixed-version pool explicit turns that into a refusal at startup.
 */
public final class SchemaVersionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SchemaVersionException(String message) {
        super(message);
    }
}
