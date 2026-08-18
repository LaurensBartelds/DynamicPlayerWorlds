package nl.gzmn.playerworlds.backend.gui;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
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
import nl.gzmn.playerworlds.core.model.Visibility;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.testing.TestDatabase;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class MenuServiceTest {

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
    private MenuListener menuListener;

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

        menuListener = new MenuListener(menuService, channel);
        server.getPluginManager().registerEvents(menuListener, plugin);
    }

    @AfterEach
    void tearDown() {
        executors.shutdown(Duration.ofSeconds(2));
        database.close();
        MainThread.clear();
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("openScreen renders inventory with MenuHolder and sets active screen")
    void openScreenSetsActiveScreen() {
        PlayerMock player = server.addPlayer();
        AtomicBoolean rendered = new AtomicBoolean(false);

        GuiScreen screen = new GuiScreen() {
            @Override
            public Inventory render(Player p) {
                rendered.set(true);
                MenuHolder holder = new MenuHolder(this);
                Inventory inv = Bukkit.createInventory(holder, 27, Component.text("Test Screen"));
                holder.setInventory(inv);
                return inv;
            }

            @Override
            public void handleClick(Player p, int slot, ClickType clickType) {}

            @Override
            public void refresh(Player p) {}
        };

        menuService.openScreen(player, screen);
        drainMain();

        assertThat(rendered).isTrue();
        assertThat(menuService.activeScreen(player)).contains(screen);
        assertThat(player.getOpenInventory().getTopInventory().getHolder()).isInstanceOf(MenuHolder.class);
    }

    @Test
    @DisplayName("openMainMenu queries database asynchronously and opens main menu")
    void openMainMenuLoadsDbAsyncAndOpens() throws Exception {
        PlayerMock player = server.addPlayer();
        onDb(() -> worldRepository.create(
                WorldId.random(), player.getUniqueId(), "alice-world", 12345L, 5000, Visibility.PRIVATE));

        AtomicBoolean factoryCalled = new AtomicBoolean(false);
        GuiScreen testMainMenu = new GuiScreen() {
            @Override
            public Inventory render(Player p) {
                MenuHolder holder = new MenuHolder(this);
                Inventory inv = Bukkit.createInventory(holder, 27, Component.text("Main Menu"));
                holder.setInventory(inv);
                return inv;
            }

            @Override
            public void handleClick(Player p, int slot, ClickType clickType) {}

            @Override
            public void refresh(Player p) {}
        };

        menuService.setMainMenuFactory(p -> {
            factoryCalled.set(true);
            return testMainMenu;
        });

        CompletableFuture<Void> future = menuService.openMainMenu(player);
        awaitCondition(() -> {
            drainMain();
            return future.isDone() && factoryCalled.get();
        });

        assertThat(factoryCalled).isTrue();
        assertThat(menuService.activeScreen(player)).contains(testMainMenu);
    }

    @Test
    @DisplayName("MenuListener cancels top inventory click and routes to screen.handleClick")
    void listenerHandlesTopInventoryClick() {
        PlayerMock player = server.addPlayer();
        AtomicInteger clickedSlot = new AtomicInteger(-1);
        AtomicReference<ClickType> receivedClick = new AtomicReference<>();

        GuiScreen screen = new GuiScreen() {
            @Override
            public Inventory render(Player p) {
                MenuHolder holder = new MenuHolder(this);
                Inventory inv = Bukkit.createInventory(holder, 27, Component.text("Interactive Menu"));
                holder.setInventory(inv);
                return inv;
            }

            @Override
            public void handleClick(Player p, int slot, ClickType clickType) {
                clickedSlot.set(slot);
                receivedClick.set(clickType);
            }

            @Override
            public void refresh(Player p) {}
        };

        menuService.openScreen(player, screen);
        drainMain();

        InventoryView view = player.getOpenInventory();
        InventoryClickEvent event = new InventoryClickEvent(
                view, InventoryType.SlotType.CONTAINER, 5, ClickType.LEFT, InventoryAction.PICKUP_ALL);

        menuListener.onInventoryClick(event);

        assertThat(event.isCancelled()).isTrue();
        assertThat(clickedSlot.get()).isEqualTo(5);
        assertThat(receivedClick.get()).isEqualTo(ClickType.LEFT);
    }

    @Test
    @DisplayName("MenuListener cancels bottom inventory click and does not route to screen.handleClick")
    void listenerCancelsBottomInventoryClick() {
        PlayerMock player = server.addPlayer();
        AtomicInteger clickedSlot = new AtomicInteger(-1);

        GuiScreen screen = new GuiScreen() {
            @Override
            public Inventory render(Player p) {
                MenuHolder holder = new MenuHolder(this);
                Inventory inv = Bukkit.createInventory(holder, 27, Component.text("Interactive Menu"));
                holder.setInventory(inv);
                return inv;
            }

            @Override
            public void handleClick(Player p, int slot, ClickType clickType) {
                clickedSlot.set(slot);
            }

            @Override
            public void refresh(Player p) {}
        };

        menuService.openScreen(player, screen);
        drainMain();

        InventoryView view = player.getOpenInventory();

        // Raw slot 30 is in bottom inventory (player inventory)
        InventoryClickEvent event = new InventoryClickEvent(
                view, InventoryType.SlotType.QUICKBAR, 30, ClickType.LEFT, InventoryAction.PICKUP_ALL);

        menuListener.onInventoryClick(event);

        assertThat(event.isCancelled()).isTrue();
        assertThat(clickedSlot.get()).isEqualTo(-1); // Not dispatched to screen
    }

    @Test
    @DisplayName("MenuListener cancels drag events when MenuHolder is open")
    void listenerCancelsDrag() {
        PlayerMock player = server.addPlayer();

        GuiScreen screen = new GuiScreen() {
            @Override
            public Inventory render(Player p) {
                MenuHolder holder = new MenuHolder(this);
                Inventory inv = Bukkit.createInventory(holder, 27, Component.text("Drag Test"));
                holder.setInventory(inv);
                return inv;
            }

            @Override
            public void handleClick(Player p, int slot, ClickType clickType) {}

            @Override
            public void refresh(Player p) {}
        };

        menuService.openScreen(player, screen);
        drainMain();

        InventoryView view = player.getOpenInventory();

        InventoryDragEvent event = new InventoryDragEvent(
                view,
                null,
                null,
                false,
                java.util.Map.of(0, new org.bukkit.inventory.ItemStack(org.bukkit.Material.DIRT)));

        menuListener.onInventoryDrag(event);

        assertThat(event.isCancelled()).isTrue();
    }

    @Test
    @DisplayName("MenuListener cleans up active screen on inventory close")
    void listenerCleansUpOnClose() {
        PlayerMock player = server.addPlayer();

        GuiScreen screen = new GuiScreen() {
            @Override
            public Inventory render(Player p) {
                MenuHolder holder = new MenuHolder(this);
                Inventory inv = Bukkit.createInventory(holder, 27, Component.text("Close Test"));
                holder.setInventory(inv);
                return inv;
            }

            @Override
            public void handleClick(Player p, int slot, ClickType clickType) {}

            @Override
            public void refresh(Player p) {}
        };

        menuService.openScreen(player, screen);
        drainMain();
        assertThat(menuService.activeScreen(player)).contains(screen);

        InventoryCloseEvent event = new InventoryCloseEvent(player.getOpenInventory());
        menuListener.onInventoryClose(event);

        assertThat(menuService.activeScreen(player)).isEmpty();
    }

    @Test
    @DisplayName("MenuListener cleans up active screen on player quit")
    void listenerCleansUpOnQuit() {
        PlayerMock player = server.addPlayer();

        GuiScreen screen = new GuiScreen() {
            @Override
            public Inventory render(Player p) {
                MenuHolder holder = new MenuHolder(this);
                Inventory inv = Bukkit.createInventory(holder, 27, Component.text("Quit Test"));
                holder.setInventory(inv);
                return inv;
            }

            @Override
            public void handleClick(Player p, int slot, ClickType clickType) {}

            @Override
            public void refresh(Player p) {}
        };

        menuService.openScreen(player, screen);
        drainMain();
        assertThat(menuService.activeScreen(player)).contains(screen);

        PlayerQuitEvent event =
                new PlayerQuitEvent(player, Component.text("Quit"), PlayerQuitEvent.QuitReason.DISCONNECTED);
        menuListener.onPlayerQuit(event);

        assertThat(menuService.activeScreen(player)).isEmpty();
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
}
