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
import nl.gzmn.playerworlds.core.model.Role;
import nl.gzmn.playerworlds.core.model.Visibility;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.model.WorldSettings;
import nl.gzmn.playerworlds.core.model.WorldState;
import nl.gzmn.playerworlds.testing.TestDatabase;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class SocialAndSettingsScreensTest {

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
    @DisplayName("MembersMenu renders member list, left-click promotes, right-click opens kick confirm, back navigates")
    void membersMenuRendersAndInteracts() throws Exception {
        RecordingPlayerMock player = createRecordingPlayer("Alice");
        WorldId worldId = WorldId.random();
        onDb(() ->
                worldRepository.create(worldId, player.getUniqueId(), "alice-world", 111L, 5000, Visibility.PRIVATE));
        PlayerWorld world = createTestWorld(worldId, player.getUniqueId(), "alice-world");

        UUID bobUuid = UUID.randomUUID();
        MembersMenu.MemberEntry ownerEntry =
                new MembersMenu.MemberEntry(player.getUniqueId(), "Alice", Role.OWNER, Instant.now());
        MembersMenu.MemberEntry visitorEntry = new MembersMenu.MemberEntry(bobUuid, "Bob", Role.VISITOR, Instant.now());

        MembersMenu menu = new MembersMenu(menuService, channel, world, List.of(ownerEntry, visitorEntry), 0);
        Inventory inv = menu.render(player);

        assertThat(inv.getSize()).isEqualTo(54);
        assertThat(inv.getItem(0)).isNotNull();
        assertThat(inv.getItem(0).getType()).isEqualTo(Material.PLAYER_HEAD);
        assertThat(inv.getItem(1)).isNotNull();
        assertThat(inv.getItem(1).getType()).isEqualTo(Material.PLAYER_HEAD);
        assertThat(inv.getItem(MembersMenu.SLOT_BACK).getType()).isEqualTo(Material.OAK_DOOR);
        assertThat(inv.getItem(MembersMenu.SLOT_INVITE).getType()).isEqualTo(Material.EMERALD);

        // Click owner slot (slot 0) - no action
        menu.handleClick(player, 0, ClickType.LEFT);
        assertThat(player.sentMessages).isEmpty();

        // Left-click visitor slot (slot 1) -> PromoteMember intent
        menu.handleClick(player, 1, ClickType.LEFT);
        byte[] promoteMsg = player.nextSentMessage();
        IntentEnvelope promoteEnv = (IntentEnvelope) MenuCodec.decode(promoteMsg);
        assertThat(promoteEnv.intent()).isEqualTo(new MenuIntent.PromoteMember("Bob", worldId));

        // Right-click visitor slot (slot 1) -> ConfirmMenu modal
        menu.handleClick(player, 1, ClickType.RIGHT);
        drainMain();
        assertThat(menuService.activeScreen(player)).isPresent();
        assertThat(menuService.activeScreen(player).get()).isInstanceOf(ConfirmMenu.class);

        // In ConfirmMenu: click confirm -> KickMember intent
        ConfirmMenu confirm = (ConfirmMenu) menuService.activeScreen(player).get();
        confirm.handleClick(player, ConfirmMenu.SLOT_CONFIRM, ClickType.LEFT);
        byte[] kickMsg = player.nextSentMessage();
        IntentEnvelope kickEnv = (IntentEnvelope) MenuCodec.decode(kickMsg);
        assertThat(kickEnv.intent()).isEqualTo(new MenuIntent.KickMember("Bob", worldId));

        // Back button (slot 48) -> openWorldMenu
        menu.handleClick(player, MembersMenu.SLOT_BACK, ClickType.LEFT);
        awaitCondition(() -> {
            drainMain();
            return menuService.activeScreen(player).isPresent()
                    && menuService.activeScreen(player).get() instanceof WorldMenu;
        });
        assertThat(menuService.activeScreen(player).get()).isInstanceOf(WorldMenu.class);
    }

    @Test
    @DisplayName("InvitesMenu renders incoming invites and transfers, accepts and declines correctly")
    void invitesMenuRendersAndInteracts() throws Exception {
        RecordingPlayerMock player = createRecordingPlayer("Charlie");
        WorldId world1Id = WorldId.random();
        WorldId world2Id = WorldId.random();

        UUID sender1 = UUID.randomUUID();
        UUID sender2 = UUID.randomUUID();

        InvitesMenu.InviteEntry invite = new InvitesMenu.InviteEntry(
                world1Id, "world-alpha", sender1, "Alice", Instant.now().plusSeconds(3600), false);
        InvitesMenu.InviteEntry transfer = new InvitesMenu.InviteEntry(
                world2Id, "world-beta", sender2, "Bob", Instant.now().plusSeconds(3600), true);

        InvitesMenu menu = new InvitesMenu(menuService, channel, List.of(invite, transfer), 0);
        Inventory inv = menu.render(player);

        assertThat(inv.getSize()).isEqualTo(54);
        assertThat(inv.getItem(0)).isNotNull();
        assertThat(inv.getItem(0).getType()).isEqualTo(Material.WRITABLE_BOOK);
        assertThat(inv.getItem(1)).isNotNull();
        assertThat(inv.getItem(1).getType()).isEqualTo(Material.NETHER_STAR);
        assertThat(inv.getItem(InvitesMenu.SLOT_BACK).getType()).isEqualTo(Material.OAK_DOOR);

        // Left-click invite (slot 0) -> AcceptInvite intent
        menu.handleClick(player, 0, ClickType.LEFT);
        byte[] acceptInviteMsg = player.nextSentMessage();
        IntentEnvelope acceptInviteEnv = (IntentEnvelope) MenuCodec.decode(acceptInviteMsg);
        assertThat(acceptInviteEnv.intent()).isEqualTo(new MenuIntent.AcceptInvite("Alice"));

        // Right-click invite (slot 0) -> DeclineTransfer / Dismiss intent
        menu.handleClick(player, 0, ClickType.RIGHT);
        byte[] declineInviteMsg = player.nextSentMessage();
        IntentEnvelope declineInviteEnv = (IntentEnvelope) MenuCodec.decode(declineInviteMsg);
        assertThat(declineInviteEnv.intent()).isEqualTo(new MenuIntent.DeclineTransfer("Alice"));

        // Left-click transfer (slot 1) -> AcceptTransfer intent
        menu.handleClick(player, 1, ClickType.LEFT);
        byte[] acceptTransferMsg = player.nextSentMessage();
        IntentEnvelope acceptTransferEnv = (IntentEnvelope) MenuCodec.decode(acceptTransferMsg);
        assertThat(acceptTransferEnv.intent()).isEqualTo(new MenuIntent.AcceptTransfer("Bob"));

        // Right-click transfer (slot 1) -> DeclineTransfer intent
        menu.handleClick(player, 1, ClickType.RIGHT);
        byte[] declineTransferMsg = player.nextSentMessage();
        IntentEnvelope declineTransferEnv = (IntentEnvelope) MenuCodec.decode(declineTransferMsg);
        assertThat(declineTransferEnv.intent()).isEqualTo(new MenuIntent.DeclineTransfer("Bob"));

        // Back button (slot 48) -> openMainMenu
        menu.handleClick(player, InvitesMenu.SLOT_BACK, ClickType.LEFT);
        awaitCondition(() -> {
            drainMain();
            return menuService.activeScreen(player).isPresent()
                    && menuService.activeScreen(player).get() instanceof MainMenu;
        });
        assertThat(menuService.activeScreen(player).get()).isInstanceOf(MainMenu.class);
    }

    @Test
    @DisplayName("BansMenu renders banned players, unbans on click, and handles empty state")
    void bansMenuRendersAndInteracts() throws Exception {
        RecordingPlayerMock player = createRecordingPlayer("Dave");
        WorldId worldId = WorldId.random();
        onDb(() -> worldRepository.create(worldId, player.getUniqueId(), "dave-world", 111L, 5000, Visibility.PRIVATE));
        PlayerWorld world = createTestWorld(worldId, player.getUniqueId(), "dave-world");

        UUID bannedUuid = UUID.randomUUID();
        BansMenu.BanEntry banEntry =
                new BansMenu.BanEntry(bannedUuid, "Griefer123", "Griefing near spawn", Instant.now());

        BansMenu menu = new BansMenu(menuService, channel, world, List.of(banEntry), 0);
        Inventory inv = menu.render(player);

        assertThat(inv.getSize()).isEqualTo(54);
        assertThat(inv.getItem(0)).isNotNull();
        assertThat(inv.getItem(0).getType()).isEqualTo(Material.PLAYER_HEAD);
        assertThat(inv.getItem(BansMenu.SLOT_BACK).getType()).isEqualTo(Material.OAK_DOOR);

        // Click ban (slot 0) -> UnbanPlayer intent
        menu.handleClick(player, 0, ClickType.LEFT);
        byte[] unbanMsg = player.nextSentMessage();
        IntentEnvelope unbanEnv = (IntentEnvelope) MenuCodec.decode(unbanMsg);
        assertThat(unbanEnv.intent()).isEqualTo(new MenuIntent.UnbanPlayer("Griefer123", worldId));

        // Back button (slot 48) -> openWorldMenu
        menu.handleClick(player, BansMenu.SLOT_BACK, ClickType.LEFT);
        awaitCondition(() -> {
            drainMain();
            return menuService.activeScreen(player).isPresent()
                    && menuService.activeScreen(player).get() instanceof WorldMenu;
        });
        assertThat(menuService.activeScreen(player).get()).isInstanceOf(WorldMenu.class);

        // Empty bans menu
        BansMenu emptyMenu = new BansMenu(menuService, channel, world, List.of(), 0);
        Inventory emptyInv = emptyMenu.render(player);
        assertThat(emptyInv.getItem(22)).isNotNull();
        assertThat(emptyInv.getItem(22).getType()).isEqualTo(Material.IRON_BARS);
    }

    @Test
    @DisplayName("SettingsMenu renders setting toggle buttons and dispatches SetSetting intents")
    void settingsMenuRendersAndToggles() throws Exception {
        RecordingPlayerMock player = createRecordingPlayer("Eve");
        WorldId worldId = WorldId.random();
        onDb(() -> worldRepository.create(worldId, player.getUniqueId(), "eve-world", 111L, 5000, Visibility.PRIVATE));
        PlayerWorld world = createTestWorld(worldId, player.getUniqueId(), "eve-world");

        WorldSettings settings = new WorldSettings(false, false, true, true);
        SettingsMenu menu = new SettingsMenu(menuService, channel, world, settings);
        Inventory inv = menu.render(player);

        assertThat(inv.getSize()).isEqualTo(27);
        assertThat(inv.getItem(SettingsMenu.SLOT_INFO).getType()).isEqualTo(Material.BEACON);
        assertThat(inv.getItem(SettingsMenu.SLOT_PVP).getType()).isEqualTo(Material.DIAMOND_SWORD);
        assertThat(inv.getItem(SettingsMenu.SLOT_CONTAINERS).getType()).isEqualTo(Material.CHEST);
        assertThat(inv.getItem(SettingsMenu.SLOT_INTERACT).getType()).isEqualTo(Material.LEVER);
        assertThat(inv.getItem(SettingsMenu.SLOT_MOB_GRIEFING).getType()).isEqualTo(Material.CREEPER_HEAD);
        assertThat(inv.getItem(SettingsMenu.SLOT_BACK).getType()).isEqualTo(Material.OAK_DOOR);

        // Toggle PvP (slot 10: false -> true)
        menu.handleClick(player, SettingsMenu.SLOT_PVP, ClickType.LEFT);
        byte[] pvpMsg = player.nextSentMessage();
        IntentEnvelope pvpEnv = (IntentEnvelope) MenuCodec.decode(pvpMsg);
        assertThat(pvpEnv.intent()).isEqualTo(new MenuIntent.SetSetting(worldId, "pvp", "true"));

        // Toggle Containers (slot 12: false -> true)
        menu.handleClick(player, SettingsMenu.SLOT_CONTAINERS, ClickType.LEFT);
        byte[] contMsg = player.nextSentMessage();
        IntentEnvelope contEnv = (IntentEnvelope) MenuCodec.decode(contMsg);
        assertThat(contEnv.intent()).isEqualTo(new MenuIntent.SetSetting(worldId, "containers", "true"));

        // Toggle Interact (slot 14: true -> false)
        menu.handleClick(player, SettingsMenu.SLOT_INTERACT, ClickType.LEFT);
        byte[] intMsg = player.nextSentMessage();
        IntentEnvelope intEnv = (IntentEnvelope) MenuCodec.decode(intMsg);
        assertThat(intEnv.intent()).isEqualTo(new MenuIntent.SetSetting(worldId, "interact", "false"));

        // Toggle Mob Griefing (slot 16: true -> false)
        menu.handleClick(player, SettingsMenu.SLOT_MOB_GRIEFING, ClickType.LEFT);
        byte[] mobMsg = player.nextSentMessage();
        IntentEnvelope mobEnv = (IntentEnvelope) MenuCodec.decode(mobMsg);
        assertThat(mobEnv.intent()).isEqualTo(new MenuIntent.SetSetting(worldId, "mob-griefing", "false"));

        // Back button (slot 22) -> openWorldMenu
        menu.handleClick(player, SettingsMenu.SLOT_BACK, ClickType.LEFT);
        awaitCondition(() -> {
            drainMain();
            return menuService.activeScreen(player).isPresent()
                    && menuService.activeScreen(player).get() instanceof WorldMenu;
        });
        assertThat(menuService.activeScreen(player).get()).isInstanceOf(WorldMenu.class);
    }

    @Test
    @DisplayName("MenuService async loaders load data from repositories and open social/settings screens")
    void menuServiceLoadsDataAndOpensScreens() throws Exception {
        PlayerMock player = server.addPlayer();
        WorldId worldId = WorldId.random();
        UUID otherPlayer = UUID.randomUUID();

        onDb(() -> {
            worldRepository.create(worldId, player.getUniqueId(), "test-world", 111L, 5000, Visibility.PRIVATE);
            nameRepository.remember(player.getUniqueId(), "PlayerOne");
            nameRepository.remember(otherPlayer, "PlayerTwo");
            database.inTransaction(conn -> {
                membershipRepository.insertMember(conn, worldId, otherPlayer, Role.BUILDER, player.getUniqueId());
                return null;
            });
            membershipRepository.invite(worldId, player.getUniqueId(), otherPlayer, Duration.ofMinutes(30));
            transferRepository.requestTransfer(worldId, player.getUniqueId(), otherPlayer, Duration.ofMinutes(30));
            banRepository.ban(worldId, otherPlayer, player.getUniqueId(), "Bad behavior");
            return null;
        });

        // 1. openMembersMenu
        CompletableFuture<Void> f1 = menuService.openMembersMenu(player, worldId);
        awaitCondition(() -> {
            drainMain();
            return f1.isDone();
        });
        assertThat(menuService.activeScreen(player)).isPresent();
        assertThat(menuService.activeScreen(player).get()).isInstanceOf(MembersMenu.class);
        MembersMenu membersScreen =
                (MembersMenu) menuService.activeScreen(player).get();
        assertThat(membersScreen.members()).hasSize(2);

        // 2. openInvitesMenu
        CompletableFuture<Void> f2 = menuService.openInvitesMenu(player);
        awaitCondition(() -> {
            drainMain();
            return f2.isDone();
        });
        assertThat(menuService.activeScreen(player)).isPresent();
        assertThat(menuService.activeScreen(player).get()).isInstanceOf(InvitesMenu.class);
        InvitesMenu invitesScreen =
                (InvitesMenu) menuService.activeScreen(player).get();
        assertThat(invitesScreen.invites()).hasSize(2);

        // 3. openBansMenu
        CompletableFuture<Void> f3 = menuService.openBansMenu(player, worldId);
        awaitCondition(() -> {
            drainMain();
            return f3.isDone();
        });
        assertThat(menuService.activeScreen(player)).isPresent();
        assertThat(menuService.activeScreen(player).get()).isInstanceOf(BansMenu.class);
        BansMenu bansScreen = (BansMenu) menuService.activeScreen(player).get();
        assertThat(bansScreen.bans()).hasSize(1);
        assertThat(bansScreen.bans().getFirst().name()).isEqualTo("PlayerTwo");

        // 4. openSettingsMenu
        CompletableFuture<Void> f4 = menuService.openSettingsMenu(player, worldId);
        awaitCondition(() -> {
            drainMain();
            return f4.isDone();
        });
        assertThat(menuService.activeScreen(player)).isPresent();
        assertThat(menuService.activeScreen(player).get()).isInstanceOf(SettingsMenu.class);
    }

    private PlayerWorld createTestWorld(WorldId id, UUID owner, String name) {
        return new PlayerWorld(
                id,
                owner,
                name,
                id.folder(),
                12345L,
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
                1024L * 1024L * 10L);
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
