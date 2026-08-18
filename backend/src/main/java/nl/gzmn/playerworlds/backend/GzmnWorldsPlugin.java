package nl.gzmn.playerworlds.backend;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import nl.gzmn.playerworlds.backend.command.PworldCommand;
import nl.gzmn.playerworlds.backend.config.BackendConfig;
import nl.gzmn.playerworlds.backend.control.DrainNodeHandler;
import nl.gzmn.playerworlds.backend.control.EjectPlayerHandler;
import nl.gzmn.playerworlds.backend.control.InvalidateCacheHandler;
import nl.gzmn.playerworlds.backend.control.MigrateWorldHandler;
import nl.gzmn.playerworlds.backend.control.UnloadWorldHandler;
import nl.gzmn.playerworlds.backend.control.WorldHandoff;
import nl.gzmn.playerworlds.backend.lease.LeaseCoordinator;
import nl.gzmn.playerworlds.backend.lease.SelfFencingHandler;
import nl.gzmn.playerworlds.backend.node.NodeHeartbeat;
import nl.gzmn.playerworlds.backend.node.TransferJoinListener;
import nl.gzmn.playerworlds.backend.platform.Platform;
import nl.gzmn.playerworlds.backend.platform.ServerIdentity;
import nl.gzmn.playerworlds.backend.platform.UnsupportedPlatformException;
import nl.gzmn.playerworlds.backend.profile.ProfileListener;
import nl.gzmn.playerworlds.backend.profile.ProfileService;
import nl.gzmn.playerworlds.backend.profile.WorldCommitService;
import nl.gzmn.playerworlds.backend.storage.PeriodicSyncTask;
import nl.gzmn.playerworlds.backend.world.IdleUnloadTask;
import nl.gzmn.playerworlds.backend.world.LoadedWorld;
import nl.gzmn.playerworlds.backend.world.MembershipCache;
import nl.gzmn.playerworlds.backend.world.PortalListener;
import nl.gzmn.playerworlds.backend.world.RoleEnforcementListener;
import nl.gzmn.playerworlds.backend.world.WorldFolders;
import nl.gzmn.playerworlds.backend.world.WorldLifecycleService;
import nl.gzmn.playerworlds.backend.world.WorldRegistry;
import nl.gzmn.playerworlds.core.concurrent.BoundedOperations;
import nl.gzmn.playerworlds.core.concurrent.MainThread;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.config.ConfigException;
import nl.gzmn.playerworlds.core.config.ConfigValidator;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.config.NodeConfig;
import nl.gzmn.playerworlds.core.control.CommandKind;
import nl.gzmn.playerworlds.core.control.ControlPlane;
import nl.gzmn.playerworlds.core.db.Database;
import nl.gzmn.playerworlds.core.db.MembershipRepository;
import nl.gzmn.playerworlds.core.db.NetworkSettings;
import nl.gzmn.playerworlds.core.db.NodeCommandRepository;
import nl.gzmn.playerworlds.core.db.NodeRepository;
import nl.gzmn.playerworlds.core.db.PendingTransferRepository;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository;
import nl.gzmn.playerworlds.core.db.ProfileRepository;
import nl.gzmn.playerworlds.core.db.Schema;
import nl.gzmn.playerworlds.core.obs.CapabilityProbe;
import nl.gzmn.playerworlds.core.obs.CapabilityReport;
import nl.gzmn.playerworlds.core.obs.MetricsSettings;
import nl.gzmn.playerworlds.core.obs.PrometheusEndpoint;
import nl.gzmn.playerworlds.core.obs.WorldsMetrics;
import nl.gzmn.playerworlds.core.storage.FileCloner;
import nl.gzmn.playerworlds.core.storage.LocalObjectCache;
import nl.gzmn.playerworlds.core.storage.ObjectStore;
import nl.gzmn.playerworlds.core.storage.QuarantineManager;
import nl.gzmn.playerworlds.core.storage.ReflinkFileCloner;
import nl.gzmn.playerworlds.core.storage.S3ObjectStore;
import nl.gzmn.playerworlds.core.storage.SnapshotCopier;
import nl.gzmn.playerworlds.core.storage.SnapshotEngine;
import nl.gzmn.playerworlds.core.storage.WorldDownloader;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/**
 * The Paper plugin entry point. Runs on every {@code worlds} node.
 *
 * <p>Enable is a bootstrap in a fixed order, and the order is load-bearing: mark
 * the main thread (NFR-2), read node-local configuration, select the
 * Minecraft-version platform (plan 00 §5.2), bring the schema up to date and read
 * network policy, validate the two against each other and against the filesystem
 * (plan 00 §8.2), open the executor topology (plan 00 §9), probe capabilities
 * (plan 00 §10.4), then start the world lifecycle (plan 01).
 *
 * <p>The database bootstrap runs on a worker thread that the main thread waits
 * for. Blocking startup is correct — a node must not accept a player before its
 * schema is current — but running the JDBC itself on the tick thread is not, and
 * {@code MainThread} is marked before any of it so a mistake fails loudly rather
 * than silently becoming the exception to NFR-2.
 *
 * <p>Not {@code final}: MockBukkit's plugin loader subclasses the main class
 * (plan 00 §11).
 */
