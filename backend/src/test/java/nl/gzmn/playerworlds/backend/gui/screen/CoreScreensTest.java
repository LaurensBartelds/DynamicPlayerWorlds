package nl.gzmn.playerworlds.backend.gui.screen;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import nl.gzmn.playerworlds.backend.gui.ItemUtil;
import nl.gzmn.playerworlds.backend.gui.MenuChannel;
import nl.gzmn.playerworlds.backend.gui.MenuService;
import nl.gzmn.playerworlds.core.concurrent.MainThread;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.db.Database;
import nl.gzmn.playerworlds.core.db.MembershipRepository;
import nl.gzmn.playerworlds.core.db.PlayerNameRepository;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository;
import nl.gzmn.playerworlds.core.db.Schema;
import nl.gzmn.playerworlds.core.db.TransferRequestRepository;
import nl.gzmn.playerworlds.core.db.WorldBanRepository;
import nl.gzmn.playerworlds.core.menu.IntentEnvelope;
import nl.gzmn.playerworlds.core.menu.MenuCodec;
import nl.gzmn.playerworlds.core.menu.MenuIntent;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.StorageQuota;
import nl.gzmn.playerworlds.core.model.Visibility;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.model.WorldState;
import nl.gzmn.playerworlds.testing.TestDatabase;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class CoreScreensTest {

    private ServerMock server;
    private Plugin plugin;
    private Database database;
    private PluginExecutors executors;
    private PlayerWorldRepository worldRepository;
    private MembershipRepository membershipRepository;
    private TransferRequestRepository transferRepository;
    private WorldBanRepository banRepository;
    private PlayerNameRepository nameRepository;
    private Queue<Runnable> mainTasks;
    private MenuChannel channel;
    private MenuService menuService;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        database = TestDatabase.openFresh();
        Schema.migrate(database);

        MainThread.enter(Thread.currentThread());

        mainTasks = new ConcurrentLinkedQueue<>();
        executors = PluginExecutors.create(2, 2, mainTasks::add);

        worldRepository = new PlayerWorldRepository(database);
        membershipRepository = new MembershipRepository(database);
        transferRepository = new TransferRequestRepository(database);
        banRepository = new WorldBanRepository(database);
        nameRepository = new PlayerNameRepository(database);

        channel = new MenuChannel(plugin, executors, null, Duration.ofSeconds(5));
        menuService = new MenuService(
                worldRepository,
                membershipRepository,
                transferRepository,
                banRepository,
                nameRepository,
                channel,
                executors,
                NetworkPolicy::defaults);
        channel.setMenuService(menuService);
        channel.register();
    }

    @AfterEach
    void tearDown() {
        channel.unregister();
        executors.shutdown(Duration.ofSeconds(2));
        database.close();
        MainThread.clear();
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("ItemUtil creates items with custom component name, lore and player skull")
    void itemUtilBuildsItems() {
        ItemStack item = ItemUtil.create(
                Material.DIAMOND_SWORD,
                Component.text("Excalibur"),
                Component.text("Line 1"),
                Component.text("Line 2"));

        assertThat(item.getType()).isEqualTo(Material.DIAMOND_SWORD);
        assertThat(item.getItemMeta()).isNotNull();
        assertThat(PlainTextComponentSerializer.plainText()
                        .serialize(item.getItemMeta().displayName()))
                .isEqualTo("Excalibur");
        assertThat(item.getItemMeta().lore()).hasSize(2);

        ItemStack head = ItemUtil.createPlayerHead(
                UUID.randomUUID(), "Alice", Component.text("Alice's Head"), List.of(Component.text("Owner")));
        assertThat(head.getType()).isEqualTo(Material.PLAYER_HEAD);
        assertThat(head.getItemMeta()).isInstanceOf(SkullMeta.class);

        ItemStack filler = ItemUtil.filler();
        assertThat(filler.getType()).isEqualTo(Material.GRAY_STAINED_GLASS_PANE);
    }

    @Test
    @DisplayName("MainMenu renders navigation slots and dispatches click actions")
    void mainMenuRendersAndNavigates() throws Exception {
        PlayerMock player = server.addPlayer();
        StorageQuota quota = new StorageQuota(player.getUniqueId(), 1024L * 1024L * 50L, 1024L * 1024L * 500L, false);
        MainMenu.MainMenuData data = new MainMenu.MainMenuData(2, 5, 1, quota);
        MainMenu menu = new MainMenu(menuService, data);

        Inventory inv = menu.render(player);
        assertThat(inv.getSize()).isEqualTo(27);
        assertThat(inv.getItem(MainMenu.SLOT_MY_WORLDS)).isNotNull();
        assertThat(inv.getItem(MainMenu.SLOT_MY_WORLDS).getType()).isEqualTo(Material.GRASS_BLOCK);
        assertThat(inv.getItem(MainMenu.SLOT_STORAGE).getType()).isEqualTo(Material.CHEST);
        assertThat(inv.getItem(MainMenu.SLOT_INVITES).getType()).isEqualTo(Material.WRITABLE_BOOK);
        assertThat(inv.getItem(MainMenu.SLOT_BROWSE).getType()).isEqualTo(Material.COMPASS);
        assertThat(inv.getItem(MainMenu.SLOT_CLOSE).getType()).isEqualTo(Material.BARRIER);

        // Click My Worlds
        menu.handleClick(player, MainMenu.SLOT_MY_WORLDS, ClickType.LEFT);
        awaitCondition(() -> {
            drainMain();
            return menuService.activeScreen(player).isPresent();
        });
        assertThat(menuService.activeScreen(player).get()).isInstanceOf(MyWorldsMenu.class);

        // Click Storage
        menu.handleClick(player, MainMenu.SLOT_STORAGE, ClickType.LEFT);
        awaitCondition(() -> {
            drainMain();
            return menuService.activeScreen(player).isPresent()
                    && menuService.activeScreen(player).get() instanceof StorageMenu;
        });
        assertThat(menuService.activeScreen(player).get()).isInstanceOf(StorageMenu.class);

        // Click Browse
        menu.handleClick(player, MainMenu.SLOT_BROWSE, ClickType.LEFT);
        awaitCondition(() -> {
            drainMain();
            return menuService.activeScreen(player).isPresent()
                    && menuService.activeScreen(player).get() instanceof BrowseMenu;
        });
        assertThat(menuService.activeScreen(player).get()).isInstanceOf(BrowseMenu.class);

        // Click Close
        player.openInventory(inv);
        menu.handleClick(player, MainMenu.SLOT_CLOSE, ClickType.LEFT);
        drainMain();
    }

    @Test
    @DisplayName("MyWorldsMenu renders owned worlds, left-click joins, right-click manages, and creates world")
    void myWorldsMenuRendersAndInteracts() throws Exception {
        RecordingPlayerMock player = createRecordingPlayer("Bob");
        WorldId world1Id = WorldId.random();
        WorldId world2Id = WorldId.random();

        onDb(() -> worldRepository.create(world1Id, player.getUniqueId(), "world-one", 123L, 5000, Visibility.PUBLIC));
        onDb(() -> worldRepository.create(world2Id, player.getUniqueId(), "world-two", 456L, 5000, Visibility.PRIVATE));

        PlayerWorld world1 = new PlayerWorld(
                world1Id,
                player.getUniqueId(),
                "world-one",
                world1Id.folder(),
                123L,
                5000,
                Visibility.PUBLIC,
                "A cool world",
                "{}",
                null,
                null,
                1L,
                null,
                null,
                null,
                Instant.now(),
                null,
                WorldState.READY,
                1024L * 1024L * 10L);

        PlayerWorld world2 = new PlayerWorld(
                world2Id,
                player.getUniqueId(),
                "world-two",
                world2Id.folder(),
                456L,
                5000,
                Visibility.PRIVATE,
                null,
                "{}",
                null,
                null,
                1L,
                null,
                null,
                null,
                Instant.now(),
                null,
                WorldState.ARCHIVED,
                1024L * 1024L * 25L);

        MyWorldsMenu menu = new MyWorldsMenu(menuService, channel, List.of(world1, world2), 0, 5);
        Inventory inv = menu.render(player);

        assertThat(inv.getSize()).isEqualTo(54);
        assertThat(inv.getItem(0)).isNotNull();
        assertThat(inv.getItem(0).getType()).isEqualTo(Material.GRASS_BLOCK);
        assertThat(inv.getItem(1)).isNotNull();
        assertThat(inv.getItem(1).getType()).isEqualTo(Material.CHEST);
        assertThat(inv.getItem(MyWorldsMenu.SLOT_CREATE).getType()).isEqualTo(Material.NETHER_STAR);
        assertThat(inv.getItem(MyWorldsMenu.SLOT_BACK).getType()).isEqualTo(Material.OAK_DOOR);

        // Left-click slot 0 (join world-one)
        menu.handleClick(player, 0, ClickType.LEFT);
        byte[] joinMsg = player.nextSentMessage();
        IntentEnvelope env = (IntentEnvelope) MenuCodec.decode(joinMsg);
        assertThat(env.intent()).isEqualTo(new MenuIntent.JoinWorld(world1Id));

        // Right-click slot 0 (manage world-one)
        menu.handleClick(player, 0, ClickType.RIGHT);
        awaitCondition(() -> {
            drainMain();
            return menuService.activeScreen(player).isPresent()
                    && menuService.activeScreen(player).get() instanceof WorldMenu;
        });
        assertThat(menuService.activeScreen(player).get()).isInstanceOf(WorldMenu.class);

        // Click Create (slot 49)
        menu.handleClick(player, MyWorldsMenu.SLOT_CREATE, ClickType.LEFT);
        byte[] createMsg = player.nextSentMessage();
        IntentEnvelope createEnv = (IntentEnvelope) MenuCodec.decode(createMsg);
        assertThat(createEnv.intent()).isInstanceOf(MenuIntent.CreateWorld.class);
    }

    @Test
    @DisplayName("WorldMenu renders options, toggles visibility, joins, and confirms archival")
    void worldMenuRendersAndOperates() throws Exception {
        RecordingPlayerMock player = createRecordingPlayer("Charlie");
        WorldId worldId = WorldId.random();
        PlayerWorld world = new PlayerWorld(
                worldId,
                player.getUniqueId(),
                "charlie-world",
                worldId.folder(),
                789L,
                5000,
                Visibility.PRIVATE,
                null,
                "{}",
                null,
                null,
                1L,
                null,
                null,
                null,
                Instant.now(),
                null,
                WorldState.READY,
                1024L * 1024L * 30L);

        WorldMenu menu = new WorldMenu(menuService, channel, world);
        Inventory inv = menu.render(player);

        assertThat(inv.getSize()).isEqualTo(27);
        assertThat(inv.getItem(WorldMenu.SLOT_INFO).getType()).isEqualTo(Material.BEACON);
        assertThat(inv.getItem(WorldMenu.SLOT_JOIN).getType()).isEqualTo(Material.ENDER_PEARL);
        assertThat(inv.getItem(WorldMenu.SLOT_MEMBERS).getType()).isEqualTo(Material.PLAYER_HEAD);
        assertThat(inv.getItem(WorldMenu.SLOT_SETTINGS).getType()).isEqualTo(Material.COMPARATOR);
        assertThat(inv.getItem(WorldMenu.SLOT_VISIBILITY).getType()).isEqualTo(Material.ENDER_EYE);
        assertThat(inv.getItem(WorldMenu.SLOT_BANS).getType()).isEqualTo(Material.IRON_BARS);
        assertThat(inv.getItem(WorldMenu.SLOT_STORAGE).getType()).isEqualTo(Material.CHEST);
        assertThat(inv.getItem(WorldMenu.SLOT_ARCHIVE).getType()).isEqualTo(Material.TNT);
        assertThat(inv.getItem(WorldMenu.SLOT_BACK).getType()).isEqualTo(Material.OAK_DOOR);

        // Join
        menu.handleClick(player, WorldMenu.SLOT_JOIN, ClickType.LEFT);
        byte[] joinMsg = player.nextSentMessage();
        IntentEnvelope joinEnv = (IntentEnvelope) MenuCodec.decode(joinMsg);
        assertThat(joinEnv.intent()).isEqualTo(new MenuIntent.JoinWorld(worldId));

        // Toggle Visibility
        menu.handleClick(player, WorldMenu.SLOT_VISIBILITY, ClickType.LEFT);
        byte[] visMsg = player.nextSentMessage();
        IntentEnvelope visEnv = (IntentEnvelope) MenuCodec.decode(visMsg);
        assertThat(visEnv.intent()).isEqualTo(new MenuIntent.SetVisibility(worldId, Visibility.PUBLIC));

        // Archive -> opens ConfirmMenu
        menu.handleClick(player, WorldMenu.SLOT_ARCHIVE, ClickType.LEFT);
        drainMain();
        assertThat(menuService.activeScreen(player)).isPresent();
        assertThat(menuService.activeScreen(player).get()).isInstanceOf(ConfirmMenu.class);

        // In ConfirmMenu: Click Confirm
        ConfirmMenu confirmMenu = (ConfirmMenu) menuService.activeScreen(player).get();
        confirmMenu.handleClick(player, ConfirmMenu.SLOT_CONFIRM, ClickType.LEFT);

        byte[] archMsg = player.nextSentMessage();
        IntentEnvelope archEnv = (IntentEnvelope) MenuCodec.decode(archMsg);
        assertThat(archEnv.intent()).isEqualTo(new MenuIntent.ArchiveWorld("charlie-world"));
    }

    @Test
    @DisplayName("StorageMenu renders quota overview and owned world sizes")
    void storageMenuRendersOverviewAndBreakdown() throws Exception {
        PlayerMock player = server.addPlayer();
        WorldId wId = WorldId.random();

        onDb(() -> worldRepository.create(wId, player.getUniqueId(), "storage-world", 999L, 5000, Visibility.PRIVATE));

        PlayerWorld world = new PlayerWorld(
                wId,
                player.getUniqueId(),
                "storage-world",
                wId.folder(),
                999L,
                5000,
                Visibility.PRIVATE,
                null,
                "{}",
                null,
                null,
                1L,
                null,
                null,
                null,
                Instant.now(),
                null,
                WorldState.READY,
                1024L * 1024L * 15L);

        StorageQuota quota = new StorageQuota(player.getUniqueId(), 1024L * 1024L * 15L, 1024L * 1024L * 100L, false);
        StorageMenu menu = new StorageMenu(menuService, quota, List.of(world));

        Inventory inv = menu.render(player);
        assertThat(inv.getSize()).isEqualTo(36);
        assertThat(inv.getItem(StorageMenu.SLOT_OVERVIEW).getType()).isEqualTo(Material.ENDER_CHEST);
        assertThat(inv.getItem(StorageMenu.WORLDS_START_SLOT).getType()).isEqualTo(Material.GRASS_BLOCK);
        assertThat(inv.getItem(StorageMenu.SLOT_BACK).getType()).isEqualTo(Material.OAK_DOOR);

        // Click world -> opens WorldMenu
        menu.handleClick(player, StorageMenu.WORLDS_START_SLOT, ClickType.LEFT);
        awaitCondition(() -> {
            drainMain();
            return menuService.activeScreen(player).isPresent()
                    && menuService.activeScreen(player).get() instanceof WorldMenu;
        });
        assertThat(menuService.activeScreen(player).get()).isInstanceOf(WorldMenu.class);
    }

    @Test
    @DisplayName("ConfirmMenu executes onConfirm and onCancel callbacks on matching slots")
    void confirmMenuExecutesCallbacks() {
        PlayerMock player = server.addPlayer();
        AtomicBoolean confirmed = new AtomicBoolean(false);
        AtomicBoolean cancelled = new AtomicBoolean(false);

        ConfirmMenu menu = new ConfirmMenu(
                Component.text("Action Title"),
                Component.text("Action Description"),
                () -> confirmed.set(true),
                () -> cancelled.set(true));

        Inventory inv = menu.render(player);
        assertThat(inv.getSize()).isEqualTo(27);
        assertThat(inv.getItem(ConfirmMenu.SLOT_INFO).getType()).isEqualTo(Material.PAPER);
        assertThat(inv.getItem(ConfirmMenu.SLOT_CONFIRM).getType()).isEqualTo(Material.LIME_CONCRETE);
        assertThat(inv.getItem(ConfirmMenu.SLOT_CANCEL).getType()).isEqualTo(Material.RED_CONCRETE);

        // Click Confirm
        menu.handleClick(player, ConfirmMenu.SLOT_CONFIRM, ClickType.LEFT);
        assertThat(confirmed).isTrue();
        assertThat(cancelled).isFalse();

        // Click Cancel
        menu.handleClick(player, ConfirmMenu.SLOT_CANCEL, ClickType.LEFT);
        assertThat(cancelled).isTrue();
    }

    @Test
    @DisplayName("MenuService async loaders fetch view models from database on db executor and render screens on main")
    void menuServiceAsyncLoadersWork() throws Exception {
        PlayerMock player = server.addPlayer();
        WorldId worldId = WorldId.random();
        onDb(() -> worldRepository.create(
                worldId, player.getUniqueId(), "service-world", 12345L, 5000, Visibility.PUBLIC));

        // 1. openMainMenu
        CompletableFuture<Void> f1 = menuService.openMainMenu(player);
        awaitCondition(() -> {
            drainMain();
            return f1.isDone();
        });
        assertThat(menuService.activeScreen(player)).isPresent();
        assertThat(menuService.activeScreen(player).get()).isInstanceOf(MainMenu.class);

        // 2. openMyWorldsMenu
        CompletableFuture<Void> f2 = menuService.openMyWorldsMenu(player);
        awaitCondition(() -> {
            drainMain();
            return f2.isDone();
        });
        assertThat(menuService.activeScreen(player).get()).isInstanceOf(MyWorldsMenu.class);
        MyWorldsMenu myWorlds = (MyWorldsMenu) menuService.activeScreen(player).get();
        assertThat(myWorlds.worlds()).hasSize(1);
        assertThat(myWorlds.worlds().getFirst().name()).isEqualTo("service-world");

        // 3. openWorldMenu
        CompletableFuture<Void> f3 = menuService.openWorldMenu(player, worldId);
        awaitCondition(() -> {
            drainMain();
            return f3.isDone();
        });
        assertThat(menuService.activeScreen(player).get()).isInstanceOf(WorldMenu.class);
        WorldMenu worldMenu = (WorldMenu) menuService.activeScreen(player).get();
        assertThat(worldMenu.world().name()).isEqualTo("service-world");

        // 4. openStorageMenu
        CompletableFuture<Void> f4 = menuService.openStorageMenu(player);
        awaitCondition(() -> {
            drainMain();
            return f4.isDone();
        });
        assertThat(menuService.activeScreen(player).get()).isInstanceOf(StorageMenu.class);
    }

    private void drainMain() {
        Runnable task;
        while ((task = mainTasks.poll()) != null) {
            task.run();
        }
    }

    private void awaitCondition(Callable<Boolean> condition) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            drainMain();
            if (Boolean.TRUE.equals(condition.call())) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("condition not met within 5s");
    }

    private <T> T onDb(Callable<T> task) throws Exception {
        return executors.db().submit(task).get(5, TimeUnit.SECONDS);
    }

    private RecordingPlayerMock createRecordingPlayer(String name) {
        RecordingPlayerMock player = new RecordingPlayerMock(server, name);
        server.addPlayer(player);
        return player;
    }

    public static class RecordingPlayerMock extends PlayerMock {
        private final List<byte[]> sentMessages = new CopyOnWriteArrayList<>();

        public RecordingPlayerMock(ServerMock server, String name) {
            super(server, name);
        }

        @Override
        public void sendPluginMessage(Plugin source, String channel, byte[] message) {
            super.sendPluginMessage(source, channel, message);
            sentMessages.add(message);
        }

        public byte[] nextSentMessage() {
            if (sentMessages.isEmpty()) {
                throw new IllegalStateException("No sent plugin messages");
            }
            return sentMessages.removeFirst();
        }
    }
}
