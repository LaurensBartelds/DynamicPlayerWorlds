package nl.gzmn.playerworlds.backend;

import nl.gzmn.playerworlds.backend.platform.Platform;
import nl.gzmn.playerworlds.backend.platform.ServerIdentity;
import nl.gzmn.playerworlds.backend.platform.UnsupportedPlatformException;
import nl.gzmn.playerworlds.core.concurrent.MainThread;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.db.DatabaseSettings;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/**
 * The Paper plugin entry point. Runs on every {@code worlds} node.
 *
 * <p>No gameplay behaviour yet: the foundation provides the seams that milestone
 * 1 onwards land in. Enable marks the main thread (NFR-2), selects the
 * Minecraft-version platform (plan section 5.2), opens the executor topology
 * (plan section 9), and refuses to start on a server older than this build
 * supports.
 */
public final class GzmnWorldsPlugin extends JavaPlugin {

    private @Nullable Platform platform;
    private @Nullable PluginExecutors executors;

    @Override
    public void onEnable() {
        // onEnable runs on the server main thread. Mark it before anything that
        // might touch JDBC so MainThread.assertOff can name the offender.
        MainThread.enter(Thread.currentThread());

        ServerIdentity identity = ServerIdentity.detect();
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
                        + pools.ioThreads());

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

    @Override
    public void onDisable() {
        // FR-28 world commits land here in a later milestone, before the pools
        // stop accepting work. Order: finish world work, then drain executors,
        // then drop the main-thread mark so a late callback cannot look like main.
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
