package nl.gzmn.playerworlds.backend.profile;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import nl.gzmn.playerworlds.backend.platform.DimensionKind;
import nl.gzmn.playerworlds.backend.platform.WorldLifecycle;
import nl.gzmn.playerworlds.backend.world.WorldFolders;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.db.ProfileRepository;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.profile.CommitQueue;
import nl.gzmn.playerworlds.core.profile.ProfileCodec;
import nl.gzmn.playerworlds.core.profile.ProfileEnvelope;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The snapshot commit, as far as milestone 4 has one (FR-15, MN-6a).
 *
 * <p>FR-15's rule is that profiles are persisted <em>only</em> as part of a world
 * snapshot commit — never on a timer of their own — because profiles and world
 * data live in different storage systems and any skew between their durability
 * points is an item duplication bug in one direction and an item destruction bug
 * in the other (FR-15a). So this is the only thing in the plugin that writes a
 * profile, and everything that would like one written asks it for a commit.
 *
 * <p>What is missing is the other half: milestone 6 adds the region-file
 * snapshot, the upload and the manifest pointer, and they join the same
 * transaction. Building the commit here first is deliberate — specification
 * section 11.4 warns that a profile design built against an independent autosave
 * timer would validate a model milestone 6 then replaces.
 *
 * <p>Commits are single-flight per world through {@link CommitQueue}: a commit
 * in flight absorbs further triggers and schedules exactly one follow-up.
 */
public final class WorldCommitService {

    private static final Logger log = LoggerFactory.getLogger(WorldCommitService.class);

    /**
     * The lease generation profiles are written under.
     *
     * <p>Zero until milestone 7 makes leases real. It is a real column and a real
     * part of the key (FR-15b); what milestone 7 changes is where the number
     * comes from, not what it means.
     */
    private static final long GENERATION_BEFORE_LEASES = 0L;

    private final ProfileRepository profiles;
    private final ProfileService profileService;
    private final WorldFolders folders;
    private final WorldLifecycle lifecycle;
    private final PluginExecutors executors;
    private final CommitQueue queue;

    public WorldCommitService(
            ProfileRepository profiles,
            ProfileService profileService,
            WorldFolders folders,
            WorldLifecycle lifecycle,
            PluginExecutors executors) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.profileService = Objects.requireNonNull(profileService, "profileService");
        this.folders = Objects.requireNonNull(folders, "folders");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.executors = Objects.requireNonNull(executors, "executors");
        this.queue = new CommitQueue(this::runCommit);
    }

    /**
     * Asks for a commit of this world (FR-15's triggers).
     *
     * @return a future completing when a commit started after this call has
     *     finished, so a caller that needs its own state durable can wait
     */
    public CompletableFuture<Void> requestCommit(WorldId worldId) {
        return queue.request(worldId);
    }

    /** Drops a world's commit queue once it has unloaded. */
    public void forget(WorldId worldId) {
        queue.forget(worldId);
    }

    /** Whether a commit is running, for the unload path and for tests. */
    public boolean isCommitting(WorldId worldId) {
        return queue.isCommitting(worldId);
    }

    /**
     * One commit: capture every player in the world on the main thread, then
     * write them all in one transaction.
     *
     * <p>The capture is a single main-thread hop for the whole world rather than
     * one per player, which is what makes the set of profiles consistent with
     * each other — FR-16 requires the commit be atomic across all players, and
     * capturing them at different ticks would defeat that before the transaction
     * ever ran.
     */
    private CompletableFuture<Void> runCommit(WorldId worldId) {
        CompletableFuture<Map<UUID, byte[]>> captured = new CompletableFuture<>();
        executors.main().execute(() -> {
            try {
                captured.complete(captureWorld(worldId));
            } catch (RuntimeException e) {
                captured.completeExceptionally(e);
            }
        });

        return captured.thenApplyAsync(
                payloads -> {
                    try {
                        profiles.commit(worldId, GENERATION_BEFORE_LEASES, ProfileCodec.FORMAT_VERSION, payloads);
                    } catch (SQLException e) {
                        throw new CompletionException(e);
                    }
                    if (!payloads.isEmpty()) {
                        log.info("committed {} profile(s) for world {} (FR-15)", payloads.size(), worldId);
                    }
                    return (Void) null;
                },
                executors.db());
    }

    /** Encodes every player currently in any of the world's three dimensions. */
    private Map<UUID, byte[]> captureWorld(WorldId worldId) {
        Map<UUID, byte[]> payloads = new LinkedHashMap<>();
        for (DimensionKind dimension : DimensionKind.values()) {
            String bukkitName = folders.bukkitWorldName(worldId, dimension);
            World world = lifecycle.loaded(bukkitName);
            if (world == null) {
                continue;
            }
            for (Player player : world.getPlayers()) {
                ProfileEnvelope envelope = profileService.capture(player, bukkitName);
                payloads.put(player.getUniqueId(), ProfileCodec.encode(envelope));
            }
        }
        return payloads;
    }

    /**
     * Captures one player who is leaving, then commits.
     *
     * <p>Separate from {@link #requestCommit} because the leaver is already gone
     * from the world by the time a quit or world-change event completes, so the
     * sweep in {@link #captureWorld} would miss them. Their payload is taken
     * first, on the tick they leave, and folded into the next commit.
     */
    public CompletableFuture<Void> commitDeparture(WorldId worldId, Player player, String dimensionName) {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(player, "player");
        UUID uuid = player.getUniqueId();
        byte[] payload = ProfileCodec.encode(profileService.capture(player, dimensionName));

        return CompletableFuture.runAsync(
                        () -> {
                            try {
                                profiles.commit(
                                        worldId,
                                        GENERATION_BEFORE_LEASES,
                                        ProfileCodec.FORMAT_VERSION,
                                        Map.of(uuid, payload));
                            } catch (SQLException e) {
                                throw new CompletionException(e);
                            }
                        },
                        executors.db())
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        // FR-15's symmetry says a player's live state is lost with
                        // the world state it belongs to. This is the case where it
                        // is lost on its own, which is worth saying out loud.
                        log.error("could not commit the departing profile of {} from world {}", uuid, worldId, failure);
                    }
                });
    }
}
