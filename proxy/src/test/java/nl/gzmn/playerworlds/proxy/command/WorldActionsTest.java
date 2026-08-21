package nl.gzmn.playerworlds.proxy.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.control.CommandKind;
import nl.gzmn.playerworlds.core.control.CommandResult;
import nl.gzmn.playerworlds.core.control.DeletePayload;
import nl.gzmn.playerworlds.core.control.NodeCommand;
import nl.gzmn.playerworlds.core.control.WorldPayload;
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
import org.junit.jupiter.api.DisplayName;
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
                database,
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
    void deleteHardArchivedWorldSucceeds() throws Exception {
        nodeRepo.heartbeat("paper-a", "127.0.0.1:25566", 0, 0, 40, 20.0, false, 4903, "26.2");
        UUID owner = UUID.randomUUID();
        Player player = mockPlayer(owner, "Alice");
        playersByUuid.put(owner, player);

        WorldId worldId = WorldId.random();
        worlds.create(worldId, owner, "archivedworld", 12345L, 5000, Visibility.PRIVATE);
        worlds.transitionState(worldId, WorldState.CREATING, WorldState.READY);
        worlds.transitionState(worldId, WorldState.READY, WorldState.ARCHIVED);

        ActionResult result = actions.deleteHard(player, "archivedworld", true).get();
        assertThat(result).isInstanceOf(ActionResult.Ok.class);
        assertThat(PlainTextComponentSerializer.plainText().serialize(result.message()))
                .contains("permanently deleting 'archivedworld' and its archives");

        // R23 / FR-37: the proxy has no object-store client, so it routes the
        // deletion to a node rather than deleting the row and orphaning every
        // archive object the row's children named.
        assertThat(worlds.findById(worldId))
                .as("the row survives until the node reports the objects gone")
                .isPresent();
        assertThat(enqueuedKinds("paper-a")).containsExactly(CommandKind.DELETE_WORLD.name());
    }

    @Test
    void deleteHardMidTransitionWorldFailsWithConflict() throws Exception {
        UUID owner = UUID.randomUUID();
        Player player = mockPlayer(owner, "Alice");
        playersByUuid.put(owner, player);

        WorldId worldId = WorldId.random();
        worlds.create(worldId, owner, "archivingworld", 12345L, 5000, Visibility.PRIVATE);
        worlds.transitionState(worldId, WorldState.CREATING, WorldState.READY);
        worlds.transitionState(worldId, WorldState.READY, WorldState.ARCHIVING);

        ActionResult result = actions.deleteHard(player, "archivingworld", true).get();
        assertThat(result).isInstanceOf(ActionResult.Failed.class);
        assertThat(PlainTextComponentSerializer.plainText().serialize(result.message()))
                .contains("ARCHIVING and cannot be permanently deleted right now");
        assertThat(worlds.findById(worldId)).isPresent();
    }

    @Test
    void deleteHardReadyWorldWarnsThereIsNoBackup() throws Exception {
        UUID owner = UUID.randomUUID();
        Player player = mockPlayer(owner, "Alice");
        playersByUuid.put(owner, player);

        WorldId worldId = WorldId.random();
        worlds.create(worldId, owner, "unsaveable", 12345L, 5000, Visibility.PRIVATE);
        worlds.transitionState(worldId, WorldState.CREATING, WorldState.READY);

        ActionResult result = actions.deleteHard(player, "unsaveable", false).get();
        assertThat(result).isInstanceOf(ActionResult.Failed.class);
        assertThat(messagesByPlayer.get(owner))
                .anySatisfy(comp -> assertThat(
                                PlainTextComponentSerializer.plainText().serialize(comp))
                        .contains("has never been archived")
                        .contains("no backup")
                        // FR-27's archival copy promises the world comes back; this must not.
                        .doesNotContain("all backup archives"));
        assertThat(worlds.findById(worldId)).isPresent();
    }

    @Test
    void deleteHardReadyWorldRoutesToANodeWithTheConfirmedState() throws Exception {
        nodeRepo.heartbeat("paper-a", "127.0.0.1:25566", 0, 0, 40, 20.0, false, 4903, "26.2");
        UUID owner = UUID.randomUUID();
        Player player = mockPlayer(owner, "Alice");
        playersByUuid.put(owner, player);

        WorldId worldId = WorldId.random();
        worlds.create(worldId, owner, "unsaveable", 12345L, 5000, Visibility.PRIVATE);
        worlds.transitionState(worldId, WorldState.CREATING, WorldState.READY);

        ActionResult result = actions.deleteHard(player, "unsaveable", true).get();
        assertThat(result).isInstanceOf(ActionResult.Ok.class);
        // No archives to mention: FR-35 never ran, which is why this path exists.
        assertThat(PlainTextComponentSerializer.plainText().serialize(result.message()))
                .contains("permanently deleting 'unsaveable' on")
                .doesNotContain("archives");
        assertThat(enqueuedKinds("paper-a")).containsExactly(CommandKind.DELETE_WORLD.name());
        // The node refuses if a restore or an archival moved the world in the meantime. Parsed
        // rather than string-compared: the column is jsonb, so it comes back re-serialised.
        assertThat(enqueuedPayloads("paper-a"))
                .singleElement()
                .satisfies(payload ->
                        assertThat(DeletePayload.parse(payload)).contains(new DeletePayload(WorldState.READY)));
    }

    @Test
    void deleteHardUnconfirmedRequiresConfirmation() throws Exception {
        UUID owner = UUID.randomUUID();
        Player player = mockPlayer(owner, "Alice");
        playersByUuid.put(owner, player);

        WorldId worldId = WorldId.random();
        worlds.create(worldId, owner, "archivedworld", 12345L, 5000, Visibility.PRIVATE);
        worlds.transitionState(worldId, WorldState.CREATING, WorldState.READY);
        worlds.transitionState(worldId, WorldState.READY, WorldState.ARCHIVED);

        ActionResult result = actions.deleteHard(player, "archivedworld", false).get();
        assertThat(result).isInstanceOf(ActionResult.Failed.class);
        assertThat(PlainTextComponentSerializer.plainText().serialize(result.message()))
                .contains("/world delete archivedworld hard confirm");
        assertThat(worlds.findById(worldId)).isPresent();
    }

    @Test
    void deleteHardByIdSucceeds() throws Exception {
        nodeRepo.heartbeat("paper-a", "127.0.0.1:25566", 0, 0, 40, 20.0, false, 4903, "26.2");
        UUID owner = UUID.randomUUID();
        Player player = mockPlayer(owner, "Alice");
        playersByUuid.put(owner, player);

        WorldId worldId = WorldId.random();
        worlds.create(worldId, owner, "archivedworld", 12345L, 5000, Visibility.PRIVATE);
        worlds.transitionState(worldId, WorldState.CREATING, WorldState.READY);
        worlds.transitionState(worldId, WorldState.READY, WorldState.ARCHIVED);

        ActionResult result = actions.deleteHard(player, worldId).get();
        assertThat(result).isInstanceOf(ActionResult.Ok.class);
        assertThat(PlainTextComponentSerializer.plainText().serialize(result.message()))
                .contains("permanently deleting 'archivedworld' and its archives");
        assertThat(worlds.findById(worldId)).isPresent();
        assertThat(enqueuedKinds("paper-a")).containsExactly(CommandKind.DELETE_WORLD.name());
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
        PlayerWorld created = worlds.create(worldId, owner, "myworld", 12345L, 5000, Visibility.PRIVATE);
        // Assign a node so setSetting has somewhere to enqueue APPLY_SETTINGS (R9).
        database.inTransaction(connection -> {
            try (var stmt =
                    connection.prepareStatement("UPDATE player_world SET assigned_node = 'node-1' WHERE id = ?")) {
                stmt.setObject(1, worldId.value());
                stmt.executeUpdate();
            }
            return null;
        });

        ActionResult result = actions.setSetting(player, "pvp", "on").get();
        assertThat(result).isInstanceOf(ActionResult.Ok.class);
        assertThat(PlainTextComponentSerializer.plainText().serialize(result.message()))
                .contains("set pvp = true");

        PlayerWorld updated = worlds.findById(worldId).orElseThrow();
        assertThat(WorldSettings.fromJson(updated.settingsJson()).pvp()).isTrue();

        List<Long> ids = nodeCommands.findClaimableIds("node-1", policy.controlClaimTimeout(), 10);
        assertThat(ids).isNotEmpty();
        var cmd = nodeCommands.findById(ids.getFirst()).orElseThrow();
        assertThat(cmd.command())
                .as("R9: /world set must send APPLY_SETTINGS, not only INVALIDATE_CACHE")
                .isEqualTo(nl.gzmn.playerworlds.core.control.CommandKind.APPLY_SETTINGS.name());
        assertThat(cmd.worldId()).isEqualTo(worldId);
        assertThat(cmd.generation()).isEqualTo(created.generation());
    }

    @Test
    @DisplayName("FR-9i: a new boolean gamerule setting persists and enqueues APPLY_SETTINGS")
    void setSettingFr9iBooleanSucceeds() throws Exception {
        UUID owner = UUID.randomUUID();
        Player player = mockPlayer(owner, "Alice");
        playersByUuid.put(owner, player);

        WorldId worldId = WorldId.random();
        worlds.create(worldId, owner, "myworld", 12345L, 5000, Visibility.PRIVATE);
        database.inTransaction(connection -> {
            try (var stmt =
                    connection.prepareStatement("UPDATE player_world SET assigned_node = 'node-1' WHERE id = ?")) {
                stmt.setObject(1, worldId.value());
                stmt.executeUpdate();
            }
            return null;
        });

        ActionResult result = actions.setSetting(player, "keep-inventory", "on").get();
        assertThat(result).isInstanceOf(ActionResult.Ok.class);
        assertThat(PlainTextComponentSerializer.plainText().serialize(result.message()))
                .contains("set keep-inventory = true");

        PlayerWorld updated = worlds.findById(worldId).orElseThrow();
        assertThat(WorldSettings.fromJson(updated.settingsJson()).keepInventory())
                .isTrue();
    }

    @Test
    @DisplayName("FR-9i: a numeric gamerule setting parses, validates its range, and persists")
    void setSettingFr9iNumericSucceeds() throws Exception {
        UUID owner = UUID.randomUUID();
        Player player = mockPlayer(owner, "Alice");
        playersByUuid.put(owner, player);

        WorldId worldId = WorldId.random();
        worlds.create(worldId, owner, "myworld", 12345L, 5000, Visibility.PRIVATE);
        database.inTransaction(connection -> {
            try (var stmt =
                    connection.prepareStatement("UPDATE player_world SET assigned_node = 'node-1' WHERE id = ?")) {
                stmt.setObject(1, worldId.value());
                stmt.executeUpdate();
            }
            return null;
        });

        ActionResult result =
                actions.setSetting(player, "sleep-percentage", "50").get();
        assertThat(result).isInstanceOf(ActionResult.Ok.class);
        assertThat(PlainTextComponentSerializer.plainText().serialize(result.message()))
                .contains("set sleep-percentage = 50");

        PlayerWorld updated = worlds.findById(worldId).orElseThrow();
        assertThat(WorldSettings.fromJson(updated.settingsJson()).playersSleepingPercentage())
                .isEqualTo(50);
    }

    @Test
    @DisplayName("FR-9i: a non-numeric value for a numeric setting is rejected")
    void setSettingFr9iNumericRejectsNonNumeric() throws Exception {
        UUID owner = UUID.randomUUID();
        Player player = mockPlayer(owner, "Alice");
        playersByUuid.put(owner, player);

        WorldId worldId = WorldId.random();
        worlds.create(worldId, owner, "myworld", 12345L, 5000, Visibility.PRIVATE);

        ActionResult result =
                actions.setSetting(player, "sleep-percentage", "many").get();
        assertThat(result).isInstanceOf(ActionResult.Failed.class);
        assertThat(PlainTextComponentSerializer.plainText().serialize(result.message()))
                .contains("must be a whole number");

        PlayerWorld unchanged = worlds.findById(worldId).orElseThrow();
        assertThat(WorldSettings.fromJson(unchanged.settingsJson()).playersSleepingPercentage())
                .isEqualTo(100);
    }

    @Test
    @DisplayName("FR-9i: an out-of-range value for a ranged numeric setting is rejected")
    void setSettingFr9iNumericRejectsOutOfRange() throws Exception {
        UUID owner = UUID.randomUUID();
        Player player = mockPlayer(owner, "Alice");
        playersByUuid.put(owner, player);

        WorldId worldId = WorldId.random();
        worlds.create(worldId, owner, "myworld", 12345L, 5000, Visibility.PRIVATE);

        ActionResult result =
                actions.setSetting(player, "sleep-percentage", "150").get();
        assertThat(result).isInstanceOf(ActionResult.Failed.class);
        assertThat(PlainTextComponentSerializer.plainText().serialize(result.message()))
                .contains("must be a whole number");

        PlayerWorld unchanged = worlds.findById(worldId).orElseThrow();
        assertThat(WorldSettings.fromJson(unchanged.settingsJson()).playersSleepingPercentage())
                .isEqualTo(100);
    }

    @Test
    @DisplayName("R12: join that cannot route releases the lease it acquired (MN-12)")
    void joinNotRoutableReleasesAcquiredLease_R12() throws Exception {
        // Placement can select the node from the heartbeat table, but Velocity has
        // no RegisteredServer — the handoff fails after acquireLease.
        nodeRepo.heartbeat("paper-a", "127.0.0.1:25566", 0, 0, 40, 20.0, false, 4903, "26.2");

        UUID owner = UUID.randomUUID();
        Player player = mockPlayer(owner, "Alice");
        playersByUuid.put(owner, player);

        WorldId worldId = WorldId.random();
        worlds.create(worldId, owner, "r12world", 12345L, 5000, Visibility.PUBLIC);
        worlds.transitionState(worldId, WorldState.CREATING, WorldState.READY);

        ActionResult result = actions.join(player, worldId).get();
        assertThat(result).isInstanceOf(ActionResult.Failed.class);
        assertThat(((ActionResult.Failed) result).code()).isEqualTo(FailureCode.SERVER_UNROUTABLE);

        var placement = worlds.placementContext(worldId).orElseThrow();
        assertThat(placement.leaseHolder())
                .as("R12: proxy must release a lease acquired for a join that never left")
                .isNull();
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
    void setPublicRefusesWithoutPublicPermission_FR9h_R5() throws Exception {
        UUID owner = UUID.randomUUID();
        Player player = mockPlayer(owner, "Alice", permission -> !WorldCommand.PUBLIC_PERMISSION.equals(permission));
        playersByUuid.put(owner, player);

        WorldId worldId = WorldId.random();
        worlds.create(worldId, owner, "myworld", 12345L, 5000, Visibility.PRIVATE);

        ActionResult result = actions.setPublic(player, true, "nope").get();
        assertThat(result).isInstanceOf(ActionResult.Failed.class);
        assertThat(((ActionResult.Failed) result).code()).isEqualTo(FailureCode.PERMISSION_DENIED);
        assertThat(worlds.findById(worldId).orElseThrow().visibility()).isEqualTo(Visibility.PRIVATE);
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

    // -----------------------------------------------------------------------
    // Which world an owner command acts on (section 6, FR-1)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("two owned worlds and nothing to go on: the refusal says how to say which")
    void inviteWithTwoWorldsSaysHowToNameOne() throws Exception {
        UUID owner = UUID.randomUUID();
        Player player = mockPlayer(owner, "Alice");
        playersByUuid.put(owner, player);
        playersByName.put("Bob", mockPlayer(UUID.randomUUID(), "Bob"));

        worlds.create(WorldId.random(), owner, "first", 1L, 5000, Visibility.PRIVATE);
        worlds.create(WorldId.random(), owner, "second", 2L, 5000, Visibility.PRIVATE);

        ActionResult result = actions.invite(player, "Bob").get();

        assertThat(result).isInstanceOf(ActionResult.Failed.class);
        String text = PlainTextComponentSerializer.plainText().serialize(result.message());
        assertThat(text).contains("you own 2 worlds");
        assertThat(text).contains("first").contains("second");
        assertThat(text).contains("/world invite <player> <world>");
    }

    @Test
    @DisplayName("a command that ends in free text cannot take a world name, and says so")
    void banWithTwoWorldsPointsAtTheMenu() throws Exception {
        UUID owner = UUID.randomUUID();
        Player player = mockPlayer(owner, "Alice");
        playersByUuid.put(owner, player);
        playersByName.put("Bob", mockPlayer(UUID.randomUUID(), "Bob"));

        worlds.create(WorldId.random(), owner, "first", 1L, 5000, Visibility.PRIVATE);
        worlds.create(WorldId.random(), owner, "second", 2L, 5000, Visibility.PRIVATE);

        ActionResult result = actions.ban(player, "Bob", "griefing").get();

        assertThat(result).isInstanceOf(ActionResult.Failed.class);
        assertThat(PlainTextComponentSerializer.plainText().serialize(result.message()))
                .contains("/world menu");
    }

    @Test
    @DisplayName("the world the caller named is the one acted on")
    void inviteUsesTheWorldNamedByTheCaller() throws Exception {
        UUID owner = UUID.randomUUID();
        Player player = mockPlayer(owner, "Alice");
        playersByUuid.put(owner, player);
        playersByName.put("Bob", mockPlayer(UUID.randomUUID(), "Bob"));

        worlds.create(WorldId.random(), owner, "first", 1L, 5000, Visibility.PRIVATE);
        WorldId second = WorldId.random();
        worlds.create(second, owner, "second", 2L, 5000, Visibility.PRIVATE);

        WorldActions.Target target = actions.ownedWorld(player, "second").get();
        assertThat(target).isInstanceOf(WorldActions.Target.Found.class);
        assertThat(((WorldActions.Target.Found) target).world().id()).isEqualTo(second);

        ActionResult result = actions.invite(player, "Bob", second).get();
        assertThat(result).isInstanceOf(ActionResult.Ok.class);
        assertThat(PlainTextComponentSerializer.plainText().serialize(result.message()))
                .contains("invited Bob to 'second'");
    }

    @Test
    @DisplayName("a name that is not one of the caller's own worlds resolves to nothing")
    void ownedWorldRefusesANameTheCallerDoesNotOwn() throws Exception {
        UUID owner = UUID.randomUUID();
        Player player = mockPlayer(owner, "Alice");
        playersByUuid.put(owner, player);
        worlds.create(WorldId.random(), owner, "mine", 1L, 5000, Visibility.PRIVATE);
        worlds.create(WorldId.random(), UUID.randomUUID(), "theirs", 2L, 5000, Visibility.PRIVATE);

        WorldActions.Target target = actions.ownedWorld(player, "theirs").get();

        assertThat(target).isInstanceOf(WorldActions.Target.None.class);
        assertThat(PlainTextComponentSerializer.plainText()
                        .serialize(((WorldActions.Target.None) target).refusal().message()))
                .contains("you own no world called 'theirs'");
    }

    @Test
    @DisplayName("standing in one of your own worlds is saying which one (FR-6)")
    void inviteUsesTheWorldTheCallerIsStandingIn() throws Exception {
        UUID owner = UUID.randomUUID();
        Player player = mockPlayerOn(owner, "Alice", "node-1");
        playersByUuid.put(owner, player);
        playersByName.put("Bob", mockPlayer(UUID.randomUUID(), "Bob"));

        worlds.create(WorldId.random(), owner, "first", 1L, 5000, Visibility.PRIVATE);
        WorldId second = WorldId.random();
        worlds.create(second, owner, "second", 2L, 5000, Visibility.PRIVATE);
        actions.presence().entered(owner, "node-1", second);

        ActionResult result = actions.invite(player, "Bob").get();

        assertThat(result).isInstanceOf(ActionResult.Ok.class);
        assertThat(PlainTextComponentSerializer.plainText().serialize(result.message()))
                .contains("invited Bob to 'second'");
    }

    @Test
    @DisplayName("standing in someone else's world does not choose it")
    void presenceInAWorldTheCallerDoesNotOwnIsNotAnAnswer() throws Exception {
        UUID owner = UUID.randomUUID();
        Player player = mockPlayerOn(owner, "Alice", "node-1");
        playersByUuid.put(owner, player);
        playersByName.put("Bob", mockPlayer(UUID.randomUUID(), "Bob"));

        worlds.create(WorldId.random(), owner, "first", 1L, 5000, Visibility.PRIVATE);
        worlds.create(WorldId.random(), owner, "second", 2L, 5000, Visibility.PRIVATE);
        WorldId visiting = WorldId.random();
        worlds.create(visiting, UUID.randomUUID(), "carols", 3L, 5000, Visibility.PUBLIC);
        actions.presence().entered(owner, "node-1", visiting);

        ActionResult result = actions.invite(player, "Bob").get();

        assertThat(result).isInstanceOf(ActionResult.Failed.class);
        assertThat(PlainTextComponentSerializer.plainText().serialize(result.message()))
                .contains("you own 2 worlds");
    }

    @Test
    @DisplayName("the invite notification carries the accept as a click (FR-6)")
    void inviteNotificationIsClickable_FR6() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID guest = UUID.randomUUID();
        Player alice = mockPlayer(owner, "Alice");
        Player bob = mockPlayer(guest, "Bob");
        playersByUuid.put(owner, alice);
        playersByUuid.put(guest, bob);
        playersByName.put("Bob", bob);

        worlds.create(WorldId.random(), owner, "hideout", 1L, 5000, Visibility.PRIVATE);

        ActionResult result = actions.invite(alice, "Bob").get();
        assertThat(result).isInstanceOf(ActionResult.Ok.class);

        List<Component> received = messagesByPlayer.getOrDefault(guest, List.of());
        assertThat(received).hasSize(1);
        Component notice = received.getFirst();

        assertThat(PlainTextComponentSerializer.plainText().serialize(notice))
                .as("the command stays readable: a click event is invisible in a screenshot or a log")
                .contains("/world accept Alice");
        assertThat(clickCommands(notice))
                .as("the invitee should not have to retype what the invite already knows")
                .contains("/world accept Alice");
    }

    @Test
    @DisplayName("accepting an invite refreshes the node's membership cache (FR-7, FR-9)")
    void acceptRefreshesTheNodeMembershipCache_FR9() throws Exception {
        // The node answers "may this player break a block" from MembershipCache,
        // which is filled at world load. A world that is already loaded when the
        // invite is accepted kept the new BUILDER as a VISITOR until it unloaded:
        // they could walk in and not build.
        UUID owner = UUID.randomUUID();
        UUID guest = UUID.randomUUID();
        Player alice = mockPlayer(owner, "Alice");
        Player bob = mockPlayer(guest, "Bob");
        playersByUuid.put(owner, alice);
        playersByUuid.put(guest, bob);
        playersByName.put("Alice", alice);
        playersByName.put("Bob", bob);

        WorldId worldId = WorldId.random();
        PlayerWorld created = worlds.create(worldId, owner, "hideout", 1L, 5000, Visibility.PRIVATE);
        database.inTransaction(connection -> {
            try (var stmt =
                    connection.prepareStatement("UPDATE player_world SET assigned_node = 'node-1' WHERE id = ?")) {
                stmt.setObject(1, worldId.value());
                stmt.executeUpdate();
            }
            return null;
        });

        assertThat(actions.invite(alice, "Bob").get()).isInstanceOf(ActionResult.Ok.class);

        ActionResult accepted = actions.accept(bob, "Alice").get();
        assertThat(accepted).isInstanceOf(ActionResult.Ok.class);
        assertThat(membership.findMember(worldId, guest).orElseThrow().role()).isEqualTo(Role.BUILDER);

        List<Long> ids = nodeCommands.findClaimableIds("node-1", policy.controlClaimTimeout(), 10);
        assertThat(ids).isNotEmpty();
        var command = nodeCommands.findById(ids.getFirst()).orElseThrow();
        assertThat(command.command()).isEqualTo(CommandKind.INVALIDATE_CACHE.name());
        assertThat(command.worldId()).isEqualTo(worldId);
        assertThat(command.generation()).isEqualTo(created.generation());
    }

    /** Every {@code runCommand} click event anywhere in a component tree. */
    private static List<String> clickCommands(Component component) {
        List<String> commands = new java.util.ArrayList<>();
        collectClickCommands(component, commands);
        return commands;
    }

    private static void collectClickCommands(Component component, List<String> into) {
        ClickEvent<?> click = component.clickEvent();
        if (click != null
                && click.action().equals(ClickEvent.Action.RUN_COMMAND)
                && click.payload() instanceof ClickEvent.Payload.Text text) {
            into.add(text.value());
        }
        for (Component child : component.children()) {
            collectClickCommands(child, into);
        }
    }

    private Player mockPlayer(UUID uuid, String name) {
        return mockPlayer(uuid, name, permission -> true);
    }

    /** A player the proxy sees on a node, for the world-they-are-standing-in rule. */
    private Player mockPlayerOn(UUID uuid, String name, String serverName) {
        ServerInfo info = new ServerInfo(serverName, new InetSocketAddress(InetAddress.getLoopbackAddress(), 25566));
        ServerConnection connection = (ServerConnection) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {ServerConnection.class},
                (proxyObj, method, args) -> method.getName().equals("getServerInfo") ? info : null);
        return mockPlayer(uuid, name, permission -> true, Optional.of(connection));
    }

    private Player mockPlayer(UUID uuid, String name, Predicate<String> permissions) {
        return mockPlayer(uuid, name, permissions, Optional.empty());
    }

    private Player mockPlayer(
            UUID uuid, String name, Predicate<String> permissions, Optional<ServerConnection> currentServer) {
        return (Player) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {Player.class}, (proxyObj, method, args) -> {
                    if (method.getName().equals("getUniqueId")) return uuid;
                    if (method.getName().equals("getCurrentServer")) return currentServer;
                    if (method.getName().equals("getUsername")) return name;
                    if (method.getName().equals("getPermissionValue")) {
                        return permissions.test((String) args[0]) ? Tristate.TRUE : Tristate.FALSE;
                    }
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

    /** The command payloads queued for a node, oldest first. */
    private List<String> enqueuedPayloads(String nodeId) throws Exception {
        NodeCommandRepository commands = new NodeCommandRepository(database);
        List<String> payloads = new ArrayList<>();
        for (Long id : commands.findClaimableIds(nodeId, Duration.ofMinutes(1), 10)) {
            commands.findById(id).ifPresent(command -> payloads.add(command.payloadJson()));
        }
        return payloads;
    }

    /** The command kinds queued for a node, oldest first. */
    private List<String> enqueuedKinds(String nodeId) throws Exception {
        NodeCommandRepository commands = new NodeCommandRepository(database);
        List<String> kinds = new ArrayList<>();
        for (Long id : commands.findClaimableIds(nodeId, Duration.ofMinutes(1), 10)) {
            commands.findById(id).ifPresent(command -> kinds.add(command.command()));
        }
        return kinds;
    }

    @Test
    @DisplayName("an archive discarded as stale reports the discard to the owner (CP-5, CP-6)")
    void anArchiveDiscardedAsStaleIsReportedToTheOwner_CP6() throws Exception {
        nodeRepo.heartbeat("paper-a", "127.0.0.1:25566", 0, 0, 40, 20.0, false, 4903, "26.2");
        UUID owner = UUID.randomUUID();
        Player player = mockPlayer(owner, "Alice");
        playersByUuid.put(owner, player);

        WorldId worldId = WorldId.random();
        worlds.create(worldId, owner, "staleworld", 12345L, 5000, Visibility.PRIVATE);
        worlds.transitionState(worldId, WorldState.CREATING, WorldState.READY);

        // Stand in for the node: claim whatever the proxy enqueues and complete it
        // the way CP-4 does when the world has moved on since the command was
        // issued. This is the outcome that used to be invisible -- the world stays
        // READY, the owner's slot stays consumed, and the owner was told it was
        // archiving.
        NodeCommandRepository commands = new NodeCommandRepository(database);
        Thread node = new Thread(() -> {
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                try {
                    for (Long id : commands.findClaimableIds("paper-a", Duration.ofMinutes(1), 10)) {
                        if (commands.claim(id, Duration.ofMinutes(1)).isPresent()) {
                            var _ = commands.complete(id, CommandResult.staleGeneration());
                            return;
                        }
                    }
                    Thread.sleep(20);
                } catch (Exception e) {
                    return;
                }
            }
        });
        node.setDaemon(true);
        node.start();

        ActionResult result = actions.delete(player, "staleworld", true).get();
        node.join(Duration.ofSeconds(10).toMillis());

        assertThat(result).isInstanceOf(ActionResult.Failed.class);
        assertThat(((ActionResult.Failed) result).code()).isEqualTo(FailureCode.STATE_CONFLICT);
        assertThat(PlainTextComponentSerializer.plainText().serialize(result.message()))
                .contains("archiving 'staleworld' did not happen")
                .contains("the world moved on");
    }

    @Test
    @DisplayName("a command nobody has claimed yet still reports as running, not as failed")
    void anUnclaimedCommandReportsAsRunning() throws Exception {
        nodeRepo.heartbeat("paper-a", "127.0.0.1:25566", 0, 0, 40, 20.0, false, 4903, "26.2");
        UUID owner = UUID.randomUUID();
        Player player = mockPlayer(owner, "Alice");
        playersByUuid.put(owner, player);

        WorldId worldId = WorldId.random();
        worlds.create(worldId, owner, "slowworld", 12345L, 5000, Visibility.PRIVATE);
        worlds.transitionState(worldId, WorldState.CREATING, WorldState.READY);

        // No node claims it. An archive of a large world takes minutes, so silence
        // is not a refusal and must not be reported as one.
        ActionResult result = actions.delete(player, "slowworld", true).get();

        assertThat(result).isInstanceOf(ActionResult.Ok.class);
        assertThat(PlainTextComponentSerializer.plainText().serialize(result.message()))
                .contains("archiving 'slowworld'");
    }

    @Test
    @DisplayName("deleting a world stuck in CREATING reports success, not failure (FR-27, R25)")
    void deletingACreatingWorldReportsSuccess_FR27() throws Exception {
        nodeRepo.heartbeat("paper-a", "127.0.0.1:25566", 0, 0, 40, 20.0, false, 4903, "26.2");
        UUID owner = UUID.randomUUID();
        Player player = mockPlayer(owner, "Alice");
        playersByUuid.put(owner, player);

        WorldId worldId = WorldId.random();
        // Left in CREATING: the create failed before the world reached READY, so
        // the row is the only thing that exists and it is consuming a cap slot.
        worlds.create(worldId, owner, "halfworld", 12345L, 5000, Visibility.PRIVATE);

        ActionResult result = actions.delete(player, "halfworld", true).get();

        // The bug: the enqueue set node_command.world_id, whose foreign key had
        // just been deleted. The SQLException reached the outer handler and the
        // owner was told "that did not work" -- after the world was gone and
        // their slot freed.
        assertThat(result).isInstanceOf(ActionResult.Ok.class);
        assertThat(PlainTextComponentSerializer.plainText().serialize(result.message()))
                .contains("removed incomplete world 'halfworld'");
        assertThat(worlds.findById(worldId)).isEmpty();

        // And the node was still told to drop whatever it materialised, with the
        // world named in the payload because the column would have cascaded away.
        NodeCommandRepository commands = new NodeCommandRepository(database);
        List<Long> queued = commands.findClaimableIds("paper-a", Duration.ofMinutes(1), 10);
        assertThat(queued).hasSize(1);
        NodeCommand command = commands.findById(queued.getFirst()).orElseThrow();
        assertThat(command.command()).isEqualTo(CommandKind.UNLOAD_WORLD.name());
        assertThat(command.worldId()).isNull();
        assertThat(WorldPayload.parse(command.payloadJson()))
                .get()
                .extracting(WorldPayload::worldId)
                .isEqualTo(worldId);
    }
}
