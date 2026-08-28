package nl.gzmn.playerworlds.core.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MN-4's completion marker: what tells a warm cache from crash debris.
 *
 * <h2>What MN-13 was reaching for</h2>
 *
 * <p>MN-13 says quarantine every scratch directory "not covered by a lease this
 * node currently holds". That is a proxy for "may have diverged from object
 * storage", and it stops being accurate the moment a clean shutdown releases its
 * leases — which is every planned restart. Taken literally it quarantines the
 * node's entire warm working set on every restart, while {@code last_node} still
 * names that node, so MN-15a's placement keeps routing joins to the node whose
 * warm copy it just destroyed.
 *
 * <p>MN-4 already specifies the missing piece: <em>"a clean unload writes a
 * completion marker, and a world whose marker is absent is fully rehashed before
 * use."</em> D18 extends it by one field — the marker names the
 * {@code manifest_key} it was written against — and that is enough to decide:
 *
 * <ul>
 *   <li>marker present and naming the world's current manifest → <b>warm cache</b>,
 *       left alone (MN-5);
 *   <li>marker absent, or naming a different manifest → <b>crash debris</b>,
 *       quarantined (MN-13).
 * </ul>
 *
 * <h2>Written on unload, deleted on load</h2>
 *
 * <p>The marker means "nothing has touched these files since the commit named",
 * so a load — which is exactly something touching them — removes it. Without that
 * deletion a world that was cleanly unloaded, loaded again and then crashed would
 * still carry its old marker, and the marker would still match {@code
 * manifest_key} because the crash committed nothing. It would read as a warm
 * cache while being the debris this exists to catch.
 *
 * <h2>Where it lives, and why not in the world folder</h2>
 *
 * <p>Under {@code <scratch>/.gzmn-clean/}, outside every dimension folder.
 * Inside one it would be picked up by {@link DirtyScanner} and uploaded as part
 * of the world (a node-local file has no business in a manifest, which is what
 * {@code storage.exclude-globs} exists to prevent — and that list is
 * operator-editable, so relying on it would be relying on configuration), and
 * {@link WorldDownloader#materialize} would delete it again as a file the
 * manifest does not list.
 */
public final class CleanUnloadMarker {

    private static final Logger log = LoggerFactory.getLogger(CleanUnloadMarker.class);

    /** Directory under the scratch root that holds one marker per world. */
    public static final String DIRECTORY = ".gzmn-clean";

    /**
     * Stands in for the manifest key on a node with no object storage, where
     * there is no manifest to name but a clean unload still happened.
     *
     * <p>Written but never matched: {@link #isWarmCache} refuses a world with no
     * current manifest outright, and a node with no object storage does not sweep
     * at all — quarantining there would move the only copy of a world to a
     * directory nothing restores from. The value exists so the file on disk says
     * what happened rather than being empty.
     */
    public static final String NO_MANIFEST = "none";

    private CleanUnloadMarker() {}

    /** Where this world's marker lives. */
    public static Path pathFor(Path scratchRoot, WorldId worldId) {
        Objects.requireNonNull(scratchRoot, "scratchRoot");
        Objects.requireNonNull(worldId, "worldId");
        return scratchRoot.resolve(DIRECTORY).resolve(worldId.folder());
    }

    /**
     * Records that this world was cleanly unloaded at {@code manifestKey}.
     *
     * <p>Call after the final snapshot commit has landed, never before: a marker
     * written first would name a manifest the commit may then fail to produce.
     *
     * @param manifestKey the manifest the unload committed, or {@code null} on a
     *     node with no object storage
     */
    public static void write(Path scratchRoot, WorldId worldId, @Nullable String manifestKey) {
        Path marker = pathFor(scratchRoot, worldId);
        String contents = manifestKey == null || manifestKey.isBlank() ? NO_MANIFEST : manifestKey.strip();
        try {
            Path parent = marker.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(marker, contents, StandardCharsets.UTF_8);
            log.debug("wrote clean-unload marker for world {} at manifest {}", worldId, contents);
        } catch (IOException e) {
            // Not fatal, and deliberately not retried: the cost of a missing
            // marker is one cold load after the next restart, which is the safe
            // direction. Failing the unload over it would be the wrong trade.
            log.warn("could not write the clean-unload marker for world {}; it will be treated as debris", worldId, e);
        }
    }

    /** The manifest key this world was last cleanly unloaded at, if any. */
    public static Optional<String> read(Path scratchRoot, WorldId worldId) {
        Path marker = pathFor(scratchRoot, worldId);
        if (!Files.isRegularFile(marker)) {
            return Optional.empty();
        }
        try {
            String contents = Files.readString(marker, StandardCharsets.UTF_8).strip();
            return contents.isEmpty() ? Optional.empty() : Optional.of(contents);
        } catch (IOException e) {
            // Unreadable is treated as absent, which quarantines. Guessing the
            // other way would keep a directory nothing can vouch for.
            log.warn("could not read the clean-unload marker for world {}; treating it as absent", worldId, e);
            return Optional.empty();
        }
    }

    /**
     * Invalidates the marker, because the world is about to be written to.
     *
     * <p>Call on load, before the world becomes reachable.
     */
    public static void clear(Path scratchRoot, WorldId worldId) {
        Path marker = pathFor(scratchRoot, worldId);
        try {
            if (Files.deleteIfExists(marker)) {
                log.debug("cleared the clean-unload marker for world {}; it is live again", worldId);
            }
        } catch (IOException e) {
            // Left in place, this marker would later vouch for a directory that
            // has since been played in. Say so loudly rather than at debug.
            log.error(
                    "could not clear the clean-unload marker for world {}; a crash from here would leave a "
                            + "directory that wrongly reads as a warm cache (MN-4)",
                    worldId,
                    e);
        }
    }

    /**
     * Whether this world's scratch copy may be trusted as a warm cache (MN-5).
     *
     * @param currentManifestKey the world's {@code manifest_key} now, or
     *     {@code null} when it has none
     */
    public static boolean isWarmCache(Path scratchRoot, WorldId worldId, @Nullable String currentManifestKey) {
        if (currentManifestKey == null || currentManifestKey.isBlank()) {
            // Nothing durable to be warm relative to. Either the world row is gone
            // (hard-deleted, so this is leftover) or it has never committed a
            // snapshot, and in both cases the scratch copy is not a cache of
            // anything — it is the only copy, or it is debris. Answering yes here
            // would keep a directory on the strength of a marker that says
            // "unloaded cleanly at nothing".
            return false;
        }
        Optional<String> marker = read(scratchRoot, worldId);
        return marker.isPresent() && marker.get().equals(currentManifestKey.strip());
    }
}
