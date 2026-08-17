package nl.gzmn.playerworlds.backend.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import nl.gzmn.playerworlds.backend.platform.DimensionKind;
import nl.gzmn.playerworlds.backend.platform.Platform;
import nl.gzmn.playerworlds.backend.platform.ServerIdentity;
import nl.gzmn.playerworlds.backend.world.MembershipCache;
import nl.gzmn.playerworlds.backend.world.WorldFolders;
import nl.gzmn.playerworlds.backend.world.WorldLifecycleService;
import nl.gzmn.playerworlds.backend.world.WorldRegistry;
import nl.gzmn.playerworlds.core.concurrent.MainThread;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.control.CommandKind;
import nl.gzmn.playerworlds.core.control.EjectPayload;
import nl.gzmn.playerworlds.core.control.NodeCommand;
import nl.gzmn.playerworlds.core.db.Database;
import nl.gzmn.playerworlds.core.db.MembershipRepository;
import nl.gzmn.playerworlds.core.db.NodeCommandRepository;
import nl.gzmn.playerworlds.core.db.PlayerNameRepository;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository;
import nl.gzmn.playerworlds.core.db.Schema;
import nl.gzmn.playerworlds.core.model.Visibility;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.obs.WorldsMetrics;
import nl.gzmn.playerworlds.testing.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

class PworldCommandTest {

    @TempDir
    Path tempDir;

    private ServerMock server;
    private Database database;
    private PluginExecutors executors;
    private WorldsMetrics metrics;
    private Platform platform;
    private WorldFolders folders;
    private WorldRegistry registry;
    private MembershipCache membershipCache;
    private PlayerWorldRepository worldRepo;
    private MembershipRepository membershipRepo;
    private PlayerNameRepository nameRepo;
    private NodeCommandRepository nodeCommands;
    private WorldLifecycleService lifecycle;
    private PworldCommand commandHandler;

    @BeforeEach
    void setUp() throws Exception {
        database = TestDatabase.openFresh();
        Schema.migrate(database);
        executors = PluginExecutors.create(2, 2, Runnable::run);
        metrics = WorldsMetrics.create();
        platform = Platform.create(new ServerIdentity("26.2", Platform.BUILD_DATA_VERSION));
        folders = new WorldFolders(platform.worldLayout());
        registry = new WorldRegistry();
        membershipCache = new MembershipCache();
        worldRepo = new PlayerWorldRepository(database);
        membershipRepo = new MembershipRepository(database);
        nameRepo = new PlayerNameRepository(database);
        nodeCommands = new NodeCommandRepository(database);

        lifecycle = new WorldLifecycleService(
                worldRepo,
                membershipRepo,
                membershipCache,
                executors,
                platform,
                folders,
                registry,
                metrics,
                NetworkPolicy::defaults,
                tempDir);

        commandHandler = new PworldCommand(
                lifecycle,
                registry,
                folders,
                worldRepo,
                membershipRepo,
                nameRepo,
                executors,
                nodeCommands,
                NetworkPolicy::defaults);

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

    private <T> T onDb(Callable<T> task) throws Exception {
        return executors.db().submit(task).get(5, TimeUnit.SECONDS);
    }

    private List<Long> awaitNodeCommands() throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        List<Long> ids = onDb(() -> nodeCommands.findClaimableIds("proxy", Duration.ofMinutes(1), 10));
        while (ids.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
            ids = onDb(() -> nodeCommands.findClaimableIds("proxy", Duration.ofMinutes(1), 10));
        }
        return ids;
    }

