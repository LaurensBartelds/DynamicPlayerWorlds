package nl.gzmn.playerworlds.backend.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;
import nl.gzmn.playerworlds.backend.control.WorldHandoff;
import nl.gzmn.playerworlds.backend.world.WorldFolders;
import nl.gzmn.playerworlds.backend.world.WorldRegistry;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
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
 *
 * <h2>READY as well as ARCHIVED</h2>
 *
 * <p>FR-37 takes a READY world too, and that case is the only one that has live
 * folders to deal with. It exists for a world that can never <em>reach</em>
 * ARCHIVED — object storage unreachable since it was created, so FR-35 has
 * nothing to pack and no retry changes that — which would otherwise hold one of
 * its owner's FR-30 slots for ever. The world is unloaded first, and discarded
 * rather than released: committing a world to object storage immediately before
 * deleting it from object storage is work done to be undone, and the commit is
 * the thing that is broken anyway.
 */
public final class WorldEraser {

    private static final Logger log = LoggerFactory.getLogger(WorldEraser.class);

    private final PlayerWorldRepository worlds;
    private final ArchiveRepository archives;
    private final ArchiveStorage archiveStorage;
    private final @Nullable ObjectStore objectStore;
    private final @Nullable WorldRegistry registry;
    private final @Nullable WorldHandoff handoff;
    private final @Nullable WorldFolders folders;
    private final @Nullable Path scratchRoot;
    private final @Nullable String primaryLevelName;
    private final Supplier<NetworkPolicy> policy;

    /**
     * @param objectStore where live snapshots are, or {@code null} on a node with
     *     no object storage, where there are none to delete
     */
    public WorldEraser(
            PlayerWorldRepository worlds,
            ArchiveRepository archives,
            ArchiveStorage archiveStorage,
            @Nullable ObjectStore objectStore) {
        this(worlds, archives, archiveStorage, objectStore, null, null, null, null, null, NetworkPolicy::defaults);
    }

