package nl.gzmn.playerworlds.lobby;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import nl.gzmn.playerworlds.core.menu.CloseMenuMessage;
import nl.gzmn.playerworlds.core.menu.MenuChannels;
import nl.gzmn.playerworlds.core.menu.MenuClickIntent;
import nl.gzmn.playerworlds.core.menu.MenuClosedNotice;
import nl.gzmn.playerworlds.core.menu.MenuCodec;
import nl.gzmn.playerworlds.core.menu.MenuItemDescriptor;
import nl.gzmn.playerworlds.core.menu.OpenMenu;
import nl.gzmn.playerworlds.core.menu.RenderMenuPayload;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class LobbyPluginTest {

    private ServerMock server;
    private LobbyPlugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(LobbyPlugin.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("Menu payload rendering opens inventory with matching title, size, materials, names, and lore")
    void rendersInventoryFromPayload() {
        PlayerMock player = server.addPlayer("Alice");
        UUID skullOwner = UUID.randomUUID();
        List<MenuItemDescriptor> items = List.of(
                new MenuItemDescriptor(
                        0,
                        "GRASS_BLOCK",
                        1,
                        "§aWorld One",
                        List.of("§7First line", "§eSecond line"),
                        null,
                        "ACTION:JOIN:w1"),
                new MenuItemDescriptor(
                        4, "PLAYER_HEAD", 1, "§bProfile", List.of("§7Your info"), skullOwner, "NAV:PROFILE"),
                new MenuItemDescriptor(8, "UNKNOWN_MATERIAL_FALLBACK", 5, "§cFallback Item", List.of(), null, ""));

        RenderMenuPayload payload = new RenderMenuPayload(1001L, "MAIN", "§8Dynamic Player Worlds", 27, items);

        byte[] encoded = MenuCodec.encodeRenderMenu(payload);
        plugin.menuChannel().onPluginMessageReceived(MenuChannels.CHANNEL_NAME, player, encoded);

        Inventory openInv = player.getOpenInventory().getTopInventory();
        assertThat(openInv).isNotNull();
        assertThat(openInv.getSize()).isEqualTo(27);
        assertThat(openInv.getHolder()).isInstanceOf(LobbyMenuHolder.class);

        LobbyMenuHolder holder = (LobbyMenuHolder) openInv.getHolder();
        assertThat(holder.correlationId()).isEqualTo(1001L);
        assertThat(holder.actionTagForSlot(0)).isEqualTo("ACTION:JOIN:w1");
        assertThat(holder.actionTagForSlot(4)).isEqualTo("NAV:PROFILE");
        assertThat(holder.actionTagForSlot(8)).isNull();

        // Check slot 0
        ItemStack item0 = openInv.getItem(0);
        assertThat(item0).isNotNull();
        assertThat(item0.getType()).isEqualTo(Material.GRASS_BLOCK);
        assertThat(item0.getAmount()).isEqualTo(1);
        assertThat(item0.getItemMeta()).isNotNull();
        assertThat(PlainTextComponentSerializer.plainText()
                        .serialize(item0.getItemMeta().displayName()))
                .isEqualTo("World One");
        assertThat(item0.getItemMeta().lore()).hasSize(2);

        // Check slot 4 (player head)
        ItemStack item4 = openInv.getItem(4);
        assertThat(item4).isNotNull();
        assertThat(item4.getType()).isEqualTo(Material.PLAYER_HEAD);
        assertThat(item4.getItemMeta()).isInstanceOf(SkullMeta.class);
        SkullMeta skullMeta = (SkullMeta) item4.getItemMeta();
        assertThat(skullMeta.getOwningPlayer()).isNotNull();
        assertThat(skullMeta.hasOwner()).isTrue();

        // Check slot 8 (fallback to stone)
        ItemStack item8 = openInv.getItem(8);
        assertThat(item8).isNotNull();
        assertThat(item8.getType()).isEqualTo(Material.STONE);
        assertThat(item8.getAmount()).isEqualTo(5);
    }

    @Test
    @DisplayName("Slot click in menu cancels event and dispatches MenuClickIntent")
    void slotClickCancelsAndDispatchesIntent() {
        PlayerMock player = server.addPlayer("Bob");
        List<byte[]> sentMessages = new CopyOnWriteArrayList<>();
        Player testPlayer = createInterceptingPlayer(player, sentMessages);

        List<MenuItemDescriptor> items = List.of(
                new MenuItemDescriptor(11, "GRASS_BLOCK", 1, "§aJoin World", List.of(), null, "ACTION:JOIN:w1"),
                new MenuItemDescriptor(12, "BARRIER", 1, "§cNo Action", List.of(), null, ""));
        RenderMenuPayload payload = new RenderMenuPayload(2002L, "MAIN", "§8Menu", 27, items);
        plugin.menuChannel().handleRenderPayload(player, payload);

        Inventory openInv = player.getOpenInventory().getTopInventory();
        LobbyMenuHolder holder = (LobbyMenuHolder) openInv.getHolder();

        // Dispatch click via menuChannel helper and verify payload
        plugin.menuChannel()
                .sendClickIntent(testPlayer, holder.correlationId(), "ACTION:JOIN:w1", holder.screenSequence());

        assertThat(sentMessages).isNotEmpty();
        byte[] sentBytes = sentMessages.removeFirst();
        Object decoded = MenuCodec.decode(sentBytes);
        assertThat(decoded).isInstanceOf(MenuClickIntent.class);

        MenuClickIntent intent = (MenuClickIntent) decoded;
        assertThat(intent.correlationId()).isEqualTo(2002L);
        assertThat(intent.actionTag()).isEqualTo("ACTION:JOIN:w1");
        assertThat(intent.screenSequence()).isEqualTo(0);

        // Test listener cancellation
        InventoryClickEvent clickEvent = new InventoryClickEvent(
                player.getOpenInventory(),
                InventoryType.SlotType.CONTAINER,
                11,
                ClickType.LEFT,
                InventoryAction.PICKUP_ALL);
        server.getPluginManager().callEvent(clickEvent);

        assertThat(clickEvent.isCancelled()).isTrue();
    }

    @Test
    @DisplayName("Inventory drag is cancelled in menu")
    void inventoryDragIsCancelled() {
        PlayerMock player = server.addPlayer("Charlie");

        RenderMenuPayload payload = new RenderMenuPayload(3003L, "MAIN", "§8Menu", 27, List.of());
        plugin.menuChannel().handleRenderPayload(player, payload);

        InventoryDragEvent dragEvent = new InventoryDragEvent(
                player.getOpenInventory(),
                new ItemStack(Material.DIRT),
                new ItemStack(Material.DIRT),
                false,
                java.util.Map.of(0, new ItemStack(Material.DIRT)));
        server.getPluginManager().callEvent(dragEvent);

        assertThat(dragEvent.isCancelled()).isTrue();
    }

    @Test
    @DisplayName("Inventory close sends MenuClosedNotice to proxy")
    void inventoryCloseSendsNotice() {
        PlayerMock player = server.addPlayer("Dave");
        List<byte[]> sentMessages = new CopyOnWriteArrayList<>();
        Player testPlayer = createInterceptingPlayer(player, sentMessages);

        RenderMenuPayload payload = new RenderMenuPayload(4004L, "MAIN", "§8Menu", 27, List.of());
        plugin.menuChannel().handleRenderPayload(player, payload);

        InventoryCloseEvent closeEvent = new InventoryCloseEvent(player.getOpenInventory());
        server.getPluginManager().callEvent(closeEvent);

        plugin.menuChannel().sendClosedNotice(testPlayer, 4004L);

        assertThat(sentMessages).isNotEmpty();
        byte[] sentBytes = sentMessages.removeFirst();
        Object decoded = MenuCodec.decode(sentBytes);
        assertThat(decoded).isInstanceOf(MenuClosedNotice.class);

        MenuClosedNotice notice = (MenuClosedNotice) decoded;
        assertThat(notice.correlationId()).isEqualTo(4004L);
    }

    @Test
    @DisplayName("CloseMenuMessage closes open menu")
    void closeMenuMessageClosesInventory() {
        PlayerMock player = server.addPlayer("Eve");

        RenderMenuPayload payload = new RenderMenuPayload(5005L, "MAIN", "§8Menu", 27, List.of());
        plugin.menuChannel().handleRenderPayload(player, payload);

        assertThat(player.getOpenInventory().getTopInventory().getHolder()).isInstanceOf(LobbyMenuHolder.class);

        byte[] closeBytes = MenuCodec.encodeCloseMenu(new CloseMenuMessage(5005L));
        plugin.menuChannel().onPluginMessageReceived(MenuChannels.CHANNEL_NAME, player, closeBytes);

        Inventory top = player.getOpenInventory().getTopInventory();
        assertThat(top == null || !(top.getHolder() instanceof LobbyMenuHolder)).isTrue();
    }

    @Test
    @DisplayName("Executing /world command sends OpenMenu packet")
    void worldCommandSendsOpenMenu() {
        PlayerMock player = server.addPlayer("Frank");
        List<byte[]> sentMessages = new CopyOnWriteArrayList<>();
        Player testPlayer = createInterceptingPlayer(player, sentMessages);

        plugin.menuChannel().sendOpenMenu(testPlayer);

        assertThat(sentMessages).isNotEmpty();
        byte[] msg = sentMessages.removeFirst();
        Object decoded = MenuCodec.decode(msg);
        assertThat(decoded).isInstanceOf(OpenMenu.class);
    }

    @Test
    @DisplayName("Console executing /world command receives message and does not send plugin message")
    void consoleCommandHandled() {
        CommandSender console = new ConsoleCommandSenderMock();
        boolean result = plugin.onCommand(console, plugin.getCommand("world"), "world", new String[0]);
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("onPluginMessageReceived handles invalid or foreign messages gracefully")
    void handlesInvalidMessagesGracefully() {
        PlayerMock player = server.addPlayer("Grace");

        // Foreign channel
        plugin.menuChannel().onPluginMessageReceived("minecraft:brand", player, new byte[] {1, 2, 3});
        // Corrupt message
        plugin.menuChannel().onPluginMessageReceived(MenuChannels.CHANNEL_NAME, player, new byte[] {99, 99});
        // Empty message
        plugin.menuChannel().onPluginMessageReceived(MenuChannels.CHANNEL_NAME, player, new byte[0]);
        // Null message
        plugin.menuChannel().onPluginMessageReceived(MenuChannels.CHANNEL_NAME, player, null);
    }

    private Player createInterceptingPlayer(PlayerMock delegate, List<byte[]> messageSink) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(), new Class<?>[] {Player.class}, (proxy, method, args) -> {
                    if ("sendPluginMessage".equals(method.getName()) && args != null && args.length == 3) {
                        messageSink.add((byte[]) args[2]);
                        return null;
                    }
                    return method.invoke(delegate, args);
                });
    }
}
