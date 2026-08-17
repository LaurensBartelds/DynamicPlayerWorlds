package nl.gzmn.playerworlds.core.storage;

/**
 * Snapshot copy, region validation or content hashing failed.
 *
 * <p>Unchecked: callers on the sync path either abort the snapshot (cheap) or
 * surface the failure through the existing commit-error channel. Forcing
 * {@code throws} on every boundary would only encourage swallowing it.
 */
public class StorageException extends RuntimeException {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
