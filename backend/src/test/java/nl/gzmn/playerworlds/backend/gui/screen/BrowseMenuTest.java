package nl.gzmn.playerworlds.backend.gui.screen;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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
import nl.gzmn.playerworlds.testing.TestDatabase;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class BrowseMenuTest {

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
    @DisplayName("BrowseMenu renders public worlds, left click sends JoinWorld intent, back returns to main menu")
    void browseMenuRendersAndInteracts() throws Exception {
        RecordingPlayerMock player = createRecordingPlayer("Alice");
        WorldId world1Id = WorldId.random();
        WorldId world2Id = WorldId.random();
        UUID owner1 = UUID.randomUUID();
        UUID owner2 = UUID.randomUUID();

        BrowseMenu.PublicWorldEntry entry1 =
                new BrowseMenu.PublicWorldEntry(world1Id, "alpha-world", owner1, "Bob", "A lovely survival realm");
        BrowseMenu.PublicWorldEntry entry2 =
                new BrowseMenu.PublicWorldEntry(world2Id, "beta-world", owner2, "Charlie", null);

        BrowseMenu menu = new BrowseMenu(menuService, channel, List.of(entry1, entry2), 0);
        Inventory inv = menu.render(player);

        assertThat(inv.getSize()).isEqualTo(54);
        assertThat(inv.getItem(0)).isNotNull();
        assertThat(inv.getItem(0).getType()).isEqualTo(Material.GRASS_BLOCK);
        assertThat(inv.getItem(1)).isNotNull();
        assertThat(inv.getItem(1).getType()).isEqualTo(Material.GRASS_BLOCK);

        ItemStack item0 = inv.getItem(0);
        assertThat(item0.getItemMeta()).isNotNull();
        String loreText = String.join(
                "\n",
                item0.getItemMeta().lore().stream()
                        .map(c -> PlainTextComponentSerializer.plainText().serialize(c))
                        .toList());
        assertThat(loreText).contains("Owner: Bob");
        assertThat(loreText).contains("A lovely survival realm");
        assertThat(loreText).contains("Click to Join World");

        assertThat(inv.getItem(BrowseMenu.SLOT_BACK).getType()).isEqualTo(Material.OAK_DOOR);

        // Click slot 0 -> sends MenuIntent.JoinWorld(world1Id)
        menu.handleClick(player, 0, ClickType.LEFT);
        byte[] joinMsg = player.nextSentMessage();
        IntentEnvelope joinEnv = (IntentEnvelope) MenuCodec.decode(joinMsg);
        assertThat(joinEnv.intent()).isEqualTo(new MenuIntent.JoinWorld(world1Id));

        // Click Back button (slot 48) -> navigates to MainMenu
        menu.handleClick(player, BrowseMenu.SLOT_BACK, ClickType.LEFT);
        awaitCondition(() -> {
            drainMain();
            return menuService.activeScreen(player).isPresent()
                    && menuService.activeScreen(player).get() instanceof MainMenu;
        });
        assertThat(menuService.activeScreen(player).get()).isInstanceOf(MainMenu.class);
    }

    @Test
    @DisplayName("BrowseMenu paginates entries across multiple pages")
    void browseMenuPagination() throws Exception {
        PlayerMock player = server.addPlayer();
        List<BrowseMenu.PublicWorldEntry> entries = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            entries.add(new BrowseMenu.PublicWorldEntry(
                    WorldId.random(), "world-" + i, UUID.randomUUID(), "Owner" + i, "Desc " + i));
        }

        // Page 0
        BrowseMenu page0 = new BrowseMenu(menuService, channel, entries, 0);
        Inventory inv0 = page0.render(player);
        assertThat(inv0.getItem(0)).isNotNull();
        assertThat(inv0.getItem(35)).isNotNull();
        assertThat(inv0.getItem(BrowseMenu.SLOT_NEXT_PAGE)).isNotNull();
        assertThat(inv0.getItem(BrowseMenu.SLOT_NEXT_PAGE).getType()).isEqualTo(Material.ARROW);
        assertThat(inv0.getItem(BrowseMenu.SLOT_PREVIOUS_PAGE).getType()).isEqualTo(Material.GRAY_STAINED_GLASS_PANE);

        // Click next page on page 0
        page0.handleClick(player, BrowseMenu.SLOT_NEXT_PAGE, ClickType.LEFT);
        awaitCondition(() -> {
            drainMain();
            return menuService.activeScreen(player).isPresent()
                    && menuService.activeScreen(player).get() instanceof BrowseMenu bm
                    && bm.page() == 1;
        });
        BrowseMenu openedPage1 = (BrowseMenu) menuService.activeScreen(player).get();
        assertThat(openedPage1.page()).isEqualTo(1);

        // Page 1
        BrowseMenu page1 = new BrowseMenu(menuService, channel, entries, 1);
        Inventory inv1 = page1.render(player);
        assertThat(inv1.getItem(0)).isNotNull();
        assertThat(inv1.getItem(3)).isNotNull();
        // Slot 4 onwards in world grid should be filler
        assertThat(inv1.getItem(4).getType()).isEqualTo(Material.GRAY_STAINED_GLASS_PANE);
        assertThat(inv1.getItem(BrowseMenu.SLOT_PREVIOUS_PAGE)).isNotNull();
        assertThat(inv1.getItem(BrowseMenu.SLOT_PREVIOUS_PAGE).getType()).isEqualTo(Material.ARROW);
        assertThat(inv1.getItem(BrowseMenu.SLOT_NEXT_PAGE).getType()).isEqualTo(Material.GRAY_STAINED_GLASS_PANE);

        // Click previous page on page 1
        page1.handleClick(player, BrowseMenu.SLOT_PREVIOUS_PAGE, ClickType.LEFT);
        awaitCondition(() -> {
            drainMain();
            return menuService.activeScreen(player).isPresent()
                    && menuService.activeScreen(player).get() instanceof BrowseMenu bm
                    && bm.page() == 0;
        });
        BrowseMenu openedPage0 = (BrowseMenu) menuService.activeScreen(player).get();
        assertThat(openedPage0.page()).isEqualTo(0);
    }

    @Test
    @DisplayName("BrowseMenu displays empty state indicator when no public worlds exist")
    void browseMenuEmptyState() {
        PlayerMock player = server.addPlayer();
        BrowseMenu menu = new BrowseMenu(menuService, channel, List.of(), 0);
        Inventory inv = menu.render(player);

        assertThat(inv.getItem(22)).isNotNull();
        assertThat(inv.getItem(22).getType()).isEqualTo(Material.COMPASS);
        assertThat(inv.getItem(BrowseMenu.SLOT_BACK).getType()).isEqualTo(Material.OAK_DOOR);
    }

    @Test
    @DisplayName("MenuService.openBrowseMenu queries database asynchronously and opens BrowseMenu")
    void menuServiceOpenBrowseMenuAsync() throws Exception {
        PlayerMock player = server.addPlayer();
        UUID ownerUuid = UUID.randomUUID();
        WorldId worldId = WorldId.random();

        onDb(() -> {
            PlayerWorld w =
                    worldRepository.create(worldId, ownerUuid, "community-world", 777L, 5000, Visibility.PUBLIC);
            worldRepository.updateVisibility(worldId, Visibility.PUBLIC, "Awesome community world");
            worldRepository.markReadyAndPlayed(worldId);
            nameRepository.remember(ownerUuid, "BuildMaster");
            return w;
        });

        CompletableFuture<Void> future = menuService.openBrowseMenu(player);
        awaitCondition(() -> {
            drainMain();
            return future.isDone();
        });

        assertThat(menuService.activeScreen(player)).isPresent();
        assertThat(menuService.activeScreen(player).get()).isInstanceOf(BrowseMenu.class);
        BrowseMenu screen = (BrowseMenu) menuService.activeScreen(player).get();
        assertThat(screen.worlds()).hasSize(1);
        BrowseMenu.PublicWorldEntry entry = screen.worlds().getFirst();
        assertThat(entry.worldId()).isEqualTo(worldId);
        assertThat(entry.worldName()).isEqualTo("community-world");
        assertThat(entry.ownerName()).isEqualTo("BuildMaster");
        assertThat(entry.description()).isEqualTo("Awesome community world");
    }

    @Test
    @DisplayName("MainMenu slot 16 navigates to BrowseMenu")
    void mainMenuSlotBrowseNavigatesToBrowseMenu() throws Exception {
        PlayerMock player = server.addPlayer();
        StorageQuota quota = new StorageQuota(player.getUniqueId(), 0L, 1024L * 1024L * 500L, false);
        MainMenu.MainMenuData data = new MainMenu.MainMenuData(0, 5, 0, quota);
        MainMenu mainMenu = new MainMenu(menuService, data);

        mainMenu.handleClick(player, MainMenu.SLOT_BROWSE, ClickType.LEFT);
        awaitCondition(() -> {
            drainMain();
            return menuService.activeScreen(player).isPresent()
                    && menuService.activeScreen(player).get() instanceof BrowseMenu;
        });

        assertThat(menuService.activeScreen(player).get()).isInstanceOf(BrowseMenu.class);
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
