package nl.gzmn.playerworlds.backend.storage;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import nl.gzmn.playerworlds.core.db.ArchiveRepository;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.WorldArchive;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.model.WorldState;
import nl.gzmn.playerworlds.core.storage.ObjectStore;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FR-37's hard deletion, on a node, because a node is what can see the bucket.
 *
 * <h2>Why this is not done on the proxy</h2>
 *
 * <p>The confirmation the owner types promises to destroy the world <em>"and all
 * backup archives"</em>, and the proxy could only keep half of that: §13 gives it
 * no object-store client. Deleting the {@code player_world} row took the
 * {@code player_world_archive} rows with it through the schema's cascade, and the
 * archive objects and the world's live snapshot prefix were then orphaned
 * permanently — MN-2b's garbage collection walks per world, and the world no
 * longer exists for it to walk.
 *
 * <h2>Objects first, row last</h2>
 *
 * <p>CONTRIBUTING rule 8: a destructive path verifies before it destroys. The
 * archive rows are the only record of which objects belong to this world, so they
 * are read and the objects removed <em>before</em> the row that points at them.
 * A failure part-way leaves the world ARCHIVED with some objects gone, which is
 * visible, reported and retryable; the other order leaves a bucket full of
 * objects nothing can ever name again.
 */
public final class WorldEraser {

    private static final Logger log = LoggerFactory.getLogger(WorldEraser.class);

    private final PlayerWorldRepository worlds;
    private final ArchiveRepository archives;
    private final ArchiveStorage archiveStorage;
    private final @Nullable ObjectStore objectStore;

    /**
     * @param objectStore where live snapshots are, or {@code null} on a node with
     *     no object storage, where there are none to delete
     */
    public WorldEraser(
            PlayerWorldRepository worlds,
            ArchiveRepository archives,
            ArchiveStorage archiveStorage,
            @Nullable ObjectStore objectStore) {
        this.worlds = Objects.requireNonNull(worlds, "worlds");
        this.archives = Objects.requireNonNull(archives, "archives");
        this.archiveStorage = Objects.requireNonNull(archiveStorage, "archiveStorage");
        this.objectStore = objectStore;
    }

    /** What the deletion did, for the control-plane result CP-6 makes visible. */
    public sealed interface Outcome {

        /** The world and everything it owned are gone. */
        record Deleted(int archiveObjects) implements Outcome {}

        /** No such world. Idempotent success (CP-5): a retry of a deletion that already ran. */
        record NotFound() implements Outcome {}

        /** FR-37 deletes archived worlds only; something took it out of ARCHIVED first. */
        record WrongState(WorldState state) implements Outcome {}

        /** Nothing was deleted, because not everything could be. */
        record Failed(String detail) implements Outcome {}
    }

    /**
     * Destroys a world, its archives and its live snapshot objects (FR-37).
     *
     * <p>Runs off the main thread; every call here is database or object storage.
     */
    public Outcome erase(WorldId worldId) {
        Objects.requireNonNull(worldId, "worldId");

        final PlayerWorld world;
        final List<WorldArchive> owned;
        try {
            var found = worlds.findById(worldId);
            if (found.isEmpty()) {
                return new Outcome.NotFound();
            }
            world = found.get();
            if (world.state() != WorldState.ARCHIVED) {
                // Re-checked here rather than trusted from the proxy: the command
                // may have sat in the queue while a restore took the world back
                // out of ARCHIVED, and CP-4's generation check does not see a
                // state change that left the generation alone.
                return new Outcome.WrongState(world.state());
            }
            owned = archives.findAllByWorld(worldId);
        } catch (SQLException e) {
            return new Outcome.Failed("could not read world " + worldId + ": " + e.getMessage());
        }

        int deletedObjects = 0;
        for (WorldArchive archive : owned) {
            try {
                archiveStorage.deleteArchive(archive.objectKey());
                deletedObjects++;
            } catch (RuntimeException e) {
                // Stop rather than press on: the row is what names the remaining
                // objects, so deleting it now would strand them.
                log.error("could not delete archive {} of world {}", archive.objectKey(), worldId, e);
                return new Outcome.Failed("could not delete archive " + archive.objectKey() + ": " + e.getMessage());
            }
        }

        // The live snapshot prefix — manifests and content-addressed data objects.
        // MN-2b would have collected these per world; after the row is gone it has
        // no world to collect them for.
        if (objectStore != null) {
            try {
                objectStore.deletePrefix("worlds/" + worldId.value() + "/");
            } catch (RuntimeException e) {
                log.error("could not delete the snapshot objects of world {}", worldId, e);
                return new Outcome.Failed("could not delete snapshot objects: " + e.getMessage());
            }
        }

        try {
            if (!worlds.deleteHard(worldId)) {
                // Raced with something that removed the row. The objects are gone
                // either way, which is what was asked for.
                log.warn("world {} was already gone when its row was deleted", worldId);
                return new Outcome.Deleted(deletedObjects);
            }
        } catch (SQLException e) {
            return new Outcome.Failed("archives were deleted but the world row was not: " + e.getMessage());
        }

        log.info(
                "world {} ('{}') permanently deleted with {} archive object(s) and its snapshot prefix (FR-37)",
                worldId,
                world.name(),
                deletedObjects);
        return new Outcome.Deleted(deletedObjects);
    }
}
