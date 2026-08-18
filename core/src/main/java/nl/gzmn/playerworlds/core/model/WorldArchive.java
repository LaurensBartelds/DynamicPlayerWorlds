package nl.gzmn.playerworlds.core.model;

import java.time.Instant;
import java.util.Objects;

/**
 * One {@code player_world_archive} row.
 *
 * <p>Represents a cold-archived backup of a player world stored in object storage or the local archive.
 *
 * @param worldId the world this archive belongs to
 * @param objectKey object storage path/key of the tarball
 * @param sizeBytes compressed size in bytes
 * @param checksum SHA-256 hex digest of the archive
 * @param dataVersion chunk DataVersion the archive was packed at (MN-29)
 * @param archivedAt database time when the archive was recorded
 * @param restoreCount how many times this archive has been restored
 */
public record WorldArchive(
        WorldId worldId,
        String objectKey,
        long sizeBytes,
        String checksum,
        int dataVersion,
        Instant archivedAt,
        int restoreCount) {

    public WorldArchive {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(objectKey, "objectKey");
        Objects.requireNonNull(checksum, "checksum");
        Objects.requireNonNull(archivedAt, "archivedAt");

        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must not be negative: " + sizeBytes);
        }
        if (restoreCount < 0) {
            throw new IllegalArgumentException("restoreCount must not be negative: " + restoreCount);
        }
    }
}
