package nl.gzmn.playerworlds.backend.world;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import nl.gzmn.playerworlds.backend.platform.DimensionKind;
import nl.gzmn.playerworlds.backend.platform.Platform;
import nl.gzmn.playerworlds.backend.platform.WorldLayout;
import nl.gzmn.playerworlds.backend.platform.WorldLifecycle;
import nl.gzmn.playerworlds.backend.platform.WorldRuntime;
import nl.gzmn.playerworlds.backend.profile.WorldCommitService;
import nl.gzmn.playerworlds.core.concurrent.MainThread;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.db.MembershipRepository;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.Visibility;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.model.WorldState;
import nl.gzmn.playerworlds.core.obs.EventLogger;
import nl.gzmn.playerworlds.core.obs.LogEvent;
import nl.gzmn.playerworlds.core.obs.WorldsMetrics;
import nl.gzmn.playerworlds.core.storage.LocalObjectCache;
import nl.gzmn.playerworlds.core.storage.Manifest;
import nl.gzmn.playerworlds.core.storage.ManifestCodec;
import nl.gzmn.playerworlds.core.storage.ObjectStore;
import nl.gzmn.playerworlds.core.storage.WorldDownloader;
import org.bukkit.World;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Create, load and unload for player worlds (FR-2, FR-3, FR-4, FR-25).
 *
 * <p>Threading is the shape of this class. Database work runs on
 * {@link PluginExecutors#db()}, the filesystem stat that decides which
 * dimensions exist runs on {@link PluginExecutors#io()}, and only the Bukkit
 * calls that must — creating a world, applying a border, unloading — hop to the
 * main thread. The methods whose names end in {@code OnMain} are the ones a
 * caller is expected to already be on the tick thread for, and each asserts it.
 *
 * <p>Milestone 1 scope. Three things the specification puts around these paths
 * belong to later milestones and are deliberately absent: lease acquisition
 * before creation (FR-1a, milestone 7), the initial and pre-unload snapshot
 * commits (MN-6a, milestone 6), and per-world profiles (FR-14, milestone 4).
 */
public final class WorldLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(WorldLifecycleService.class);
    private static final EventLogger events = EventLogger.create(WorldLifecycleService.class);

    /**
     * FR-25a: end, then nether, then overworld. The overworld goes last because
     * it is the dimension the other two are addressed relative to, and a world
     * whose overworld is gone while its nether is up is the split state FR-25a
     * exists to prevent.
     */
    private static final List<DimensionKind> UNLOAD_ORDER =
            List.of(DimensionKind.END, DimensionKind.NETHER, DimensionKind.OVERWORLD);

    private final PlayerWorldRepository worlds;
    private final MembershipRepository membership;
    private final MembershipCache membershipCache;
    private final PluginExecutors executors;
    private final Platform platform;
    private final WorldFolders folders;
    private final WorldRegistry registry;
    private final WorldsMetrics metrics;
    private final Supplier<NetworkPolicy> policy;
    private final Path worldContainer;
    private final @Nullable String nodeId;
    private final int nodeDataVersion;
    private final @Nullable WorldDownloader worldDownloader;
    private final @Nullable ObjectStore objectStore;
    private final @Nullable WorldCommitService commitService;
    private final @Nullable LocalObjectCache cache;

    public WorldLifecycleService(
            PlayerWorldRepository worlds,
            MembershipRepository membership,
            MembershipCache membershipCache,
            PluginExecutors executors,
            Platform platform,
            WorldFolders folders,
            WorldRegistry registry,
            WorldsMetrics metrics,
            Supplier<NetworkPolicy> policy,
            Path worldContainer,
            @Nullable String nodeId,
            @Nullable WorldDownloader worldDownloader,
            @Nullable ObjectStore objectStore,
            @Nullable WorldCommitService commitService,
            @Nullable LocalObjectCache cache) {
        this.worlds = Objects.requireNonNull(worlds, "worlds");
        this.membership = Objects.requireNonNull(membership, "membership");
        this.membershipCache = Objects.requireNonNull(membershipCache, "membershipCache");
        this.executors = Objects.requireNonNull(executors, "executors");
        this.platform = Objects.requireNonNull(platform, "platform");
        this.folders = Objects.requireNonNull(folders, "folders");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.worldContainer = Objects.requireNonNull(worldContainer, "worldContainer");
        this.nodeId = nodeId;
        this.nodeDataVersion = platform.identity().dataVersion();
        this.worldDownloader = worldDownloader;
        this.objectStore = objectStore;
        this.commitService = commitService;
        this.cache = cache;
    }

    public WorldLifecycleService(
            PlayerWorldRepository worlds,
            MembershipRepository membership,
            MembershipCache membershipCache,
            PluginExecutors executors,
            Platform platform,
            WorldFolders folders,
            WorldRegistry registry,
            WorldsMetrics metrics,
            Supplier<NetworkPolicy> policy,
            Path worldContainer,
            @Nullable WorldDownloader worldDownloader,
            @Nullable ObjectStore objectStore,
            @Nullable WorldCommitService commitService,
            @Nullable LocalObjectCache cache) {
        this(
                worlds,
                membership,
                membershipCache,
                executors,
                platform,
                folders,
                registry,
                metrics,
                policy,
                worldContainer,
                null,
                worldDownloader,
                objectStore,
                commitService,
                cache);
    }

    public @Nullable WorldDownloader worldDownloader() {
        return worldDownloader;
    }

    public @Nullable ObjectStore objectStore() {
        return objectStore;
    }

    public @Nullable WorldCommitService commitService() {
        return commitService;
    }

    public @Nullable LocalObjectCache cache() {
        return cache;
    }

    public WorldLifecycleService(
            PlayerWorldRepository worlds,
            MembershipRepository membership,
            MembershipCache membershipCache,
            PluginExecutors executors,
            Platform platform,
            WorldFolders folders,
            WorldRegistry registry,
            WorldsMetrics metrics,
            Supplier<NetworkPolicy> policy,
            Path worldContainer) {
        this(
                worlds,
                membership,
                membershipCache,
                executors,
                platform,
                folders,
                registry,
                metrics,
                policy,
                worldContainer,
                null,
                null,
                null,
                null,
                null);
    }

    // -----------------------------------------------------------------------
    // Create (FR-1, FR-2, FR-2a, FR-4)
    // -----------------------------------------------------------------------

    /**
     * Creates a world and materialises its overworld.
     *
     * <p>Only the overworld (FR-4). The nether and end are created on first
     * transit by {@link #materialiseOnMain}, which turns one multi-second stall
     * into three smaller ones spread over the world's life.
     *
     * @param seed {@code null} for a random one (FR-2)
     */
    public CompletableFuture<CreateOutcome> create(UUID owner, String name, @Nullable Long seed) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(name, "name");
        NetworkPolicy current = policy.get();

        return CompletableFuture.supplyAsync(() -> insertRow(owner, name, seed, current), executors.db())
                .thenCompose(inserted -> {
                    PlayerWorld row = inserted.row();
                    if (row == null) {
                        return CompletableFuture.completedFuture(Objects.requireNonNull(inserted.refusal()));
                    }
                    return materialiseNewWorld(row, current);
                });
    }

    /** The cap checks and the insert, all on the database executor. */
    private Inserted insertRow(UUID owner, String name, @Nullable Long seed, NetworkPolicy current) {
        try {
            int owned = worlds.countOwnedBy(owner);
            if (owned >= current.maxWorldsPerPlayer()) {
                return Inserted.refused(new CreateOutcome.CapReached(owned, current.maxWorldsPerPlayer()));
            }
            if (worlds.findByOwnerAndName(owner, name).isPresent()) {
                return Inserted.refused(new CreateOutcome.NameTaken(name));
            }
            // FR-26 is a per-node limit and MN-15 enforces it at placement time,
            // which is milestone 8. Until a placement service exists the node has
            // to refuse for itself, or one node accepts every create on the
            // network.
            int loaded = registry.size();
            if (loaded >= current.maxWorldsPerNode()) {
                return Inserted.refused(new CreateOutcome.NodeFull(loaded, current.maxWorldsPerNode()));
            }

            WorldId id = WorldId.random();
            long chosenSeed = seed != null ? seed : new java.security.SecureRandom().nextLong();
            PlayerWorld row = worlds.create(
                    id,
                    owner,
                    name,
                    chosenSeed,
                    current.defaultBorderRadius(),
                    Visibility.valueOf(current.defaultVisibility()),
                    nodeId,
                    current.leaseDuration());
            return Inserted.of(row);
        } catch (SQLException e) {
            throw new CompletionException(e);
        }
    }

    /**
     * Generates the overworld, applies FR-3 / FR-25c / FR-9e, pre-generates spawn
     * and promotes the row to {@code READY}.
     *
     * <p>Any failure removes the row it inserted. A create that leaves a
     * {@code CREATING} row behind has consumed the owner's cap for a world that
     * does not exist, and only the FR-40 sweep would ever give it back.
     */
    private CompletableFuture<CreateOutcome> materialiseNewWorld(PlayerWorld row, NetworkPolicy current) {
        LoadedWorld loaded = registry.register(LoadedWorld.of(row));

        return onMain(() -> {
                    String bukkitName = folders.bukkitWorldName(row.id(), DimensionKind.OVERWORLD);
                    World world = timedMaterialise(bukkitName, DimensionKind.OVERWORLD, loaded.seed(), true, current);
                    if (world == null) {
                        return Boolean.FALSE;
                    }
                    applySettings(world, DimensionKind.OVERWORLD, loaded, current);
                    loaded.markMaterialised(DimensionKind.OVERWORLD);
                    return Boolean.TRUE;
                })
                .thenCompose(created -> {
                    if (!created) {
                        return rollbackCreate(row, "the server refused to create the overworld");
                    }
                    // Pre-generation is asynchronous and bounded (FR-4). A failure
                    // here is not a failed create: the world exists and is
                    // playable, the spawn area is just cold.
                    return pregenerateSpawn(row, current)
                            .thenCompose(ignored -> promoteToReady(row, loaded))
                            .thenApply(outcome -> {
                                if (commitService != null && outcome instanceof CreateOutcome.Created) {
                                    var _ = commitService.requestCommit(row.id());
                                }
                                return outcome;
                            });
                })
                .exceptionallyCompose(failure -> {
                    log.error("create failed for world {}", row.id(), failure);
                    return rollbackCreate(row, "world generation failed");
                });
    }

    /**
     * FR-4's bounded spawn pre-generation.
     *
     * <p>Always completes normally. A cold spawn area is a slow first few seconds,
     * not a failed create, and rolling back a world that exists and works because
     * chunk generation hiccupped would be the worse outcome.
     */
    private CompletableFuture<Boolean> pregenerateSpawn(PlayerWorld row, NetworkPolicy current) {
        String bukkitName = folders.bukkitWorldName(row.id(), DimensionKind.OVERWORLD);
        CompletableFuture<Void> future;
        try {
            future = platform.worldLifecycle().pregenerateSpawn(bukkitName, current.pregenSpawnChunks());
        } catch (Exception e) {
            future = CompletableFuture.failedFuture(e);
        }
        return future.handle((ignored, failure) -> {
            if (failure != null) {
                log.warn("spawn pre-generation failed for world {}; the world is still usable", row.id(), failure);
            }
            return Boolean.TRUE;
        });
    }

    private CompletableFuture<CreateOutcome> promoteToReady(PlayerWorld row, LoadedWorld loaded) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        worlds.markReadyAndPlayed(row.id());
                    } catch (SQLException e) {
                        throw new CompletionException(e);
                    }
                    cacheMembership(row);
                    metrics.setWorldsLoaded(registry.size());
                    events.info(LogEvent.WORLD_CREATE, "world created: " + row.name(), row.id());
                    PlayerWorld ready = new PlayerWorld(
                            row.id(),
                            row.ownerUuid(),
                            row.name(),
                            row.folder(),
                            row.seed(),
                            row.borderRadius(),
                            row.visibility(),
                            row.description(),
                            row.settingsJson(),
                            row.assignedNode(),
                            row.leaseExpires(),
                            row.generation(),
                            row.manifestKey(),
                            row.dataVersion(),
                            row.mcVersion(),
                            row.createdAt(),
                            row.lastPlayed(),
                            WorldState.READY);
                    return (CreateOutcome) new CreateOutcome.Created(ready, loaded);
                },
                executors.db());
    }

    private CompletableFuture<CreateOutcome> rollbackCreate(PlayerWorld row, String reason) {
        registry.unregister(row.id());
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        worlds.deleteIfCreating(row.id());
                    } catch (SQLException e) {
                        // The row is stuck in CREATING. FR-40's sweep reclaims it;
                        // say so loudly rather than pretending the create was clean.
                        log.error(
                                "could not roll back the CREATING row for world {}; "
                                        + "it will hold the owner's cap until the FR-40 maintenance sweep runs",
                                row.id(),
                                e);
                    }
                    metrics.setWorldsLoaded(registry.size());
                    return (CreateOutcome) new CreateOutcome.Failed(reason);
                },
                executors.db());
    }

    // -----------------------------------------------------------------------
    // Load (FR-3, FR-25c, MN-26)
    // -----------------------------------------------------------------------

    /**
     * Loads a world onto this node, materialising whichever dimensions already
     * exist on disk.
     *
     * <p>Never all three: a world created but never entered through a portal has
     * one folder, and generating the other two here would undo FR-4 in the other
     * direction — the stall would simply move from first transit to first join.
     */
    public CompletableFuture<LoadOutcome> load(WorldId id) {
        Objects.requireNonNull(id, "id");
        Optional<LoadedWorld> already = registry.find(id);
        if (already.isPresent()) {
            return CompletableFuture.completedFuture(new LoadOutcome.Loaded(already.get()));
        }
        NetworkPolicy current = policy.get();

        return CompletableFuture.supplyAsync(() -> readForLoad(id, current), executors.db())
                .thenComposeAsync(
                        checked -> {
                            PlayerWorld row = checked.row();
                            if (row == null) {
                                return CompletableFuture.completedFuture(Objects.requireNonNull(checked.refusal()));
                            }
                            return materialiseExisting(row, current);
                        },
                        executors.io());
    }

    private Checked readForLoad(WorldId id, NetworkPolicy current) {
        try {
            Optional<PlayerWorld> found = worlds.findById(id);
            if (found.isEmpty()) {
                return Checked.refused(new LoadOutcome.NotFound(id));
            }
            PlayerWorld row = found.get();
            if (row.state() != WorldState.READY && row.state() != WorldState.CREATING) {
                return Checked.refused(new LoadOutcome.WrongState(id, row.state()));
            }
            if (!row.isOpenableBy(nodeDataVersion)) {
                Integer worldVersion = row.dataVersion();
                events.warn(
                        LogEvent.VERSION_REFUSED,
                        "world was last written by data version " + worldVersion + "; this node is at "
                                + nodeDataVersion,
                        id);
                metrics.leaseAcquireDenied();
                return Checked.refused(
                        new LoadOutcome.TooNew(id, worldVersion == null ? 0 : worldVersion, nodeDataVersion));
            }
            int loaded = registry.size();
            if (loaded >= current.maxWorldsPerNode()) {
                return Checked.refused(new LoadOutcome.NodeFull(loaded, current.maxWorldsPerNode()));
            }

            // Lease handling (MN-8, MN-14)
            if (nodeId != null) {
                boolean heldByUs = row.assignedNode() != null
                        && row.assignedNode().equals(nodeId)
                        && row.leaseExpires() != null
                        && row.leaseExpires().isAfter(java.time.Instant.now());

                if (!heldByUs) {
                    Optional<PlayerWorldRepository.LeaseGrant> grant =
                            worlds.acquireLease(id, nodeId, nodeDataVersion, current.leaseDuration());
                    if (grant.isEmpty()) {
                        metrics.leaseAcquireDenied();
                        Optional<PlayerWorld> refetched = worlds.findById(id);
                        if (refetched.isPresent()
                                && refetched.get().assignedNode() != null
                                && !refetched.get().assignedNode().equals(nodeId)
                                && refetched.get().leaseExpires() != null
                                && refetched.get().leaseExpires().isAfter(java.time.Instant.now())) {
                            return Checked.refused(new LoadOutcome.Failed(
                                    id,
                                    "World is currently leased to node "
                                            + refetched.get().assignedNode()));
                        }
                        return Checked.refused(new LoadOutcome.Failed(id, "Could not acquire lease for world"));
                    }
                    metrics.leaseAcquireOk();
                    events.info(
                            LogEvent.LEASE_ACQUIRE,
                            "acquired lease for world " + row.name() + " gen "
                                    + grant.get().generation(),
                            id);
                    row = row.withLease(
                            nodeId, grant.get().expiresAt(), grant.get().generation());
                }
            }

            return Checked.of(row);
        } catch (SQLException e) {
            throw new CompletionException(e);
        }
    }

    /** Runs on the io executor: stats the folders, then hops to main to load them. */
    private CompletableFuture<LoadOutcome> materialiseExisting(PlayerWorld row, NetworkPolicy current) {
        long startNanos = System.nanoTime();
        boolean isCold = false;

        if (row.manifestKey() != null
                && !row.manifestKey().isBlank()
                && objectStore != null
                && worldDownloader != null) {
            try {
                byte[] manifestBytes = objectStore.getBytes(row.manifestKey());
                String manifestJson = new String(manifestBytes, StandardCharsets.UTF_8);
                Manifest manifest = ManifestCodec.decode(manifestJson);
                WorldDownloader.Result dlResult = worldDownloader.materialize(manifest, worldContainer);
                isCold = !dlResult.wasWarm();
                if (commitService != null) {
                    commitService.cacheManifest(row.id(), manifest);
                }
            } catch (Exception e) {
                log.error("could not download or materialize manifest for world {}", row.id(), e);
                return CompletableFuture.completedFuture(new LoadOutcome.Failed(
                        row.id(), "could not materialize world from storage: " + e.getMessage()));
            }
        }

        Set<DimensionKind> onDisk = dimensionsOnDisk(row.id());
        if (onDisk.isEmpty()) {
            if (row.state() == WorldState.CREATING) {
                // A row the proxy inserted at /world create, arriving on the node
                // it routed to. Generating here rather than on the proxy is the
                // whole point: only a node can run createWorld, and FR-4's
                // main-thread stall is its to pay.
                return materialiseNewWorld(row, current).thenApply(outcome -> switch (outcome) {
                    case CreateOutcome.Created created -> (LoadOutcome) new LoadOutcome.Loaded(created.world());
                    case CreateOutcome.Failed failed -> new LoadOutcome.Failed(row.id(), failed.reason());
                    case CreateOutcome.CapReached cap ->
                        new LoadOutcome.Failed(row.id(), "owner is at their world limit (" + cap.cap() + ")");
                    case CreateOutcome.NameTaken taken ->
                        new LoadOutcome.Failed(row.id(), "a world called '" + taken.name() + "' already exists");
                    case CreateOutcome.NodeFull full -> new LoadOutcome.NodeFull(full.loaded(), full.cap());
                });
            }
            // A READY row whose folders are gone and no manifest could be loaded.
            return CompletableFuture.completedFuture(new LoadOutcome.Failed(row.id(), "no world folder on this node"));
        }

        LoadedWorld loaded = registry.register(LoadedWorld.of(row));
        final boolean finalIsCold = isCold;

        return onMain(() -> {
                    for (DimensionKind dimension : onDisk) {
                        String bukkitName = folders.bukkitWorldName(row.id(), dimension);
                        World world = timedMaterialise(bukkitName, dimension, loaded.seed(), false, current);
                        if (world == null) {
                            return dimension.name();
                        }
                        applySettings(world, dimension, loaded, current);
                        loaded.markMaterialised(dimension);
                    }
                    return "";
                })
                .thenApplyAsync(
                        failedDimension -> {
                            if (!failedDimension.isEmpty()) {
                                registry.unregister(row.id());
                                metrics.setWorldsLoaded(registry.size());
                                return (LoadOutcome)
                                        new LoadOutcome.Failed(row.id(), "could not load dimension " + failedDimension);
                            }
                            try {
                                worlds.touchLastPlayed(row.id());
                            } catch (SQLException e) {
                                log.warn("could not record last_played for world {}", row.id(), e);
                            }
                            cacheMembership(row);
                            metrics.setWorldsLoaded(registry.size());
                            Duration loadDuration = Duration.ofNanos(System.nanoTime() - startNanos);
                            if (finalIsCold) {
                                metrics.worldLoadCold(loadDuration);
                            } else {
                                metrics.worldLoadWarm(loadDuration);
                            }
                            events.info(LogEvent.WORLD_JOIN, "world loaded: " + row.name(), row.id());
                            return (LoadOutcome) new LoadOutcome.Loaded(loaded);
                        },
                        executors.db());
    }

    /**
     * Which dimension folders exist on disk. Runs off the main thread — this is a
     * filesystem walk and NFR-2 forbids those on the tick thread.
     *
     * <p>A folder counts only when it carries the layout's root files, which today
     * means {@code level.dat}. An empty directory left by an interrupted download
     * or a manual copy is not a world, and treating it as one would load a world
     * with no data and then save over the real one.
     */
    private Set<DimensionKind> dimensionsOnDisk(WorldId id) {
        WorldLayout layout = platform.worldLayout();
        EnumSet<DimensionKind> present = EnumSet.noneOf(DimensionKind.class);
        for (DimensionKind dimension : DimensionKind.values()) {
            Path folder = layout.bukkitWorldFolder(worldContainer, id.folder(), dimension);
            if (!Files.isDirectory(folder)) {
                continue;
            }
            boolean complete = true;
            for (String rootFile : layout.worldRootFiles()) {
                if (!Files.isRegularFile(folder.resolve(rootFile))) {
                    complete = false;
                    break;
                }
            }
            if (complete) {
                present.add(dimension);
            } else {
                log.warn(
                        "world folder {} exists but is missing one of {}; not treating it as a materialised dimension",
                        folder,
                        layout.worldRootFiles());
            }
        }
        return Set.copyOf(present);
    }

    // -----------------------------------------------------------------------
    // Main-thread operations
    // -----------------------------------------------------------------------

    /**
     * Materialises one dimension on demand (FR-2), for the portal path.
     *
     * <p>Runs on the main thread and blocks it for as long as generation takes.
     * That is FR-4's accepted trade: one stall at the moment a player first walks
     * into a portal, rather than three at {@code /world create}.
     *
     * @return false when the server refused to create the dimension
     */
    public boolean materialiseOnMain(LoadedWorld loaded, DimensionKind dimension) {
        MainThread.assertOn();
        Objects.requireNonNull(loaded, "loaded");
        Objects.requireNonNull(dimension, "dimension");
        if (loaded.isMaterialised(dimension)) {
            return true;
        }
        NetworkPolicy current = policy.get();
        String bukkitName = folders.bukkitWorldName(loaded.id(), dimension);
        World world = timedMaterialise(bukkitName, dimension, loaded.seed(), true, current);
        if (world == null) {
            log.error("could not materialise {} for world {}", dimension, loaded.id());
            return false;
        }
        applySettings(world, dimension, loaded, current);
        loaded.markMaterialised(dimension);
        log.info("materialised {} for world {} on first transit (FR-2)", dimension, loaded.id());
        return true;
    }

    /**
     * Unloads every dimension of one world in FR-25a order.
     *
     * <p>Stops at the first refusal and reports it, rather than carrying on: a
     * partially unloaded world has a split visibility group, and the FR-25a retry
     * re-attempts the world as a unit precisely so it is never left that way.
     */
    public UnloadOutcome unloadOnMain(LoadedWorld loaded) {
        MainThread.assertOn();
        Objects.requireNonNull(loaded, "loaded");
        WorldLifecycle lifecycle = platform.worldLifecycle();
        List<DimensionKind> unloaded = new ArrayList<>(UNLOAD_ORDER.size());

        for (DimensionKind dimension : UNLOAD_ORDER) {
            String bukkitName = folders.bukkitWorldName(loaded.id(), dimension);
            if (lifecycle.loaded(bukkitName) == null) {
                // Never materialised, or already down. Nothing to do, and not a
                // failure: FR-25a's retry attempts the whole world, so an absent
                // dimension is the normal case on the second pass.
                loaded.markUnloaded(dimension);
                continue;
            }
            if (!lifecycle.unload(bukkitName, true)) {
                List<String> blockers = lifecycle.unloadBlockers(bukkitName);
                return new UnloadOutcome.Blocked(dimension, blockers);
            }
            loaded.markUnloaded(dimension);
            unloaded.add(dimension);
        }
        return new UnloadOutcome.Complete(unloaded);
    }

    /** Records a completed unload: deregisters, updates meters, writes the row. */
    public void afterUnload(LoadedWorld loaded) {
        Objects.requireNonNull(loaded, "loaded");
        registry.unregister(loaded.id());
        membershipCache.invalidate(loaded.id());
        metrics.setWorldsLoaded(registry.size());
        events.info(LogEvent.WORLD_UNLOAD, "world unloaded: " + loaded.name(), loaded.id());
        executors.db().execute(() -> {
            try {
                worlds.touchLastPlayed(loaded.id());
                if (nodeId != null) {
                    boolean released = worlds.releaseLease(loaded.id(), nodeId, loaded.generation());
                    if (released) {
                        events.info(LogEvent.LEASE_RELEASE, "lease released for world " + loaded.name(), loaded.id());
                    }
                }
            } catch (SQLException e) {
                log.warn("could not record last_played or release lease after unloading world {}", loaded.id(), e);
            }
        });
    }

    /**
     * Loads this world's membership into the cache role enforcement reads (FR-9).
     *
     * <p>Failure is not fatal to the load, but it is not silent either: an empty
     * cache makes every player a visitor, which is the safe direction and a very
     * visible one.
     */
    private void cacheMembership(PlayerWorld row) {
        try {
            membershipCache.put(row.id(), row.ownerUuid(), membership.rolesIn(row.id()));
        } catch (SQLException e) {
            log.error(
                    "could not load membership for world {}; every player there will be treated as a "
                            + "visitor until it reloads (FR-9)",
                    row.id(),
                    e);
        }
    }

    /**
     * Border, spawn-chunk radius and gamerules, applied on <em>every</em> load.
     *
     * <p>Not once at creation. All three are persisted in {@code level.dat}, so
     * they arrive from a restore carrying whatever the folder happened to hold —
     * FR-3, FR-25c and FR-9e each say in their own words that the database value
     * wins over the folder.
     */
    private void applySettings(World world, DimensionKind dimension, LoadedWorld loaded, NetworkPolicy current) {
        WorldRuntime runtime = platform.worldRuntime();
        runtime.applyBorder(world, dimension, loaded.borderRadius(), current.netherBorderDivisor());
        runtime.disableAlwaysLoadedSpawnChunks(world);
        // FR-9e: safe defaults. Per-world overrides live in player_world.settings
        // and arrive with milestone 9; until then every world gets the default the
        // specification names, rather than whatever level.dat carried.
        runtime.setPvp(world, false);
    }

    /**
     * Creates or loads one dimension, timing the main-thread stall (FR-4).
     *
     * @param fresh true when this is a generation rather than the load of an
     *     existing folder. Only generations feed {@code create_stall_ms}: mixing
     *     loads into it would blur the release-gating number FR-4 asks for.
     */
    private @Nullable World timedMaterialise(
            String bukkitName, DimensionKind dimension, long seed, boolean fresh, NetworkPolicy current) {
        MainThread.assertOn();
        long start = System.nanoTime();
        World world =
                platform.worldLifecycle().createOrLoad(WorldLifecycle.CreationRequest.of(bukkitName, dimension, seed));
        Duration stall = Duration.ofNanos(System.nanoTime() - start);

        if (fresh) {
            metrics.createStall(stall);
            // Logged on every generation, not only when it is over budget. FR-4
            // calls this number release-gating and spec section 11 says to measure
            // it in this milestone, so an operator has to be able to read it off a
            // boot log without standing up a Prometheus scrape first. World
            // generation is rare enough that this costs nothing in log volume.
            log.info(
                    "createWorld stalled the main thread for {} ms generating {} of {} (FR-4; budget {} ms)",
                    stall.toMillis(),
                    dimension,
                    bukkitName,
                    current.createStallBudget().toMillis());
            if (stall.compareTo(current.createStallBudget()) > 0) {
                log.warn(
                        "createWorld stalled the main thread for {} ms creating {} of {}, over the "
                                + "{} ms budget (FR-4); creation should be routed to a node with no other loaded worlds",
                        stall.toMillis(),
                        dimension,
                        bukkitName,
                        current.createStallBudget().toMillis());
            }
        }
        return world;
    }

    /** Schedules {@code work} on the main thread and completes when it returns. */
    private <T> CompletableFuture<T> onMain(Supplier<T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        executors.main().execute(() -> {
            try {
                future.complete(work.get());
            } catch (RuntimeException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /** Either an inserted row or the refusal that stopped it. */
    private record Inserted(
            @Nullable PlayerWorld row, @Nullable CreateOutcome refusal) {
        static Inserted of(PlayerWorld row) {
            return new Inserted(row, null);
        }

        static Inserted refused(CreateOutcome refusal) {
            return new Inserted(null, refusal);
        }
    }

    /** Either a row cleared for loading or the refusal that stopped it. */
    private record Checked(
            @Nullable PlayerWorld row, @Nullable LoadOutcome refusal) {
        static Checked of(PlayerWorld row) {
            return new Checked(row, null);
        }

        static Checked refused(LoadOutcome refusal) {
            return new Checked(null, refusal);
        }
    }
}
