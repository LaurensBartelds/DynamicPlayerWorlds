package nl.gzmn.playerworlds.backend.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import nl.gzmn.playerworlds.backend.platform.DimensionKind;
import nl.gzmn.playerworlds.backend.platform.Platform;
import nl.gzmn.playerworlds.backend.platform.ServerIdentity;
import nl.gzmn.playerworlds.backend.world.GroupChatBuffer;
import nl.gzmn.playerworlds.backend.world.WorldFolders;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.db.Database;
import nl.gzmn.playerworlds.core.db.NodeCommandRepository;
import nl.gzmn.playerworlds.core.db.PlayerNameRepository;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository;
import nl.gzmn.playerworlds.core.db.ReportRepository;
import nl.gzmn.playerworlds.core.db.Schema;
import nl.gzmn.playerworlds.core.model.Visibility;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.model.WorldReport;
import nl.gzmn.playerworlds.testing.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

class BackendWorldCommandTest {

    private ServerMock server;
    private Database database;
    private PluginExecutors executors;
    private WorldFolders folders;
    private ReportRepository reports;
    private PlayerNameRepository names;
    private GroupChatBuffer chatBuffer;
    private NodeCommandRepository nodeCommands;
    private BackendWorldCommand command;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        database = TestDatabase.openFresh();
        Schema.migrate(database);
        executors = PluginExecutors.create(2, 2, Runnable::run);
        Platform platform = Platform.create(new ServerIdentity("1.21.4", Platform.BUILD_DATA_VERSION));
        folders = new WorldFolders(platform.worldLayout());
        reports = new ReportRepository(database);
        names = new PlayerNameRepository(database);
        chatBuffer = new GroupChatBuffer();
        nodeCommands = new NodeCommandRepository(database);

        command = new BackendWorldCommand(
                folders, reports, names, chatBuffer, nodeCommands, executors, NetworkPolicy::defaults);
    }

    @AfterEach
    void tearDown() {
        executors.shutdown(Duration.ofSeconds(5));
        database.close();
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("/world leave teleports and enqueues EJECT_PLAYER")
    void leaveWorld() throws Exception {
        WorldMock lobby = server.addSimpleWorld("world");
        WorldId worldId = WorldId.random();
        PlayerWorldRepository worlds = new PlayerWorldRepository(database);
        PlayerMock player = server.addPlayer();
        worlds.create(worldId, player.getUniqueId(), "home", 1L, 5000, Visibility.PUBLIC);

        WorldMock playerWorld = server.addSimpleWorld(folders.bukkitWorldName(worldId, DimensionKind.OVERWORLD));
        player.teleport(playerWorld.getSpawnLocation());
        assertThat(player.getWorld()).isEqualTo(playerWorld);

        boolean executed = command.onCommand(player, null, "world", new String[] {"leave"});
        assertThat(executed).isTrue();

        assertThat(player.getWorld()).isEqualTo(lobby);

        List<Long> ids = awaitNodeCommands();
        assertThat(ids).hasSize(1);
    }

    @Test
    @DisplayName("/world report creates report with chat log (FR-39)")
    void reportPlayer() throws Exception {
        WorldId worldId = WorldId.random();
        WorldMock playerWorld = server.addSimpleWorld(folders.bukkitWorldName(worldId, DimensionKind.OVERWORLD));
        PlayerWorldRepository worlds = new PlayerWorldRepository(database);
        UUID owner = UUID.randomUUID();
        worlds.create(worldId, owner, "home", 1L, 5000, Visibility.PUBLIC);

        PlayerMock reporter = server.addPlayer("Alice");
        PlayerMock target = server.addPlayer("Bob");
        names.remember(reporter.getUniqueId(), "Alice");
        names.remember(target.getUniqueId(), "Bob");

        reporter.teleport(playerWorld.getSpawnLocation());
        target.teleport(playerWorld.getSpawnLocation());

        chatBuffer.record(worldId, target.getUniqueId(), "Bob", "Inappropriate chat message");

        boolean executed =
                command.onCommand(reporter, null, "world", new String[] {"report", "Bob", "Toxicity and harassment"});
        assertThat(executed).isTrue();

        awaitCondition(() -> !reports.listOpenReports().isEmpty());

        List<WorldReport> openReports = reports.listOpenReports();
        assertThat(openReports).hasSize(1);
        WorldReport report = openReports.getFirst();
        assertThat(report.worldId()).isEqualTo(worldId);
        assertThat(report.reporterUuid()).isEqualTo(reporter.getUniqueId());
        assertThat(report.targetUuid()).isEqualTo(target.getUniqueId());
        assertThat(report.reason()).isEqualTo("Toxicity and harassment");
        assertThat(report.chatLogJson()).contains("Inappropriate chat message").contains("Bob");
    }

    private <T> T onDb(Callable<T> task) throws Exception {
        return executors.db().submit(task).get(5, java.util.concurrent.TimeUnit.SECONDS);
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
}