public class GzmnWorldsPlugin extends JavaPlugin {

    /**
     * How long enable waits for migrations and the first policy read.
     *
     * <p>Generous, because a first boot applies the whole baseline migration, and
     * bounded, because a node hanging on an unreachable database must fail the
     * enable rather than leave the server half-started forever.
     */
    private static final Duration BOOTSTRAP_TIMEOUT = Duration.ofSeconds(90);

    /** How long enable waits for the capability probe (plan 00 §10.4). */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(30);

    /** How often the cached network policy is refreshed from {@code network_setting}. */
    private static final Duration POLICY_REFRESH = Duration.ofMinutes(1);

    private static final long TICKS_PER_SECOND = 20L;

    private @Nullable Platform platform;
    private @Nullable PluginExecutors executors;
    private @Nullable WorldsMetrics metrics;
    private @Nullable PrometheusEndpoint metricsEndpoint;
    private @Nullable Database database;
    private @Nullable ObjectStore objectStore;
    private @Nullable WorldRegistry registry;
    private @Nullable IdleUnloadTask idleUnload;
    private @Nullable WorldCommitService commitService;
    private @Nullable NodeHeartbeat nodeHeartbeat;
    private @Nullable LeaseCoordinator leaseCoordinator;
    private @Nullable SelfFencingHandler fencingHandler;
    private @Nullable ControlPlane controlPlane;
    private @Nullable ExecutorService listenExecutor;
    private @Nullable NodeConfig nodeConfig;

    /**
     * Last policy read from the database.
     *
     * <p>Cached rather than read on demand because every caller is on the main
     * thread and {@code network_setting} is a database table (NFR-2). Refreshed on
     * a timer; milestone 5's {@code INVALIDATE_CACHE} control command makes the
     * refresh immediate. {@code volatile} is enough: the refresh publishes a whole
     * immutable record and readers take whichever one is current.
     */
    private volatile NetworkPolicy policy = NetworkPolicy.defaults();

