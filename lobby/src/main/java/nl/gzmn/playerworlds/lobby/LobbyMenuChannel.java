package nl.gzmn.playerworlds.lobby;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import nl.gzmn.playerworlds.core.menu.CloseMenuMessage;
import nl.gzmn.playerworlds.core.menu.MenuChannels;
import nl.gzmn.playerworlds.core.menu.MenuClickIntent;
import nl.gzmn.playerworlds.core.menu.MenuClosedNotice;
import nl.gzmn.playerworlds.core.menu.MenuCodec;
import nl.gzmn.playerworlds.core.menu.MenuCodecException;
import nl.gzmn.playerworlds.core.menu.MenuItemDescriptor;
import nl.gzmn.playerworlds.core.menu.OpenMenu;
import nl.gzmn.playerworlds.core.menu.RenderMenuPayload;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

/**
 * Plugin message channel listener and dispatcher for the {@code gzmn:menu} protocol on the lobby server.
 *
 * <p>Receives {@link RenderMenuPayload} and {@link CloseMenuMessage} from the Velocity proxy, and sends
 * {@link MenuClickIntent}, {@link MenuClosedNotice}, and {@link OpenMenu} to the proxy.
 */
public final class LobbyMenuChannel implements PluginMessageListener {

    private static final Logger log = Logger.getLogger(LobbyMenuChannel.class.getName());
    private static final AtomicLong CORRELATION_SEQUENCE = new AtomicLong(1);

    private final Plugin plugin;

    public LobbyMenuChannel(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /**
     * Registers incoming and outgoing plugin channels with Bukkit messenger.
     */
    public void register() {
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, MenuChannels.CHANNEL_NAME, this);
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, MenuChannels.CHANNEL_NAME);
    }

    /**
     * Unregisters incoming and outgoing plugin channels from Bukkit messenger.
     */
    public void unregister() {
        Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, MenuChannels.CHANNEL_NAME, this);
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin, MenuChannels.CHANNEL_NAME);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!MenuChannels.CHANNEL_NAME.equals(channel)) {
            return;
        }
        if (message == null || message.length == 0) {
            return;
        }

        final Object decoded;
        try {
            decoded = MenuCodec.decode(message);
        } catch (MenuCodecException e) {
            log.log(Level.WARNING, "Failed to decode menu packet for player " + player.getName(), e);
            return;
        }

        if (decoded instanceof RenderMenuPayload payload) {
            handleRenderPayload(player, payload);
        } else if (decoded instanceof CloseMenuMessage close) {
            handleCloseMessage(player, close);
        } else {
            log.log(
                    Level.FINE,
                    "Ignored unexpected menu message type {0} on lobby",
                    decoded.getClass().getSimpleName());
        }
    }

    /**
     * Renders a {@link RenderMenuPayload} as an active chest inventory for the player.
     *
     * @param player the player receiving the menu
     * @param payload the screen payload
     */
    public void handleRenderPayload(Player player, RenderMenuPayload payload) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(payload, "payload");

        Map<Integer, String> slotActions = new HashMap<>();
        for (MenuItemDescriptor item : payload.items()) {
            if (item.actionTag() != null && !item.actionTag().isBlank()) {
                slotActions.put(item.slot(), item.actionTag());
            }
        }

        LobbyMenuHolder holder = new LobbyMenuHolder(payload.correlationId(), 0, slotActions);
        Component title = LegacyComponentSerializer.legacySection().deserialize(payload.title());
        Inventory inventory = Bukkit.createInventory(holder, payload.size(), title);
        holder.setInventory(inventory);

        for (MenuItemDescriptor item : payload.items()) {
            if (item.slot() >= 0 && item.slot() < payload.size()) {
                inventory.setItem(item.slot(), LobbyItemUtil.create(item));
            }
        }

        player.openInventory(inventory);
    }

    /**
     * Closes the active menu for the player if one is open.
     *
     * @param player the player
     * @param close the close message
     */
    public void handleCloseMessage(Player player, CloseMenuMessage close) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(close, "close");

        Inventory topInventory = player.getOpenInventory().getTopInventory();
        if (topInventory != null && topInventory.getHolder() instanceof LobbyMenuHolder) {
            player.closeInventory();
        }
    }

    /**
     * Sends a {@link MenuClickIntent} to the Velocity proxy.
     *
     * @param player the clicking player
     * @param correlationId correlation ID of the current menu
     * @param actionTag action tag of the clicked slot
     * @param sequence screen sequence number
     */
    public void sendClickIntent(Player player, long correlationId, String actionTag, int sequence) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(actionTag, "actionTag");

        byte[] data = MenuCodec.encodeClickIntent(new MenuClickIntent(correlationId, actionTag, sequence));
        player.sendPluginMessage(plugin, MenuChannels.CHANNEL_NAME, data);
    }

    /**
     * Sends a {@link MenuClosedNotice} to the Velocity proxy when a player closes their menu.
     *
     * @param player the player closing the menu
     * @param correlationId correlation ID of the closed menu
     */
    public void sendClosedNotice(Player player, long correlationId) {
        Objects.requireNonNull(player, "player");

        byte[] data = MenuCodec.encodeClosedNotice(new MenuClosedNotice(correlationId));
        player.sendPluginMessage(plugin, MenuChannels.CHANNEL_NAME, data);
    }

    /**
     * Sends an {@link OpenMenu} request to the Velocity proxy.
     *
     * @param player the requesting player
     */
    public void sendOpenMenu(Player player) {
        Objects.requireNonNull(player, "player");

        long correlationId = CORRELATION_SEQUENCE.getAndIncrement();
        byte[] data = MenuCodec.encodeOpenMenu(new OpenMenu(correlationId));
        player.sendPluginMessage(plugin, MenuChannels.CHANNEL_NAME, data);
    }
}