    @Test
    @DisplayName("/pworld leave messages player, teleports to fallback world, and enqueues EJECT_PLAYER to proxy")
    void leaveSubcommandMessagesPlayerTeleportsToFallbackAndEnqueuesEject() throws Exception {
        WorldMock defaultWorld = server.addSimpleWorld("world");
        PlayerMock player = server.addPlayer();
        UUID playerUuid = player.getUniqueId();
        WorldId worldId = WorldId.random();

        onDb(() -> worldRepo.create(worldId, playerUuid, "HomeWorld", 12345L, 5000, Visibility.PRIVATE));
        String overworldName = folders.bukkitWorldName(worldId, DimensionKind.OVERWORLD);
        WorldMock playerWorld = server.addSimpleWorld(overworldName);

        player.teleport(playerWorld.getSpawnLocation());
        assertEquals(playerWorld, player.getWorld());

        boolean result = commandHandler.onCommand(player, null, "pworld", new String[] {"leave"});
        assertThat(result).isTrue();

        assertEquals(defaultWorld, player.getWorld());
        String message = PlainTextComponentSerializer.plainText().serialize(player.nextComponentMessage());
        assertThat(message).contains("Returning to lobby...");

        List<Long> ids = awaitNodeCommands();
        assertThat(ids).hasSize(1);
        NodeCommand command = onDb(() -> nodeCommands.findById(ids.getFirst())).orElseThrow();
        assertEquals(CommandKind.EJECT_PLAYER.name(), command.command());
        assertEquals(worldId, command.worldId());
        var eject = EjectPayload.parse(command.payloadJson());
        assertThat(eject).isPresent();
        assertEquals(playerUuid, eject.get().playerUuid());
        assertEquals("Left world", eject.get().reason());
    }

    @Test
    @DisplayName("/pworld leave when in holding area enqueues EJECT_PLAYER to proxy")
    void leaveSubcommandWhenInHoldingAreaEnqueuesEject() throws Exception {
        WorldMock defaultWorld = server.addSimpleWorld("world");
        PlayerMock player = server.addPlayer();
        player.teleport(defaultWorld.getSpawnLocation());

        boolean result = commandHandler.onCommand(player, null, "pworld", new String[] {"leave"});
        assertThat(result).isTrue();

        String message = PlainTextComponentSerializer.plainText().serialize(player.nextComponentMessage());
        assertThat(message).contains("Returning to lobby...");

        List<Long> ids = awaitNodeCommands();
        assertThat(ids).hasSize(1);
        NodeCommand command = onDb(() -> nodeCommands.findById(ids.getFirst())).orElseThrow();
        assertEquals(CommandKind.EJECT_PLAYER.name(), command.command());
        assertThat(command.worldId()).isNull();
    }

    @Test
    @DisplayName("tab completion offers leave subcommand")
    void tabCompletionOffersLeave() {
        PlayerMock player = server.addPlayer();
        List<String> all = commandHandler.onTabComplete(player, null, "pworld", new String[] {""});
        assertThat(all).contains("leave", "create", "list", "tp", "unload", "info");

        List<String> l = commandHandler.onTabComplete(player, null, "pworld", new String[] {"l"});
        assertThat(l).containsExactly("list", "leave");

        List<String> le = commandHandler.onTabComplete(player, null, "pworld", new String[] {"le"});
        assertThat(le).containsExactly("leave");
    }

    @Test
    @DisplayName("non-player sender is refused on /pworld leave")
    void nonPlayerRefused() {
        ConsoleCommandSenderMock console = server.getConsoleSender();
        boolean result = commandHandler.onCommand(console, null, "pworld", new String[] {"leave"});
        assertThat(result).isTrue();

        String message = PlainTextComponentSerializer.plainText().serialize(console.nextComponentMessage());
        assertThat(message).contains("/pworld must be run by a player");
    }

    @Test
    @DisplayName("usage message includes leave")
    void usageIncludesLeave() {
        PlayerMock player = server.addPlayer();
        boolean result = commandHandler.onCommand(player, null, "pworld", new String[] {});
        assertThat(result).isTrue();

        String message = PlainTextComponentSerializer.plainText().serialize(player.nextComponentMessage());
        assertThat(message).contains("/pworld <create|list|tp|unload|info|leave>");
    }
}
