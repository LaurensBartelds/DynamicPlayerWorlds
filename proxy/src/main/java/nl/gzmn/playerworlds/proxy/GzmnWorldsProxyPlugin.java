package nl.gzmn.playerworlds.proxy;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
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
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.config.ConfigException;
import nl.gzmn.playerworlds.core.config.ConfigValidator;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.config.ProxyConfig;
import nl.gzmn.playerworlds.core.control.CommandKind;
import nl.gzmn.playerworlds.core.control.ControlPlane;
import nl.gzmn.playerworlds.core.db.Database;
import nl.gzmn.playerworlds.core.db.MembershipRepository;
import nl.gzmn.playerworlds.core.db.NetworkSettings;
import nl.gzmn.playerworlds.core.db.NodeCommandRepository;
import nl.gzmn.playerworlds.core.db.NodeRepository;
import nl.gzmn.playerworlds.core.db.PendingTransferRepository;
import nl.gzmn.playerworlds.core.db.PlayerNameRepository;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository;
import nl.gzmn.playerworlds.core.db.Schema;
import nl.gzmn.playerworlds.proxy.command.WorldCommand;
import nl.gzmn.playerworlds.proxy.config.ProxyConfigLoader;
import nl.gzmn.playerworlds.proxy.control.ProxyEjectHandler;
import nl.gzmn.playerworlds.proxy.node.NodeRegistry;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The Velocity plugin entry point. Owns the {@code /world} command root.
 *
 * <p>Commands live here rather than on a backend because a world is unloaded
 * most of the time (FR-25), so its owner is usually somewhere else on the
 * network, and because FR-6's invite notification has to reach a player who may
 * be on any server — something a backend node cannot do by itself.
 *
 * <p>Registering {@code /world} claims the whole namespace, so the two entries
 * specification section 6 keeps on the backend, {@code /world leave} and
 * {@code /world report}, are only reachable if this plugin forwards them. That
 * forwarding list is declared in {@link WorldCommand} and is empty until those
 * two exist (milestones 5 and 9). Resolves OQ-15.
 */
@Plugin(
        id = "gzmn-worlds-proxy",
        name = "gzmn-worlds-proxy",
        version = "0.1.0-SNAPSHOT",
        description = "Private per-player worlds for the GZMN network",
        authors = {"GZMN"})
public final class GzmnWorldsProxyPlugin {

    /** How long enable waits for the schema check and the first policy read. */
    private static final Duration BOOTSTRAP_TIMEOUT = Duration.ofSeconds(90);

    /** How often Velocity's server list is reconciled with the heartbeat table (MN-17). */
    private static final Duration NODE_SYNC = Duration.ofSeconds(15);

    /** How often the cached network policy is refreshed from {@code network_setting}. */
    private static final Duration POLICY_REFRESH = Duration.ofMinutes(1);

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private @Nullable Database database;
    private @Nullable PluginExecutors executors;
    private @Nullable PlayerNameRepository playerNames;
    private @Nullable NodeRegistry nodeRegistry;
    private @Nullable ControlPlane controlPlane;
    private @Nullable ExecutorService listenExecutor;

    /**
     * Last policy read from the database.
     *
     * <p>Cached for the same reason the backend caches it: a command path must
     * not turn a cap check into a query per keystroke (Q3 in plan 00), and
     * {@code volatile} is enough because the refresh publishes a whole immutable
     * record.
     */
    private volatile NetworkPolicy policy = NetworkPolicy.defaults();

    @Inject
    public GzmnWorldsProxyPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        final ProxyConfig config;
        try {
            config = ProxyConfigLoader.load(dataDirectory);
        } catch (ConfigException e) {
            // A proxy that came up without its database would accept players and
            // then fail every /world command, which is worse than not coming up.
            logger.error("invalid {}: {}; /world will not be registered", ProxyConfigLoader.FILE_NAME, e.getMessage());
            return;
        }

        Database openedDatabase = Database.open(config.database());
        this.database = openedDatabase;

        final NetworkPolicy loadedPolicy;
        try {
            loadedPolicy = bootstrapDatabase(openedDatabase);
        } catch (RuntimeException e) {
            logger.error("database bootstrap failed: {}; /world will not be registered", e.getMessage());
            closeDatabase();
            return;
        }
        this.policy = loadedPolicy;

        try {
            // The proxy has no scratch paths and no heartbeat, so only the policy
            // half of the section 8.2 checks applies to it.
            ConfigValidator.validatePolicy(loadedPolicy);
        } catch (ConfigException e) {
            logger.error("network policy refused: {}; /world will not be registered", e.getMessage());
            closeDatabase();
            return;
        }

        PluginExecutors pools = PluginExecutors.create(
                config.database().poolSize(),
                loadedPolicy.parallelTransfers(),
                task -> proxy.getScheduler().buildTask(this, task).schedule());
        this.executors = pools;
        schedulePolicyRefresh(openedDatabase, pools);
        this.playerNames = new PlayerNameRepository(openedDatabase);

