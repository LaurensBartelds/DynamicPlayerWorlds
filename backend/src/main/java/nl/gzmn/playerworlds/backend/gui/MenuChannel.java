package nl.gzmn.playerworlds.backend.gui;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.menu.FailureCode;
import nl.gzmn.playerworlds.core.menu.MenuChannels;
import nl.gzmn.playerworlds.core.menu.MenuCodec;
import nl.gzmn.playerworlds.core.menu.MenuIntent;
import nl.gzmn.playerworlds.core.menu.MenuResult;
import nl.gzmn.playerworlds.core.menu.OpenMenu;
import nl.gzmn.playerworlds.core.menu.WorldPresenceNotice;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Backend plugin message channel listener and sender for the menu protocol (FR-27, FR-30a).
 *
 * <p>Transmits {@link MenuIntent}s with monotonic correlation IDs to the proxy, correlates
 * incoming {@link MenuResult}s with timeouts, and dispatches {@link OpenMenu} triggers to
 * {@link MenuService}.
 */
public final class MenuChannel implements PluginMessageListener {

    private static final Logger log = LoggerFactory.getLogger(MenuChannel.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private final Plugin plugin;
    private final PluginExecutors executors;
    private final Duration timeout;
    private final AtomicLong nextCorrelationId = new AtomicLong(1L);
    private final ConcurrentMap<Long, CompletableFuture<MenuResult>> pending = new ConcurrentHashMap<>();
    private @Nullable MenuService menuService;

    public MenuChannel(Plugin plugin, PluginExecutors executors) {
        this(plugin, executors, null, DEFAULT_TIMEOUT);
    }

    public MenuChannel(Plugin plugin, PluginExecutors executors, @Nullable MenuService menuService) {
        this(plugin, executors, menuService, DEFAULT_TIMEOUT);
    }

    public MenuChannel(Plugin plugin, PluginExecutors executors, @Nullable MenuService menuService, Duration timeout) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.executors = Objects.requireNonNull(executors, "executors");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.menuService = menuService;
    }

    /** Sets the {@link MenuService} instance used to open menus upon receiving proxy triggers. */
    public void setMenuService(MenuService menuService) {
        this.menuService = Objects.requireNonNull(menuService, "menuService");
    }

    /**
     * Sends a {@link MenuIntent} for the player to the proxy and returns a future completing with the result.
     *
     * @param player the acting player
     * @param intent the intent to execute
     * @return CompletableFuture completing when the proxy responds or when the request times out
     */
    public CompletableFuture<MenuResult> sendIntent(Player player, MenuIntent intent) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(intent, "intent");

        long correlationId = nextCorrelationId.getAndIncrement();
        CompletableFuture<MenuResult> future = new CompletableFuture<>();
        pending.put(correlationId, future);

        ScheduledFuture<?> timeoutTask = executors
                .sched()
                .schedule(
                        () -> {
                            CompletableFuture<MenuResult> removed = pending.remove(correlationId);
                            if (removed != null) {
                                removed.complete(
                                        new MenuResult.Failed(correlationId, FailureCode.TIMEOUT, "Request timed out"));
                            }
                        },
                        timeout.toMillis(),
                        TimeUnit.MILLISECONDS);

        var _ = future.whenComplete((res, ex) -> timeoutTask.cancel(false));

        byte[] bytes = MenuCodec.encodeIntent(correlationId, intent);
        player.sendPluginMessage(plugin, MenuChannels.CHANNEL_NAME, bytes);
        return future;
    }

    /**
     * Tells the proxy which player world this player is standing in (FR-6).
     *
     * <p>Fire and forget: there is no reply and nothing to correlate. The proxy
     * routes every entry into a world, but a move <em>between</em> worlds on this
     * node is invisible to it, and section 6's owner commands all run there.
     *
     * @param player the player whose location changed
     * @param worldId the world they are now in, or {@code null} for anywhere that
     *     is not a player world
     */
    public void sendPresence(Player player, @Nullable WorldId worldId) {
        Objects.requireNonNull(player, "player");
        player.sendPluginMessage(
                plugin, MenuChannels.CHANNEL_NAME, MenuCodec.encodePresence(new WorldPresenceNotice(worldId)));
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!MenuChannels.CHANNEL_NAME.equals(channel) || message.length == 0) {
            return;
        }

        final Object decoded;
        try {
            decoded = MenuCodec.decode(message);
        } catch (Exception e) {
            log.warn(
                    "Failed to decode menu plugin message on {} from {}: {}",
                    channel,
                    player.getName(),
                    e.getMessage());
            return;
        }

        if (decoded instanceof OpenMenu) {
            MenuService service = this.menuService;
            if (service != null) {
                var _ = service.openMainMenu(player);
            } else {
                log.warn("Received OpenMenu for player {} but MenuService is not set", player.getName());
            }
        } else if (decoded instanceof MenuResult result) {
            CompletableFuture<MenuResult> future = pending.remove(result.correlationId());
            if (future != null) {
                future.complete(result);
            }
        }
    }

    /** Cleans up state when a player disconnects from this backend. */
    public void handleQuit(Player player) {
        // Player quit handling
    }

    /** Registers plugin messaging incoming and outgoing channels with Bukkit. */
    public void register() {
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, MenuChannels.CHANNEL_NAME);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, MenuChannels.CHANNEL_NAME, this);
    }

    /** Unregisters plugin messaging channels and cancels pending futures. */
    public void unregister() {
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, MenuChannels.CHANNEL_NAME, this);
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, MenuChannels.CHANNEL_NAME);
        for (CompletableFuture<MenuResult> future : pending.values()) {
            future.complete(new MenuResult.Failed(0L, FailureCode.GENERIC_ERROR, "MenuChannel unregistered"));
        }
        pending.clear();
    }
}
