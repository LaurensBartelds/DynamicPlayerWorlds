package nl.gzmn.playerworlds.backend;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import nl.gzmn.playerworlds.backend.platform.Platform;
import nl.gzmn.playerworlds.backend.platform.ServerIdentity;
import nl.gzmn.playerworlds.backend.platform.UnsupportedPlatformException;
import nl.gzmn.playerworlds.core.concurrent.MainThread;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.db.DatabaseSettings;
import nl.gzmn.playerworlds.core.obs.CapabilityProbe;
import nl.gzmn.playerworlds.core.obs.CapabilityReport;
import nl.gzmn.playerworlds.core.obs.MetricsSettings;
import nl.gzmn.playerworlds.core.obs.PrometheusEndpoint;
import nl.gzmn.playerworlds.core.obs.WorldsMetrics;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/**
 * The Paper plugin entry point. Runs on every {@code worlds} node.
 *
 * <p>No gameplay behaviour yet: the foundation provides the seams that milestone
 * 1 onwards land in. Enable marks the main thread (NFR-2), selects the
 * Minecraft-version platform (plan section 5.2), opens the executor topology
 * (plan section 9), runs the capability probe including the reflink verdict
 * (plan section 10.4), and opens the Prometheus scrape endpoint (plan section
 * 10.2).
 *
 * <p>Not {@code final}: MockBukkit's plugin loader subclasses the main class
 * (plan section 11). Everything else that should stay sealed does so behind
 * package-private helpers.
 */
public class GzmnWorldsPlugin extends JavaPlugin {

    private @Nullable Platform platform;
    private @Nullable PluginExecutors executors;
    private @Nullable WorldsMetrics metrics;
    private @Nullable PrometheusEndpoint metricsEndpoint;

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
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.platform = selected;

        // Pool sizes will come from NodeConfig / NetworkPolicy once config is
        // loaded at enable; until then the specification defaults keep the
        // topology correctly shaped so milestone code has somewhere to submit.
        PluginExecutors pools = PluginExecutors.create(
                DatabaseSettings.DEFAULT_POOL_SIZE,
                NetworkPolicy.defaults().parallelTransfers(),
                task -> getServer().getScheduler().runTask(this, task));
        this.executors = pools;

        WorldsMetrics worldsMetrics = WorldsMetrics.create();
        this.metrics = worldsMetrics;

        // Capability probe before advertising healthy. Scratch path will come
        // from NodeConfig once config load is wired; the plugin data folder is
        // on the same volume operators care about for a first boot verdict.
        Path probeRoot = getDataFolder().toPath();
        try {
            Files.createDirectories(probeRoot);
        } catch (IOException e) {
            getLogger().severe(() -> "could not create plugin data folder: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        CapabilityReport report = CapabilityProbe.run(CapabilityProbe.Request.filesystemOnly(probeRoot, 0)
                .withMinecraft(identity.minecraftVersion(), identity.dataVersion()));
        CapabilityProbe.log(report);
        worldsMetrics.setScratchFreeBytes(Math.max(0L, report.freeBytes()));
        if (!report.safeToEnable()) {
            getLogger().severe("capability probe failed; refusing enable");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Metrics bind comes from config later; defaults are loopback:9464 so a
        // scrape is available on a booted node without opening a public port.
        try {
            this.metricsEndpoint = PrometheusEndpoint.start(worldsMetrics, MetricsSettings.defaults());
        } catch (IOException e) {
            getLogger()
                    .warning(() -> "could not bind prometheus endpoint on "
                            + MetricsSettings.DEFAULT_BIND
                            + ":"
                            + MetricsSettings.DEFAULT_PORT
                            + " ("
                            + e.getMessage()
                            + "); meters remain in-process only");
        }

        // Concatenation rather than a format string: %d formats through the
        // default locale, which forbidden-apis bans and which would render the
        // data version in non-ASCII digits on some hosts. The one number an
        // operator reads out of this line has to be readable everywhere.
        getLogger()
                .info(() -> "enabled: minecraft "
                        + identity.minecraftVersion()
                        + ", data version "
                        + identity.dataVersion()
                        + ", world layout "
                        + selected.worldLayout().id()
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
     * The selected platform seam, or {@code null} when enable refused.
     * Milestone code takes dependencies from here rather than constructing
     * adapters itself.
     */
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

    /**
     * Node identity for enable. Overridden in the MockBukkit smoke test because
     * MockBukkit's {@code UnsafeValuesMock#getDataVersion()} is hardcoded to
     * {@code 1}, which is below the D1 floor and would refuse every enable on the
     * mock server. Production always uses {@link ServerIdentity#detect()}.
     */
    protected ServerIdentity detectIdentity() {
        return ServerIdentity.detect();
    }

    @Override
    public void onDisable() {
        // FR-28 world commits land here in a later milestone, before the pools
        // stop accepting work. Order: finish world work, then drain executors,
        // then drop the main-thread mark so a late callback cannot look like main.
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
        platform = null;
        MainThread.clear();
        getLogger().info("disabled");
    }
}
