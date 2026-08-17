package nl.gzmn.playerworlds.backend.profile;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Supplier;
import nl.gzmn.playerworlds.backend.platform.DimensionKind;
import nl.gzmn.playerworlds.backend.platform.Platform;
import nl.gzmn.playerworlds.backend.platform.WorldLifecycle;
import nl.gzmn.playerworlds.backend.platform.WorldRuntime;
import nl.gzmn.playerworlds.backend.storage.QuiesceWatchdog;
import nl.gzmn.playerworlds.backend.world.WorldFolders;
import nl.gzmn.playerworlds.core.concurrent.MainThread;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository;
import nl.gzmn.playerworlds.core.db.ProfileRepository;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.profile.CommitQueue;
import nl.gzmn.playerworlds.core.profile.ProfileCodec;
import nl.gzmn.playerworlds.core.profile.ProfileEnvelope;
import nl.gzmn.playerworlds.core.storage.DirtyScanner;
import nl.gzmn.playerworlds.core.storage.Manifest;
import nl.gzmn.playerworlds.core.storage.ManifestEntry;
import nl.gzmn.playerworlds.core.storage.SnapshotEngine;
import nl.gzmn.playerworlds.core.storage.StorageException;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates point-in-time quiesced snapshot commits unifying world data and player profiles
 * (FR-15, MN-3a, MN-5a, MN-5c, MN-6a).
 *
 * <p>FR-15's rule is that profiles are persisted <em>only</em> as part of a world
 * snapshot commit — never on a timer of their own — because profiles and world
 * data live in different storage systems and any skew between their durability
 * points is an item duplication bug in one direction and an item destruction bug
 * in the other (FR-15a). Committing both through one transaction removes the window.
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
    private final @Nullable PlayerWorldRepository playerWorlds;
    private final ProfileService profileService;
    private final WorldFolders folders;
    private final WorldLifecycle lifecycle;
    private final @Nullable WorldRuntime runtime;
    private final PluginExecutors executors;
    private final @Nullable SnapshotEngine snapshotEngine;
    private final @Nullable Supplier<NetworkPolicy> policySupplier;
    private final Path scratchRoot;
    private final @Nullable String nodeId;
    private final int dataVersion;
    private final String mcVersion;
    private final CommitQueue queue;

    private final Map<WorldId, Manifest> cachedManifests = new ConcurrentHashMap<>();
    private final Map<WorldId, Map<UUID, byte[]>> pendingDepartures = new ConcurrentHashMap<>();

    public WorldCommitService(
            ProfileRepository profiles,
            @Nullable PlayerWorldRepository playerWorlds,
            ProfileService profileService,
            WorldFolders folders,
            WorldLifecycle lifecycle,
            @Nullable WorldRuntime runtime,
            PluginExecutors executors,
            @Nullable SnapshotEngine snapshotEngine,
            @Nullable Supplier<NetworkPolicy> policySupplier,
            Path scratchRoot,
            @Nullable String nodeId,
            int dataVersion,
            String mcVersion) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.playerWorlds = playerWorlds;
        this.profileService = Objects.requireNonNull(profileService, "profileService");
        this.folders = Objects.requireNonNull(folders, "folders");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.runtime = runtime;
        this.executors = Objects.requireNonNull(executors, "executors");
        this.snapshotEngine = snapshotEngine;
        this.policySupplier = policySupplier;
        this.scratchRoot = Objects.requireNonNull(scratchRoot, "scratchRoot");
        this.nodeId = nodeId;
        this.dataVersion = dataVersion;
        this.mcVersion = Objects.requireNonNull(mcVersion, "mcVersion");
        this.queue = new CommitQueue(this::runCommit);
    }

    public WorldCommitService(
            ProfileRepository profiles,
            PlayerWorldRepository playerWorlds,
            ProfileService profileService,
            WorldFolders folders,
            Platform platform,
            PluginExecutors executors,
            @Nullable SnapshotEngine snapshotEngine,
            Supplier<NetworkPolicy> policySupplier,
            Path scratchRoot,
            @Nullable String nodeId) {
        this(
                profiles,
                playerWorlds,
                profileService,
                folders,
                platform.worldLifecycle(),
                platform.worldRuntime(),
                executors,
                snapshotEngine,
                policySupplier,
                scratchRoot,
                nodeId,
                platform.identity().dataVersion(),
                platform.identity().minecraftVersion());
    }

    /** Backward-compatible constructor for milestone 4 profile-only commit without object storage. */
    public WorldCommitService(
            ProfileRepository profiles,
            ProfileService profileService,
            WorldFolders folders,
            WorldLifecycle lifecycle,
            PluginExecutors executors) {
        this(
                profiles,
                null,
                profileService,
                folders,
                lifecycle,
                null,
                executors,
                null,
                null,
                Path.of("."),
                null,
                Platform.BUILD_DATA_VERSION,
                "26.2");
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

    /** Drops a world's commit queue, cached manifest, and pending departures once it has unloaded. */
    public void forget(WorldId worldId) {
        queue.forget(worldId);
        cachedManifests.remove(worldId);
        pendingDepartures.remove(worldId);
    }

    /** Whether a commit is running, for the unload path and for tests. */
    public boolean isCommitting(WorldId worldId) {
        return queue.isCommitting(worldId);
    }

    /** Retrieves the last known in-memory manifest for a world if cached. */
    public Optional<Manifest> cachedManifest(WorldId worldId) {
        return Optional.ofNullable(cachedManifests.get(worldId));
    }

    /** Explicitly seeds or updates the local cached manifest reference for a world. */
    public void cacheManifest(WorldId worldId, Manifest manifest) {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(manifest, "manifest");
        cachedManifests.put(worldId, manifest);
    }

    /**
     * One commit: executes the quiescence pipeline across main thread, IO executor,
     * and DB executor.
     */
    private CompletableFuture<Void> runCommit(WorldId worldId) {
        if (snapshotEngine == null || playerWorlds == null) {
            // Fallback for setups without object storage / repository integration
            CompletableFuture<Map<UUID, byte[]>> captured = new CompletableFuture<>();
            executors.main().execute(() -> {
                try {
                    Map<UUID, byte[]> payloads = captureWorld(worldId);
                    Map<UUID, byte[]> departures = pendingDepartures.remove(worldId);
                    if (departures != null) {
                        payloads.putAll(departures);
                    }
                    captured.complete(payloads);
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

        // Milestone 6 multi-stage quiesce & atomic commit pipeline
        CompletableFuture<Phase1Result> phase1Future = new CompletableFuture<>();
        executors.main().execute(() -> {
            try {
                phase1Future.complete(phase1MainThread(worldId));
            } catch (RuntimeException e) {
                phase1Future.completeExceptionally(e);
            }
        });

        return phase1Future
                .thenApplyAsync(this::phase2IoThread, executors.io())
                .thenApplyAsync(this::phase3DbThread, executors.db())
                .thenAccept(this::phase4Completion);
    }

    private record Phase1Result(
            WorldId worldId,
            Map<UUID, byte[]> payloads,
            List<World> quiescedWorlds,
            List<ScheduledFuture<?>> watchdogs) {}

    private Phase1Result phase1MainThread(WorldId worldId) {
        MainThread.assertOn();
        List<World> quiescedWorlds = new ArrayList<>();
        List<ScheduledFuture<?>> watchdogs = new ArrayList<>();
        Map<UUID, byte[]> payloads = new LinkedHashMap<>();

        NetworkPolicy policy = policySupplier != null ? policySupplier.get() : NetworkPolicy.defaults();
        Duration timeout = policy.snapshotQuiesceTimeout();

        for (DimensionKind dimension : DimensionKind.values()) {
            String bukkitName = folders.bukkitWorldName(worldId, dimension);
            World world = lifecycle.loaded(bukkitName);
            if (world == null) {
                continue;
            }
            if (runtime != null) {
                runtime.setAutoSave(world, false);
                runtime.save(world);
                if (executors.sched() != null) {
                    ScheduledFuture<?> wf = QuiesceWatchdog.arm(executors.sched(), runtime, world, timeout);
                    watchdogs.add(wf);
                }
            }
            quiescedWorlds.add(world);
            for (Player player : world.getPlayers()) {
                ProfileEnvelope envelope = profileService.capture(player, bukkitName);
                payloads.put(player.getUniqueId(), ProfileCodec.encode(envelope));
            }
        }

        Map<UUID, byte[]> departures = pendingDepartures.remove(worldId);
        if (departures != null) {
            payloads.putAll(departures);
        }

        return new Phase1Result(worldId, payloads, quiescedWorlds, watchdogs);
    }

    private record Phase2Result(
            WorldId worldId,
            Map<UUID, byte[]> profiles,
            @Nullable Manifest newManifest,
            @Nullable Manifest baselineManifest) {}

    private Phase2Result phase2IoThread(Phase1Result phase1) {
        WorldId worldId = phase1.worldId();
        try {
            NetworkPolicy policy = policySupplier != null ? policySupplier.get() : NetworkPolicy.defaults();

            Manifest baseline = cachedManifests.get(worldId);
            Map<String, ManifestEntry> baselineEntries = baseline != null ? baseline.entries() : Map.of();

            List<Path> dirty = DirtyScanner.scanDirty(scratchRoot, worldId, baselineEntries, policy.excludeGlobs());

            long generation = GENERATION_BEFORE_LEASES;
            int sequence = baseline != null ? baseline.sequence() + 1 : 1;

            SnapshotEngine.SnapshotResult snapshotResult = null;
            if (!dirty.isEmpty() || baseline == null) {
                Duration quiet = policy.snapshotQuiet();
                if (!quiet.isZero() && !quiet.isNegative()) {
                    try {
                        Thread.sleep(quiet.toMillis());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new CompletionException(e);
                    }
                }
                if (snapshotEngine != null) {
                    snapshotResult = snapshotEngine.executeSnapshot(
                            scratchRoot,
                            worldId,
                            generation,
                            sequence,
                            dataVersion,
                            mcVersion,
                            baselineEntries,
                            dirty,
                            policy.verifyRegionStructure());
                }
            }

            Manifest newManifest = snapshotResult != null ? snapshotResult.manifest() : null;
            return new Phase2Result(worldId, phase1.payloads(), newManifest, baseline);
        } finally {
            restoreAutoSave(phase1.quiescedWorlds(), phase1.watchdogs());
        }
    }

    private @Nullable Manifest phase3DbThread(Phase2Result phase2) {
        WorldId worldId = phase2.worldId();
        Manifest manifestToCommit = phase2.newManifest() != null ? phase2.newManifest() : phase2.baselineManifest();
        if (manifestToCommit == null || playerWorlds == null) {
            return null;
        }

        ProfileRepository.Snapshot snapshot =
                new ProfileRepository.Snapshot(manifestToCommit.generation(), manifestToCommit.sequence());

        try {
            boolean committed = playerWorlds.commitSnapshot(
                    worldId,
                    manifestToCommit.generation(),
                    nodeId,
                    manifestToCommit.manifestKey(),
                    manifestToCommit.dataVersion(),
                    manifestToCommit.mcVersion(),
                    snapshot,
                    ProfileCodec.FORMAT_VERSION,
                    phase2.profiles(),
                    profiles);

            if (!committed) {
                throw new StorageException(
                        "Failed to commit snapshot for world " + worldId + ": fenced or row missing");
            }
            return manifestToCommit;
        } catch (SQLException e) {
            throw new CompletionException(e);
        }
    }

    private void phase4Completion(@Nullable Manifest committedManifest) {
        if (committedManifest != null) {
            cachedManifests.put(committedManifest.worldId(), committedManifest);
            log.debug(
                    "World commit completed for world {}: manifestKey={}",
                    committedManifest.worldId(),
                    committedManifest.manifestKey());
        }
    }

    private void restoreAutoSave(List<World> worlds, List<ScheduledFuture<?>> watchdogs) {
        for (ScheduledFuture<?> wf : watchdogs) {
            wf.cancel(false);
        }
        if (runtime != null && !worlds.isEmpty()) {
            executors.main().execute(() -> {
                for (World world : worlds) {
                    try {
                        runtime.setAutoSave(world, true);
                    } catch (Exception e) {
                        log.warn("Failed to restore auto-save for world {}", world.getName(), e);
                    }
                }
            });
        }
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

        if (snapshotEngine == null || playerWorlds == null) {
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
                            log.error(
                                    "could not commit the departing profile of {} from world {}",
                                    uuid,
                                    worldId,
                                    failure);
                        }
                    });
        }

        pendingDepartures
                .computeIfAbsent(worldId, k -> new ConcurrentHashMap<>())
                .put(uuid, payload);
        return requestCommit(worldId);
    }
}
