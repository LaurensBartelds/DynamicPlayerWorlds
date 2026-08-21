package nl.gzmn.playerworlds.core.storage;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MN-2b's garbage collection: removes data objects no retained manifest names.
 *
 * <p>Data objects are content-addressed and immutable, so a writer never deletes
 * one — it cannot know whether another manifest still points at it. That leaves
 * two kinds of garbage, and MN-2b says orphans "are expected in normal
 * operation":
 *
 * <ul>
 *   <li>objects a fenced node uploaded for a snapshot whose manifest never
 *       committed, and
 *   <li>objects that were referenced only by manifests old enough to prune.
 * </ul>
 *
 * <h2>Why this could not run before</h2>
 *
 * <p>Two reasons, both now gone. Manifests were cumulative before R21, so nearly
 * every object was still referenced by a retained manifest and a pass would have
 * reclaimed almost nothing; and {@link ObjectStore} could not enumerate a prefix,
 * so "any object not referenced" was a set nothing could compute.
 *
 * <h2>The retained set is read, not assumed</h2>
 *
 * <p>The manifests to keep are chosen by their {@code (generation, sequence)} key
 * and the world's current {@code manifest_key} is <em>always</em> kept, however
 * old it sorts. A world restored from an archive commits under a fresh
 * generation, and a world that has been quiet for months still has a current
 * manifest that must survive its own collection.
 */
public final class SnapshotCollector {

    private static final Logger log = LoggerFactory.getLogger(SnapshotCollector.class);

    private final ObjectStore objectStore;

    public SnapshotCollector(ObjectStore objectStore) {
        this.objectStore = Objects.requireNonNull(objectStore, "objectStore");
    }

    /**
     * What one world's collection reclaimed.
     *
     * @param dataObjectsDeleted content objects no retained manifest referenced
     * @param manifestsDeleted manifest objects past the retention count
     */
    public record Collected(int dataObjectsDeleted, int manifestsDeleted) {

        static Collected nothing() {
            return new Collected(0, 0);
        }
    }

    /**
     * Collects one world (MN-2b).
     *
     * <p>Object storage only — the caller decides which worlds to visit and how
     * many per sweep.
     *
     * @param worldId the world to collect
     * @param currentManifestKey the world's {@code manifest_key}, kept whatever
     *     else is; {@code null} for a world that has never committed, in which
     *     case nothing is retained and every orphan goes
     * @param retain how many manifests to keep, {@code storage.manifest-retention-count}
     */
    public Collected collect(WorldId worldId, @Nullable String currentManifestKey, int retain) {
        Objects.requireNonNull(worldId, "worldId");
        if (retain < 1) {
            throw new IllegalArgumentException("retain must be at least 1, was: " + retain);
        }

        String manifestPrefix = "worlds/" + worldId.value() + "/manifest/";
        List<String> manifestKeys = objectStore.listKeys(manifestPrefix);
        if (manifestKeys.isEmpty()) {
            return Collected.nothing();
        }

        Set<String> keep = retainedManifests(manifestKeys, currentManifestKey, retain);

        // Read the keepers first. A failure here must not delete anything: the
        // referenced set would be short, and every object it was missing would
        // look like an orphan (CONTRIBUTING rule 8).
        Set<String> referenced = new HashSet<>();
        for (String key : keep) {
            Manifest manifest = readManifest(key);
            if (manifest == null) {
                log.warn("could not read retained manifest {}; skipping collection for world {}", key, worldId);
                return Collected.nothing();
            }
            for (ManifestEntry entry : manifest.entries().values()) {
                referenced.add(entry.sha256Hex());
            }
        }

        int dataDeleted = deleteUnreferencedData(worldId, referenced);
        int manifestsDeleted = 0;
        for (String key : manifestKeys) {
            if (keep.contains(key)) {
                continue;
            }
            try {
                objectStore.deleteObject(key);
                manifestsDeleted++;
            } catch (RuntimeException e) {
                log.warn("could not delete superseded manifest {}", key, e);
            }
        }

        if (dataDeleted > 0 || manifestsDeleted > 0) {
            log.info(
                    "collected world {}: {} data object(s) and {} superseded manifest(s) removed (MN-2b)",
                    worldId,
                    dataDeleted,
                    manifestsDeleted);
        }
        return new Collected(dataDeleted, manifestsDeleted);
    }

    /**
     * The newest {@code retain} manifests, plus the current one whatever its age.
     *
     * <p>Sorted by {@code (generation, sequence)} rather than by key, because the
     * keys are decimal and unpadded: {@code 10-1.json} sorts before {@code 9-1.json}
     * as a string, which would retain the wrong ones and delete the world.
     */
    private static Set<String> retainedManifests(
            List<String> manifestKeys, @Nullable String currentManifestKey, int retain) {
        List<String> byAge = new ArrayList<>(manifestKeys);
        byAge.sort(Comparator.comparing(SnapshotCollector::snapshotOrder).reversed());

        Set<String> keep = new HashSet<>(byAge.subList(0, Math.min(retain, byAge.size())));
        if (currentManifestKey != null && !currentManifestKey.isBlank()) {
            keep.add(currentManifestKey.strip());
        }
        return keep;
    }

    /**
     * A manifest key's {@code (generation, sequence)} as a sortable pair.
     *
     * <p>An unparseable name sorts oldest, which retains it only if nothing else
     * is older — safe, since the alternative is deleting a manifest nobody can
     * read the age of.
     */
    private static SnapshotOrder snapshotOrder(String key) {
        String name = key.substring(key.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf(".json");
        int dash = name.indexOf('-');
        if (dash <= 0 || dot <= dash) {
            return SnapshotOrder.UNREADABLE;
        }
        try {
            return new SnapshotOrder(
                    Long.parseLong(name.substring(0, dash)), Long.parseLong(name.substring(dash + 1, dot)));
        } catch (NumberFormatException e) {
            return SnapshotOrder.UNREADABLE;
        }
    }

    /** A manifest's place in a world's history, for choosing what to retain. */
    private record SnapshotOrder(long generation, long sequence) implements Comparable<SnapshotOrder> {

        static final SnapshotOrder UNREADABLE = new SnapshotOrder(Long.MIN_VALUE, Long.MIN_VALUE);

        @Override
        public int compareTo(SnapshotOrder other) {
            int byGeneration = Long.compare(generation, other.generation);
            return byGeneration != 0 ? byGeneration : Long.compare(sequence, other.sequence);
        }
    }

    private @Nullable Manifest readManifest(String key) {
        try {
            return ManifestCodec.decode(new String(objectStore.getBytes(key), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            log.warn("could not decode manifest {}", key, e);
            return null;
        }
    }

    private int deleteUnreferencedData(WorldId worldId, Set<String> referenced) {
        String dataPrefix = "worlds/" + worldId.value() + "/data/";
        int deleted = 0;
        for (String key : objectStore.listKeys(dataPrefix)) {
            String sha = key.substring(key.lastIndexOf('/') + 1);
            if (referenced.contains(sha)) {
                continue;
            }
            try {
                objectStore.deleteObject(key);
                deleted++;
            } catch (RuntimeException e) {
                log.warn("could not delete orphaned data object {}", key, e);
            }
        }
        return deleted;
    }
}
