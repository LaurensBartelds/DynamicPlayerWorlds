package nl.gzmn.playerworlds.core.storage;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import nl.gzmn.playerworlds.core.model.WorldId;

/**
 * Immutable snapshot manifest describing all files belonging to a player world generation and sequence (MN-2, MN-3).
 *
 * <p>Manifests are stored write-once in object storage at {@link #manifestKey()}
 * ({@code worlds/<world_id>/manifest/<generation>-<sequence>.json}). Entries are maintained in deterministic
 * lexicographical path order.
 *
 * @param worldId world identity
 * @param generation epoch generation counter
 * @param sequence monotonic sequence counter within the generation
 * @param dataVersion Minecraft data version (from {@code level.dat})
 * @param mcVersion Minecraft release version string (e.g. {@code 26.2})
 * @param createdAt creation timestamp of this snapshot
 * @param entries map of relative file path to entry metadata, sorted by path
 */
public record Manifest(
        WorldId worldId,
        long generation,
        int sequence,
        int dataVersion,
        String mcVersion,
        Instant createdAt,
        Map<String, ManifestEntry> entries) {

    public Manifest {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(mcVersion, "mcVersion");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(entries, "entries");
        entries = Collections.unmodifiableMap(new TreeMap<>(entries));
    }

    /**
     * Relative S3 key where this manifest is stored.
     */
    public String manifestKey() {
        return "worlds/" + worldId.value() + "/manifest/" + generation + "-" + sequence + ".json";
    }
}
