package nl.gzmn.playerworlds.backend;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import nl.gzmn.playerworlds.backend.command.BackendWorldCommand;
import nl.gzmn.playerworlds.backend.command.PworldCommand;
import nl.gzmn.playerworlds.backend.config.BackendConfig;
import nl.gzmn.playerworlds.backend.control.ApplySettingsHandler;
import nl.gzmn.playerworlds.backend.control.BackendControlHandlers;
import nl.gzmn.playerworlds.backend.control.DrainNodeHandler;
import nl.gzmn.playerworlds.backend.control.EjectPlayerHandler;
import nl.gzmn.playerworlds.backend.control.InvalidateCacheHandler;
import nl.gzmn.playerworlds.backend.control.MigrateWorldHandler;
import nl.gzmn.playerworlds.backend.control.NodeShutdown;
import nl.gzmn.playerworlds.backend.control.UnloadWorldHandler;
import nl.gzmn.playerworlds.backend.control.WorldHandoff;
import nl.gzmn.playerworlds.backend.gui.MenuChannel;
import nl.gzmn.playerworlds.backend.gui.MenuListener;
import nl.gzmn.playerworlds.backend.gui.MenuService;
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
import nl.gzmn.playerworlds.backend.storage.ArchiveStorage;
import nl.gzmn.playerworlds.backend.storage.MaintenanceTask;
import nl.gzmn.playerworlds.backend.storage.PeriodicSyncTask;
import nl.gzmn.playerworlds.backend.storage.WorldArchiver;
import nl.gzmn.playerworlds.backend.storage.WorldEraser;
import nl.gzmn.playerworlds.backend.storage.WorldRestorer;
import nl.gzmn.playerworlds.backend.world.CommandGuardListener;
import nl.gzmn.playerworlds.backend.world.GroupChatBuffer;
import nl.gzmn.playerworlds.backend.world.IdleUnloadTask;
import nl.gzmn.playerworlds.backend.world.MembershipCache;
import nl.gzmn.playerworlds.backend.world.PortalListener;
import nl.gzmn.playerworlds.backend.world.RoleEnforcementListener;
import nl.gzmn.playerworlds.backend.world.VisibilityGroups;
import nl.gzmn.playerworlds.backend.world.VisibilityListener;
import nl.gzmn.playerworlds.backend.world.WorldCacheLoader;
import nl.gzmn.playerworlds.backend.world.WorldFolders;
import nl.gzmn.playerworlds.backend.world.WorldLifecycleService;
import nl.gzmn.playerworlds.backend.world.WorldRegistry;
import nl.gzmn.playerworlds.backend.world.WorldSettingsCache;
import nl.gzmn.playerworlds.core.concurrent.BoundedOperations;
import nl.gzmn.playerworlds.core.concurrent.DrainableMainScheduler;
import nl.gzmn.playerworlds.core.concurrent.MainThread;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.config.ConfigException;
import nl.gzmn.playerworlds.core.config.ConfigValidator;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.config.NodeConfig;
import nl.gzmn.playerworlds.core.config.NodeMode;
import nl.gzmn.playerworlds.core.control.CommandKind;
import nl.gzmn.playerworlds.core.control.ControlPlane;
import nl.gzmn.playerworlds.core.db.ArchiveRepository;
import nl.gzmn.playerworlds.core.db.Database;
import nl.gzmn.playerworlds.core.db.MembershipRepository;
import nl.gzmn.playerworlds.core.db.NetworkSettings;
import nl.gzmn.playerworlds.core.db.NodeCommandRepository;
import nl.gzmn.playerworlds.core.db.NodeRepository;
import nl.gzmn.playerworlds.core.db.PendingTransferRepository;
import nl.gzmn.playerworlds.core.db.PlayerNameRepository;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository;
import nl.gzmn.playerworlds.core.db.ProfileRepository;
import nl.gzmn.playerworlds.core.db.ReportRepository;
import nl.gzmn.playerworlds.core.db.Schema;
import nl.gzmn.playerworlds.core.db.TransferRequestRepository;
import nl.gzmn.playerworlds.core.db.WorldBanRepository;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.WorldId;
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

    /**
     * What FR-28's shutdown budget adds to {@code storage.commit-timeout-seconds}
     * for the unload and the lease release that follow the commit. Both are short
     * and neither touches object storage; this is slack, not a target.
     */
    private static final Duration SHUTDOWN_UNLOAD_MARGIN = Duration.ofSeconds(5);

    /**
     * How long enable waits for the MN-13 startup sweep. It walks the scratch
     * volume, so it scales with how many worlds this node was holding, and it has
     * to finish before anything can load.
     */
    private static final Duration STARTUP_SWEEP_TIMEOUT = Duration.ofSeconds(60);

    private @Nullable Platform platform;
    private @Nullable PluginExecutors executors;
    private @Nullable DrainableMainScheduler mainScheduler;
    private @Nullable WorldsMetrics metrics;
    private @Nullable PrometheusEndpoint metricsEndpoint;
    private @Nullable Database database;
    private @Nullable ObjectStore objectStore;
    private @Nullable WorldRegistry registry;
    private @Nullable WorldCommitService commitService;
    /** FR-28's shutdown drives the same give-up sequence as the control plane. */
    private @Nullable WorldHandoff worldHandoff;

    private @Nullable NodeHeartbeat nodeHeartbeat;
    private @Nullable LeaseCoordinator leaseCoordinator;
    private @Nullable SelfFencingHandler fencingHandler;
    private @Nullable ControlPlane controlPlane;
    private @Nullable ExecutorService listenExecutor;
    private @Nullable WorldArchiver archiver;
    private @Nullable WorldRestorer restorer;
    private @Nullable MenuChannel menuChannel;
    private @Nullable MenuService menuService;
    private @Nullable MenuListener menuListener;

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

        // Drainable, because Paper marks a plugin disabled before calling
        // onDisable and its scheduler then refuses tasks: without this, nothing
        // inside onDisable can hop back to the tick thread and FR-28's shutdown
        // cannot use the ordinary give-up path (R14).
        DrainableMainScheduler mainThread = new DrainableMainScheduler(getServer()::isPrimaryThread, task -> {
            getServer().getScheduler().runTask(this, task);
        });
        this.mainScheduler = mainThread;

        PluginExecutors pools =
                PluginExecutors.create(node.database().poolSize(), loadedPolicy.parallelTransfers(), mainThread);
        this.executors = pools;

        schedulePolicyRefresh(openedDatabase, pools);

        PlayerWorldRepository menuWorldRepo = new PlayerWorldRepository(openedDatabase);
        MembershipRepository menuMembershipRepo = new MembershipRepository(openedDatabase);
        TransferRequestRepository menuTransferRepo = new TransferRequestRepository(openedDatabase);
        WorldBanRepository menuBanRepo = new WorldBanRepository(openedDatabase);
        PlayerNameRepository menuNameRepo = new PlayerNameRepository(openedDatabase);

        MenuChannel channel = new MenuChannel(this, pools);
        MenuService service = new MenuService(
                menuWorldRepo,
                menuMembershipRepo,
                menuTransferRepo,
                menuBanRepo,
                menuNameRepo,
                channel,
                pools,
                this::policy);
        channel.setMenuService(service);
        channel.register();
        this.menuChannel = channel;
        this.menuService = service;

        MenuListener listener = new MenuListener(service, channel);
        this.menuListener = listener;
        getServer().getPluginManager().registerEvents(listener, this);

        if (node.mode() == NodeMode.GUI_ONLY) {
            getLogger()
                    .info(
                            () -> "enabled (gui-only mode): node "
                                    + node.nodeId()
                                    + ", database connected, menu infrastructure active, world lifecycle and heartbeat suppressed");
            return;
        }

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

        startWorldLifecycle(selected, openedDatabase, pools, worldsMetrics, node, report);

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

    /**
     * MN-13 and MN-5a: quarantines crash debris and deletes leftover snapshot
     * directories, before any world can load.
     *
     * <p>Off the tick thread, because it reads the lease table and walks the
     * scratch volume, and enable waits for it: a world that loads first would be
     * swept out from under itself.
     *
     * <p>Skipped entirely on a node with no object storage. There, quarantine has
     * nothing to restore from — moving a world's directory aside <em>is</em>
     * losing the world, which turns MN-13's recoverable fault into an
     * unrecoverable one and is the opposite of what it is for.
     */
    private void sweepStartupScratch(
            Database openedDatabase,
            PluginExecutors pools,
            NodeConfig node,
            WorldFolders worldFolders,
            String primaryLevelName,
            boolean hasObjectStorage) {
        if (!hasObjectStorage) {
            getLogger()
                    .info("no object storage configured; skipping the MN-13 startup sweep, which would move the "
                            + "only copy of every world into quarantine");
            return;
        }
        try {
            var _ = pools.db()
                    .submit(() -> {
                        PlayerWorldRepository sweepWorlds = new PlayerWorldRepository(openedDatabase);
                        var quarantined = QuarantineManager.sweepStartup(new QuarantineManager.StartupSweep(
                                node.scratchPath(),
                                worldFolders.dimensionsRoot(node.scratchPath(), primaryLevelName),
                                node.quarantinePath(),
                                worldFolders::worldIdOf,
                                Set.copyOf(sweepWorlds.worldsLeasedTo(node.nodeId())),
                                id -> currentManifestKey(sweepWorlds, id),
                                UUID.randomUUID().toString()));
                        if (!quarantined.isEmpty()) {
                            getLogger()
                                    .warning(() -> "startup sweep quarantined " + quarantined.size()
                                            + " directory/directories as crash debris (MN-13)");
                        }
                        return null;
                    })
                    .get(STARTUP_SWEEP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            getLogger().warning("interrupted during the startup quarantine sweep");
        } catch (Exception e) {
            getLogger().warning(() -> "could not complete startup quarantine sweep: " + e.getMessage());
        }
    }

    /**
     * A world's current {@code manifest_key}, or empty when it has none or the row
     * is gone.
     *
     * <p>A read that fails also answers empty, which quarantines. Losing a warm
     * copy costs one cold load; keeping a directory nothing could vouch for costs
     * whatever diverged in it.
     */
    private Optional<String> currentManifestKey(PlayerWorldRepository worlds, WorldId worldId) {
        try {
            return worlds.findById(worldId).map(PlayerWorld::manifestKey);
        } catch (SQLException e) {
            getLogger()
                    .warning(() -> "could not read manifest_key for " + worldId
                            + " during the startup sweep; treating its scratch copy as debris");
            return Optional.empty();
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
        // Paper 26 nests every Bukkit world under the primary save (level-name).
        String primaryLevelName = primaryLevelName();

        sweepStartupScratch(openedDatabase, pools, node, worldFolders, primaryLevelName, store != null);

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
                node.nodeId(),
                primaryLevelName);
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
                primaryLevelName,
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
        WorldSettingsCache settingsCache = new WorldSettingsCache();
        // The fill half of both caches (FR-9, FR-9e, FR-31a). INVALIDATE_CACHE
        // used to evict only, and a miss in these two is a wrong answer rather
        // than an absence.
        WorldCacheLoader worldCaches =
                new WorldCacheLoader(worldRepository, membershipRepository, membershipCache, settingsCache);
        GroupChatBuffer chatBuffer = new GroupChatBuffer();
        PendingTransferRepository transferRepository = new PendingTransferRepository(openedDatabase);
        NodeCommandRepository nodeCommands = new NodeCommandRepository(openedDatabase);

        WorldLifecycleService lifecycle = new WorldLifecycleService(
                worldRepository,
                membershipRepository,
                membershipCache,
                settingsCache,
                pools,
                selected,
                worldFolders,
                worldRegistry,
                worldsMetrics,
                this::policy,
                node.scratchPath(),
                primaryLevelName,
                node.nodeId(),
                worldDownloader,
                store,
                worldCommitService,
                objectCache);

        // One instance, shared: FR-18's grouping and FR-22's allow-list must
        // agree about who is in which group, and two copies of the rule are two
        // chances for them to disagree.
        VisibilityGroups visibilityGroups = new VisibilityGroups(worldFolders);

        getServer()
                .getPluginManager()
                .registerEvents(
                        new PortalListener(selected, worldFolders, worldRegistry, lifecycle, this::policy), this);
        // FR-9 in world: OWNER and BUILDER build, VISITOR does not.
        getServer()
                .getPluginManager()
                .registerEvents(new RoleEnforcementListener(worldFolders, membershipCache, settingsCache), this);
        // FR-18/19/20: Visibility and group chat buffer
        getServer().getPluginManager().registerEvents(new VisibilityListener(this, visibilityGroups, chatBuffer), this);
        // FR-21/FR-22: the command allow-list inside a player world. Without this
        // registration the class is dead code and vanilla /list and /tell leak
        // presence between two worlds on one node, which is the whole of §5.5.
        getServer().getPluginManager().registerEvents(new CommandGuardListener(visibilityGroups, this::policy), this);
        // FR-11: routed join listener. R13: worldsMetrics so the holding-area
        // deadline moves holding_timeouts_total when it fires.
        TransferJoinListener transferListener = new TransferJoinListener(
                node, transferRepository, lifecycle, worldFolders, pools, nodeCommands, this::policy, worldsMetrics);
        getServer().getPluginManager().registerEvents(transferListener, this);

        getServer()
                .getScheduler()
                .runTaskTimer(
                        this,
                        () -> {
                            for (org.bukkit.entity.Player player : getServer().getOnlinePlayers()) {
                                if (!worldFolders.isPlayerWorld(
                                        player.getWorld().getName())) {
                                    transferListener.processPlayer(player);
                                }
                            }
                        },
                        10L,
                        10L);
        // FR-15: profile commit triggers & manifest snapshot restores.
        // R11: nodeCommands + policy so FR-16 refusals eject via EJECT_PLAYER.
        getServer()
                .getPluginManager()
                .registerEvents(
                        new ProfileListener(
                                worldFolders,
                                profileService,
                                profileRepository,
                                worldRepository,
                                worldCommitService,
                                pools,
                                nodeCommands,
                                this::policy),
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

        BackendWorldCommand backendWorldHandler = new BackendWorldCommand(
                worldFolders,
                new ReportRepository(openedDatabase),
                new nl.gzmn.playerworlds.core.db.PlayerNameRepository(openedDatabase),
                chatBuffer,
                nodeCommands,
                pools,
                this::policy);
        getServer().getCommandMap().register("gzmn-worlds", new org.bukkit.command.Command("world") {
            @Override
            public boolean execute(org.bukkit.command.CommandSender sender, String commandLabel, String[] args) {
                return backendWorldHandler.onCommand(sender, this, commandLabel, args);
            }

            @Override
            public List<String> tabComplete(org.bukkit.command.CommandSender sender, String alias, String[] args) {
                return backendWorldHandler.onTabComplete(sender, this, alias, args);
            }
        });
        getLogger().info("/world (backend) registered dynamically for leave and report");

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
        long periodTicks = IdleUnloadTask.SWEEP_INTERVAL.toSeconds() * TICKS_PER_SECOND;
        getServer().getScheduler().runTaskTimer(this, sweep, periodTicks, periodTicks);

        // MN-6: schedule periodic incremental snapshot commits
        PeriodicSyncTask syncTask = new PeriodicSyncTask(worldRegistry, worldCommitService, this::policy);
        long syncIntervalSeconds = Math.max(1, this.policy.syncInterval().toSeconds());
        var _ = pools.sched()
                .scheduleWithFixedDelay(syncTask, syncIntervalSeconds, syncIntervalSeconds, TimeUnit.SECONDS);

        // FR-40: inactivity archival and recovery of interrupted archival and restore. Every
        // node schedules it; the advisory lock inside decides which one actually sweeps.
        MaintenanceTask maintenance = new MaintenanceTask(
                openedDatabase,
                new PlayerWorldRepository(openedDatabase),
                new TransferRequestRepository(openedDatabase),
                new NodeCommandRepository(openedDatabase),
                this::policy,
                node.nodeId());
        long maintenanceSeconds = Math.max(1, this.policy.maintenanceInterval().toSeconds());
        var _ = pools.sched()
                .scheduleWithFixedDelay(maintenance, maintenanceSeconds, maintenanceSeconds, TimeUnit.SECONDS);

        // Before the control plane, because DRAIN_NODE acts on the heartbeat: a
        // drain that could not set the draining flag would take the node's worlds
        // away and then let placement send it more.
        NodeHeartbeat heartbeat = startHeartbeat(openedDatabase, pools, node, selected.identity(), worldRegistry);
        startControlPlane(
                worldRegistry,
                lifecycle,
                worldFolders,
                worldCaches,
                settingsCache,
                openedDatabase,
                nodeCommands,
                pools,
                node,
                this.policy,
                worldCommitService,
                heartbeat,
                snapshotEngine,
                store,
                selected.worldLayout(),
                primaryLevelName,
                selected.identity());
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
            WorldCacheLoader worldCaches,
            WorldSettingsCache settingsCache,
            Database openedDatabase,
            NodeCommandRepository nodeCommands,
            PluginExecutors pools,
            NodeConfig node,
            NetworkPolicy loadedPolicy,
            WorldCommitService commits,
            NodeHeartbeat heartbeat,
            @Nullable SnapshotEngine snapshotEngine,
            @Nullable ObjectStore store,
            nl.gzmn.playerworlds.backend.platform.WorldLayout worldLayout,
            String primaryLevelName,
            ServerIdentity identity) {
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
        this.worldHandoff = handoff;

        plane.register(CommandKind.UNLOAD_WORLD, new UnloadWorldHandler(handoff, this::policy));
        plane.register(CommandKind.MIGRATE_WORLD, new MigrateWorldHandler(handoff, this::policy));
        plane.register(CommandKind.DRAIN_NODE, new DrainNodeHandler(worldRegistry, handoff, heartbeat, this::policy));
        plane.register(
                CommandKind.INVALIDATE_CACHE,
                new InvalidateCacheHandler(
                        new NetworkSettings(openedDatabase), worldCaches, worldRegistry, pools.db()));
        plane.register(
                CommandKind.APPLY_SETTINGS,
                new ApplySettingsHandler(worldCaches, settingsCache, worldRegistry, worldFolders, platform, pools));
        EjectPlayerHandler ejectHandler =
                new EjectPlayerHandler(worldCaches, worldFolders, pools, nodeCommands, this::policy);
        plane.register(CommandKind.KICK_MEMBER, ejectHandler);
        plane.register(CommandKind.EJECT_PLAYER, ejectHandler);

        ArchiveStorage archiveStorage = store != null
                ? ArchiveStorage.s3(store)
                : ArchiveStorage.filesystem(node.scratchPath().resolve("archives"));
        WorldArchiver worldArchiver = new WorldArchiver(
                new PlayerWorldRepository(openedDatabase),
                openedDatabase,
                archiveStorage,
                node.scratchPath(),
                worldLayout,
                primaryLevelName,
                store,
                worldRegistry,
                handoff,
                this::policy,
                node.nodeId(),
                identity.dataVersion());
        WorldRestorer worldRestorer = new WorldRestorer(
                new PlayerWorldRepository(openedDatabase),
                new ProfileRepository(openedDatabase),
                new ArchiveRepository(openedDatabase),
                archiveStorage,
                snapshotEngine,
                store,
                node.scratchPath(),
                worldFolders,
                this::policy,
                node.nodeId(),
                identity.dataVersion(),
                identity.minecraftVersion());
        // FR-37: hard deletion runs here rather than on the proxy, because the
        // archive objects and the world's snapshot prefix are only reachable from
        // a node (R23).
        WorldEraser worldEraser = new WorldEraser(
                new PlayerWorldRepository(openedDatabase),
                new ArchiveRepository(openedDatabase),
                archiveStorage,
                store);
        this.archiver = worldArchiver;
        this.restorer = worldRestorer;
        BackendControlHandlers.registerStorageHandlers(plane, worldArchiver, worldRestorer, worldEraser);

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

    /** World archiver service, or {@code null} when enable refused. */
    public @Nullable WorldArchiver archiver() {
        return archiver;
    }

    /** World restorer service, or {@code null} when enable refused. */
    public @Nullable WorldRestorer restorer() {
        return restorer;
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
     * Primary save name ({@code level-name} / first loaded world). Paper 26 nests
     * every Bukkit world under {@code <this>/dimensions/minecraft/<name>/}.
     *
     * <p>Overridden in tests that do not load a default world. Production asks
     * the server for its first world, falling back to {@code world}.
     */
    protected String primaryLevelName() {
        var loaded = getServer().getWorlds();
        if (!loaded.isEmpty()) {
            return loaded.getFirst().getName();
        }
        return "world";
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

    public @Nullable MenuService menuService() {
        return menuService;
    }

    public @Nullable MenuChannel menuChannel() {
        return menuChannel;
    }

    public @Nullable MenuListener menuListener() {
        return menuListener;
    }

    protected Path worldContainer() {
        return getServer().getWorldContainer().toPath();
    }

    @Override
    public void onDisable() {
        // Order matters and is FR-28's shape: finish world work while the pools
        // still accept it, then drain the pools, then close the database, then
        // drop the main-thread mark so a late callback cannot look like main.

        // Paper has already marked this plugin disabled, so its scheduler will not
        // take a task any more. From here the main thread runs its own queued work
        // (R14) — without which nothing below that hops to main can complete.
        DrainableMainScheduler mainThread = this.mainScheduler;
        if (mainThread != null) {
            mainThread.beginShutdown();
        }

        // FR-28, FR-25, MN-12: commit, unload, release — in that order, and by the
        // same handoff the control plane uses, so there is one implementation of
        // the order rather than two that disagree about it.
        WorldRegistry reg = this.registry;
        WorldHandoff handoff = this.worldHandoff;
        this.worldHandoff = null;
        PluginExecutors pools = this.executors;
        if (handoff != null && reg != null && mainThread != null && MainThread.isMain()) {
            Duration budget = policy.commitTimeout().plus(SHUTDOWN_UNLOAD_MARGIN);
            try {
                new NodeShutdown(reg, handoff, mainThread).releaseAll(budget);
            } catch (RuntimeException e) {
                getLogger().warning(() -> "shutdown handoff failed: " + e.getMessage());
            }
        } else if (reg != null && !reg.loadedWorlds().isEmpty()) {
            getLogger()
                    .warning(() -> "disabling with " + reg.loadedWorlds().size()
                            + " world(s) still loaded and no handoff available; their leases expire on their own"
                            + " (MN-12)");
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

        MenuChannel ch = this.menuChannel;
        this.menuChannel = null;
        if (ch != null) {
            ch.unregister();
        }
        this.menuService = null;
        this.menuListener = null;

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
