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
import nl.gzmn.playerworlds.backend.world.WorldCacheLoader;
import nl.gzmn.playerworlds.backend.world.WorldFolders;
import nl.gzmn.playerworlds.backend.world.WorldLifecycleService;
import nl.gzmn.playerworlds.backend.world.WorldRegistry;
import nl.gzmn.playerworlds.backend.world.WorldSettingsCache;
import nl.gzmn.playerworlds.core.concurrent.MainThread;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.control.CommandKind;
import nl.gzmn.playerworlds.core.control.CommandResult;
import nl.gzmn.playerworlds.core.control.EjectPayload;
import nl.gzmn.playerworlds.core.control.MigratePayload;
import nl.gzmn.playerworlds.core.control.NodeCommand;
import nl.gzmn.playerworlds.core.db.Database;
import nl.gzmn.playerworlds.core.db.MembershipRepository;
import nl.gzmn.playerworlds.core.db.NetworkSettings;
import nl.gzmn.playerworlds.core.db.NodeCommandRepository;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository;
import nl.gzmn.playerworlds.core.model.Role;
import nl.gzmn.playerworlds.core.model.Visibility;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.model.WorldSettings;
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
        UnloadWorldHandler handler = new UnloadWorldHandler(null, NetworkPolicy::defaults);

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
        UnloadWorldHandler handler = new UnloadWorldHandler(null, NetworkPolicy::defaults);

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

        // No commit service: this node has no object storage configured, so the
        // handoff is the eject and the unload. The commit path is exercised where
        // a store exists, in :testing.
        WorldHandoff handoff =
                new WorldHandoff(registry, lifecycle, folders, executors, null, nodeCommands, NetworkPolicy::defaults);
        UnloadWorldHandler handler = new UnloadWorldHandler(handoff, NetworkPolicy::defaults);

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
        assertThat(message).contains("This world is being unloaded");
    }

    @Test
    void migrateWorldReturnsErrorWhenWorldIdMissing() throws Exception {
        WorldHandoff handoff = new WorldHandoff(
                new WorldRegistry(),
                lifecycleFor(new WorldRegistry()),
                folders,
                executors,
                null,
                new NodeCommandRepository(database),
                NetworkPolicy::defaults);
        MigrateWorldHandler handler = new MigrateWorldHandler(handoff, NetworkPolicy::defaults);

        CommandResult result = handler.handle(migrateCommand(null, "{\"targetNode\":\"node-2\"}"));

        assertFalse(result.isOk());
        assertEquals("ERROR:missing world_id", result.wire());
    }

    @Test
    void migrateWorldRefusesAnUnreadablePayload() throws Exception {
        // Refusing beats defaulting: a migration run against a payload nobody
        // could read would move the world somewhere the operator did not ask for.
        WorldHandoff handoff = new WorldHandoff(
                new WorldRegistry(),
                lifecycleFor(new WorldRegistry()),
                folders,
                executors,
                null,
                new NodeCommandRepository(database),
                NetworkPolicy::defaults);
        MigrateWorldHandler handler = new MigrateWorldHandler(handoff, NetworkPolicy::defaults);

        CommandResult result = handler.handle(migrateCommand(WorldId.random(), "not json"));

        assertFalse(result.isOk());
        assertThat(result.wire()).contains("unreadable migrate payload");
    }

    @Test
    void migrateWorldIsIdempotentForAWorldThisNodeDoesNotHold() throws Exception {
        // CP-5: the same instruction can land twice, and the second landing has
        // nothing to move.
        WorldRegistry registry = new WorldRegistry();
        WorldHandoff handoff = new WorldHandoff(
                registry,
                lifecycleFor(registry),
                folders,
                executors,
                null,
                new NodeCommandRepository(database),
                NetworkPolicy::defaults);
        MigrateWorldHandler handler = new MigrateWorldHandler(handoff, NetworkPolicy::defaults);

        CommandResult result = handler.handle(
                migrateCommand(WorldId.random(), MigratePayload.to("node-2", 0).format()));

        assertTrue(result.isOk());
    }

    private NodeCommand migrateCommand(WorldId worldId, String payload) {
        return new NodeCommand(
                20L,
                "node-1",
                worldId,
                0L,
                CommandKind.MIGRATE_WORLD.name(),
                payload,
                Instant.now(),
                Instant.now().plusSeconds(60),
                null,
                null,
                0,
                null);
    }

    private WorldLifecycleService lifecycleFor(WorldRegistry registry) {
        return new WorldLifecycleService(
                new PlayerWorldRepository(database),
                new MembershipRepository(database),
                new MembershipCache(),
                executors,
                platform,
                folders,
                registry,
                metrics,
                NetworkPolicy::defaults,
                tempDir);
    }

    @Test
    void invalidateCacheRefreshesMembershipFromTheRowWithWorldId() throws Exception {
        // R4/D13. This used to assert the cache was *emptied*, which is what the
        // handler did and what made /world promote demote the owner: a miss in
        // MembershipCache answers VISITOR rather than "go and read", so an evicted
        // entry on a loaded world is a wrong answer until the world unloads.
        MembershipCache membershipCache = new MembershipCache();
        WorldSettingsCache settingsCache = new WorldSettingsCache();
        PlayerWorldRepository worlds = new PlayerWorldRepository(database);
        MembershipRepository members = new MembershipRepository(database);

        UUID owner = UUID.randomUUID();
        UUID builder = UUID.randomUUID();
        WorldId worldId = WorldId.random();
        var _ = offMain(() -> worlds.create(worldId, owner, "cache-refresh", 1L, 5000, Visibility.PRIVATE));

        // Stale on purpose: the node cached the membership before the promotion.
        membershipCache.put(worldId, owner, Map.of(owner, Role.OWNER));
        assertThat(membershipCache.roleOf(worldId, builder)).isEmpty();

        // The proxy's half of /world promote, already committed when the command
        // reaches the node.
        var _ = offMain(() -> database.inTransaction(
                connection -> members.insertMember(connection, worldId, builder, Role.BUILDER, owner)));

        WorldCacheLoader caches = new WorldCacheLoader(worlds, members, membershipCache, settingsCache);

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

        InvalidateCacheHandler handler = new InvalidateCacheHandler(networkSettings, caches, null, Runnable::run);

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

        CommandResult result = offMain(() -> handler.handle(command));
        assertTrue(result.isOk());

        // Refreshed, not emptied: the world is still cached, the owner is still
        // the owner (FR-31a), and the promotion the command announced is visible.
        assertTrue(membershipCache.isCached(worldId));
        assertThat(membershipCache.roleOf(worldId, owner)).contains(Role.OWNER);
        assertThat(membershipCache.roleOf(worldId, builder)).contains(Role.BUILDER);
    }

    @Test
    void invalidateCacheDropsAWorldThatNoLongerExists() throws Exception {
        // The one case where eviction is still right: the row is gone, so there
        // is nothing authoritative left to answer from.
        MembershipCache membershipCache = new MembershipCache();
        WorldSettingsCache settingsCache = new WorldSettingsCache();
        WorldCacheLoader caches = new WorldCacheLoader(
                new PlayerWorldRepository(database),
                new MembershipRepository(database),
                membershipCache,
                settingsCache);

        WorldId worldId = WorldId.random();
        UUID owner = UUID.randomUUID();
        membershipCache.put(worldId, owner, Map.of(owner, Role.OWNER));
        settingsCache.put(worldId, WorldSettings.defaults().withPvp(true));
        assertTrue(membershipCache.isCached(worldId));

        InvalidateCacheHandler handler = new InvalidateCacheHandler(null, caches, null, Runnable::run);

        NodeCommand command = new NodeCommand(
                3L,
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

        CommandResult result = offMain(() -> handler.handle(command));
        assertTrue(result.isOk());
        assertEquals(0, membershipCache.size());
    }

    @Test
    void ejectPlayerHandlesInvalidPayloadGracefully() {
        EjectPlayerHandler handler = new EjectPlayerHandler(loaderFor(new MembershipCache()), null, null, null, null);

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
    void ejectPlayerInvalidatesMembershipCacheWhenWorldIdProvided() throws Exception {
        MembershipCache membershipCache = new MembershipCache();
        WorldId worldId = WorldId.random();
        UUID owner = UUID.randomUUID();
        membershipCache.put(worldId, owner, Map.of(owner, Role.OWNER));
        assertTrue(membershipCache.isCached(worldId));

        EjectPlayerHandler handler = new EjectPlayerHandler(loaderFor(membershipCache), null, null, null, null);

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

        CommandResult result = offMain(() -> handler.handle(command));
        assertTrue(result.isOk());
        assertFalse(membershipCache.isCached(worldId));
    }

    @Test
    void ejectPlayerMessagesOnlinePlayerWhenInTargetWorld() throws Exception {
        MembershipCache membershipCache = new MembershipCache();
        WorldId worldId = WorldId.random();
        NodeCommandRepository nodeCommands = new NodeCommandRepository(database);

        String overworldName = folders.bukkitWorldName(worldId, DimensionKind.OVERWORLD);
        WorldMock worldMock = server.addSimpleWorld(overworldName);

        PlayerMock player = server.addPlayer();
        player.teleport(worldMock.getSpawnLocation());

        EjectPlayerHandler handler = new EjectPlayerHandler(
                loaderFor(membershipCache), folders, executors, nodeCommands, NetworkPolicy::defaults);

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

        CommandResult result = offMain(() -> handler.handle(command));
        assertTrue(result.isOk());

        String message = PlainTextComponentSerializer.plainText().serialize(player.nextComponentMessage());
        assertThat(message).contains("Custom kick reason");
    }

    @Test
    void archiveWorldHandlerReturnsErrorWhenWorldIdMissing() throws Exception {
        nl.gzmn.playerworlds.backend.storage.ArchiveStorage storage =
                nl.gzmn.playerworlds.backend.storage.ArchiveStorage.filesystem(tempDir.resolve("archives"));
        nl.gzmn.playerworlds.backend.storage.WorldArchiver archiver =
                new nl.gzmn.playerworlds.backend.storage.WorldArchiver(
                        new PlayerWorldRepository(database),
                        database,
                        storage,
                        tempDir.resolve("scratch"),
                        platform.worldLayout(),
                        "world",
                        null,
                        null,
                        null,
                        NetworkPolicy::defaults,
                        "node-1",
                        Platform.BUILD_DATA_VERSION);
        BackendControlHandlers.ArchiveWorldHandler handler = new BackendControlHandlers.ArchiveWorldHandler(archiver);

        NodeCommand command = new NodeCommand(
                7L,
                "node-1",
                null,
                null,
                CommandKind.ARCHIVE_WORLD.name(),
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
    void restoreWorldHandlerReturnsErrorWhenWorldIdMissing() throws Exception {
        nl.gzmn.playerworlds.backend.storage.ArchiveStorage storage =
                nl.gzmn.playerworlds.backend.storage.ArchiveStorage.filesystem(tempDir.resolve("archives"));
        nl.gzmn.playerworlds.backend.storage.WorldRestorer restorer =
                new nl.gzmn.playerworlds.backend.storage.WorldRestorer(
                        new PlayerWorldRepository(database),
                        new nl.gzmn.playerworlds.core.db.ArchiveRepository(database),
                        storage,
                        null,
                        null,
                        tempDir.resolve("scratch"),
                        NetworkPolicy::defaults,
                        "node-1",
                        Platform.BUILD_DATA_VERSION,
                        "26.2");
        BackendControlHandlers.RestoreWorldHandler handler = new BackendControlHandlers.RestoreWorldHandler(restorer);

        NodeCommand command = new NodeCommand(
                8L,
                "node-1",
                null,
                null,
                CommandKind.RESTORE_WORLD.name(),
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

    /**
     * Runs {@code work} off the thread this test marked as main.
     *
     * <p>The control plane dispatches handlers on its poll or LISTEN thread,
     * never on the tick thread, so a handler doing JDBC inline is correct in
     * production. This test class calls {@code MainThread.enter} on itself, so
     * without this the NFR-2 guard fires on the test thread rather than on a
     * real defect.
     */
    private <T> T offMain(java.util.concurrent.Callable<T> work) throws Exception {
        return executors.db().submit(work).get();
    }

    /** A loader over the caches under test, backed by the real schema. */
    private WorldCacheLoader loaderFor(MembershipCache membershipCache) {
        return new WorldCacheLoader(
                new PlayerWorldRepository(database),
                new MembershipRepository(database),
                membershipCache,
                new WorldSettingsCache());
    }
}
