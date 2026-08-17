package nl.gzmn.playerworlds.core.obs;

/**
 * Round-trip check against object storage for the startup capability probe
 * (plan section 10.4).
 *
 * <p>Implemented by the storage engine when it exists. Until then the probe
 * records storage as unchecked rather than inventing a client here.
 */
@FunctionalInterface
public interface StorageHealthCheck {

    /**
     * Performs a real round trip (for example head-bucket or a tiny put/get).
     *
     * @throws Exception on any failure; the probe turns the message into the
     *     capability report line
     */
    void ping() throws Exception;
}