    /**
     * @param registry {@code null} on a node with no lifecycle service; a READY world is then
     *     never loaded here and needs no handoff
     * @param handoff how a loaded world is given up before its folders are deleted
     * @param folders, scratchRoot, primaryLevelName where the live dimension folders are; all
     *     three {@code null} together on a node that hosts no worlds, where a READY deletion has
     *     no local folders to remove
     */
    public WorldEraser(
            PlayerWorldRepository worlds,
            ArchiveRepository archives,
            ArchiveStorage archiveStorage,
            @Nullable ObjectStore objectStore,
            @Nullable WorldRegistry registry,
            @Nullable WorldHandoff handoff,
            @Nullable WorldFolders folders,
            @Nullable Path scratchRoot,
            @Nullable String primaryLevelName,
            Supplier<NetworkPolicy> policy) {
        this.worlds = Objects.requireNonNull(worlds, "worlds");
        this.archives = Objects.requireNonNull(archives, "archives");
        this.archiveStorage = Objects.requireNonNull(archiveStorage, "archiveStorage");
        this.objectStore = objectStore;
        this.registry = registry;
        this.handoff = handoff;
        this.folders = folders;
        this.scratchRoot = scratchRoot;
        this.primaryLevelName = primaryLevelName;
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /** What the deletion did, for the control-plane result CP-6 makes visible. */
    public sealed interface Outcome {

        /** The world and everything it owned are gone. */
        record Deleted(int archiveObjects) implements Outcome {}

        /** No such world. Idempotent success (CP-5): a retry of a deletion that already ran. */
        record NotFound() implements Outcome {}

        /** FR-37 deletes ARCHIVED and READY worlds; something took it out of both first. */
        record WrongState(WorldState state) implements Outcome {}

        /** Nothing was deleted, because not everything could be. */
        record Failed(String detail) implements Outcome {}
    }

    /**
     * Destroys a world, its archives and its live snapshot objects (FR-37).
     *
     * <p>Runs off the main thread; every call here is database, object storage or a filesystem
     * walk.
     */
    public Outcome erase(WorldId worldId) {
        return erase(worldId, null);
    }

    /**
     * {@link #erase(WorldId)}, refusing anything that is no longer in the state the owner
     * confirmed against (FR-37).
     *
     * @param expectedState the state the deletion was confirmed against, or null to apply only
     *     FR-37's own rule
     */
    public Outcome erase(WorldId worldId, @Nullable WorldState expectedState) {
        Objects.requireNonNull(worldId, "worldId");

        final PlayerWorld world;
        final List<WorldArchive> owned;
        try {
            var found = worlds.findById(worldId);
            if (found.isEmpty()) {
                return new Outcome.NotFound();
            }
            world = found.get();
            if (world.state() != WorldState.ARCHIVED && world.state() != WorldState.READY) {
                // ARCHIVING and RESTORING are refused rather than waited on: both are transient,
                // FR-40 resolves them, and the delete can be retried afterwards.
                return new Outcome.WrongState(world.state());
            }
            if (expectedState != null && world.state() != expectedState) {
                // Re-checked here rather than trusted from the proxy: the command may have sat in
                // the queue while a restore took the world out of ARCHIVED, and CP-4's generation
                // check does not see a state change that left the generation alone. FR-37's two
                // states carry different promises — one destroys a world with archives behind it,
                // the other one with none — so a confirmation given for one is not consent for
                // the other.
                log.warn(
                        "refusing to delete world {}: confirmed against {} but it is now {} (FR-37)",
                        worldId,
                        expectedState,
                        world.state());
                return new Outcome.WrongState(world.state());
            }
            owned = archives.findAllByWorld(worldId);
        } catch (SQLException e) {
            return new Outcome.Failed("could not read world " + worldId + ": " + e.getMessage());
        }

        // A READY world may be loaded and ticking on this node. Deleting the folders under it
        // would leave three Bukkit worlds reading files that no longer exist, which is the
        // failure FR-35's own handoff exists to prevent.
        if (world.state() == WorldState.READY) {
            Outcome released = giveUpBeforeDeleting(worldId);
            if (released != null) {
                return released;
            }
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
            String prefix = "worlds/" + worldId.value() + "/";
            try {
                objectStore.deletePrefix(prefix);
            } catch (RuntimeException e) {
                // A world that never committed and never archived has nothing under this prefix
                // to strand, and refusing here would defeat the whole point of FR-37 taking a
                // READY world: the case it exists for is object storage being unreachable, so a
                // rule that needs object storage to work would leave the owner just as stuck.
                // Anything else keeps CONTRIBUTING rule 8 and is retried once storage is back.
                if (!hasNothingInObjectStorage(world, owned)) {
                    log.error("could not delete the snapshot objects of world {}", worldId, e);
                    return new Outcome.Failed("could not delete snapshot objects: " + e.getMessage());
                }
                log.warn(
                        "could not reach object storage to sweep {} while deleting world {}; it never committed"
                                + " a snapshot and has no archives, so there should be nothing there. Check the"
                                + " prefix by hand if a commit had partly uploaded when storage failed.",
                        prefix,
                        worldId,
                        e);
            }
        }

        // Local folders last of the three, and before the row, for the same reason the objects
        // are: while the row exists, anything left behind is still attributable to a world.
        deleteLiveFolders(worldId);

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

    /**
     * Whether this world can be certain it left nothing in the bucket.
     *
     * <p>Never a successful commit ({@code manifest_key} is still null — it is set in the same
     * transaction that finishes one) and never an archive. A commit that uploaded some data
     * objects and then failed before the manifest is the gap this cannot see; those objects are
     * content-addressed and unreferenced, which is what MN-2b collects, and the log line says
     * where to look.
     */
    private static boolean hasNothingInObjectStorage(PlayerWorld world, List<WorldArchive> archives) {
        return world.manifestKey() == null && archives.isEmpty();
    }

    /**
     * Unloads a READY world this node is holding, so its folders can be deleted.
     *
     * @return {@code null} when there is nothing loaded here or it was given up, or the outcome
     *     to report when it could not be
     */
    private @Nullable Outcome giveUpBeforeDeleting(WorldId worldId) {
        if (registry == null || registry.find(worldId).isEmpty()) {
            return null;
        }
        if (handoff == null) {
            return new Outcome.Failed("world " + worldId + " is loaded here but there is no handoff to release it");
        }
        final WorldHandoff.Outcome outcome;
        try {
            outcome = handoff.discard(worldId, 0, "This world is being deleted")
                    .get(policy.get().commitTimeout().plusSeconds(10).toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome.Failed("interrupted while unloading world " + worldId + " for deletion");
        } catch (Exception e) {
            return new Outcome.Failed("could not unload world " + worldId + " for deletion: " + e.getMessage());
        }
        return switch (outcome) {
            // Released or gone: nothing of this world is ticking here any more.
            case WorldHandoff.Outcome.Released ignored -> null;
            case WorldHandoff.Outcome.NotHeld ignored -> null;
            case WorldHandoff.Outcome.Blocked blocked ->
                new Outcome.Failed("cannot delete world " + worldId + ": dimension " + blocked.dimension()
                        + " would not unload (" + String.join(", ", blocked.blockers())
                        + "). Nothing has been deleted.");
            // discard() never commits, so this cannot arise; reported rather than ignored.
            case WorldHandoff.Outcome.CommitFailed failed ->
                new Outcome.Failed("cannot delete world " + worldId + ": " + failed.detail());
        };
    }

    /**
     * Removes this node's copy of the world's dimension folders.
     *
     * <p>Only this node's. A READY world that is not leased anywhere left its folders on
     * whichever node last held it, and nothing records which that was — but MN-13 quarantines
     * any scratch directory not covered by a live lease on the next startup of that node, and
     * MN-13a prunes the quarantine, so a copy elsewhere is bounded debris rather than a leak.
     *
     * <p>Best effort, and deliberately not a failure: the objects and the row are the parts that
     * cost money and hold the owner's cap slot. A folder that could not be removed is logged and
     * swept by MN-13.
     */
    private void deleteLiveFolders(WorldId worldId) {
        if (folders == null || scratchRoot == null || primaryLevelName == null) {
            return;
        }
        for (Path folder : folders.onDiskFolders(scratchRoot, primaryLevelName, worldId)) {
            if (!Files.isDirectory(folder)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(folder)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        log.warn("could not delete {} of world {} (MN-13 will sweep it)", path, worldId, e);
                    }
                });
            } catch (IOException e) {
                log.warn("could not walk {} of world {} for deletion (MN-13 will sweep it)", folder, worldId, e);
            }
        }
    }
}
