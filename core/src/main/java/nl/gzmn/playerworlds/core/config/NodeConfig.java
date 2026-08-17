package nl.gzmn.playerworlds.core.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import nl.gzmn.playerworlds.core.db.DatabaseSettings;

/**
 * Node-local configuration for a {@code worlds} backend node.
 *
 * <p>Everything here is a fact about <em>this</em> process: who it is, where it
 * writes, which credentials it holds. Network-wide policy — caps, expiries,
 * retention — lives in {@link NetworkPolicy} and the {@code network_setting}
 * table, so two nodes cannot silently disagree about it (plan section 8.1).
 *
 * <p>Constructed by the platform entry point from its config file. This record
 * does not read files itself; {@code :core} has no YAML dependency and the
 * Paper/Velocity config APIs stay at the boundary.
 *
 * @param nodeId unique per node; also the Velocity server name (MN-17)
 * @param address host:port the proxy should route to
 * @param heartbeatInterval how often this node writes its heartbeat row
 *     ({@code node.heartbeat-seconds})
 * @param database connection settings
 * @param storage object-storage client settings
 * @param scratchPath live Anvil working copies ({@code storage.local-scratch-path};
 *     replaces the duplicate {@code worlds.storage-path} from specification
 *     section 7 — see ADR 0007)
 * @param cachePath content-addressed local object cache
 * @param quarantinePath crash-debris holding area (MN-13)
 * @param minFreeSpaceBytes refuse creation below this free space on the scratch
 *     volume (NFR-3)
 */
public record NodeConfig(
        String nodeId,
        String address,
        Duration heartbeatInterval,
        DatabaseSettings database,
        StorageClientSettings storage,
        Path scratchPath,
        Path cachePath,
        Path quarantinePath,
        long minFreeSpaceBytes) {

    /** Specification section 12.8 default for {@code node.heartbeat-seconds}. */
    public static final Duration DEFAULT_HEARTBEAT_INTERVAL = Duration.ofSeconds(30);

    /**
     * Default free-space floor. A fully explored default-border world is several
     * gigabytes per dimension (NFR-3); refusing creation under 20 GiB leaves room
     * for one more world plus a snapshot copy on filesystems without reflink.
     */
    public static final long DEFAULT_MIN_FREE_SPACE_BYTES = 20L * 1024 * 1024 * 1024;

    public NodeConfig {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(heartbeatInterval, "heartbeatInterval");
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(storage, "storage");
        Objects.requireNonNull(scratchPath, "scratchPath");
        Objects.requireNonNull(cachePath, "cachePath");
        Objects.requireNonNull(quarantinePath, "quarantinePath");

        if (nodeId.isBlank()) {
            throw new ConfigException("node.id must not be blank");
        }
        if (address.isBlank()) {
            throw new ConfigException("node.address must not be blank");
        }
        if (heartbeatInterval.isNegative() || heartbeatInterval.isZero()) {
            throw new ConfigException("node.heartbeat-seconds must be positive, was: " + heartbeatInterval);
        }
        if (minFreeSpaceBytes < 0) {
            throw new ConfigException("storage.min-free-space-bytes must not be negative, was: " + minFreeSpaceBytes);
        }
    }
}
