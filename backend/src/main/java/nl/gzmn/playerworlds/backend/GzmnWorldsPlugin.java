package nl.gzmn.playerworlds.backend;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import nl.gzmn.playerworlds.backend.command.PworldCommand;
import nl.gzmn.playerworlds.backend.config.BackendConfig;
import nl.gzmn.playerworlds.backend.platform.Platform;
import nl.gzmn.playerworlds.backend.platform.ServerIdentity;
import nl.gzmn.playerworlds.backend.platform.UnsupportedPlatformException;
import nl.gzmn.playerworlds.backend.world.IdleUnloadTask;
import nl.gzmn.playerworlds.backend.world.PortalListener;
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
import nl.gzmn.playerworlds.core.db.Database;
import nl.gzmn.playerworlds.core.db.NetworkSettings;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository;
import nl.gzmn.playerworlds.core.db.Schema;
import nl.gzmn.playerworlds.core.obs.CapabilityProbe;
import nl.gzmn.playerworlds.core.obs.CapabilityReport;
import nl.gzmn.playerworlds.core.obs.MetricsSettings;
import nl.gzmn.playerworlds.core.obs.PrometheusEndpoint;
import nl.gzmn.playerworlds.core.obs.WorldsMetrics;
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
    private @Nullable WorldRegistry registry;
    private @Nullable IdleUnloadTask idleUnload;

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

        PluginExecutors pools = PluginExecutors.create(
                node.database().poolSize(),
                loadedPolicy.parallelTransfers(),
                task -> getServer().getScheduler().runTask(this, task));
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

        startWorldLifecycle(selected, openedDatabase, pools, worldsMetrics, node);
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

    /** Wires the milestone-1 world lifecycle and registers its listener, command and sweep. */
    private void startWorldLifecycle(
            Platform selected,
            Database openedDatabase,
            PluginExecutors pools,
            WorldsMetrics worldsMetrics,
            NodeConfig node) {
        WorldFolders worldFolders = new WorldFolders(selected.worldLayout());
        WorldRegistry worldRegistry = new WorldRegistry();
        this.registry = worldRegistry;
        PlayerWorldRepository worldRepository = new PlayerWorldRepository(openedDatabase);

        WorldLifecycleService lifecycle = new WorldLifecycleService(
                worldRepository,
                pools,
                selected,
                worldFolders,
                worldRegistry,
                worldsMetrics,
                this::policy,
                node.scratchPath());

        getServer()
                .getPluginManager()
                .registerEvents(
                        new PortalListener(selected, worldFolders, worldRegistry, lifecycle, this::policy), this);

        PluginCommand command = getCommand("pworld");
        if (command == null) {
            getLogger().severe("plugin.yml does not declare the pworld command; the operator surface is unavailable");
        } else {
            PworldCommand handler = new PworldCommand(lifecycle, worldRegistry, worldFolders, worldRepository, pools);
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

        IdleUnloadTask sweep =
                new IdleUnloadTask(worldRegistry, lifecycle, selected.worldLifecycle(), worldFolders, this::policy);
        this.idleUnload = sweep;
        long periodTicks = IdleUnloadTask.SWEEP_INTERVAL.toSeconds() * TICKS_PER_SECOND;
        getServer().getScheduler().runTaskTimer(this, sweep, periodTicks, periodTicks);
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

    /** Worlds this node holds, or {@code null} when enable refused. */
    public @Nullable WorldRegistry registry() {
        return registry;
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
    protected Path worldContainer() {
        return getServer().getWorldContainer().toPath();
    }

    @Override
    public void onDisable() {
        // Order matters and is FR-28's shape: finish world work while the pools
        // still accept it, then drain the pools, then close the database, then
        // drop the main-thread mark so a late callback cannot look like main.
        IdleUnloadTask sweep = this.idleUnload;
        this.idleUnload = null;
        if (sweep != null && MainThread.isMain()) {
            // The snapshot-commit half of FR-28 arrives with milestone 6. What
            // this does today is unload every world with save=true, so a planned
            // restart leaves the folders as a clean shutdown would rather than as
            // a crash would.
            sweep.unloadAllForShutdown();
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
        PluginExecutors pools = this.executors;
        this.executors = null;
        if (pools != null) {
            pools.shutdown(PluginExecutors.DEFAULT_SHUTDOWN_TIMEOUT);
        }
        closeDatabase();
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
