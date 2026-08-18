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
import nl.gzmn.playerworlds.core.db.WorldBanRepository;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.Role;
import nl.gzmn.playerworlds.core.model.Visibility;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.model.WorldSettings;
import nl.gzmn.playerworlds.core.model.WorldState;
import nl.gzmn.playerworlds.proxy.node.NodeRegistry;
import nl.gzmn.playerworlds.proxy.node.Placement;
import nl.gzmn.playerworlds.testing.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorldCommandTest {

    private Database database;
    private PluginExecutors executors;
    private PlayerWorldRepository worlds;
    private MembershipRepository membership;
    private WorldBanRepository bans;
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
        bans = new WorldBanRepository(database);
        names = new PlayerNameRepository(database);
        nodeRepo = new NodeRepository(database);
        registry = new NodeRegistry(proxy, nodeRepo);
        transfers = new PendingTransferRepository(database);
        nodeCommands = new NodeCommandRepository(database);

        WorldCommand worldCommand = new WorldCommand(
                proxy,
                executors,
                worlds,
                membership,
                bans,
                names,
                transfers,
                registry,
                new Placement(nodeRepo, worlds),
                nodeCommands,
                () -> policy);
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

    @Test
    void deleteConfirmRemovesCreatingWorld() throws Exception {
        UUID ownerUuid = UUID.randomUUID();
        Player player = registerPlayer(ownerUuid, "Alice");
        nodeRepo.heartbeat("node-1", "127.0.0.1:25565", 0, 0, 10, 20.0, false, 3000, "1.21.4");
        registry.sync(policy.deadAfter());

        WorldId worldId = WorldId.random();
        PlayerWorld created = worlds.create(
                worldId, ownerUuid, "incomplete", 12345L, policy.defaultBorderRadius(), Visibility.PRIVATE);
        assertThat(created.state()).isEqualTo(WorldState.CREATING);

        dispatcher.execute("world delete incomplete confirm", player);

        awaitCondition(() -> worlds.findById(worldId).isEmpty());
        assertThat(worlds.findById(worldId)).isEmpty();
    }

    @Test
    void joinAllowsCreatingWorld() throws Exception {
        UUID ownerUuid = UUID.randomUUID();
        Player player = registerPlayer(ownerUuid, "Alice");
        names.remember(ownerUuid, "Alice");
        nodeRepo.heartbeat("node-1", "127.0.0.1:25565", 0, 0, 10, 20.0, false, 3000, "1.21.4");
        registry.sync(policy.deadAfter());

        WorldId worldId = WorldId.random();
        PlayerWorld created = worlds.create(
                worldId, ownerUuid, "incompleteworld", 12345L, policy.defaultBorderRadius(), Visibility.PRIVATE);
        assertThat(created.state()).isEqualTo(WorldState.CREATING);

        dispatcher.execute("world join Alice incompleteworld", player);

        awaitCondition(() -> transfers.claim(ownerUuid, policy.transferExpiry()).isPresent());
    }

    @Test
    void joinRoutesToTheLeaseHolderRatherThanTheEmptiestNode() throws Exception {
        // MN-16: all members of a world always resolve to the same node. Before
        // milestone 8 the proxy scored first and only then checked the lease, so a
        // second member joining a loaded world was sent to whichever node was
        // emptiest and then refused, because the acquisition lost to the holder's
        // live lease.
        UUID ownerUuid = UUID.randomUUID();
        UUID memberUuid = UUID.randomUUID();
        Player member = registerPlayer(memberUuid, "Bob");
        registerPlayer(ownerUuid, "Alice");
        names.remember(ownerUuid, "Alice");
        names.remember(memberUuid, "Bob");

        nodeRepo.heartbeat("node-busy", "127.0.0.1:25565", 3, 8, 40, 19.9, false, 4903, "26.2");
        nodeRepo.heartbeat("node-idle", "127.0.0.1:25566", 0, 0, 5, 20.0, false, 4903, "26.2");
        registry.sync(policy.deadAfter());

        WorldId worldId = WorldId.random();
        PlayerWorld world = worlds.create(
                worldId,
                ownerUuid,
                "shared",
                12345L,
                policy.defaultBorderRadius(),
                Visibility.PRIVATE,
                "node-busy",
                policy.leaseDuration());
        worlds.transitionState(worldId, WorldState.CREATING, WorldState.READY);
        membership.invite(worldId, memberUuid, ownerUuid, policy.inviteExpiry());
        membership.acceptInvite(worldId, memberUuid);

        dispatcher.execute("world join Alice shared", member);

        // Claiming consumes the row, so capture it rather than filtering: a wrong
        // node would otherwise show up as a timeout instead of as a mismatch.
        java.util.concurrent.atomic.AtomicReference<String> routedTo =
                new java.util.concurrent.atomic.AtomicReference<>();
        awaitCondition(() -> {
            Optional<PendingTransferRepository.PendingTransfer> claimed =
                    transfers.claim(memberUuid, policy.transferExpiry());
            claimed.ifPresent(transfer -> routedTo.set(transfer.nodeId()));
            return claimed.isPresent();
        });
        assertThat(routedTo.get()).isEqualTo("node-busy");
        // The lease is untouched: routing to the holder does not re-acquire, so the
        // generation the node checks on arrival is still the one it loaded against.
        assertThat(worlds.findById(worldId).orElseThrow().generation()).isEqualTo(world.generation());
    }

    @Test
    void joinRefusesAWorldNewerThanEveryNode() throws Exception {
        // Section 12.7: the pool was rolled back after an upgrade. The world is
        // safe and unreachable, and saying so is the whole of the correct handling.
        UUID ownerUuid = UUID.randomUUID();
        List<Component> messages = Collections.synchronizedList(new ArrayList<>());
        Player owner = mockPlayer(ownerUuid, "Alice", messages);
        playersByUuid.put(ownerUuid, owner);
        playersByName.put("Alice", owner);
        names.remember(ownerUuid, "Alice");

        nodeRepo.heartbeat("node-old", "127.0.0.1:25565", 0, 0, 5, 20.0, false, 4189, "1.21.4");
        registry.sync(policy.deadAfter());

        WorldId worldId = WorldId.random();
        worlds.create(worldId, ownerUuid, "upgraded", 12345L, policy.defaultBorderRadius(), Visibility.PRIVATE);
        worlds.transitionState(worldId, WorldState.CREATING, WorldState.READY);
        setDataVersion(worldId, 4903);

        dispatcher.execute("world join Alice upgraded", owner);

        awaitCondition(() -> messages.stream()
                .map(component -> net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(component))
                .anyMatch(text -> text.contains("newer Minecraft version")));
        assertThat(transfers.claim(ownerUuid, policy.transferExpiry())).isEmpty();
    }

    @Test
    void listShowsOwnedAndMemberWorlds() throws Exception {
        UUID ownerUuid = UUID.randomUUID();
        List<Component> messages = Collections.synchronizedList(new ArrayList<>());
        Player player = mockPlayer(ownerUuid, "Alice", messages);
        playersByUuid.put(ownerUuid, player);
        playersByName.put("Alice", player);
        names.remember(ownerUuid, "Alice");

        WorldId w1 = WorldId.random();
        worlds.create(w1, ownerUuid, "alice-world", 123L, 5000, Visibility.PRIVATE);
        worlds.transitionState(w1, WorldState.CREATING, WorldState.READY);

        dispatcher.execute("world list", player);

        awaitCondition(() -> messages.stream()
                .map(c -> net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(c))
                .anyMatch(text -> text.contains("alice-world")));
    }

    @Test
    void browseShowsPublicWorlds() throws Exception {
        UUID ownerUuid = UUID.randomUUID();
        List<Component> messages = Collections.synchronizedList(new ArrayList<>());
        Player player = mockPlayer(ownerUuid, "Alice", messages);
        playersByUuid.put(ownerUuid, player);
        playersByName.put("Alice", player);
        names.remember(ownerUuid, "Alice");

        WorldId w1 = WorldId.random();
        worlds.create(w1, ownerUuid, "public-park", 123L, 5000, Visibility.PUBLIC);
        worlds.transitionState(w1, WorldState.CREATING, WorldState.READY);
        worlds.updateVisibility(w1, Visibility.PUBLIC, "A beautiful public park");

        dispatcher.execute("world browse", player);

        awaitCondition(() -> messages.stream()
                .map(c -> net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(c))
                .anyMatch(text -> text.contains("public-park") && text.contains("A beautiful public park")));
    }

    @Test
    void setPublicTogglesVisibility() throws Exception {
        UUID ownerUuid = UUID.randomUUID();
        List<Component> messages = Collections.synchronizedList(new ArrayList<>());
        Player player = mockPlayer(ownerUuid, "Alice", messages);
        playersByUuid.put(ownerUuid, player);
        playersByName.put("Alice", player);
        names.remember(ownerUuid, "Alice");

        WorldId w1 = WorldId.random();
        worlds.create(w1, ownerUuid, "my-world", 123L, 5000, Visibility.PRIVATE);
        worlds.transitionState(w1, WorldState.CREATING, WorldState.READY);

        dispatcher.execute("world public on Welcome everyone", player);

        awaitCondition(() -> messages.stream()
                .map(c -> net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(c))
                .anyMatch(text -> text.contains("now PUBLIC")));

        PlayerWorld updated = worlds.findById(w1).orElseThrow();
        assertThat(updated.visibility()).isEqualTo(Visibility.PUBLIC);
        assertThat(updated.description()).isEqualTo("Welcome everyone");
    }

    @Test
    void setSettingAndShowSettings() throws Exception {
        UUID ownerUuid = UUID.randomUUID();
        List<Component> messages = Collections.synchronizedList(new ArrayList<>());
        Player player = mockPlayer(ownerUuid, "Alice", messages);
        playersByUuid.put(ownerUuid, player);
        playersByName.put("Alice", player);
        names.remember(ownerUuid, "Alice");

        WorldId w1 = WorldId.random();
        worlds.create(w1, ownerUuid, "pvp-arena", 123L, 5000, Visibility.PRIVATE);
        worlds.transitionState(w1, WorldState.CREATING, WorldState.READY);

        dispatcher.execute("world set pvp on", player);

        awaitCondition(() -> messages.stream()
                .map(c -> net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(c))
                .anyMatch(text -> text.contains("set pvp = true")));

        PlayerWorld updated = worlds.findById(w1).orElseThrow();
        WorldSettings settings = WorldSettings.fromJson(updated.settingsJson());
        assertThat(settings.pvp()).isTrue();

        dispatcher.execute("world settings", player);
        awaitCondition(() -> messages.stream()
                .map(c -> net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(c))
                .anyMatch(text -> text.contains("PVP: on")));
    }

    @Test
    void banAndUnbanPlayer() throws Exception {
        UUID ownerUuid = UUID.randomUUID();
        UUID targetUuid = UUID.randomUUID();

        List<Component> ownerMessages = Collections.synchronizedList(new ArrayList<>());
        Player owner = mockPlayer(ownerUuid, "Alice", ownerMessages);
        playersByUuid.put(ownerUuid, owner);
        playersByName.put("Alice", owner);
        names.remember(ownerUuid, "Alice");

        List<Component> targetMessages = Collections.synchronizedList(new ArrayList<>());
        Player target = mockPlayer(targetUuid, "Bob", targetMessages);
        playersByUuid.put(targetUuid, target);
        playersByName.put("Bob", target);
        names.remember(targetUuid, "Bob");

        WorldId w1 = WorldId.random();
        worlds.create(w1, ownerUuid, "alice-world", 123L, 5000, Visibility.PUBLIC);
        worlds.transitionState(w1, WorldState.CREATING, WorldState.READY);
        membership.addVisitorIfAbsent(w1, targetUuid);

        dispatcher.execute("world ban Bob Griefing", owner);

        awaitCondition(() -> ownerMessages.stream()
                .map(c -> net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(c))
                .anyMatch(text -> text.contains("banned Bob")));

        assertThat(bans.isBanned(w1, targetUuid)).isTrue();
        assertThat(membership.findMember(w1, targetUuid)).isEmpty();

        // Banned player cannot join
        dispatcher.execute("world join Alice alice-world", target);
        awaitCondition(() -> targetMessages.stream()
                .map(c -> net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(c))
                .anyMatch(text -> text.contains("banned from 'alice-world'")));

        // Unban
        dispatcher.execute("world unban Bob", owner);
        awaitCondition(() -> ownerMessages.stream()
                .map(c -> net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(c))
                .anyMatch(text -> text.contains("unbanned Bob")));
        assertThat(bans.isBanned(w1, targetUuid)).isFalse();
    }

    @Test
    void joinPublicWorldAutoEnrollsVisitor() throws Exception {
        UUID ownerUuid = UUID.randomUUID();
        UUID visitorUuid = UUID.randomUUID();

        registerPlayer(ownerUuid, "Alice");
        names.remember(ownerUuid, "Alice");

        List<Component> visitorMessages = Collections.synchronizedList(new ArrayList<>());
        Player visitor = mockPlayer(visitorUuid, "Bob", visitorMessages);
        playersByUuid.put(visitorUuid, visitor);
        playersByName.put("Bob", visitor);
        names.remember(visitorUuid, "Bob");

        nodeRepo.heartbeat("node-1", "127.0.0.1:25565", 0, 0, 10, 20.0, false, 3000, "1.21.4");
        registry.sync(policy.deadAfter());

        WorldId w1 = WorldId.random();
        worlds.create(w1, ownerUuid, "public-hub", 123L, 5000, Visibility.PUBLIC);
        worlds.transitionState(w1, WorldState.CREATING, WorldState.READY);

        dispatcher.execute("world join Alice public-hub", visitor);

        awaitCondition(
                () -> transfers.claim(visitorUuid, policy.transferExpiry()).isPresent());
        assertThat(membership.findMember(w1, visitorUuid)).isPresent();
        assertThat(membership.findMember(w1, visitorUuid).get().role()).isEqualTo(Role.VISITOR);
    }

    private void setDataVersion(WorldId worldId, int dataVersion) throws Exception {
        database.inTransaction(connection -> {
            try (var statement = connection.prepareStatement("UPDATE player_world SET data_version = ? WHERE id = ?")) {
                statement.setInt(1, dataVersion);
                statement.setObject(2, worldId.value());
                statement.executeUpdate();
            }
            return null;
        });
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
                    if ("hasPermission".equals(method.getName())) {
                        return true;
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
