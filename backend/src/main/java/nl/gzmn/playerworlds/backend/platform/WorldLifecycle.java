package nl.gzmn.playerworlds.backend.platform;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.bukkit.World;
import org.jspecify.annotations.Nullable;

/**
 * Creating, loading and unloading Bukkit worlds (FR-2, FR-4, FR-25a).
 *
 * <p>Separate from {@link WorldRuntime}, which is documented as operations on an
 * already-loaded world. Creation is its own version-sensitive surface:
 * {@code WorldCreator} has gained and lost methods across versions, the
 * asynchronous chunk API is a Paper extension that has been renamed, and the set
 * of things that can hold a world open — the reason {@code unloadWorld} returns a
 * boolean at all (FR-25a) — grows with every release.
 *
 * <p>Every method takes a Bukkit world <em>name</em> rather than a {@link World}.
 * A {@code World} reference does not survive an unload (FR-25b), so the seam
 * never asks a caller to hold one; the returned references are for immediate use
 * on the calling tick and nothing more.
 */
public interface WorldLifecycle {

    /**
     * What to create, or what to load if the folder is already on disk.
     *
     * @param bukkitWorldName from {@link WorldLayout#bukkitWorldName}
     * @param dimension which of the three (FR-2)
     * @param seed shared by all three dimensions, so a dimension materialised
     *     later is identical to one created up front (FR-2)
     * @param generateStructures normally true; false only for tests that want a
     *     cheap world
     */
    record CreationRequest(String bukkitWorldName, DimensionKind dimension, long seed, boolean generateStructures) {

        public CreationRequest {
            Objects.requireNonNull(bukkitWorldName, "bukkitWorldName");
            Objects.requireNonNull(dimension, "dimension");
            if (bukkitWorldName.isBlank()) {
                throw new IllegalArgumentException("bukkitWorldName must not be blank");
            }
        }

        public static CreationRequest of(String bukkitWorldName, DimensionKind dimension, long seed) {
            return new CreationRequest(bukkitWorldName, dimension, seed, true);
        }
    }

    /**
     * Creates the world, or loads it when its folder already exists.
     *
     * <p><b>Blocks the calling thread for as long as generation takes, and must be
     * called on the main thread.</b> That stall is FR-4's whole subject: it is why
     * only the overworld is created eagerly, why the other two wait for first
     * transit, and why {@code worlds.create-stall-budget-ms} is a release-gating
     * number rather than a curiosity.
     *
     * @return the world, or {@code null} when the server refused to create it
     */
    @Nullable
    World createOrLoad(CreationRequest request);

    /**
     * The loaded world with this name, or {@code null}.
     *
     * <p>Resolved through the server every time (FR-25b). Callers use the result
     * within the tick and never store it.
     */
    @Nullable
    World loaded(String bukkitWorldName);

    /**
     * Unloads one dimension, saving it first (FR-25a).
     *
     * <p>The boolean return is the requirement, not a convenience: {@code false}
     * means something still holds the world open, and FR-25a requires that be
     * logged, the remaining unloads for that world abandoned, and the whole world
     * retried later. Callers must not ignore it.
     *
     * @return false when the world is still loaded afterwards, or was never loaded
     */
    boolean unload(String bukkitWorldName, boolean save);

    /**
     * Pre-generates a square of chunks around spawn, through the asynchronous
     * chunk API (FR-4).
     *
     * <p>Never a synchronous {@code getChunkAt} loop: on a node ticking other
     * players' worlds, that is the stall FR-4 exists to bound, multiplied by the
     * number of chunks.
     *
     * @param chunkSide side length in chunks ({@code worlds.pregen-spawn-chunks});
     *     a value below 1 pre-generates nothing
     * @return completes when every chunk is loaded, or completes exceptionally
     */
    CompletableFuture<Void> pregenerateSpawn(String bukkitWorldName, int chunkSide);

    /**
     * Why this world would refuse to unload, in words an operator can act on
     * (FR-25a's "with the holding cause where determinable").
     *
     * <p>Empty when nothing determinable is holding it — which is not a promise
     * that the unload will succeed, only that the API cannot name the reason.
     */
    List<String> unloadBlockers(String bukkitWorldName);
}
