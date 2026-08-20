package nl.gzmn.playerworlds.proxy.menu;

import static org.assertj.core.api.Assertions.assertThat;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.db.Database;
import nl.gzmn.playerworlds.core.db.MembershipRepository;
import nl.gzmn.playerworlds.core.db.NodeCommandRepository;
import nl.gzmn.playerworlds.core.db.NodeRepository;
import nl.gzmn.playerworlds.core.db.PendingTransferRepository;
import nl.gzmn.playerworlds.core.db.PlayerNameRepository;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository;
import nl.gzmn.playerworlds.core.db.Schema;
import nl.gzmn.playerworlds.core.db.TransferRequestRepository;
import nl.gzmn.playerworlds.core.db.WorldBanRepository;
import nl.gzmn.playerworlds.core.menu.CloseMenuMessage;
import nl.gzmn.playerworlds.core.menu.FailureCode;
import nl.gzmn.playerworlds.core.menu.MenuClickIntent;
import nl.gzmn.playerworlds.core.menu.MenuClosedNotice;
import nl.gzmn.playerworlds.core.menu.MenuCodec;
import nl.gzmn.playerworlds.core.menu.MenuIntent;
import nl.gzmn.playerworlds.core.menu.MenuResult;
import nl.gzmn.playerworlds.core.menu.OpenMenu;
import nl.gzmn.playerworlds.core.menu.RenderMenuPayload;
import nl.gzmn.playerworlds.core.model.Role;
import nl.gzmn.playerworlds.core.model.Visibility;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.model.WorldState;
import nl.gzmn.playerworlds.proxy.command.WorldActions;
import nl.gzmn.playerworlds.proxy.command.WorldCommand;
import nl.gzmn.playerworlds.proxy.node.NodeRegistry;
import nl.gzmn.playerworlds.proxy.node.Placement;
import nl.gzmn.playerworlds.proxy.permission.WorldPermissions;
import nl.gzmn.playerworlds.testing.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MenuChannelListenerTest {

    private Database database;
    private PluginExecutors executors;
    private PlayerWorldRepository worlds;
    private MembershipRepository membership;
    private TransferRequestRepository transferRequests;
    private WorldBanRepository bans;
    private PlayerNameRepository names;
    private NodeRepository nodeRepo;
    private NodeRegistry registry;
    private PendingTransferRepository transfers;
    private NodeCommandRepository nodeCommands;
    private NetworkPolicy policy;
    private ProxyServer proxy;
    private WorldActions actions;
    private MenuChannelListener listener;

    private Map<UUID, List<Component>> messagesByPlayer;
    private Map<UUID, Player> playersByUuid;
    private Map<String, Player> playersByName;
    private Map<String, RegisteredServer> registeredServers;

    @BeforeEach
    void setUp() throws Exception {
        database = TestDatabase.openFresh();
        Schema.migrate(database);
        executors = PluginExecutors.create(2, 2, Runnable::run);
        policy = NetworkPolicy.defaults();

        messagesByPlayer = new ConcurrentHashMap<>();
        playersByUuid = new ConcurrentHashMap<>();
        playersByName = new ConcurrentHashMap<>();
        registeredServers = new ConcurrentHashMap<>();
        proxy = mockProxy(playersByUuid, playersByName, registeredServers);

        worlds = new PlayerWorldRepository(database);
        membership = new MembershipRepository(database);
        transferRequests = new TransferRequestRepository(database);
        bans = new WorldBanRepository(database);
        names = new PlayerNameRepository(database);
        nodeRepo = new NodeRepository(database);
        registry = new NodeRegistry(proxy, nodeRepo);
        transfers = new PendingTransferRepository(database);
        nodeCommands = new NodeCommandRepository(database);

        actions = new WorldActions(
                proxy,
                executors,
                worlds,
                membership,
                transferRequests,
                bans,
                names,
                transfers,
                registry,
                new Placement(nodeRepo, worlds),
                nodeCommands,
                database,
                () -> policy);
        MenuViewService viewService =
                new MenuViewService(worlds, membership, transferRequests, bans, names, () -> policy, executors);
        listener = new MenuChannelListener(actions, viewService);
    }

    @AfterEach
    void tearDown() {
        executors.close();
        database.close();
    }

    @Test
    void clientSourcedMessageIsRejectedAndHandled() throws Exception {
        UUID playerId = UUID.randomUUID();
        Player player = mockPlayer(playerId, "Alice");
        List<byte[]> sentMessages = Collections.synchronizedList(new ArrayList<>());
        ServerConnection connection = mockServerConnection(player, sentMessages);
        byte[] payload = MenuCodec.encodeIntent(1L, new MenuIntent.CreateWorld("testworld", null));

        PluginMessageEvent event =
                new PluginMessageEvent(player, connection, MenuChannelListener.CHANNEL_IDENTIFIER, payload);

        listener.onPluginMessage(event);

        assertThat(event.getResult().isAllowed()).isFalse();
        assertThat(worlds.listOwnedBy(playerId)).isEmpty();
    }

    @Test
    void nonMenuChannelIsIgnored() {
        UUID playerId = UUID.randomUUID();
        Player player = mockPlayer(playerId, "Alice");
        List<byte[]> sentMessages = Collections.synchronizedList(new ArrayList<>());
        ServerConnection connection = mockServerConnection(player, sentMessages);

        MinecraftChannelIdentifier otherChannel = MinecraftChannelIdentifier.from("custom:channel");
        PluginMessageEvent event = new PluginMessageEvent(connection, player, otherChannel, new byte[] {1, 2, 3});

        listener.onPluginMessage(event);

        assertThat(event.getResult().isAllowed()).isTrue();
        assertThat(sentMessages).isEmpty();
    }

    @Test
    void serverSourcedCreateWorldDispatchesAndSendsOkResult() throws Exception {
        UUID playerId = UUID.randomUUID();
        Player player = mockPlayer(playerId, "Alice");
        playersByUuid.put(playerId, player);
        playersByName.put("Alice", player);
        names.remember(playerId, "Alice");

        nodeRepo.heartbeat("node-1", "127.0.0.1:25565", 0, 0, 10, 20.0, false, 3000, "1.21.4");
        registry.sync(policy.deadAfter());

        List<byte[]> sentMessages = Collections.synchronizedList(new ArrayList<>());
        ServerConnection connection = mockServerConnection(player, sentMessages);

        byte[] payload = MenuCodec.encodeIntent(42L, new MenuIntent.CreateWorld("my-menu-world", null));
        PluginMessageEvent event =
                new PluginMessageEvent(connection, player, MenuChannelListener.CHANNEL_IDENTIFIER, payload);

        listener.onPluginMessage(event);

        assertThat(event.getResult().isAllowed()).isFalse();

        awaitCondition(() -> !sentMessages.isEmpty());

        assertThat(sentMessages).hasSize(1);
        MenuResult result = MenuCodec.decodeResult(sentMessages.getFirst());
        assertThat(result).isInstanceOf(MenuResult.Ok.class);
        assertThat(result.correlationId()).isEqualTo(42L);
        assertThat(result.message()).contains("my-menu-world");

        assertThat(worlds.listOwnedBy(playerId)).hasSize(1);
    }

    @Test
    void serverSourcedActionFailureSendsFailedResultWithMappedCode() throws Exception {
        UUID playerId = UUID.randomUUID();
        Player player = mockPlayer(playerId, "Alice");
        playersByUuid.put(playerId, player);
        playersByName.put("Alice", player);

        // Bob not found -> FailureCode.PLAYER_NOT_FOUND
        WorldId worldId = WorldId.random();
        worlds.create(worldId, playerId, "world1", 1L, 5000, Visibility.PRIVATE);

        List<byte[]> sentMessages = Collections.synchronizedList(new ArrayList<>());
        ServerConnection connection = mockServerConnection(player, sentMessages);

        byte[] payload = MenuCodec.encodeIntent(99L, new MenuIntent.InviteMember("Bob", worldId));
        PluginMessageEvent event =
                new PluginMessageEvent(connection, player, MenuChannelListener.CHANNEL_IDENTIFIER, payload);

        listener.onPluginMessage(event);

        assertThat(event.getResult().isAllowed()).isFalse();

        awaitCondition(() -> !sentMessages.isEmpty());

        assertThat(sentMessages).hasSize(1);
        MenuResult result = MenuCodec.decodeResult(sentMessages.getFirst());
        assertThat(result).isInstanceOf(MenuResult.Failed.class);
        MenuResult.Failed failed = (MenuResult.Failed) result;
        assertThat(failed.correlationId()).isEqualTo(99L);
        assertThat(failed.code()).isEqualTo(FailureCode.PLAYER_NOT_FOUND);
        assertThat(failed.message()).contains("no player called 'Bob'");
    }

    @Test
    void setPublicViaMenuRefusesCallerWithoutPublicPermission_FR9h_R5() throws Exception {
        // D14: permission is a property of the action. The GUI path must not bypass
        // gzmn.worlds.public the way Brigadier .requires once gated only the command tree.
        UUID playerId = UUID.randomUUID();
        Player player = mockPlayer(playerId, "Alice", permission -> Tristate.UNDEFINED);
        playersByUuid.put(playerId, player);
        playersByName.put("Alice", player);

        WorldId worldId = WorldId.random();
        worlds.create(worldId, playerId, "private-world", 1L, 5000, Visibility.PRIVATE);
        worlds.transitionState(worldId, WorldState.CREATING, WorldState.READY);

        List<byte[]> sentMessages = Collections.synchronizedList(new ArrayList<>());
        ServerConnection connection = mockServerConnection(player, sentMessages);

        byte[] payload = MenuCodec.encodeIntent(77L, new MenuIntent.SetVisibility(worldId, Visibility.PUBLIC));
        PluginMessageEvent event =
                new PluginMessageEvent(connection, player, MenuChannelListener.CHANNEL_IDENTIFIER, payload);

        listener.onPluginMessage(event);

        awaitCondition(() -> !sentMessages.isEmpty());

        MenuResult result = MenuCodec.decodeResult(sentMessages.getFirst());
        assertThat(result).isInstanceOf(MenuResult.Failed.class);
        MenuResult.Failed failed = (MenuResult.Failed) result;
        assertThat(failed.correlationId()).isEqualTo(77L);
        assertThat(failed.code()).isEqualTo(FailureCode.PERMISSION_DENIED);
        assertThat(failed.message()).containsIgnoringCase("permission");
        assertThat(worlds.findById(worldId).orElseThrow().visibility()).isEqualTo(Visibility.PRIVATE);
        assertThat(WorldPermissions.allows(player, WorldPermissions.PUBLIC)).isFalse();
    }

    @Test
    void serverSourcedArchiveWorldDispatchesWithConfirmation() throws Exception {
        UUID playerId = UUID.randomUUID();
        Player player = mockPlayer(playerId, "Alice");
        playersByUuid.put(playerId, player);
        playersByName.put("Alice", player);

        nodeRepo.heartbeat("node-1", "127.0.0.1:25565", 0, 0, 10, 20.0, false, 3000, "1.21.4");
        registry.sync(policy.deadAfter());

        WorldId worldId = WorldId.random();
        worlds.create(worldId, playerId, "archive-me", 1L, 5000, Visibility.PRIVATE);
        worlds.transitionState(worldId, WorldState.CREATING, WorldState.READY);

        List<byte[]> sentMessages = Collections.synchronizedList(new ArrayList<>());
        ServerConnection connection = mockServerConnection(player, sentMessages);

        byte[] payload = MenuCodec.encodeIntent(101L, new MenuIntent.ArchiveWorld("archive-me"));
        PluginMessageEvent event =
                new PluginMessageEvent(connection, player, MenuChannelListener.CHANNEL_IDENTIFIER, payload);

        listener.onPluginMessage(event);

        assertThat(event.getResult().isAllowed()).isFalse();

        awaitCondition(() -> !sentMessages.isEmpty());

        MenuResult result = MenuCodec.decodeResult(sentMessages.getFirst());
        assertThat(result).isInstanceOf(MenuResult.Ok.class);
        assertThat(result.correlationId()).isEqualTo(101L);
        assertThat(result.message()).contains("archiving 'archive-me'");
    }

    @Test
    void malformedPayloadIsHandledGracefully() {
        UUID playerId = UUID.randomUUID();
        Player player = mockPlayer(playerId, "Alice");
        List<byte[]> sentMessages = Collections.synchronizedList(new ArrayList<>());
        ServerConnection connection = mockServerConnection(player, sentMessages);

        PluginMessageEvent event = new PluginMessageEvent(
                connection, player, MenuChannelListener.CHANNEL_IDENTIFIER, new byte[] {0x7F, 0x01, 0x02});

        listener.onPluginMessage(event);

        assertThat(event.getResult().isAllowed()).isFalse();
        assertThat(sentMessages).isEmpty();
    }

    @Test
    void bareWorldCommandWithServerConnectionSendsOpenMenu() throws Exception {
        UUID playerId = UUID.randomUUID();
        List<byte[]> sentMessages = Collections.synchronizedList(new ArrayList<>());
        Player player = mockPlayerWithServer(playerId, "Alice", sentMessages);

        com.mojang.brigadier.CommandDispatcher<CommandSource> dispatcher =
                new com.mojang.brigadier.CommandDispatcher<>();
        WorldCommand worldCommand = new WorldCommand(
                actions, proxy, executors, worlds, new Placement(nodeRepo, worlds), nodeCommands, () -> policy);
        dispatcher.getRoot().addChild(worldCommand.build().getNode());

        dispatcher.execute("world", player);

        assertThat(sentMessages).hasSize(1);
        OpenMenu openMenu = MenuCodec.decodeOpenMenu(sentMessages.getFirst());
        assertThat(openMenu.correlationId()).isPositive();
    }

    @Test
    void hardDeleteWorldIntentDispatchesAndSendsOkResult() throws Exception {
        // R23: hard deletion is routed to a node, so there has to be one.
        nodeRepo.heartbeat("paper-a", "127.0.0.1:25566", 0, 0, 40, 20.0, false, 4903, "26.2");
        UUID playerId = UUID.randomUUID();
        Player player = mockPlayer(playerId, "Alice");
        playersByUuid.put(playerId, player);
        playersByName.put("Alice", player);
        names.remember(playerId, "Alice");

        WorldId worldId = WorldId.random();
        worlds.create(worldId, playerId, "archivedworld", 12345L, 5000, Visibility.PRIVATE);
        worlds.transitionState(worldId, WorldState.CREATING, WorldState.READY);
        worlds.transitionState(worldId, WorldState.READY, WorldState.ARCHIVED);

        List<byte[]> sentMessages = Collections.synchronizedList(new ArrayList<>());
        ServerConnection connection = mockServerConnection(player, sentMessages);

        byte[] payload = MenuCodec.encodeIntent(99L, new MenuIntent.HardDeleteWorld(worldId));
        PluginMessageEvent event =
                new PluginMessageEvent(connection, player, MenuChannelListener.CHANNEL_IDENTIFIER, payload);

        listener.onPluginMessage(event);

        awaitCondition(() -> !sentMessages.isEmpty());

        assertThat(sentMessages).hasSize(1);
        MenuResult result = MenuCodec.decodeResult(sentMessages.getFirst());
        assertThat(result).isInstanceOf(MenuResult.Ok.class);
        MenuResult.Ok ok = (MenuResult.Ok) result;
        assertThat(ok.correlationId()).isEqualTo(99L);
        assertThat(ok.message()).contains("permanently deleting 'archivedworld' and its archives");
        // The node deletes the objects and then the row (R23, FR-37); the proxy
        // has no object-store client and must not remove the row on its own.
        assertThat(worlds.findById(worldId)).isPresent();
    }

    @Test
    void bareWorldsCommandWithServerConnectionSendsOpenMenu() throws Exception {
        UUID playerId = UUID.randomUUID();
        List<byte[]> sentMessages = Collections.synchronizedList(new ArrayList<>());
        Player player = mockPlayerWithServer(playerId, "Alice", sentMessages);

        com.mojang.brigadier.CommandDispatcher<CommandSource> dispatcher =
                new com.mojang.brigadier.CommandDispatcher<>();
        WorldCommand worldCommand = new WorldCommand(
                actions, proxy, executors, worlds, new Placement(nodeRepo, worlds), nodeCommands, () -> policy);
        dispatcher.getRoot().addChild(worldCommand.buildWorlds().getNode());

        dispatcher.execute("worlds", player);

        assertThat(sentMessages).hasSize(1);
        OpenMenu openMenu = MenuCodec.decodeOpenMenu(sentMessages.getFirst());
        assertThat(openMenu.correlationId()).isPositive();
    }

    @Test
    void bareWorldCommandWithoutServerConnectionPrintsUsage() throws Exception {
        UUID playerId = UUID.randomUUID();
        Player player = mockPlayer(playerId, "Alice");

        com.mojang.brigadier.CommandDispatcher<CommandSource> dispatcher =
                new com.mojang.brigadier.CommandDispatcher<>();
        WorldCommand worldCommand = new WorldCommand(
                actions, proxy, executors, worlds, new Placement(nodeRepo, worlds), nodeCommands, () -> policy);
        dispatcher.getRoot().addChild(worldCommand.build().getNode());

        dispatcher.execute("world", player);

        assertThat(messagesByPlayer.get(playerId)).isNotNull();
        assertThat(messagesByPlayer.get(playerId))
                .anySatisfy(comp -> assertThat(
                                PlainTextComponentSerializer.plainText().serialize(comp))
                        .contains("/world <"));
    }

    private ConnectionRequestBuilder mockConnectionRequestBuilder() {
        return (ConnectionRequestBuilder) Proxy.newProxyInstance(
                ConnectionRequestBuilder.class.getClassLoader(),
                new Class<?>[] {ConnectionRequestBuilder.class},
                (proxy, method, args) -> {
                    if ("fireAndForget".equals(method.getName())) {
                        return null;
                    }
                    if ("connect".equals(method.getName())) {
                        return java.util.concurrent.CompletableFuture.completedFuture(null);
                    }
                    return proxy;
                });
    }

    private Player mockPlayer(UUID uuid, String name) {
        return mockPlayer(uuid, name, permission -> Tristate.TRUE);
    }

    private Player mockPlayer(UUID uuid, String name, Function<String, Tristate> permissions) {
        ConnectionRequestBuilder reqBuilder = mockConnectionRequestBuilder();
        return (Player) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {Player.class}, (proxyObj, method, args) -> {
                    if (method.getName().equals("getUniqueId")) return uuid;
                    if (method.getName().equals("getUsername")) return name;
                    if (method.getName().equals("getPermissionValue")) {
                        Tristate value = permissions.apply((String) args[0]);
                        return value != null ? value : Tristate.UNDEFINED;
                    }
                    if (method.getName().equals("hasPermission")) {
                        return permissions.apply((String) args[0]) == Tristate.TRUE;
                    }
                    if (method.getName().equals("getCurrentServer")) return Optional.empty();
                    if (method.getName().equals("createConnectionRequest")) return reqBuilder;
                    if (method.getName().equals("sendMessage")) {
                        messagesByPlayer
                                .computeIfAbsent(uuid, k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                                .add((Component) args[0]);
                        return null;
                    }
                    return null;
                });
    }

    private Player mockPlayerWithServer(UUID uuid, String name, List<byte[]> sentMessages) {
        ServerConnection connection = mockServerConnection(null, sentMessages);
        ConnectionRequestBuilder reqBuilder = mockConnectionRequestBuilder();
        return (Player) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {Player.class}, (proxyObj, method, args) -> {
                    if (method.getName().equals("getUniqueId")) return uuid;
                    if (method.getName().equals("getUsername")) return name;
                    if (method.getName().equals("getPermissionValue")) return Tristate.TRUE;
                    if (method.getName().equals("hasPermission")) return true;
                    if (method.getName().equals("getCurrentServer")) return Optional.of(connection);
                    if (method.getName().equals("createConnectionRequest")) return reqBuilder;
                    if (method.getName().equals("sendMessage")) {
                        messagesByPlayer
                                .computeIfAbsent(uuid, k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                                .add((Component) args[0]);
                        return null;
                    }
                    return null;
                });
    }

    private ServerConnection mockServerConnection(Player player, List<byte[]> sentMessages) {
        ServerInfo info =
                new ServerInfo("node-1", new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 25565));
        return (ServerConnection) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {ServerConnection.class}, (proxyObj, method, args) -> {
                    if (method.getName().equals("getPlayer")) return player;
                    if (method.getName().equals("getServerInfo")) return info;
                    if (method.getName().equals("sendPluginMessage")) {
                        sentMessages.add((byte[]) args[1]);
                        return true;
                    }
                    return null;
                });
    }

    private RegisteredServer mockRegisteredServer(ServerInfo info) {
        return (RegisteredServer) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {RegisteredServer.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getServerInfo")) return info;
                    return null;
                });
    }

    private ProxyServer mockProxy(
            Map<UUID, Player> byUuid, Map<String, Player> byName, Map<String, RegisteredServer> servers) {
        return (ProxyServer) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {ProxyServer.class}, (proxyObj, method, args) -> {
                    if (method.getName().equals("getPlayer")) {
                        if (args[0] instanceof UUID id) return Optional.ofNullable(byUuid.get(id));
                        if (args[0] instanceof String n) return Optional.ofNullable(byName.get(n));
                    }
                    if (method.getName().equals("getServer")) {
                        return Optional.ofNullable(servers.get(args[0]));
                    }
                    if (method.getName().equals("getAllServers")) {
                        return servers.values();
                    }
                    if (method.getName().equals("registerServer")) {
                        ServerInfo info = (ServerInfo) args[0];
                        RegisteredServer s = mockRegisteredServer(info);
                        servers.put(info.getName(), s);
                        return s;
                    }
                    if (method.getName().equals("unregisterServer")) {
                        ServerInfo info = (ServerInfo) args[0];
                        servers.remove(info.getName());
                        return null;
                    }
                    return null;
                });
    }

    private static void awaitCondition(java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while awaiting condition", e);
            }
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    @Test
    void aGuiActionDeliversItsMessageOnce_NFR5() throws Exception {
        UUID playerId = UUID.randomUUID();
        Player player = mockPlayer(playerId, "Alice");
        playersByUuid.put(playerId, player);
        playersByName.put("Alice", player);
        names.remember(playerId, "Alice");

        WorldId worldId = WorldId.random();
        worlds.create(worldId, playerId, "guiworld", 12345L, 5000, Visibility.PRIVATE);
        worlds.transitionState(worldId, WorldState.CREATING, WorldState.READY);

        List<byte[]> sentMessages = Collections.synchronizedList(new ArrayList<>());
        ServerConnection connection = mockServerConnection(player, sentMessages);

        byte[] payload = MenuCodec.encodeIntent(7L, new MenuIntent.SetVisibility(worldId, Visibility.PUBLIC));
        listener.onPluginMessage(
                new PluginMessageEvent(connection, player, MenuChannelListener.CHANNEL_IDENTIFIER, payload));
        awaitCondition(() -> !sentMessages.isEmpty());

        // The menu result carries the message. The player must not also have been
        // sent it down chat: info/success/error used to build and send, and the
        // built Component then went into the ActionResult the menu serialised.
        assertThat(sentMessages).hasSize(1);
        assertThat(messagesByPlayer.getOrDefault(playerId, List.of()))
                .as("a GUI action must not also write to chat")
                .isEmpty();
    }

    @Test
    void serverSourcedOpenMenuBuildsAndRendersMainMenu() throws Exception {
        UUID playerId = UUID.randomUUID();
        Player player = mockPlayer(playerId, "Alice");
        playersByUuid.put(playerId, player);
        playersByName.put("Alice", player);
        names.remember(playerId, "Alice");

        List<byte[]> sentMessages = Collections.synchronizedList(new ArrayList<>());
        ServerConnection connection = mockServerConnection(player, sentMessages);

        byte[] payload = MenuCodec.encodeOpenMenu(new OpenMenu(10L));
        PluginMessageEvent event =
                new PluginMessageEvent(connection, player, MenuChannelListener.CHANNEL_IDENTIFIER, payload);

        listener.onPluginMessage(event);

        assertThat(event.getResult().isAllowed()).isFalse();

        awaitCondition(() -> !sentMessages.isEmpty());

        assertThat(sentMessages).hasSize(1);
        RenderMenuPayload rendered = MenuCodec.decodeRenderMenu(sentMessages.getFirst());
        assertThat(rendered.correlationId()).isEqualTo(10L);
        assertThat(rendered.screenType()).isEqualTo("MAIN");
    }

    @Test
    void serverSourcedNavMainReturnsMainMenu() throws Exception {
        UUID playerId = UUID.randomUUID();
        Player player = mockPlayer(playerId, "Alice");
        playersByUuid.put(playerId, player);
        playersByName.put("Alice", player);
        names.remember(playerId, "Alice");

        List<byte[]> sentMessages = Collections.synchronizedList(new ArrayList<>());
        ServerConnection connection = mockServerConnection(player, sentMessages);

        byte[] payload = MenuCodec.encodeClickIntent(new MenuClickIntent(11L, "NAV:MAIN", 1));
        PluginMessageEvent event =
                new PluginMessageEvent(connection, player, MenuChannelListener.CHANNEL_IDENTIFIER, payload);

        listener.onPluginMessage(event);
        awaitCondition(() -> !sentMessages.isEmpty());

        assertThat(sentMessages).hasSize(1);
        RenderMenuPayload rendered = MenuCodec.decodeRenderMenu(sentMessages.getFirst());
        assertThat(rendered.correlationId()).isEqualTo(11L);
        assertThat(rendered.screenType()).isEqualTo("MAIN");
    }

    @Test
    void serverSourcedNavMyWorldsReturnsMyWorldsMenu() throws Exception {
        UUID playerId = UUID.randomUUID();
        Player player = mockPlayer(playerId, "Alice");
        playersByUuid.put(playerId, player);
        playersByName.put("Alice", player);
        names.remember(playerId, "Alice");

        List<byte[]> sentMessages = Collections.synchronizedList(new ArrayList<>());
        ServerConnection connection = mockServerConnection(player, sentMessages);

        byte[] payload = MenuCodec.encodeClickIntent(new MenuClickIntent(12L, "NAV:MY_WORLDS:0", 1));
        PluginMessageEvent event =
                new PluginMessageEvent(connection, player, MenuChannelListener.CHANNEL_IDENTIFIER, payload);

        listener.onPluginMessage(event);
        awaitCondition(() -> !sentMessages.isEmpty());

        assertThat(sentMessages).hasSize(1);
        RenderMenuPayload rendered = MenuCodec.decodeRenderMenu(sentMessages.getFirst());
        assertThat(rendered.correlationId()).isEqualTo(12L);
        assertThat(rendered.screenType()).isEqualTo("MY_WORLDS");
    }

    @Test
    void serverSourcedNavWorldReturnsWorldDetails() throws Exception {
        UUID playerId = UUID.randomUUID();
        Player player = mockPlayer(playerId, "Alice");
        playersByUuid.put(playerId, player);
        playersByName.put("Alice", player);
        names.remember(playerId, "Alice");

        WorldId worldId = WorldId.random();
        worlds.create(worldId, playerId, "detail-world", 1L, 5000, Visibility.PRIVATE);

        List<byte[]> sentMessages = Collections.synchronizedList(new ArrayList<>());
        ServerConnection connection = mockServerConnection(player, sentMessages);

        byte[] payload = MenuCodec.encodeClickIntent(new MenuClickIntent(14L, "NAV:WORLD:" + worldId.value(), 1));
        PluginMessageEvent event =
                new PluginMessageEvent(connection, player, MenuChannelListener.CHANNEL_IDENTIFIER, payload);

        listener.onPluginMessage(event);
        awaitCondition(() -> !sentMessages.isEmpty());

        assertThat(sentMessages).hasSize(1);
        RenderMenuPayload rendered = MenuCodec.decodeRenderMenu(sentMessages.getFirst());
        assertThat(rendered.correlationId()).isEqualTo(14L);
        assertThat(rendered.screenType()).isEqualTo("WORLD_DETAILS");
    }

    @Test
    void serverSourcedNavSettingsReturnsSettingsMenu() throws Exception {
        UUID playerId = UUID.randomUUID();
        Player player = mockPlayer(playerId, "Alice");
        playersByUuid.put(playerId, player);
        playersByName.put("Alice", player);
        names.remember(playerId, "Alice");

        WorldId worldId = WorldId.random();
        worlds.create(worldId, playerId, "settings-world", 1L, 5000, Visibility.PRIVATE);

        List<byte[]> sentMessages = Collections.synchronizedList(new ArrayList<>());
        ServerConnection connection = mockServerConnection(player, sentMessages);

        byte[] payload = MenuCodec.encodeClickIntent(new MenuClickIntent(15L, "NAV:SETTINGS:" + worldId.value(), 1));
        PluginMessageEvent event =
                new PluginMessageEvent(connection, player, MenuChannelListener.CHANNEL_IDENTIFIER, payload);

        listener.onPluginMessage(event);
        awaitCondition(() -> !sentMessages.isEmpty());

        assertThat(sentMessages).hasSize(1);
        RenderMenuPayload rendered = MenuCodec.decodeRenderMenu(sentMessages.getFirst());
        assertThat(rendered.correlationId()).isEqualTo(15L);
        assertThat(rendered.screenType()).isEqualTo("SETTINGS");
    }

    @Test
    void serverSourcedNavMembersReturnsMembersMenu() throws Exception {
        UUID playerId = UUID.randomUUID();
        Player player = mockPlayer(playerId, "Alice");
        playersByUuid.put(playerId, player);
        playersByName.put("Alice", player);
        names.remember(playerId, "Alice");

        WorldId worldId = WorldId.random();
        worlds.create(worldId, playerId, "members-world", 1L, 5000, Visibility.PRIVATE);

        List<byte[]> sentMessages = Collections.synchronizedList(new ArrayList<>());
        ServerConnection connection = mockServerConnection(player, sentMessages);

        byte[] payload =
                MenuCodec.encodeClickIntent(new MenuClickIntent(16L, "NAV:MEMBERS:" + worldId.value() + ":0", 1));
        PluginMessageEvent event =
                new PluginMessageEvent(connection, player, MenuChannelListener.CHANNEL_IDENTIFIER, payload);

        listener.onPluginMessage(event);
        awaitCondition(() -> !sentMessages.isEmpty());

        assertThat(sentMessages).hasSize(1);
        RenderMenuPayload rendered = MenuCodec.decodeRenderMenu(sentMessages.getFirst());
        assertThat(rendered.correlationId()).isEqualTo(16L);
        assertThat(rendered.screenType()).isEqualTo("MEMBERS");
    }

    @Test
    void serverSourcedNavStorageReturnsStorageMenu() throws Exception {
        UUID playerId = UUID.randomUUID();
        Player player = mockPlayer(playerId, "Alice");
        playersByUuid.put(playerId, player);
        playersByName.put("Alice", player);
        names.remember(playerId, "Alice");

        List<byte[]> sentMessages = Collections.synchronizedList(new ArrayList<>());
        ServerConnection connection = mockServerConnection(player, sentMessages);

        byte[] payload = MenuCodec.encodeClickIntent(new MenuClickIntent(17L, "NAV:STORAGE", 1));
        PluginMessageEvent event =
                new PluginMessageEvent(connection, player, MenuChannelListener.CHANNEL_IDENTIFIER, payload);

        listener.onPluginMessage(event);
        awaitCondition(() -> !sentMessages.isEmpty());

        assertThat(sentMessages).hasSize(1);
        RenderMenuPayload rendered = MenuCodec.decodeRenderMenu(sentMessages.getFirst());
        assertThat(rendered.correlationId()).isEqualTo(17L);
        assertThat(rendered.screenType()).isEqualTo("STORAGE");
    }

    @Test
    void serverSourcedNavInvitesReturnsInvitesMenu() throws Exception {
        UUID playerId = UUID.randomUUID();
        Player player = mockPlayer(playerId, "Alice");
        playersByUuid.put(playerId, player);
        playersByName.put("Alice", player);
        names.remember(playerId, "Alice");

        List<byte[]> sentMessages = Collections.synchronizedList(new ArrayList<>());
        ServerConnection connection = mockServerConnection(player, sentMessages);

        byte[] payload = MenuCodec.encodeClickIntent(new MenuClickIntent(18L, "NAV:INVITES:0", 1));
        PluginMessageEvent event =
                new PluginMessageEvent(connection, player, MenuChannelListener.CHANNEL_IDENTIFIER, payload);

        listener.onPluginMessage(event);
        awaitCondition(() -> !sentMessages.isEmpty());

        assertThat(sentMessages).hasSize(1);
        RenderMenuPayload rendered = MenuCodec.decodeRenderMenu(sentMessages.getFirst());
        assertThat(rendered.correlationId()).isEqualTo(18L);
        assertThat(rendered.screenType()).isEqualTo("INVITES");
    }

    @Test
    void serverSourcedNavBansReturnsBansMenu() throws Exception {
        UUID playerId = UUID.randomUUID();
        Player player = mockPlayer(playerId, "Alice");
        playersByUuid.put(playerId, player);
        playersByName.put("Alice", player);
        names.remember(playerId, "Alice");

        WorldId worldId = WorldId.random();
        worlds.create(worldId, playerId, "bans-world", 1L, 5000, Visibility.PRIVATE);

        List<byte[]> sentMessages = Collections.synchronizedList(new ArrayList<>());
        ServerConnection connection = mockServerConnection(player, sentMessages);

        byte[] payload = MenuCodec.encodeClickIntent(new MenuClickIntent(19L, "NAV:BANS:" + worldId.value() + ":0", 1));
        PluginMessageEvent event =
                new PluginMessageEvent(connection, player, MenuChannelListener.CHANNEL_IDENTIFIER, payload);

        listener.onPluginMessage(event);
        awaitCondition(() -> !sentMessages.isEmpty());

        assertThat(sentMessages).hasSize(1);
        RenderMenuPayload rendered = MenuCodec.decodeRenderMenu(sentMessages.getFirst());
        assertThat(rendered.correlationId()).isEqualTo(19L);
        assertThat(rendered.screenType()).isEqualTo("BANS");
    }

    @Test
    void serverSourcedNavBrowseReturnsBrowseMenu() throws Exception {
        UUID playerId = UUID.randomUUID();
        Player player = mockPlayer(playerId, "Alice");
        playersByUuid.put(playerId, player);
        playersByName.put("Alice", player);
        names.remember(playerId, "Alice");

        List<byte[]> sentMessages = Collections.synchronizedList(new ArrayList<>());
        ServerConnection connection = mockServerConnection(player, sentMessages);

        byte[] payload = MenuCodec.encodeClickIntent(new MenuClickIntent(20L, "NAV:BROWSE:0", 1));
        PluginMessageEvent event =
                new PluginMessageEvent(connection, player, MenuChannelListener.CHANNEL_IDENTIFIER, payload);

        listener.onPluginMessage(event);
        awaitCondition(() -> !sentMessages.isEmpty());

        assertThat(sentMessages).hasSize(1);
        RenderMenuPayload rendered = MenuCodec.decodeRenderMenu(sentMessages.getFirst());
        assertThat(rendered.correlationId()).isEqualTo(20L);
        assertThat(rendered.screenType()).isEqualTo("BROWSE");
    }

    @Test
    void serverSourcedActionCloseSendsCloseMenuMessage() throws Exception {
        UUID playerId = UUID.randomUUID();
        Player player = mockPlayer(playerId, "Alice");
        playersByUuid.put(playerId, player);
        playersByName.put("Alice", player);
        names.remember(playerId, "Alice");

        List<byte[]> sentMessages = Collections.synchronizedList(new ArrayList<>());
        ServerConnection connection = mockServerConnection(player, sentMessages);

        byte[] payload = MenuCodec.encodeClickIntent(new MenuClickIntent(21L, "ACTION:CLOSE", 1));
        PluginMessageEvent event =
                new PluginMessageEvent(connection, player, MenuChannelListener.CHANNEL_IDENTIFIER, payload);

        listener.onPluginMessage(event);
        awaitCondition(() -> !sentMessages.isEmpty());

        assertThat(sentMessages).hasSize(1);
        CloseMenuMessage close = MenuCodec.decodeCloseMenu(sentMessages.getFirst());
        assertThat(close.correlationId()).isEqualTo(21L);
    }

    @Test
    void serverSourcedActionJoinWorldSendsCloseAndInitiatesJoin() throws Exception {
        UUID playerId = UUID.randomUUID();
        Player player = mockPlayer(playerId, "Alice");
        playersByUuid.put(playerId, player);
        playersByName.put("Alice", player);
        names.remember(playerId, "Alice");

        proxy.registerServer(
                new ServerInfo("node-1", new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 25565)));
        nodeRepo.heartbeat("node-1", "127.0.0.1:25565", 0, 0, 10, 20.0, false, 3000, "1.21.4");
        registry.sync(policy.deadAfter());

        WorldId worldId = WorldId.random();
        worlds.create(worldId, playerId, "joinable-world", 1L, 5000, Visibility.PRIVATE);
        database.withConnection(conn -> membership.insertMember(conn, worldId, playerId, Role.OWNER, null));
        worlds.transitionState(worldId, WorldState.CREATING, WorldState.READY);

        List<byte[]> sentMessages = Collections.synchronizedList(new ArrayList<>());
        ServerConnection connection = mockServerConnection(player, sentMessages);

        byte[] payload = MenuCodec.encodeClickIntent(new MenuClickIntent(22L, "ACTION:JOIN:" + worldId.value(), 1));
        PluginMessageEvent event =
                new PluginMessageEvent(connection, player, MenuChannelListener.CHANNEL_IDENTIFIER, payload);

        listener.onPluginMessage(event);
        awaitCondition(() -> !sentMessages.isEmpty());

        assertThat(sentMessages).hasSize(1);
        CloseMenuMessage close = MenuCodec.decodeCloseMenu(sentMessages.getFirst());
        assertThat(close.correlationId()).isEqualTo(22L);
        awaitCondition(() -> {
            try {
                return transfers
                        .claim(playerId, java.time.Duration.ofMinutes(1))
                        .isPresent();
            } catch (Exception e) {
                return false;
            }
        });
    }

    @Test
    void serverSourcedActionCreateCreatesAndRerendersMyWorlds() throws Exception {
        UUID playerId = UUID.randomUUID();
        Player player = mockPlayer(playerId, "Alice");
        playersByUuid.put(playerId, player);
        playersByName.put("Alice", player);
        names.remember(playerId, "Alice");

        proxy.registerServer(
                new ServerInfo("node-1", new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 25565)));
        nodeRepo.heartbeat("node-1", "127.0.0.1:25565", 0, 0, 10, 20.0, false, 3000, "1.21.4");
        registry.sync(policy.deadAfter());

        List<byte[]> sentMessages = Collections.synchronizedList(new ArrayList<>());
        ServerConnection connection = mockServerConnection(player, sentMessages);

        byte[] payload = MenuCodec.encodeClickIntent(new MenuClickIntent(23L, "ACTION:CREATE", 1));
        PluginMessageEvent event =
                new PluginMessageEvent(connection, player, MenuChannelListener.CHANNEL_IDENTIFIER, payload);

        listener.onPluginMessage(event);
        awaitCondition(() -> !sentMessages.isEmpty());

        assertThat(sentMessages).hasSize(1);
        RenderMenuPayload rendered = MenuCodec.decodeRenderMenu(sentMessages.getFirst());
        assertThat(rendered.correlationId()).isEqualTo(23L);
        assertThat(rendered.screenType()).isEqualTo("MY_WORLDS");
        assertThat(worlds.listOwnedBy(playerId)).hasSize(1);
    }

    @Test
    void serverSourcedActionArchiveArchivesAndRerendersMyWorlds() throws Exception {
        UUID playerId = UUID.randomUUID();
        Player player = mockPlayer(playerId, "Alice");
        playersByUuid.put(playerId, player);
        playersByName.put("Alice", player);
        names.remember(playerId, "Alice");

        proxy.registerServer(
                new ServerInfo("node-1", new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 25565)));
        nodeRepo.heartbeat("node-1", "127.0.0.1:25565", 0, 0, 10, 20.0, false, 3000, "1.21.4");
        registry.sync(policy.deadAfter());

        WorldId worldId = WorldId.random();
        worlds.create(worldId, playerId, "archive-me-tag", 1L, 5000, Visibility.PRIVATE);
        database.withConnection(conn -> membership.insertMember(conn, worldId, playerId, Role.OWNER, null));
        worlds.transitionState(worldId, WorldState.CREATING, WorldState.READY);

        List<byte[]> sentMessages = Collections.synchronizedList(new ArrayList<>());
        ServerConnection connection = mockServerConnection(player, sentMessages);

        byte[] payload = MenuCodec.encodeClickIntent(new MenuClickIntent(24L, "ACTION:ARCHIVE:archive-me-tag", 1));
        PluginMessageEvent event =
                new PluginMessageEvent(connection, player, MenuChannelListener.CHANNEL_IDENTIFIER, payload);

        listener.onPluginMessage(event);
        awaitCondition(() -> !sentMessages.isEmpty());

        assertThat(sentMessages).hasSize(1);
        RenderMenuPayload rendered = MenuCodec.decodeRenderMenu(sentMessages.getFirst());
        assertThat(rendered.correlationId()).isEqualTo(24L);
        assertThat(rendered.screenType()).isEqualTo("MY_WORLDS");
    }

    @Test
    void serverSourcedActionRestoreRestoresAndRerendersMyWorlds() throws Exception {
        UUID playerId = UUID.randomUUID();
        Player player = mockPlayer(playerId, "Alice");
        playersByUuid.put(playerId, player);
        playersByName.put("Alice", player);
        names.remember(playerId, "Alice");

        proxy.registerServer(
                new ServerInfo("node-1", new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 25565)));
        nodeRepo.heartbeat("node-1", "127.0.0.1:25565", 0, 0, 10, 20.0, false, 3000, "1.21.4");
        registry.sync(policy.deadAfter());

        WorldId worldId = WorldId.random();
        worlds.create(worldId, playerId, "restore-me-tag", 1L, 5000, Visibility.PRIVATE);
        database.withConnection(conn -> membership.insertMember(conn, worldId, playerId, Role.OWNER, null));
        worlds.transitionState(worldId, WorldState.CREATING, WorldState.READY);
        worlds.transitionState(worldId, WorldState.READY, WorldState.ARCHIVED);

        List<byte[]> sentMessages = Collections.synchronizedList(new ArrayList<>());
        ServerConnection connection = mockServerConnection(player, sentMessages);

        byte[] payload = MenuCodec.encodeClickIntent(new MenuClickIntent(25L, "ACTION:RESTORE:restore-me-tag", 1));
        PluginMessageEvent event =
                new PluginMessageEvent(connection, player, MenuChannelListener.CHANNEL_IDENTIFIER, payload);

        listener.onPluginMessage(event);
        awaitCondition(() -> !sentMessages.isEmpty());

        assertThat(sentMessages).hasSize(1);
        RenderMenuPayload rendered = MenuCodec.decodeRenderMenu(sentMessages.getFirst());
        assertThat(rendered.correlationId()).isEqualTo(25L);
        assertThat(rendered.screenType()).isEqualTo("MY_WORLDS");
    }

    @Test
    void serverSourcedActionSetVisibilitySetsAndRerendersWorldMenu() throws Exception {
        UUID playerId = UUID.randomUUID();
        Player player = mockPlayer(playerId, "Alice");
        playersByUuid.put(playerId, player);
        playersByName.put("Alice", player);
        names.remember(playerId, "Alice");

        WorldId worldId = WorldId.random();
        worlds.create(worldId, playerId, "vis-world", 1L, 5000, Visibility.PRIVATE);
        worlds.transitionState(worldId, WorldState.CREATING, WorldState.READY);

        List<byte[]> sentMessages = Collections.synchronizedList(new ArrayList<>());
        ServerConnection connection = mockServerConnection(player, sentMessages);

        byte[] payload = MenuCodec.encodeClickIntent(
                new MenuClickIntent(26L, "ACTION:SET_VISIBILITY:" + worldId.value() + ":PUBLIC", 1));
        PluginMessageEvent event =
                new PluginMessageEvent(connection, player, MenuChannelListener.CHANNEL_IDENTIFIER, payload);

        listener.onPluginMessage(event);
        awaitCondition(() -> !sentMessages.isEmpty());

        assertThat(sentMessages).hasSize(1);
        RenderMenuPayload rendered = MenuCodec.decodeRenderMenu(sentMessages.getFirst());
        assertThat(rendered.correlationId()).isEqualTo(26L);
        assertThat(rendered.screenType()).isEqualTo("WORLD_DETAILS");
        assertThat(worlds.findById(worldId).orElseThrow().visibility()).isEqualTo(Visibility.PUBLIC);
    }

    @Test
    void serverSourcedActionSetSettingSetsAndRerendersSettingsMenu() throws Exception {
        UUID playerId = UUID.randomUUID();
        Player player = mockPlayer(playerId, "Alice");
        playersByUuid.put(playerId, player);
        playersByName.put("Alice", player);
        names.remember(playerId, "Alice");

        WorldId worldId = WorldId.random();
        worlds.create(worldId, playerId, "setting-world", 1L, 5000, Visibility.PRIVATE);
        worlds.transitionState(worldId, WorldState.CREATING, WorldState.READY);

        List<byte[]> sentMessages = Collections.synchronizedList(new ArrayList<>());
        ServerConnection connection = mockServerConnection(player, sentMessages);

        byte[] payload = MenuCodec.encodeClickIntent(
                new MenuClickIntent(27L, "ACTION:SET_SETTING:" + worldId.value() + ":pvp:true", 1));
        PluginMessageEvent event =
                new PluginMessageEvent(connection, player, MenuChannelListener.CHANNEL_IDENTIFIER, payload);

        listener.onPluginMessage(event);
        awaitCondition(() -> !sentMessages.isEmpty());

        assertThat(sentMessages).hasSize(1);
        RenderMenuPayload rendered = MenuCodec.decodeRenderMenu(sentMessages.getFirst());
        assertThat(rendered.correlationId()).isEqualTo(27L);
        assertThat(rendered.screenType()).isEqualTo("SETTINGS");
    }

    @Test
    void serverSourcedActionPromotePromotesAndRerendersMembersMenu() throws Exception {
        UUID playerId = UUID.randomUUID();
        Player player = mockPlayer(playerId, "Alice");
        playersByUuid.put(playerId, player);
        playersByName.put("Alice", player);
        names.remember(playerId, "Alice");

        UUID bobId = UUID.randomUUID();
        Player bob = mockPlayer(bobId, "Bob");
        playersByUuid.put(bobId, bob);
        playersByName.put("Bob", bob);
        names.remember(bobId, "Bob");

        WorldId worldId = WorldId.random();
        worlds.create(worldId, playerId, "promo-world", 1L, 5000, Visibility.PRIVATE);
        membership.addVisitorIfAbsent(worldId, bobId);

        List<byte[]> sentMessages = Collections.synchronizedList(new ArrayList<>());
        ServerConnection connection = mockServerConnection(player, sentMessages);

        byte[] payload =
                MenuCodec.encodeClickIntent(new MenuClickIntent(28L, "ACTION:PROMOTE:" + worldId.value() + ":Bob", 1));
        PluginMessageEvent event =
                new PluginMessageEvent(connection, player, MenuChannelListener.CHANNEL_IDENTIFIER, payload);

        listener.onPluginMessage(event);
        awaitCondition(() -> !sentMessages.isEmpty());

        assertThat(sentMessages).hasSize(1);
        RenderMenuPayload rendered = MenuCodec.decodeRenderMenu(sentMessages.getFirst());
        assertThat(rendered.correlationId()).isEqualTo(28L);
        assertThat(rendered.screenType()).isEqualTo("MEMBERS");
        assertThat(membership.findMember(worldId, bobId).orElseThrow().role()).isEqualTo(Role.BUILDER);
    }

    @Test
    void serverSourcedActionKickKicksAndRerendersMembersMenu() throws Exception {
        UUID playerId = UUID.randomUUID();
        Player player = mockPlayer(playerId, "Alice");
        playersByUuid.put(playerId, player);
        playersByName.put("Alice", player);
        names.remember(playerId, "Alice");

        UUID bobId = UUID.randomUUID();
        Player bob = mockPlayer(bobId, "Bob");
        playersByUuid.put(bobId, bob);
        playersByName.put("Bob", bob);
        names.remember(bobId, "Bob");

        WorldId worldId = WorldId.random();
        worlds.create(worldId, playerId, "kick-world", 1L, 5000, Visibility.PRIVATE);
        membership.addVisitorIfAbsent(worldId, bobId);

        List<byte[]> sentMessages = Collections.synchronizedList(new ArrayList<>());
        ServerConnection connection = mockServerConnection(player, sentMessages);

        byte[] payload =
                MenuCodec.encodeClickIntent(new MenuClickIntent(29L, "ACTION:KICK:" + worldId.value() + ":Bob", 1));
        PluginMessageEvent event =
                new PluginMessageEvent(connection, player, MenuChannelListener.CHANNEL_IDENTIFIER, payload);

        listener.onPluginMessage(event);
        awaitCondition(() -> !sentMessages.isEmpty());

        assertThat(sentMessages).hasSize(1);
        RenderMenuPayload rendered = MenuCodec.decodeRenderMenu(sentMessages.getFirst());
        assertThat(rendered.correlationId()).isEqualTo(29L);
        assertThat(rendered.screenType()).isEqualTo("MEMBERS");
        assertThat(membership.findMember(worldId, bobId)).isEmpty();
    }

    @Test
    void serverSourcedActionUnbanUnbansAndRerendersBansMenu() throws Exception {
        UUID playerId = UUID.randomUUID();
        Player player = mockPlayer(playerId, "Alice");
        playersByUuid.put(playerId, player);
        playersByName.put("Alice", player);
        names.remember(playerId, "Alice");

        UUID bobId = UUID.randomUUID();
        Player bob = mockPlayer(bobId, "Bob");
        playersByUuid.put(bobId, bob);
        playersByName.put("Bob", bob);
        names.remember(bobId, "Bob");

        WorldId worldId = WorldId.random();
        worlds.create(worldId, playerId, "unban-world", 1L, 5000, Visibility.PRIVATE);
        bans.ban(worldId, bobId, playerId, "test ban");

        List<byte[]> sentMessages = Collections.synchronizedList(new ArrayList<>());
        ServerConnection connection = mockServerConnection(player, sentMessages);

        byte[] payload =
                MenuCodec.encodeClickIntent(new MenuClickIntent(30L, "ACTION:UNBAN:" + worldId.value() + ":Bob", 1));
        PluginMessageEvent event =
                new PluginMessageEvent(connection, player, MenuChannelListener.CHANNEL_IDENTIFIER, payload);

        listener.onPluginMessage(event);
        awaitCondition(() -> !sentMessages.isEmpty());

        assertThat(sentMessages).hasSize(1);
        RenderMenuPayload rendered = MenuCodec.decodeRenderMenu(sentMessages.getFirst());
        assertThat(rendered.correlationId()).isEqualTo(30L);
        assertThat(rendered.screenType()).isEqualTo("BANS");
        assertThat(bans.isBanned(worldId, bobId)).isFalse();
    }

    @Test
    void serverSourcedActionAcceptInviteAcceptsAndRerendersInvitesMenu() throws Exception {
        UUID playerId = UUID.randomUUID();
        Player player = mockPlayer(playerId, "Alice");
        playersByUuid.put(playerId, player);
        playersByName.put("Alice", player);
        names.remember(playerId, "Alice");

        UUID bobId = UUID.randomUUID();
        Player bob = mockPlayer(bobId, "Bob");
        playersByUuid.put(bobId, bob);
        playersByName.put("Bob", bob);
        names.remember(bobId, "Bob");

        WorldId worldId = WorldId.random();
        worlds.create(worldId, bobId, "bobs-world", 1L, 5000, Visibility.PRIVATE);
        membership.invite(worldId, playerId, bobId, java.time.Duration.ofHours(1));

        List<byte[]> sentMessages = Collections.synchronizedList(new ArrayList<>());
        ServerConnection connection = mockServerConnection(player, sentMessages);

        byte[] payload = MenuCodec.encodeClickIntent(new MenuClickIntent(31L, "ACTION:ACCEPT_INVITE:Bob", 1));
        PluginMessageEvent event =
                new PluginMessageEvent(connection, player, MenuChannelListener.CHANNEL_IDENTIFIER, payload);

        listener.onPluginMessage(event);
        awaitCondition(() -> !sentMessages.isEmpty());

        assertThat(sentMessages).hasSize(1);
        RenderMenuPayload rendered = MenuCodec.decodeRenderMenu(sentMessages.getFirst());
        assertThat(rendered.correlationId()).isEqualTo(31L);
        assertThat(rendered.screenType()).isEqualTo("INVITES");
        assertThat(membership.findMember(worldId, playerId)).isPresent();
    }

    @Test
    void serverSourcedActionAcceptTransferAcceptsAndRerendersInvitesMenu() throws Exception {
        UUID playerId = UUID.randomUUID();
        Player player = mockPlayer(playerId, "Alice");
        playersByUuid.put(playerId, player);
        playersByName.put("Alice", player);
        names.remember(playerId, "Alice");

        UUID bobId = UUID.randomUUID();
        Player bob = mockPlayer(bobId, "Bob");
        playersByUuid.put(bobId, bob);
        playersByName.put("Bob", bob);
        names.remember(bobId, "Bob");

        WorldId worldId = WorldId.random();
        worlds.create(worldId, bobId, "transfer-world", 1L, 5000, Visibility.PRIVATE);
        transferRequests.requestTransfer(worldId, playerId, bobId, java.time.Duration.ofHours(1));

        List<byte[]> sentMessages = Collections.synchronizedList(new ArrayList<>());
        ServerConnection connection = mockServerConnection(player, sentMessages);

        byte[] payload = MenuCodec.encodeClickIntent(new MenuClickIntent(32L, "ACTION:ACCEPT_TRANSFER:Bob", 1));
        PluginMessageEvent event =
                new PluginMessageEvent(connection, player, MenuChannelListener.CHANNEL_IDENTIFIER, payload);

        listener.onPluginMessage(event);
        awaitCondition(() -> !sentMessages.isEmpty());

        assertThat(sentMessages).hasSize(1);
        RenderMenuPayload rendered = MenuCodec.decodeRenderMenu(sentMessages.getFirst());
        assertThat(rendered.correlationId()).isEqualTo(32L);
        assertThat(rendered.screenType()).isEqualTo("INVITES");
        assertThat(worlds.findById(worldId).orElseThrow().ownerUuid()).isEqualTo(playerId);
    }

    @Test
    void serverSourcedActionDeclineTransferDeclinesAndRerendersInvitesMenu() throws Exception {
        UUID playerId = UUID.randomUUID();
        Player player = mockPlayer(playerId, "Alice");
        playersByUuid.put(playerId, player);
        playersByName.put("Alice", player);
        names.remember(playerId, "Alice");

        UUID bobId = UUID.randomUUID();
        Player bob = mockPlayer(bobId, "Bob");
        playersByUuid.put(bobId, bob);
        playersByName.put("Bob", bob);
        names.remember(bobId, "Bob");

        WorldId worldId = WorldId.random();
        worlds.create(worldId, bobId, "decline-world", 1L, 5000, Visibility.PRIVATE);
        transferRequests.requestTransfer(worldId, playerId, bobId, java.time.Duration.ofHours(1));

        List<byte[]> sentMessages = Collections.synchronizedList(new ArrayList<>());
        ServerConnection connection = mockServerConnection(player, sentMessages);

        byte[] payload = MenuCodec.encodeClickIntent(new MenuClickIntent(33L, "ACTION:DECLINE_TRANSFER:Bob", 1));
        PluginMessageEvent event =
                new PluginMessageEvent(connection, player, MenuChannelListener.CHANNEL_IDENTIFIER, payload);

        listener.onPluginMessage(event);
        awaitCondition(() -> !sentMessages.isEmpty());

        assertThat(sentMessages).hasSize(1);
        RenderMenuPayload rendered = MenuCodec.decodeRenderMenu(sentMessages.getFirst());
        assertThat(rendered.correlationId()).isEqualTo(33L);
        assertThat(rendered.screenType()).isEqualTo("INVITES");
        assertThat(transferRequests.findLiveRequestsFor(playerId)).isEmpty();
    }

    @Test
    void serverSourcedActionInviteInfoSendsPlayerMessage() throws Exception {
        UUID playerId = UUID.randomUUID();
        Player player = mockPlayer(playerId, "Alice");
        playersByUuid.put(playerId, player);
        playersByName.put("Alice", player);
        names.remember(playerId, "Alice");

        List<byte[]> sentMessages = Collections.synchronizedList(new ArrayList<>());
        ServerConnection connection = mockServerConnection(player, sentMessages);

        byte[] payload = MenuCodec.encodeClickIntent(new MenuClickIntent(34L, "ACTION:INVITE_INFO", 1));
        PluginMessageEvent event =
                new PluginMessageEvent(connection, player, MenuChannelListener.CHANNEL_IDENTIFIER, payload);

        listener.onPluginMessage(event);

        assertThat(messagesByPlayer.get(playerId)).isNotNull();
        assertThat(messagesByPlayer.get(playerId))
                .anySatisfy(comp -> assertThat(
                                PlainTextComponentSerializer.plainText().serialize(comp))
                        .contains("/world invite"));
    }

    @Test
    void serverSourcedActionFailureSendsErrorMessageToPlayer() throws Exception {
        UUID playerId = UUID.randomUUID();
        Player player = mockPlayer(playerId, "Alice");
        playersByUuid.put(playerId, player);
        playersByName.put("Alice", player);
        names.remember(playerId, "Alice");

        List<byte[]> sentMessages = Collections.synchronizedList(new ArrayList<>());
        ServerConnection connection = mockServerConnection(player, sentMessages);

        byte[] payload = MenuCodec.encodeClickIntent(new MenuClickIntent(35L, "ACTION:RESTORE:nonexistent", 1));
        PluginMessageEvent event =
                new PluginMessageEvent(connection, player, MenuChannelListener.CHANNEL_IDENTIFIER, payload);

        listener.onPluginMessage(event);
        awaitCondition(() -> messagesByPlayer.containsKey(playerId));

        assertThat(messagesByPlayer.get(playerId))
                .anySatisfy(comp -> assertThat(
                                PlainTextComponentSerializer.plainText().serialize(comp))
                        .containsIgnoringCase("no world"));
    }

    @Test
    void serverSourcedMenuClosedNoticeHandledGracefully() throws Exception {
        UUID playerId = UUID.randomUUID();
        Player player = mockPlayer(playerId, "Alice");
        playersByUuid.put(playerId, player);
        playersByName.put("Alice", player);

        List<byte[]> sentMessages = Collections.synchronizedList(new ArrayList<>());
        ServerConnection connection = mockServerConnection(player, sentMessages);

        byte[] payload = MenuCodec.encodeClosedNotice(new MenuClosedNotice(100L));
        PluginMessageEvent event =
                new PluginMessageEvent(connection, player, MenuChannelListener.CHANNEL_IDENTIFIER, payload);

        listener.onPluginMessage(event);

        assertThat(event.getResult().isAllowed()).isFalse();
        assertThat(sentMessages).isEmpty();
    }

    @Test
    void worldMenuCommandWithServerConnectionSendsOpenMenu() throws Exception {
        UUID playerId = UUID.randomUUID();
        List<byte[]> sentMessages = Collections.synchronizedList(new ArrayList<>());
        Player player = mockPlayerWithServer(playerId, "Alice", sentMessages);

        com.mojang.brigadier.CommandDispatcher<CommandSource> dispatcher =
                new com.mojang.brigadier.CommandDispatcher<>();
        WorldCommand worldCommand = new WorldCommand(
                actions, proxy, executors, worlds, new Placement(nodeRepo, worlds), nodeCommands, () -> policy);
        dispatcher.getRoot().addChild(worldCommand.build().getNode());

        dispatcher.execute("world menu", player);

        assertThat(sentMessages).hasSize(1);
        OpenMenu openMenu = MenuCodec.decodeOpenMenu(sentMessages.getFirst());
        assertThat(openMenu.correlationId()).isPositive();
    }
}
