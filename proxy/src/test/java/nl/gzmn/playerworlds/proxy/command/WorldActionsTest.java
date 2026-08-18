package nl.gzmn.playerworlds.proxy.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
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
import nl.gzmn.playerworlds.core.model.PlayerWorld;
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

class WorldActionsTest {

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
    }

    @AfterEach
    void tearDown() {
        executors.close();
        database.close();
    }

    @Test
    void createWorldEnforcesCap() throws Exception {
        UUID owner = UUID.randomUUID();
        Player player = mockPlayer(owner, "Alice");
        playersByUuid.put(owner, player);

        for (int i = 0; i < policy.maxWorldsPerPlayer(); i++) {
            worlds.create(WorldId.random(), owner, "world" + i, 12345L, 5000, Visibility.PRIVATE);
        }

        ActionResult result = actions.create(player, "extra-world", null).get();
        assertThat(result).isInstanceOf(ActionResult.Failed.class);
        assertThat(PlainTextComponentSerializer.plainText().serialize(result.message()))
                .contains("already own " + policy.maxWorldsPerPlayer() + " worlds");
    }

    @Test
    void createWorldDuplicateNameFails() throws Exception {
        UUID owner = UUID.randomUUID();
        Player player = mockPlayer(owner, "Alice");
        playersByUuid.put(owner, player);

        worlds.create(WorldId.random(), owner, "myworld", 12345L, 5000, Visibility.PRIVATE);

        ActionResult result = actions.create(player, "myworld", null).get();
        assertThat(result).isInstanceOf(ActionResult.Failed.class);
        assertThat(PlainTextComponentSerializer.plainText().serialize(result.message()))
                .contains("already own a world called 'myworld'");
    }

    @Test
    void deleteWorldNotFoundFails() throws Exception {
        UUID owner = UUID.randomUUID();
        Player player = mockPlayer(owner, "Alice");
        playersByUuid.put(owner, player);

        ActionResult result = actions.delete(player, "nonexistent", true).get();
        assertThat(result).isInstanceOf(ActionResult.Failed.class);
        assertThat(PlainTextComponentSerializer.plainText().serialize(result.message()))
                .contains("you own no world called 'nonexistent'");
    }

    @Test
    void deleteWorldUnconfirmedRequiresConfirmation() throws Exception {
        UUID owner = UUID.randomUUID();
        Player player = mockPlayer(owner, "Alice");
        playersByUuid.put(owner, player);

        WorldId worldId = WorldId.random();
        worlds.create(worldId, owner, "readyworld", 12345L, 5000, Visibility.PRIVATE);
        worlds.transitionState(worldId, WorldState.CREATING, WorldState.READY);

        ActionResult result = actions.delete(player, "readyworld", false).get();
        assertThat(result).isInstanceOf(ActionResult.Failed.class);
        assertThat(PlainTextComponentSerializer.plainText().serialize(result.message()))
                .contains("/world delete readyworld confirm");
    }

    @Test
    void restoreWorldNotArchivedFails() throws Exception {
        UUID owner = UUID.randomUUID();
        Player player = mockPlayer(owner, "Alice");
        playersByUuid.put(owner, player);

        WorldId worldId = WorldId.random();
        worlds.create(worldId, owner, "readyworld", 12345L, 5000, Visibility.PRIVATE);
        worlds.transitionState(worldId, WorldState.CREATING, WorldState.READY);

        ActionResult result = actions.restore(player, "readyworld").get();
        assertThat(result).isInstanceOf(ActionResult.Failed.class);
        assertThat(PlainTextComponentSerializer.plainText().serialize(result.message()))
                .contains("does not need restoring");
    }

    @Test
    void inviteTargetNotFoundFails() throws Exception {
        UUID owner = UUID.randomUUID();
        Player player = mockPlayer(owner, "Alice");
        playersByUuid.put(owner, player);

        WorldId worldId = WorldId.random();
        worlds.create(worldId, owner, "myworld", 12345L, 5000, Visibility.PRIVATE);

        ActionResult result = actions.invite(player, "Ghost").get();
        assertThat(result).isInstanceOf(ActionResult.Failed.class);
        assertThat(PlainTextComponentSerializer.plainText().serialize(result.message()))
                .contains("no player called 'Ghost' has been seen");
    }

    @Test
    void inviteSelfFails() throws Exception {
        UUID owner = UUID.randomUUID();
        Player player = mockPlayer(owner, "Alice");
        playersByUuid.put(owner, player);
        playersByName.put("Alice", player);

        WorldId worldId = WorldId.random();
        worlds.create(worldId, owner, "myworld", 12345L, 5000, Visibility.PRIVATE);

        ActionResult result = actions.invite(player, "Alice").get();
        assertThat(result).isInstanceOf(ActionResult.Failed.class);
        assertThat(PlainTextComponentSerializer.plainText().serialize(result.message()))
                .contains("you are already the owner");
    }

    @Test
    void kickOwnerFails() throws Exception {
        UUID owner = UUID.randomUUID();
        Player player = mockPlayer(owner, "Alice");
        playersByUuid.put(owner, player);
        playersByName.put("Alice", player);

        WorldId worldId = WorldId.random();
        worlds.create(worldId, owner, "myworld", 12345L, 5000, Visibility.PRIVATE);

        ActionResult result = actions.kick(player, "Alice").get();
        assertThat(result).isInstanceOf(ActionResult.Failed.class);
        assertThat(PlainTextComponentSerializer.plainText().serialize(result.message()))
                .contains("you cannot kick yourself");
    }

    @Test
    void promoteTargetNotFoundFails() throws Exception {
        UUID owner = UUID.randomUUID();
        Player player = mockPlayer(owner, "Alice");
        playersByUuid.put(owner, player);

        WorldId worldId = WorldId.random();
        worlds.create(worldId, owner, "myworld", 12345L, 5000, Visibility.PRIVATE);

        ActionResult result = actions.promote(player, "Ghost").get();
        assertThat(result).isInstanceOf(ActionResult.Failed.class);
        assertThat(PlainTextComponentSerializer.plainText().serialize(result.message()))
                .contains("no player called 'Ghost' has been seen");
    }

    @Test
    void banTargetSelfFails() throws Exception {
        UUID owner = UUID.randomUUID();
        Player player = mockPlayer(owner, "Alice");
        playersByUuid.put(owner, player);
        playersByName.put("Alice", player);

        WorldId worldId = WorldId.random();
        worlds.create(worldId, owner, "myworld", 12345L, 5000, Visibility.PRIVATE);

        ActionResult result = actions.ban(player, "Alice", "Reason").get();
        assertThat(result).isInstanceOf(ActionResult.Failed.class);
        assertThat(PlainTextComponentSerializer.plainText().serialize(result.message()))
                .contains("you cannot ban yourself");
    }

    @Test
    void unbanNotBannedFails() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        Player player = mockPlayer(owner, "Alice");
        Player targetPlayer = mockPlayer(target, "Bob");
        playersByUuid.put(owner, player);
        playersByUuid.put(target, targetPlayer);
        playersByName.put("Bob", targetPlayer);

        WorldId worldId = WorldId.random();
        worlds.create(worldId, owner, "myworld", 12345L, 5000, Visibility.PRIVATE);

        ActionResult result = actions.unban(player, "Bob").get();
        assertThat(result).isInstanceOf(ActionResult.Failed.class);
        assertThat(PlainTextComponentSerializer.plainText().serialize(result.message()))
                .contains("Bob was not banned");
    }

    @Test
    void setSettingUnknownFails() throws Exception {
        UUID owner = UUID.randomUUID();
        Player player = mockPlayer(owner, "Alice");
        playersByUuid.put(owner, player);

        WorldId worldId = WorldId.random();
        worlds.create(worldId, owner, "myworld", 12345L, 5000, Visibility.PRIVATE);

        ActionResult result = actions.setSetting(player, "flyspeed", "10").get();
        assertThat(result).isInstanceOf(ActionResult.Failed.class);
        assertThat(PlainTextComponentSerializer.plainText().serialize(result.message()))
                .contains("unknown setting 'flyspeed'");
    }

    @Test
    void setSettingValidSucceeds() throws Exception {
        UUID owner = UUID.randomUUID();
        Player player = mockPlayer(owner, "Alice");
        playersByUuid.put(owner, player);

        WorldId worldId = WorldId.random();
        worlds.create(worldId, owner, "myworld", 12345L, 5000, Visibility.PRIVATE);

        ActionResult result = actions.setSetting(player, "pvp", "on").get();
        assertThat(result).isInstanceOf(ActionResult.Ok.class);
        assertThat(PlainTextComponentSerializer.plainText().serialize(result.message()))
                .contains("set pvp = true");

        PlayerWorld updated = worlds.findById(worldId).orElseThrow();
        assertThat(WorldSettings.fromJson(updated.settingsJson()).pvp()).isTrue();
    }

    @Test
    void setPublicTogglesVisibility() throws Exception {
        UUID owner = UUID.randomUUID();
        Player player = mockPlayer(owner, "Alice");
        playersByUuid.put(owner, player);

        WorldId worldId = WorldId.random();
        worlds.create(worldId, owner, "myworld", 12345L, 5000, Visibility.PRIVATE);

        ActionResult result = actions.setPublic(player, true, "Come visit").get();
        assertThat(result).isInstanceOf(ActionResult.Ok.class);
        assertThat(PlainTextComponentSerializer.plainText().serialize(result.message()))
                .contains("is now PUBLIC");

        PlayerWorld updated = worlds.findById(worldId).orElseThrow();
        assertThat(updated.visibility()).isEqualTo(Visibility.PUBLIC);
        assertThat(updated.description()).isEqualTo("Come visit");
    }

    @Test
    void showSettingsDisplaysCurrentSettings() throws Exception {
        UUID owner = UUID.randomUUID();
        Player player = mockPlayer(owner, "Alice");
        playersByUuid.put(owner, player);

        WorldId worldId = WorldId.random();
        worlds.create(worldId, owner, "myworld", 12345L, 5000, Visibility.PRIVATE);

        ActionResult result = actions.showSettings(player).get();
        assertThat(result).isInstanceOf(ActionResult.Ok.class);
        assertThat(messagesByPlayer.get(owner))
                .anySatisfy(comp -> assertThat(
                                PlainTextComponentSerializer.plainText().serialize(comp))
                        .contains("Settings for 'myworld':"));
    }

    @Test
    void listBansEmptyDisplaysNoBans() throws Exception {
        UUID owner = UUID.randomUUID();
        Player player = mockPlayer(owner, "Alice");
        playersByUuid.put(owner, player);

        WorldId worldId = WorldId.random();
        worlds.create(worldId, owner, "myworld", 12345L, 5000, Visibility.PRIVATE);

        ActionResult result = actions.listBans(player).get();
        assertThat(result).isInstanceOf(ActionResult.Ok.class);
        assertThat(PlainTextComponentSerializer.plainText().serialize(result.message()))
                .contains("No players are currently banned");
    }

    private Player mockPlayer(UUID uuid, String name) {
        return mockPlayer(uuid, name, permission -> true);
    }

    private Player mockPlayer(UUID uuid, String name, Predicate<String> permissions) {
        return (Player) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {Player.class}, (proxyObj, method, args) -> {
                    if (method.getName().equals("getUniqueId")) return uuid;
                    if (method.getName().equals("getUsername")) return name;
                    if (method.getName().equals("hasPermission")) {
                        return permissions.test((String) args[0]);
                    }
                    if (method.getName().equals("sendMessage")) {
                        messagesByPlayer
                                .computeIfAbsent(uuid, k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                                .add((Component) args[0]);
                        return null;
                    }
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
                    return null;
                });
    }
}
