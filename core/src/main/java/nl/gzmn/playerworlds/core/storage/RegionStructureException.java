package nl.gzmn.playerworlds.core.storage;

/**
 * An Anvil region file failed MN-5c structural validation.
 *
 * <p>The snapshot must abort rather than upload the file: a torn or corrupt
 * region that reaches object storage cannot be detected later by hashing alone.
 */
public final class RegionStructureException extends StorageException {

    public RegionStructureException(String message) {
        super(message);
    }
}
