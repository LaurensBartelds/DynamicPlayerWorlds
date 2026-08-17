package nl.gzmn.playerworlds.backend.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import nl.gzmn.playerworlds.backend.platform.DimensionKind;
import nl.gzmn.playerworlds.backend.platform.Platform;
import nl.gzmn.playerworlds.backend.platform.ServerIdentity;
import nl.gzmn.playerworlds.backend.world.LoadedWorld;
import nl.gzmn.playerworlds.backend.world.MembershipCache;
import nl.gzmn.playerworlds.backend.world.WorldFolders;
import nl.gzmn.playerworlds.backend.world.WorldLifecycleService;
import nl.gzmn.playerworlds.backend.world.WorldRegistry;
import nl.gzmn.playerworlds.core.concurrent.MainThread;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.control.CommandKind;
import nl.gzmn.playerworlds.core.control.CommandResult;
import nl.gzmn.playerworlds.core.control.EjectPayload;
import nl.gzmn.playerworlds.core.control.NodeCommand;
import nl.gzmn.playerworlds.core.db.Database;
import nl.gzmn.playerworlds.core.db.MembershipRepository;
import nl.gzmn.playerworlds.core.db.NetworkSettings;
import nl.gzmn.playerworlds.core.db.NodeCommandRepository;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository;
import nl.gzmn.playerworlds.core.model.Role;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.obs.WorldsMetrics;
import nl.gzmn.playerworlds.testing.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

class BackendControlHandlersTest {

    @TempDir
    Path tempDir;

    private ServerMock server;
    private Database database;
    private PluginExecutors executors;
    private WorldsMetrics metrics;
    private Platform platform;
    private WorldFolders folders;

    @BeforeEach
    void setUp() throws Exception {
        database = TestDatabase.openFresh();
        nl.gzmn.playerworlds.core.db.Schema.migrate(database);
        executors = PluginExecutors.create(2, 2, Runnable::run);
        metrics = WorldsMetrics.create();
        platform = Platform.create(new ServerIdentity("26.2", Platform.BUILD_DATA_VERSION));
        folders = new WorldFolders(platform.worldLayout());

        MainThread.enter(Thread.currentThread());
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
        MainThread.clear();
        metrics.close();
        executors.shutdown(Duration.ofSeconds(5));
        database.close();
    }

    @Test
    void unloadWorldReturnsOkWhenWorldNotLoaded() throws Exception {
        WorldRegistry registry = new WorldRegistry();
        UnloadWorldHandler handler = new UnloadWorldHandler(registry, null, null, null, null, null);

        NodeCommand command = new NodeCommand(
                1L,
                "node-1",
                WorldId.random(),
                0L,
                CommandKind.UNLOAD_WORLD.name(),
                "{}",
                Instant.now(),
                Instant.now().plusSeconds(60),
                null,
                null,
                0,
                null);

        CommandResult result = handler.handle(command);
        assertTrue(result.isOk());
    }

    @Test
    void unloadWorldReturnsErrorWhenWorldIdMissing() throws Exception {
        WorldRegistry registry = new WorldRegistry();
        UnloadWorldHandler handler = new UnloadWorldHandler(registry, null, null, null, null, null);

        NodeCommand command = new NodeCommand(
                1L,
                "node-1",
                null,
                0L,
                CommandKind.UNLOAD_WORLD.name(),
                "{}",
                Instant.now(),
                Instant.now().plusSeconds(60),
                null,
                null,
                0,
                null);

        CommandResult result = handler.handle(command);
        assertFalse(result.isOk());
        assertEquals("ERROR:missing world_id", result.wire());
    }

    @Test
    void unloadWorldUnloadsLoadedWorldAndMessagesPlayers() throws Exception {
        WorldMock defaultWorld = server.addSimpleWorld("world");
        WorldRegistry registry = new WorldRegistry();
        MembershipCache membershipCache = new MembershipCache();
        PlayerWorldRepository worlds = new PlayerWorldRepository(database);
        MembershipRepository membership = new MembershipRepository(database);
        NodeCommandRepository nodeCommands = new NodeCommandRepository(database);

        WorldLifecycleService lifecycle = new WorldLifecycleService(
                worlds,
                membership,
                membershipCache,
                executors,
                platform,
                folders,
                registry,
                metrics,
                NetworkPolicy::defaults,
                tempDir);

        WorldId worldId = WorldId.random();
        UUID owner = UUID.randomUUID();
        LoadedWorld loaded = new LoadedWorld(worldId, owner, "TestWorld", 12345L, 5000);
        loaded.markMaterialised(DimensionKind.OVERWORLD);
        registry.register(loaded);

        String overworldName = folders.bukkitWorldName(worldId, DimensionKind.OVERWORLD);
        WorldMock worldMock = server.addSimpleWorld(overworldName);

        PlayerMock player = server.addPlayer();
        player.teleport(worldMock.getSpawnLocation());

        UnloadWorldHandler handler =
                new UnloadWorldHandler(registry, lifecycle, folders, executors, nodeCommands, NetworkPolicy::defaults);

        NodeCommand command = new NodeCommand(
                10L,
                "node-1",
                worldId,
                0L,
                CommandKind.UNLOAD_WORLD.name(),
                "{}",
                Instant.now(),
                Instant.now().plusSeconds(60),
                null,
                null,
                0,
                null);

        CommandResult result = handler.handle(command);
        assertTrue(result.isOk());
        assertFalse(registry.isLoaded(worldId));
        assertEquals(defaultWorld, player.getWorld());

        String message = PlainTextComponentSerializer.plainText().serialize(player.nextComponentMessage());
        assertThat(message).contains("World is unloading...");
    }