    @Override
    public void onEnable() {
        // onEnable runs on the server main thread. Mark it before anything that
        // might touch JDBC so MainThread.assertOff can name the offender.
        MainThread.enter(Thread.currentThread());

        ServerIdentity identity = detectIdentity();
        final Platform selected;
        try {
            selected = Platform.create(identity);
        } catch (UnsupportedPlatformException e) {
            getLogger().severe(() -> e.getMessage());
            disableSelf();
            return;
        }
        this.platform = selected;

        saveDefaultConfig();
        final NodeConfig node;
        final MetricsSettings metricsSettings;
        try {
            node = BackendConfig.node(getConfig(), getDataFolder().toPath(), worldContainer());
            metricsSettings = BackendConfig.metrics(getConfig());
        } catch (ConfigException e) {
            getLogger().severe(() -> "invalid config.yml: " + e.getMessage());
            disableSelf();
            return;
        }

        Database openedDatabase = Database.open(node.database());
        this.database = openedDatabase;

        final NetworkPolicy loadedPolicy;
        try {
            loadedPolicy = bootstrapDatabase(openedDatabase);
        } catch (RuntimeException e) {
            getLogger().severe(() -> "database bootstrap failed: " + e.getMessage());
            closeDatabase();
            disableSelf();
            return;
        }
        this.policy = loadedPolicy;

        // Config that violates a safety property disables the plugin rather than
        // running with a "close enough" default (plan 00 §8.2).
        try {
            ConfigValidator.validate(node, loadedPolicy);
        } catch (ConfigException e) {
            getLogger().severe(() -> "configuration refused: " + e.getMessage());
            closeDatabase();
            disableSelf();
            return;
        }

        PluginExecutors pools =
                PluginExecutors.create(node.database().poolSize(), loadedPolicy.parallelTransfers(), task -> {
                    if (getServer().isPrimaryThread()) {
                        task.run();
                    } else {
                        getServer().getScheduler().runTask(this, task);
                    }
                });
        this.executors = pools;

        WorldsMetrics worldsMetrics = WorldsMetrics.create();
        this.metrics = worldsMetrics;

        // The probe does a database round trip, a free-space stat and a reflink
        // trial copy — all three are work NFR-2 keeps off the tick thread, so it
        // runs on the io pool and enable waits for it under a budget.
        final CapabilityReport report;
        try {
            report = BoundedOperations.call(
                    pools.io(),
                    PROBE_TIMEOUT,
                    () -> CapabilityProbe.run(new CapabilityProbe.Request(
                            node.scratchPath(),
                            node.minFreeSpaceBytes(),
                            identity.minecraftVersion(),
                            identity.dataVersion(),
                            openedDatabase,
                            null)));
        } catch (TimeoutException | ExecutionException e) {
            getLogger().severe(() -> "capability probe did not complete: " + e);
            disableSelf();
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            getLogger().severe("interrupted during the capability probe");
            disableSelf();
            return;
        }
        CapabilityProbe.log(report);
        worldsMetrics.setScratchFreeBytes(Math.max(0L, report.freeBytes()));
        if (!report.safeToEnable()) {
            getLogger().severe("capability probe failed; refusing enable");
            disableSelf();
            return;
        }

        try {
            this.metricsEndpoint = PrometheusEndpoint.start(worldsMetrics, metricsSettings);
        } catch (java.io.IOException e) {
            getLogger()
                    .warning(() -> "could not bind prometheus endpoint on "
                            + metricsSettings.bindAddress()
                            + ":"
                            + metricsSettings.port()
                            + " ("
                            + e.getMessage()
                            + "); meters remain in-process only");
        }

        this.nodeConfig = node;
        startWorldLifecycle(selected, openedDatabase, pools, worldsMetrics, node, report);
        schedulePolicyRefresh(openedDatabase, pools);

        // Concatenation rather than a format string: %d formats through the
        // default locale, which forbidden-apis bans and which would render the
        // data version in non-ASCII digits on some hosts. The one number an
        // operator reads out of this line has to be readable everywhere.
        getLogger()
                .info(() -> "enabled: node "
                        + node.nodeId()
                        + ", minecraft "
                        + identity.minecraftVersion()
                        + ", data version "
                        + identity.dataVersion()
                        + ", world layout "
                        + selected.worldLayout().id()
                        + ", worlds in "
                        + node.scratchPath()
                        + ", db threads "
                        + pools.dbThreads()
                        + ", io threads "
                        + pools.ioThreads()
                        + ", reflink "
                        + report.reflink().wire());

        if (selected.unknownNewerVersion()) {
            getLogger()
                    .warning(() -> "server data version "
                            + identity.dataVersion()
                            + " is newer than this build's verified version "
                            + Platform.BUILD_DATA_VERSION
                            + "; using layout "
                            + selected.worldLayout().id()
                            + ". Check for a gzmn-worlds release built against this Paper line.");
        }
    }

