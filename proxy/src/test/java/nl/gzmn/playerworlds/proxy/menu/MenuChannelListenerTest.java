package nl.gzmn.playerworlds.proxy.menu;

import static org.assertj.core.api.Assertions.assertThat;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
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
import nl.gzmn.playerworlds.core.menu.FailureCode;
import nl.gzmn.playerworlds.core.menu.MenuCodec;
import nl.gzmn.playerworlds.core.menu.MenuIntent;
import nl.gzmn.playerworlds.core.menu.MenuResult;
import nl.gzmn.playerworlds.core.menu.OpenMenu;
import nl.gzmn.playerworlds.core.model.Visibility;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.model.WorldState;
import nl.gzmn.playerworlds.proxy.command.WorldActions;
import nl.gzmn.playerworlds.proxy.command.WorldCommand;
import nl.gzmn.playerworlds.proxy.node.NodeRegistry;
import nl.gzmn.playerworlds.proxy.node.Placement;
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
                () -> policy);
        listener = new MenuChannelListener(actions);
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
        assertThat(ok.message()).contains("Permanently deleted world 'archivedworld'");
        assertThat(worlds.findById(worldId)).isEmpty();
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
                        .contains("/world <create|join|"));
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
        ConnectionRequestBuilder reqBuilder = mockConnectionRequestBuilder();
        return (Player) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {Player.class}, (proxyObj, method, args) -> {
                    if (method.getName().equals("getUniqueId")) return uuid;
                    if (method.getName().equals("getUsername")) return name;
                    if (method.getName().equals("hasPermission")) return true;
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
}