    @Test
    void invalidateCacheInvalidatesMembershipCacheAndPolicyWithWorldId() throws Exception {
        MembershipCache membershipCache = new MembershipCache();
        WorldId worldId = WorldId.random();
        UUID owner = UUID.randomUUID();
        membershipCache.put(worldId, owner, Map.of(owner, Role.OWNER));
        assertTrue(membershipCache.isCached(worldId));

        NetworkSettings networkSettings = new NetworkSettings(database);
        executors
                .db()
                .submit(() -> {
                    try {
                        networkSettings.reload();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .get();
        assertThat(networkSettings.policy()).isNotNull();

        InvalidateCacheHandler handler = new InvalidateCacheHandler(networkSettings, membershipCache, Runnable::run);

        NodeCommand command = new NodeCommand(
                2L,
                "node-1",
                worldId,
                null,
                CommandKind.INVALIDATE_CACHE.name(),
                "{}",
                Instant.now(),
                Instant.now().plusSeconds(60),
                null,
                null,
                0,
                null);

        CommandResult result = handler.handle(command);
        assertTrue(result.isOk());
        assertFalse(membershipCache.isCached(worldId));
    }

    @Test
    void invalidateCacheClearsMembershipCacheWhenNoWorldId() {
        MembershipCache membershipCache = new MembershipCache();
        WorldId worldId1 = WorldId.random();
        WorldId worldId2 = WorldId.random();
        UUID owner = UUID.randomUUID();
        membershipCache.put(worldId1, owner, Map.of(owner, Role.OWNER));
        membershipCache.put(worldId2, owner, Map.of(owner, Role.OWNER));
        assertEquals(2, membershipCache.size());

        InvalidateCacheHandler handler = new InvalidateCacheHandler(null, membershipCache, Runnable::run);

        NodeCommand command = new NodeCommand(
                3L,
                "node-1",
                null,
                null,
                CommandKind.INVALIDATE_CACHE.name(),
                "{}",
                Instant.now(),
                Instant.now().plusSeconds(60),
                null,
                null,
                0,
                null);

        CommandResult result = handler.handle(command);
        assertTrue(result.isOk());
        assertEquals(0, membershipCache.size());
    }

    @Test
    void ejectPlayerHandlesInvalidPayloadGracefully() {
        MembershipCache membershipCache = new MembershipCache();
        EjectPlayerHandler handler = new EjectPlayerHandler(membershipCache, null, null, null, null);

        NodeCommand command = new NodeCommand(
                4L,
                "node-1",
                null,
                null,
                CommandKind.EJECT_PLAYER.name(),
                "invalid json",
                Instant.now(),
                Instant.now().plusSeconds(60),
                null,
                null,
                0,
                null);

        CommandResult result = handler.handle(command);
        assertTrue(result.isOk());
    }

    @Test
    void ejectPlayerInvalidatesMembershipCacheWhenWorldIdProvided() {
        MembershipCache membershipCache = new MembershipCache();
        WorldId worldId = WorldId.random();
        UUID owner = UUID.randomUUID();
        membershipCache.put(worldId, owner, Map.of(owner, Role.OWNER));
        assertTrue(membershipCache.isCached(worldId));

        EjectPlayerHandler handler = new EjectPlayerHandler(membershipCache, null, null, null, null);

        UUID target = UUID.randomUUID();
        String payload = EjectPayload.format(target, "Kicked from world");
        NodeCommand command = new NodeCommand(
                5L,
                "node-1",
                worldId,
                null,
                CommandKind.KICK_MEMBER.name(),
                payload,
                Instant.now(),
                Instant.now().plusSeconds(60),
                null,
                null,
                0,
                null);

        CommandResult result = handler.handle(command);
        assertTrue(result.isOk());
        assertFalse(membershipCache.isCached(worldId));
    }

    @Test
    void ejectPlayerMessagesOnlinePlayerWhenInTargetWorld() {
        MembershipCache membershipCache = new MembershipCache();
        WorldId worldId = WorldId.random();
        NodeCommandRepository nodeCommands = new NodeCommandRepository(database);

        String overworldName = folders.bukkitWorldName(worldId, DimensionKind.OVERWORLD);
        WorldMock worldMock = server.addSimpleWorld(overworldName);

        PlayerMock player = server.addPlayer();
        player.teleport(worldMock.getSpawnLocation());

        EjectPlayerHandler handler =
                new EjectPlayerHandler(membershipCache, folders, executors, nodeCommands, NetworkPolicy::defaults);

        String payload = EjectPayload.format(player.getUniqueId(), "Custom kick reason");
        NodeCommand command = new NodeCommand(
                6L,
                "node-1",
                worldId,
                null,
                CommandKind.KICK_MEMBER.name(),
                payload,
                Instant.now(),
                Instant.now().plusSeconds(60),
                null,
                null,
                0,
                null);

        CommandResult result = handler.handle(command);
        assertTrue(result.isOk());

        String message = PlainTextComponentSerializer.plainText().serialize(player.nextComponentMessage());
        assertThat(message).contains("Custom kick reason");
    }
}