    /**
     * Migrates the schema and reads network policy, off the main thread.
     *
     * <p>Uses a single-use thread rather than {@link PluginExecutors} because the
     * io pool is sized from {@code storage.parallel-transfers}, which is one of
     * the values this call goes and fetches.
     */
    private NetworkPolicy bootstrapDatabase(Database openedDatabase) {
        ExecutorService bootstrap = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "gzmn-bootstrap");
            thread.setDaemon(true);
            return thread;
        });
        try {
            CompletableFuture<NetworkPolicy> future = CompletableFuture.supplyAsync(
                    () -> {
                        try {
                            Schema.migrate(openedDatabase);
                            NetworkSettings settings = new NetworkSettings(openedDatabase);
                            settings.reload();
                            return settings.policy();
                        } catch (SQLException e) {
                            throw new CompletionException(e);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new CompletionException(e);
                        }
                    },
                    bootstrap);
            return future.get(BOOTSTRAP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IllegalStateException(
                    "schema migration and policy load did not finish within " + BOOTSTRAP_TIMEOUT, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw new IllegalStateException(cause == null ? e.toString() : cause.toString(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during database bootstrap", e);
        } finally {
            bootstrap.shutdownNow();
        }
    }

    /** Wires the world lifecycle, storage, and registers listeners, commands and sweeps. */
    private void startWorldLifecycle(
            Platform selected,
            Database openedDatabase,
            PluginExecutors pools,
            WorldsMetrics worldsMetrics,
            NodeConfig node,
            CapabilityReport report) {
        // MN-13 & MN-5a: Startup quarantine sweep for crash debris and stale snapshot directories
        try {
            QuarantineManager.sweepStartup(node.scratchPath(), node.quarantinePath(), java.util.Set.of());
        } catch (Exception e) {
            getLogger().warning(() -> "could not complete startup quarantine sweep: " + e.getMessage());
        }

        FileCloner cloner = new ReflinkFileCloner(report.reflink());
        LocalObjectCache objectCache = new LocalObjectCache(node.cachePath(), cloner);

        ObjectStore store = node.objectStorage().map(S3ObjectStore::open).orElse(null);
        this.objectStore = store;

        SnapshotEngine snapshotEngine = store != null
                ? new SnapshotEngine(store, objectCache, new SnapshotCopier(cloner, this.policy.snapshotCopyRetries()))
                : null;
        WorldDownloader worldDownloader = store != null ? new WorldDownloader(store, objectCache, cloner) : null;

        WorldFolders worldFolders = new WorldFolders(selected.worldLayout());
        WorldRegistry worldRegistry = new WorldRegistry();
        this.registry = worldRegistry;
        PlayerWorldRepository worldRepository = new PlayerWorldRepository(openedDatabase);
        MembershipRepository membershipRepository = new MembershipRepository(openedDatabase);
        ProfileRepository profileRepository = new ProfileRepository(openedDatabase);
        ProfileService profileService = new ProfileService(selected.itemCodec());

        WorldCommitService worldCommitService = new WorldCommitService(
                profileRepository,
                worldRepository,
                profileService,
                worldFolders,
                selected,
                pools,
                snapshotEngine,
                this::policy,
                node.scratchPath(),
                node.nodeId());
        this.commitService = worldCommitService;

        SelfFencingHandler fencing = new SelfFencingHandler(
                worldRegistry,
                worldFolders,
                selected,
                pools,
                worldCommitService,
                new NodeCommandRepository(openedDatabase),
                worldsMetrics,
                node.scratchPath(),
                node.quarantinePath(),
                this::policy);
        this.fencingHandler = fencing;
        worldCommitService.setRegistry(worldRegistry);
        worldCommitService.setFencingHandler(fencing);

        LeaseCoordinator leases = new LeaseCoordinator(
                node.nodeId(), worldRegistry, worldRepository, fencing, pools, this::policy, node.heartbeatInterval());
        this.leaseCoordinator = leases;
        leases.start(pools.sched());

        MembershipCache membershipCache = new MembershipCache();
        PendingTransferRepository transferRepository = new PendingTransferRepository(openedDatabase);
        NodeCommandRepository nodeCommands = new NodeCommandRepository(openedDatabase);

        WorldLifecycleService lifecycle = new WorldLifecycleService(
                worldRepository,
                membershipRepository,
                membershipCache,
                pools,
                selected,
                worldFolders,
                worldRegistry,
                worldsMetrics,
                this::policy,
                node.scratchPath(),
                node.nodeId(),
                worldDownloader,
                store,
                worldCommitService,
                objectCache);

        getServer()
                .getPluginManager()
                .registerEvents(
                        new PortalListener(selected, worldFolders, worldRegistry, lifecycle, this::policy), this);
        // FR-9 in world: OWNER and BUILDER build, VISITOR does not.
        getServer().getPluginManager().registerEvents(new RoleEnforcementListener(worldFolders, membershipCache), this);
        // FR-11: routed join listener
        getServer()
                .getPluginManager()
                .registerEvents(
                        new TransferJoinListener(
                                node, transferRepository, lifecycle, worldFolders, pools, nodeCommands, this::policy),
                        this);
        // FR-15: profile commit triggers & manifest snapshot restores
        getServer()
                .getPluginManager()
                .registerEvents(
                        new ProfileListener(
                                worldFolders,
                                profileService,
                                profileRepository,
                                worldRepository,
                                worldCommitService,
                                pools),
                        this);

        PluginCommand command = getCommand("pworld");
        if (command == null) {
            getLogger().severe("plugin.yml does not declare the pworld command; the operator surface is unavailable");
        } else {
            PworldCommand handler = new PworldCommand(
                    lifecycle,
                    worldRegistry,
                    worldFolders,
                    worldRepository,
                    membershipRepository,
                    new nl.gzmn.playerworlds.core.db.PlayerNameRepository(openedDatabase),
                    pools,
                    nodeCommands,
                    this::policy);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
            // Say so positively. A command gated behind a permission is tested for
            // that permission before the handler is ever called, and Paper hides
            // commands the caller cannot use — so "nothing happens" looks identical
            // whether the command failed to register or the caller simply lacks
            // the permission. Naming both here tells an operator which it is.
            getLogger()
                    .info(() -> "/" + command.getName() + " registered; requires permission " + command.getPermission()
                            + " (default: op, so grant it or /op yourself)");
        }

        // FR-25 orders it commit, unload, release. Without the commit hook the
        // unload discards up to storage.sync-minutes of play for the world and
        // every profile in it (FR-15), so it is wired whenever object storage is.
        IdleUnloadTask sweep = new IdleUnloadTask(
                worldRegistry,
                lifecycle,
                selected.worldLifecycle(),
                worldFolders,
                this::policy,
                worldCommitService::requestCommit,
                pools.main());
        this.idleUnload = sweep;
        long periodTicks = IdleUnloadTask.SWEEP_INTERVAL.toSeconds() * TICKS_PER_SECOND;
        getServer().getScheduler().runTaskTimer(this, sweep, periodTicks, periodTicks);

        // MN-6: schedule periodic incremental snapshot commits
        PeriodicSyncTask syncTask = new PeriodicSyncTask(worldRegistry, worldCommitService, this::policy);
        long syncIntervalSeconds = Math.max(1, this.policy.syncInterval().toSeconds());
        var _ = pools.sched()
                .scheduleWithFixedDelay(syncTask, syncIntervalSeconds, syncIntervalSeconds, TimeUnit.SECONDS);

        // Before the control plane, because DRAIN_NODE acts on the heartbeat: a
        // drain that could not set the draining flag would take the node's worlds
        // away and then let placement send it more.
        NodeHeartbeat heartbeat = startHeartbeat(openedDatabase, pools, node, selected.identity(), worldRegistry);
        startControlPlane(
                worldRegistry,
                lifecycle,
                worldFolders,
                membershipCache,
                openedDatabase,
                nodeCommands,
                pools,
                node,
                this.policy,
                worldCommitService,
                heartbeat);
    }

    /** Publishes this node's heartbeat row (MN-17, MN-18). */
    private NodeHeartbeat startHeartbeat(
            Database openedDatabase,
            PluginExecutors pools,
            NodeConfig node,
            ServerIdentity identity,
            WorldRegistry worldRegistry) {
        NodeHeartbeat heartbeat = new NodeHeartbeat(
                new NodeRepository(openedDatabase),
                node,
                identity,
                worldRegistry::size,
                () -> getServer().getOnlinePlayers().size(),
                // MN-15 excludes on TPS, so it has to be reported. getTPS()[0] is
                // the one-minute average: the five- and fifteen-minute figures lag
                // a node going bad by longer than a lease.
                () -> {
                    double[] tps = getServer().getTPS();
                    return tps.length == 0 ? Double.NaN : tps[0];
                });
        this.nodeHeartbeat = heartbeat;
        // First heartbeat immediately so the proxy can discover this node right away (MN-17)
        pools.db().execute(heartbeat);
        long intervalSeconds = node.heartbeatInterval().toSeconds();
        var _ = pools.sched().scheduleWithFixedDelay(heartbeat, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        return heartbeat;
    }

    /**
     * Starts the control plane listener and command dispatcher (milestone 5, and
     * milestone 8's {@code MIGRATE_WORLD} and {@code DRAIN_NODE}).
     */
    private void startControlPlane(
            WorldRegistry worldRegistry,
            WorldLifecycleService lifecycle,
            WorldFolders worldFolders,
            MembershipCache membershipCache,
            Database openedDatabase,
            NodeCommandRepository nodeCommands,
            PluginExecutors pools,
            NodeConfig node,
            NetworkPolicy loadedPolicy,
            WorldCommitService commits,
            NodeHeartbeat heartbeat) {
        ExecutorService listen = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "gzmn-backend-listen");
            thread.setDaemon(true);
            return thread;
        });
        this.listenExecutor = listen;

        ControlPlane plane = ControlPlane.forNode(
                node.nodeId(),
                node.database(),
                nodeCommands,
                loadedPolicy.controlPollInterval(),
                loadedPolicy.controlClaimTimeout());

        // One implementation of MN-19's warn, eject, commit, unload, release for
        // all three commands that give a world up.
        WorldHandoff handoff =
                new WorldHandoff(worldRegistry, lifecycle, worldFolders, pools, commits, nodeCommands, this::policy);

        plane.register(CommandKind.UNLOAD_WORLD, new UnloadWorldHandler(handoff, this::policy));
        plane.register(CommandKind.MIGRATE_WORLD, new MigrateWorldHandler(handoff, this::policy));
        plane.register(CommandKind.DRAIN_NODE, new DrainNodeHandler(worldRegistry, handoff, heartbeat, this::policy));
        plane.register(
                CommandKind.INVALIDATE_CACHE,
                new InvalidateCacheHandler(new NetworkSettings(openedDatabase), membershipCache, pools.db()));
        EjectPlayerHandler ejectHandler =
                new EjectPlayerHandler(membershipCache, worldFolders, pools, nodeCommands, this::policy);
        plane.register(CommandKind.KICK_MEMBER, ejectHandler);
        plane.register(CommandKind.EJECT_PLAYER, ejectHandler);

        plane.start(pools.sched(), listen);
        this.controlPlane = plane;
    }

    /**
     * Keeps the cached policy fresh.
     *
     * <p>A poll rather than a push for now. Milestone 5 lands the control plane's
     * {@code INVALIDATE_CACHE} command, which makes a policy change take effect
     * across the network at once instead of within one refresh interval.
     */
    private void schedulePolicyRefresh(Database openedDatabase, PluginExecutors pools) {
        NetworkSettings settings = new NetworkSettings(openedDatabase);
        // The handle is not retained: PluginExecutors.shutdown stops the sched
        // pool during disable, which cancels this along with everything else.
        var _ = pools.sched()
                .scheduleWithFixedDelay(
                        () -> {
                            try {
                                settings.reload();
                                this.policy = settings.policy();
                            } catch (SQLException e) {
                                // Keep the last good policy. A database blip must not
                                // reset every cap to its default mid-session.
                                getLogger().warning(() -> "could not refresh network policy: " + e.getMessage());
                            } catch (RuntimeException e) {
                                getLogger()
                                        .warning(() -> "network policy is invalid; keeping the previous values: "
                                                + e.getMessage());
                            }
                        },
                        POLICY_REFRESH.toSeconds(),
                        POLICY_REFRESH.toSeconds(),
                        TimeUnit.SECONDS);
    }

    /** The selected platform seam, or {@code null} when enable refused. */
    public @Nullable Platform platform() {
        return platform;
    }

    /** Executor topology, or {@code null} when enable refused. */
    public @Nullable PluginExecutors executors() {
        return executors;
    }

    /** In-process meters, or {@code null} when enable refused. */
    public @Nullable WorldsMetrics metrics() {
        return metrics;
    }

    /** The snapshot commit engine (FR-15), or {@code null} when enable refused. */
    public @Nullable WorldCommitService commits() {
        return commitService;
    }

    /** Object store instance, or {@code null} when not configured. */
    public @Nullable ObjectStore objectStore() {
        return objectStore;
    }

    /** Worlds this node holds, or {@code null} when enable refused. */
    public @Nullable WorldRegistry registry() {
        return registry;
    }

    /** The control plane consumer, or {@code null} when enable refused. */
    public @Nullable ControlPlane controlPlane() {
        return controlPlane;
    }

    /** The node heartbeat publisher, or {@code null} when enable refused. */
    public @Nullable NodeHeartbeat heartbeat() {
        return nodeHeartbeat;
    }

    /** Network policy as last read from {@code network_setting}. */
    public NetworkPolicy policy() {
        return policy;
    }

    /**
     * Node identity for enable. Overridden in the MockBukkit smoke test because
     * MockBukkit's {@code UnsafeValuesMock#getDataVersion()} is hardcoded to
     * {@code 1}, which is below the D1 floor and would refuse every enable on the
     * mock server. Production always uses {@link ServerIdentity#detect()}.
     */
    protected ServerIdentity detectIdentity() {
        return ServerIdentity.detect();
    }

    /**
     * Where the server materialises world folders, and therefore where this
     * plugin's live worlds live (plan 01 §5.1).
     *
     * <p>Overridden in tests because MockBukkit's {@code ServerMock} does not
     * implement {@code getWorldContainer()}. Production always asks the server:
     * there is no other directory Bukkit will create a world in.
     */
    public @Nullable LeaseCoordinator leaseCoordinator() {
        return leaseCoordinator;
    }

    public @Nullable SelfFencingHandler fencingHandler() {
        return fencingHandler;
    }

    protected Path worldContainer() {
        return getServer().getWorldContainer().toPath();
    }

    @Override
    public void onDisable() {
        // Order matters and is FR-28's shape: finish world work while the pools
        // still accept it, then drain the pools, then close the database, then
        // drop the main-thread mark so a late callback cannot look like main.

        // FR-28 & MN-12: commit final snapshot and release lease for all loaded worlds synchronously
        WorldRegistry reg = this.registry;
        WorldCommitService commits = this.commitService;
        PluginExecutors pools = this.executors;
        Database db = this.database;
        NodeConfig cfg = this.nodeConfig;
        if (commits != null && reg != null) {
            List<LoadedWorld> loadedList = List.copyOf(reg.loadedWorlds());
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (LoadedWorld loaded : loadedList) {
                try {
                    futures.add(commits.requestCommit(loaded.id()));
                } catch (Exception e) {
                    getLogger()
                            .warning(() -> "could not request shutdown commit for world " + loaded.id() + ": "
                                    + e.getMessage());
                }
            }
            if (!futures.isEmpty()) {
                Duration commitTimeout = policy.commitTimeout();
                try {
                    CompletableFuture.allOf(futures.toArray(CompletableFuture<?>[]::new))
                            .get(commitTimeout.toMillis(), TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    getLogger().warning(() -> "shutdown commits timed out after " + commitTimeout);
                } catch (Exception e) {
                    getLogger().warning(() -> "shutdown commit failed: " + e.getMessage());
                }
            }

            // Cleanly release held leases in DB (MN-12)
            if (db != null && pools != null && cfg != null) {
                PlayerWorldRepository repo = new PlayerWorldRepository(db);
                for (LoadedWorld loaded : loadedList) {
                    try {
                        BoundedOperations.run(pools.db(), Duration.ofSeconds(2), () -> {
                            try {
                                repo.releaseLease(loaded.id(), cfg.nodeId(), loaded.generation());
                            } catch (SQLException e) {
                                getLogger()
                                        .warning(() -> "could not release lease for world " + loaded.id() + ": "
                                                + e.getMessage());
                            }
                        });
                    } catch (Exception e) {
                        getLogger().warning(() -> "timeout or error releasing lease for world " + loaded.id());
                    }
                }
            }
        }

        IdleUnloadTask sweep = this.idleUnload;
        this.idleUnload = null;
        if (sweep != null && MainThread.isMain()) {
            sweep.unloadAllForShutdown();
        }

        // MN-17: leave the registration cleanly, so the proxy stops routing here
        // immediately rather than after the node ages out of the alive set.
        NodeHeartbeat heartbeat = this.nodeHeartbeat;
        this.nodeHeartbeat = null;
        if (heartbeat != null && pools != null) {
            try {
                BoundedOperations.run(pools.db(), Duration.ofSeconds(2), heartbeat::deregister);
            } catch (Exception e) {
                getLogger().warning(() -> "could not deregister node heartbeat cleanly: " + e.getMessage());
            }
        }

        ControlPlane plane = this.controlPlane;
        this.controlPlane = null;
        if (plane != null) {
            plane.close();
        }

        ExecutorService listen = this.listenExecutor;
        this.listenExecutor = null;
        if (listen != null) {
            listen.shutdownNow();
        }

        getServer().getScheduler().cancelTasks(this);

        PrometheusEndpoint endpoint = this.metricsEndpoint;
        this.metricsEndpoint = null;
        if (endpoint != null) {
            endpoint.close();
        }
        WorldsMetrics worldsMetrics = this.metrics;
        this.metrics = null;
        if (worldsMetrics != null) {
            worldsMetrics.close();
        }

        ObjectStore store = this.objectStore;
        this.objectStore = null;
        if (store != null) {
            try {
                store.close();
            } catch (Exception e) {
                getLogger().warning(() -> "could not close object store cleanly: " + e.getMessage());
            }
        }

        this.executors = null;
        if (pools != null) {
            pools.shutdown(PluginExecutors.DEFAULT_SHUTDOWN_TIMEOUT);
        }
        closeDatabase();
        commitService = null;
        registry = null;
        platform = null;
        MainThread.clear();
        getLogger().info("disabled");
    }

    private void closeDatabase() {
        Database open = this.database;
        this.database = null;
        if (open != null) {
            open.close();
        }
    }

    private void disableSelf() {
        getServer().getPluginManager().disablePlugin(this);
    }
}
