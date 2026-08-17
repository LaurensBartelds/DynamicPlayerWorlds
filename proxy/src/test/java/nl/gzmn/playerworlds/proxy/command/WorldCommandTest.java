package nl.gzmn.playerworlds.proxy.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.CommandDispatcher;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.control.CommandKind;
import nl.gzmn.playerworlds.core.control.EjectPayload;
import nl.gzmn.playerworlds.core.control.NodeCommand;
import nl.gzmn.playerworlds.core.db.Database;
import nl.gzmn.playerworlds.core.db.MembershipRepository;
import nl.gzmn.playerworlds.core.db.NodeCommandRepository;
import nl.gzmn.playerworlds.core.db.NodeRepository;
import nl.gzmn.playerworlds.core.db.PendingTransferRepository;
import nl.gzmn.playerworlds.core.db.PlayerNameRepository;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository;
import nl.gzmn.playerworlds.core.db.Schema;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.Role;
import nl.gzmn.playerworlds.core.model.Visibility;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.model.WorldState;
import nl.gzmn.playerworlds.proxy.node.NodeRegistry;
import nl.gzmn.playerworlds.testing.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorldCommandTest {

    private Database database;
    private PluginExecutors executors;
    private PlayerWorldRepository worlds;
    private MembershipRepository membership;
    private PlayerNameRepository names;
    private NodeRepository nodeRepo;
    private NodeRegistry registry;
    private PendingTransferRepository transfers;
    private NodeCommandRepository nodeCommands;
    private NetworkPolicy policy;

    private Map<UUID, Player> playersByUuid;
    private Map<String, Player> playersByName;
    private Map<String, RegisteredServer> registeredServers;
    private ProxyServer proxy;
    private CommandDispatcher<CommandSource> dispatcher;

    @BeforeEach
    void setUp() throws Exception {
        database = TestDatabase.openFresh();
        Schema.migrate(database);
        executors = PluginExecutors.create(2, 2, Runnable::run);
        policy = NetworkPolicy.defaults();

        playersByUuid = new ConcurrentHashMap<>();
        playersByName = new ConcurrentHashMap<>();
        registeredServers = new ConcurrentHashMap<>();
        proxy = mockProxy(playersByUuid, playersByName, registeredServers);

        worlds = new PlayerWorldRepository(database);
        membership = new MembershipRepository(database);
        names = new PlayerNameRepository(database);
        nodeRepo = new NodeRepository(database);
        registry = new NodeRegistry(proxy, nodeRepo);
        transfers = new PendingTransferRepository(database);
        nodeCommands = new NodeCommandRepository(database);

        WorldCommand worldCommand = new WorldCommand(
                proxy, executors, worlds, membership, names, transfers, registry, nodeCommands, () -> policy);
        BrigadierCommand brigadierCommand = worldCommand.build();
        dispatcher = new CommandDispatcher<>();
        dispatcher.getRoot().addChild(brigadierCommand.getNode());
    }

    @AfterEach
    void tearDown() {
        executors.shutdown(Duration.ofSeconds(5));
        database.close();
    }

    @Test
    void backendSubcommandsContainsLeave() {
        assertThat(WorldCommand.BACKEND_SUBCOMMANDS).contains("leave");
    }

    @Test
    void deleteConfirmEnqueuesUnloadWorldWhenAssignedNodePresent() throws Exception {
        UUID ownerUuid = UUID.randomUUID();
        Player player = registerPlayer(ownerUuid, "Alice");
        nodeRepo.heartbeat("node-1", "127.0.0.1:25565", 0, 0, 10, 20.0, false, 3000, "1.21.4");
        registry.sync(policy.deadAfter());

        WorldId worldId = WorldId.random();
        PlayerWorld created = worlds.create(
                worldId, ownerUuid, "testworld", 12345L, policy.defaultBorderRadius(), Visibility.PRIVATE);
        worlds.transitionState(worldId, WorldState.CREATING, WorldState.READY);
        database.inTransaction(connection -> {
            try (var stmt =
                    connection.prepareStatement("UPDATE player_world SET assigned_node = 'node-1' WHERE id = ?")) {
                stmt.setObject(1, worldId.value());
                stmt.executeUpdate();
            }
            return null;
        });

        dispatcher.execute("world delete testworld confirm", player);

        awaitCondition(() -> !nodeCommands
                .findClaimableIds("node-1", policy.controlClaimTimeout(), 10)
                .isEmpty());

        List<Long> ids = nodeCommands.findClaimableIds("node-1", policy.controlClaimTimeout(), 10);
        assertThat(ids).hasSize(1);
        NodeCommand command = nodeCommands.findById(ids.getFirst()).orElseThrow();
        assertThat(command.command()).isEqualTo(CommandKind.UNLOAD_WORLD.name());
        assertThat(command.targetNode()).isEqualTo("node-1");
        assertThat(command.worldId()).isEqualTo(worldId);
        assertThat(command.generation()).isEqualTo(created.generation());
        assertThat(command.payloadJson()).isEqualTo(NodeCommand.EMPTY_PAYLOAD);

        PlayerWorld updated = worlds.findById(worldId).orElseThrow();
        assertThat(updated.state()).isEqualTo(WorldState.ARCHIVED);
    }

    @Test
    void deleteConfirmEnqueuesUnloadWorldToAllAliveNodesWhenAssignedNodeNull() throws Exception {
        UUID ownerUuid = UUID.randomUUID();
        Player player = registerPlayer(ownerUuid, "Alice");
        nodeRepo.heartbeat("node-1", "127.0.0.1:25565", 0, 0, 10, 20.0, false, 3000, "1.21.4");
        nodeRepo.heartbeat("node-2", "127.0.0.1:25566", 0, 0, 10, 20.0, false, 3000, "1.21.4");
        registry.sync(policy.deadAfter());

        WorldId worldId = WorldId.random();
        PlayerWorld created = worlds.create(
                worldId, ownerUuid, "broadcastworld", 12345L, policy.defaultBorderRadius(), Visibility.PRIVATE);
        worlds.transitionState(worldId, WorldState.CREATING, WorldState.READY);

        dispatcher.execute("world delete broadcastworld confirm", player);

        awaitCondition(() -> !nodeCommands
                        .findClaimableIds("node-1", policy.controlClaimTimeout(), 10)
                        .isEmpty()
                && !nodeCommands
                        .findClaimableIds("node-2", policy.controlClaimTimeout(), 10)
                        .isEmpty());

        List<Long> node1Ids = nodeCommands.findClaimableIds("node-1", policy.controlClaimTimeout(), 10);
        assertThat(node1Ids).hasSize(1);
        NodeCommand cmd1 = nodeCommands.findById(node1Ids.getFirst()).orElseThrow();
        assertThat(cmd1.command()).isEqualTo(CommandKind.UNLOAD_WORLD.name());
        assertThat(cmd1.worldId()).isEqualTo(worldId);
        assertThat(cmd1.generation()).isEqualTo(created.generation());

        List<Long> node2Ids = nodeCommands.findClaimableIds("node-2", policy.controlClaimTimeout(), 10);
        assertThat(node2Ids).hasSize(1);
        NodeCommand cmd2 = nodeCommands.findById(node2Ids.getFirst()).orElseThrow();
        assertThat(cmd2.command()).isEqualTo(CommandKind.UNLOAD_WORLD.name());
        assertThat(cmd2.worldId()).isEqualTo(worldId);
        assertThat(cmd2.generation()).isEqualTo(created.generation());
    }

    @Test
    void kickEnqueuesInvalidateCacheAndKickMember() throws Exception {
        UUID ownerUuid = UUID.randomUUID();
        UUID targetUuid = UUID.randomUUID();
        Player ownerPlayer = registerPlayer(ownerUuid, "Alice");
        registerPlayer(targetUuid, "Bob");
        names.remember(targetUuid, "Bob");

        nodeRepo.heartbeat("node-1", "127.0.0.1:25565", 0, 0, 10, 20.0, false, 3000, "1.21.4");
        registry.sync(policy.deadAfter());

        WorldId worldId = WorldId.random();
        PlayerWorld created = worlds.create(
                worldId, ownerUuid, "partyworld", 12345L, policy.defaultBorderRadius(), Visibility.PRIVATE);
        database.inTransaction(connection -> {
            try (var stmt =
                    connection.prepareStatement("UPDATE player_world SET assigned_node = 'node-1' WHERE id = ?")) {
                stmt.setObject(1, worldId.value());
                stmt.executeUpdate();
            }
            return null;
        });
        membership.invite(worldId, targetUuid, ownerUuid, policy.inviteExpiry());
        membership.acceptInvite(worldId, targetUuid);

        dispatcher.execute("world kick Bob", ownerPlayer);

        awaitCondition(() -> nodeCommands
                        .findClaimableIds("node-1", policy.controlClaimTimeout(), 10)
                        .size()
                >= 2);

        List<Long> ids = nodeCommands.findClaimableIds("node-1", policy.controlClaimTimeout(), 10);
        assertThat(ids).hasSize(2);

        NodeCommand cmd1 = nodeCommands.findById(ids.get(0)).orElseThrow();
        NodeCommand cmd2 = nodeCommands.findById(ids.get(1)).orElseThrow();

        assertThat(cmd1.command()).isEqualTo(CommandKind.INVALIDATE_CACHE.name());
        assertThat(cmd1.worldId()).isEqualTo(worldId);
        assertThat(cmd1.generation()).isEqualTo(created.generation());
        assertThat(cmd1.payloadJson()).isEqualTo(NodeCommand.EMPTY_PAYLOAD);

        assertThat(cmd2.command()).isEqualTo(CommandKind.KICK_MEMBER.name());
        assertThat(cmd2.worldId()).isEqualTo(worldId);
        assertThat(cmd2.generation()).isEqualTo(created.generation());
        Optional<EjectPayload> ejectPayload = EjectPayload.parse(cmd2.payloadJson());
        assertTrue(ejectPayload.isPresent());
        assertThat(ejectPayload.get().playerUuid()).isEqualTo(targetUuid);
        assertThat(ejectPayload.get().reason()).isEqualTo("You were removed from this world");
    }

    @Test
    void promoteEnqueuesInvalidateCache() throws Exception {
        UUID ownerUuid = UUID.randomUUID();
        UUID targetUuid = UUID.randomUUID();
        Player ownerPlayer = registerPlayer(ownerUuid, "Alice");
        registerPlayer(targetUuid, "Bob");
        names.remember(targetUuid, "Bob");

        nodeRepo.heartbeat("node-1", "127.0.0.1:25565", 0, 0, 10, 20.0, false, 3000, "1.21.4");
        registry.sync(policy.deadAfter());

        WorldId worldId = WorldId.random();
        PlayerWorld created = worlds.create(
                worldId, ownerUuid, "builderworld", 12345L, policy.defaultBorderRadius(), Visibility.PRIVATE);
        database.inTransaction(connection -> {
            try (var stmt =
                    connection.prepareStatement("UPDATE player_world SET assigned_node = 'node-1' WHERE id = ?")) {
                stmt.setObject(1, worldId.value());
                stmt.executeUpdate();
            }
            return null;
        });
        membership.invite(worldId, targetUuid, ownerUuid, policy.inviteExpiry());
        membership.acceptInvite(worldId, targetUuid);

        dispatcher.execute("world promote Bob", ownerPlayer);

        awaitCondition(() -> !nodeCommands
                .findClaimableIds("node-1", policy.controlClaimTimeout(), 10)
                .isEmpty());

        List<Long> ids = nodeCommands.findClaimableIds("node-1", policy.controlClaimTimeout(), 10);
        assertThat(ids).hasSize(1);

        NodeCommand cmd = nodeCommands.findById(ids.getFirst()).orElseThrow();
        assertThat(cmd.command()).isEqualTo(CommandKind.INVALIDATE_CACHE.name());
        assertThat(cmd.worldId()).isEqualTo(worldId);
        assertThat(cmd.generation()).isEqualTo(created.generation());
        assertThat(cmd.payloadJson()).isEqualTo(NodeCommand.EMPTY_PAYLOAD);

        assertThat(membership.findMember(worldId, targetUuid).orElseThrow().role())
                .isEqualTo(Role.BUILDER);
    }

    @Test
    void kickEnqueuesInvalidateCacheAndKickMemberToAllAliveNodesWhenAssignedNodeNull() throws Exception {
        UUID ownerUuid = UUID.randomUUID();
        UUID targetUuid = UUID.randomUUID();
        Player ownerPlayer = registerPlayer(ownerUuid, "Alice");
        registerPlayer(targetUuid, "Bob");
        names.remember(targetUuid, "Bob");

        nodeRepo.heartbeat("node-1", "127.0.0.1:25565", 0, 0, 10, 20.0, false, 3000, "1.21.4");
        nodeRepo.heartbeat("node-2", "127.0.0.1:25566", 0, 0, 10, 20.0, false, 3000, "1.21.4");
        registry.sync(policy.deadAfter());

        WorldId worldId = WorldId.random();
        PlayerWorld created = worlds.create(
                worldId, ownerUuid, "kickbroadcast", 12345L, policy.defaultBorderRadius(), Visibility.PRIVATE);
        membership.invite(worldId, targetUuid, ownerUuid, policy.inviteExpiry());
        membership.acceptInvite(worldId, targetUuid);

        dispatcher.execute("world kick Bob", ownerPlayer);

        awaitCondition(() -> nodeCommands
                                .findClaimableIds("node-1", policy.controlClaimTimeout(), 10)
                                .size()
                        >= 2
                && nodeCommands
                                .findClaimableIds("node-2", policy.controlClaimTimeout(), 10)
                                .size()
                        >= 2);

        List<Long> node1Ids = nodeCommands.findClaimableIds("node-1", policy.controlClaimTimeout(), 10);
        assertThat(node1Ids).hasSize(2);
        NodeCommand n1c1 = nodeCommands.findById(node1Ids.get(0)).orElseThrow();
        NodeCommand n1c2 = nodeCommands.findById(node1Ids.get(1)).orElseThrow();
        assertThat(n1c1.command()).isEqualTo(CommandKind.INVALIDATE_CACHE.name());
        assertThat(n1c1.generation()).isEqualTo(created.generation());
        assertThat(n1c2.command()).isEqualTo(CommandKind.KICK_MEMBER.name());
        assertThat(n1c2.generation()).isEqualTo(created.generation());

        List<Long> node2Ids = nodeCommands.findClaimableIds("node-2", policy.controlClaimTimeout(), 10);
        assertThat(node2Ids).hasSize(2);
        NodeCommand n2c1 = nodeCommands.findById(node2Ids.get(0)).orElseThrow();
        NodeCommand n2c2 = nodeCommands.findById(node2Ids.get(1)).orElseThrow();
        assertThat(n2c1.command()).isEqualTo(CommandKind.INVALIDATE_CACHE.name());
        assertThat(n2c1.generation()).isEqualTo(created.generation());
        assertThat(n2c2.command()).isEqualTo(CommandKind.KICK_MEMBER.name());
        assertThat(n2c2.generation()).isEqualTo(created.generation());
    }

    @Test
    void promoteEnqueuesInvalidateCacheToAllAliveNodesWhenAssignedNodeNull() throws Exception {
        UUID ownerUuid = UUID.randomUUID();
        UUID targetUuid = UUID.randomUUID();
        Player ownerPlayer = registerPlayer(ownerUuid, "Alice");
        registerPlayer(targetUuid, "Bob");
        names.remember(targetUuid, "Bob");

        nodeRepo.heartbeat("node-1", "127.0.0.1:25565", 0, 0, 10, 20.0, false, 3000, "1.21.4");
        nodeRepo.heartbeat("node-2", "127.0.0.1:25566", 0, 0, 10, 20.0, false, 3000, "1.21.4");
        registry.sync(policy.deadAfter());

        WorldId worldId = WorldId.random();
        PlayerWorld created = worlds.create(
                worldId, ownerUuid, "promoteall", 12345L, policy.defaultBorderRadius(), Visibility.PRIVATE);
        membership.invite(worldId, targetUuid, ownerUuid, policy.inviteExpiry());
        membership.acceptInvite(worldId, targetUuid);

        dispatcher.execute("world promote Bob", ownerPlayer);

        awaitCondition(() -> !nodeCommands
                        .findClaimableIds("node-1", policy.controlClaimTimeout(), 10)
                        .isEmpty()
                && !nodeCommands
                        .findClaimableIds("node-2", policy.controlClaimTimeout(), 10)
                        .isEmpty());

        List<Long> node1Ids = nodeCommands.findClaimableIds("node-1", policy.controlClaimTimeout(), 10);
        assertThat(node1Ids).hasSize(1);
        NodeCommand n1c = nodeCommands.findById(node1Ids.getFirst()).orElseThrow();
        assertThat(n1c.command()).isEqualTo(CommandKind.INVALIDATE_CACHE.name());
        assertThat(n1c.generation()).isEqualTo(created.generation());

        List<Long> node2Ids = nodeCommands.findClaimableIds("node-2", policy.controlClaimTimeout(), 10);
        assertThat(node2Ids).hasSize(1);
        NodeCommand n2c = nodeCommands.findById(node2Ids.getFirst()).orElseThrow();
        assertThat(n2c.command()).isEqualTo(CommandKind.INVALIDATE_CACHE.name());
        assertThat(n2c.generation()).isEqualTo(created.generation());
    }

    private Player registerPlayer(UUID uuid, String username) {
        List<Component> messages = Collections.synchronizedList(new ArrayList<>());
        Player player = mockPlayer(uuid, username, messages);
        playersByUuid.put(uuid, player);
        playersByName.put(username, player);
        return player;
    }

    private void awaitCondition(Callable<Boolean> condition) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(condition.call())) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Condition not met within timeout");
    }

    private ProxyServer mockProxy(
            Map<UUID, Player> playersByUuid, Map<String, Player> playersByName, Map<String, RegisteredServer> servers) {
        return (ProxyServer) Proxy.newProxyInstance(
                ProxyServer.class.getClassLoader(), new Class<?>[] {ProxyServer.class}, (proxy, method, args) -> {
                    if ("getPlayer".equals(method.getName()) && args != null && args.length == 1) {
                        if (args[0] instanceof UUID uuid) {
                            return Optional.ofNullable(playersByUuid.get(uuid));
                        }
                        if (args[0] instanceof String name) {
                            return Optional.ofNullable(playersByName.get(name));
                        }
                    }
                    if ("getAllPlayers".equals(method.getName())) {
                        return playersByUuid.values();
                    }
                    if ("getServer".equals(method.getName()) && args != null && args.length == 1) {
                        if (args[0] instanceof String name) {
                            return Optional.ofNullable(servers.get(name));
                        }
                    }
                    if ("registerServer".equals(method.getName()) && args != null && args.length == 1) {
                        if (args[0] instanceof ServerInfo info) {
                            servers.put(info.getName(), mockServer(info.getName(), info.getAddress()));
                        }
                        return null;
                    }
                    if ("unregisterServer".equals(method.getName()) && args != null && args.length == 1) {
                        if (args[0] instanceof ServerInfo info) {
                            servers.remove(info.getName());
                        }
                        return null;
                    }
                    if ("toString".equals(method.getName())) {
                        return "MockProxyServer";
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private Player mockPlayer(UUID uuid, String username, List<Component> receivedMessages) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(), new Class<?>[] {Player.class}, (proxy, method, args) -> {
                    if ("getUniqueId".equals(method.getName())) {
                        return uuid;
                    }
                    if ("getUsername".equals(method.getName())) {
                        return username;
                    }
                    if ("sendMessage".equals(method.getName()) && args != null && args.length > 0) {
                        if (args[0] instanceof Component comp) {
                            receivedMessages.add(comp);
                        }
                        return null;
                    }
                    if ("toString".equals(method.getName())) {
                        return "MockPlayer[" + username + "]";
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private RegisteredServer mockServer(String name, InetSocketAddress address) {
        ServerInfo info = new ServerInfo(name, address);
        return (RegisteredServer) Proxy.newProxyInstance(
                RegisteredServer.class.getClassLoader(),
                new Class<?>[] {RegisteredServer.class},
                (proxy, method, args) -> {
                    if ("getServerInfo".equals(method.getName())) {
                        return info;
                    }
                    if ("toString".equals(method.getName())) {
                        return "MockServer[" + name + "]";
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == void.class) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == byte.class || returnType == short.class || returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0.0f;
        }
        if (returnType == double.class) {
            return 0.0d;
        }
        if (returnType == char.class) {
            return '\0';
        }
        if (returnType == Optional.class) {
            return Optional.empty();
        }
        return null;
    }
}