        // MN-17: nodes register themselves; velocity.toml is never edited to add
        // capacity. The sweep mirrors the heartbeat table into Velocity's server
        // list and is idempotent, so running it on a timer is enough.
        NodeRegistry registry = new NodeRegistry(proxy, new NodeRepository(openedDatabase));
        this.nodeRegistry = registry;
        registry.sync(loadedPolicy.deadAfter());
        var _ = pools.sched()
                .scheduleWithFixedDelay(
                        () -> registry.sync(this.policy.deadAfter()),
                        NODE_SYNC.toSeconds(),
                        NODE_SYNC.toSeconds(),
                        TimeUnit.SECONDS);

        ExecutorService listen = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "gzmn-proxy-listen");
            thread.setDaemon(true);
            return thread;
        });
        this.listenExecutor = listen;

        NodeCommandRepository nodeCommands = new NodeCommandRepository(openedDatabase);
        ControlPlane proxyPlane = ControlPlane.forProxy(
                "proxy",
                config.database(),
                nodeCommands,
                loadedPolicy.controlPollInterval(),
                loadedPolicy.controlClaimTimeout());
        proxyPlane.register(CommandKind.EJECT_PLAYER, new ProxyEjectHandler(proxy, config::lobbyServer));
        proxyPlane.start(pools.sched(), listen);
        this.controlPlane = proxyPlane;

        WorldCommand command = new WorldCommand(
                proxy,
                pools,
                new PlayerWorldRepository(openedDatabase),
                new MembershipRepository(openedDatabase),
                this.playerNames,
                new PendingTransferRepository(openedDatabase),
                registry,
                nodeCommands,
                this::policy);
        // metaBuilder rather than register(BrigadierCommand): the single-argument
        // form is deprecated, and naming the owning plugin is what lets Velocity
        // attribute the command in /velocity dump and unregister it cleanly.
        com.velocitypowered.api.command.BrigadierCommand built = command.build();
        proxy.getCommandManager()
                .register(
                        proxy.getCommandManager()
                                .metaBuilder(built)
                                .plugin(this)
                                .build(),
                        built);

        logger.info(
                "enabled: lobby '{}', /world registered ({} subcommands), db threads {}",
                config.lobbyServer(),
                WorldCommand.SUBCOMMANDS.size(),
                pools.dbThreads());
    }

    /**
     * Checks the schema and reads network policy, off the event thread.
     *
     * <p>The proxy migrates too, under the same advisory lock every node uses.
     * Sharing the lock is what makes that safe, and the alternative — a proxy
     * that refuses to start until some node has booted at least once — is a
     * worse first-deployment story for no gain.
     */
    private NetworkPolicy bootstrapDatabase(Database openedDatabase) {
        ExecutorService bootstrap = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "gzmn-proxy-bootstrap");
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
                    "schema check and policy load did not finish within " + BOOTSTRAP_TIMEOUT, e);
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

    private void schedulePolicyRefresh(Database openedDatabase, PluginExecutors pools) {
        NetworkSettings settings = new NetworkSettings(openedDatabase);
        // Not retained: PluginExecutors.shutdown stops the sched pool on disable.
        var _ = pools.sched()
                .scheduleWithFixedDelay(
                        () -> {
                            try {
                                settings.reload();
                                this.policy = settings.policy();
                            } catch (SQLException e) {
                                // Keep the last good policy: a database blip must not
                                // reset every cap to its default mid-session.
                                logger.warn("could not refresh network policy: {}", e.getMessage());
                            } catch (RuntimeException e) {
                                logger.warn(
                                        "network policy is invalid; keeping the previous values: {}", e.getMessage());
                            }
                        },
                        POLICY_REFRESH.toSeconds(),
                        POLICY_REFRESH.toSeconds(),
                        TimeUnit.SECONDS);
    }

    /**
     * Fills the {@code player_name} cache (V2).
     *
     * <p>The proxy is the only component that sees every login on the network,
     * which is what makes it the right place to learn the name-to-UUID mapping
     * every section 6 command needs for its first argument.
     */
    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        PlayerNameRepository repository = this.playerNames;
        PluginExecutors pools = this.executors;
        if (repository == null || pools == null) {
            return;
        }
        pools.db().execute(() -> {
            try {
                repository.remember(
                        event.getPlayer().getUniqueId(), event.getPlayer().getUsername());
            } catch (SQLException e) {
                // A cache miss degrades a display name to a UUID; it never fails
                // an operation, so this is a warning and not a disconnect.
                logger.warn(
                        "could not cache the name of {}: {}", event.getPlayer().getUsername(), e.getMessage());
            }
        });
    }

    /** Network policy as last read from {@code network_setting}. */
    public NetworkPolicy policy() {
        return policy;
    }

    public @Nullable ControlPlane controlPlane() {
        return controlPlane;
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
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
        NodeRegistry registry = this.nodeRegistry;
        this.nodeRegistry = null;
        if (registry != null) {
            registry.unregisterAll();
        }
        PluginExecutors pools = this.executors;
        this.executors = null;
        if (pools != null) {
            pools.shutdown(PluginExecutors.DEFAULT_SHUTDOWN_TIMEOUT);
        }
        closeDatabase();
        logger.info("disabled");
    }

    private void closeDatabase() {
        Database open = this.database;
        this.database = null;
        if (open != null) {
            open.close();
        }
    }
}
