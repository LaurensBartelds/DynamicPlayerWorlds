package nl.gzmn.playerworlds.core.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import nl.gzmn.playerworlds.core.db.Database;
import nl.gzmn.playerworlds.core.db.DatabaseSettings;
import nl.gzmn.playerworlds.core.db.NodeCommandRepository;
import nl.gzmn.playerworlds.core.db.Schema;
import nl.gzmn.playerworlds.core.db.TestPostgres;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F7 acceptance: two consumers against one database exchange a command, survive
 * a killed LISTEN connection, and never double-execute (plan section 7).
 */
class ControlPlaneTest {

    private static final String NODE_A = "worlds-a";
    private static final String NODE_B = "worlds-b";
    private static final Duration POLL = Duration.ofMillis(200);
    private static final Duration CLAIM_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration TTL = Duration.ofMinutes(5);

    private Database database;
    private DatabaseSettings settings;
    private NodeCommandRepository commands;
    private final List<AutoCloseable> toClose = new ArrayList<>();

    @BeforeEach
    void openDatabase() throws Exception {
        database = TestPostgres.freshDatabase();
        Schema.migrate(database);
        settings = new DatabaseSettings(
                TestPostgres.container().getJdbcUrl(),
                TestPostgres.container().getUsername(),
                TestPostgres.container().getPassword(),
                4,
                Duration.ofSeconds(10));
        commands = new NodeCommandRepository(database);
    }

    @AfterEach
    void tearDown() throws Exception {
        for (int i = toClose.size() - 1; i >= 0; i--) {
            toClose.get(i).close();
        }
        toClose.clear();
        if (database != null) {
            database.close();
        }
    }

    @Test
    @DisplayName("poll delivers an enqueued command to the handler (CP-2, CP-3)")
    void pollDeliversEnqueuedCommand() throws Exception {
        ControlPlane plane = plane(NODE_A);
        List<NodeCommand> seen = new CopyOnWriteArrayList<>();
        plane.register(CommandKind.INVALIDATE_CACHE, command -> {
            seen.add(command);
            return CommandResult.ok();
        });

        long id = plane.enqueue(null, null, CommandKind.INVALIDATE_CACHE, NodeCommand.EMPTY_PAYLOAD, TTL);
        int claimed = plane.pollOnce();

        assertThat(claimed).isEqualTo(1);
        assertThat(seen).hasSize(1);
        assertThat(seen.getFirst().id()).isEqualTo(id);
        assertThat(commands.findById(id))
                .get()
                .extracting(NodeCommand::result, NodeCommand::isCompleted)
                .containsExactly(CommandResult.OK, true);
    }

    @Test
    @DisplayName("two claimers never double-execute the same command (CP-5)")
    void twoClaimersNeverDoubleExecute() throws Exception {
        long id = commands.enqueue(
                NODE_A,
                null,
                null,
                CommandKind.DRAIN_NODE.name(),
                NodeCommand.EMPTY_PAYLOAD,
                TTL,
                ControlChannels.forNode(NODE_A));

        AtomicInteger executions = new AtomicInteger();
        CountDownLatch bothReady = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        ControlPlane a = plane(NODE_A);
        ControlPlane b = plane(NODE_A);
        CommandHandler handler = command -> {
            executions.incrementAndGet();
            return CommandResult.ok();
        };
        a.register(CommandKind.DRAIN_NODE, handler);
        b.register(CommandKind.DRAIN_NODE, handler);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        toClose.add(pool::shutdownNow);
        pool.execute(() -> {
            bothReady.countDown();
            await(go);
            a.dispatchId(id);
        });
        pool.execute(() -> {
            bothReady.countDown();
            await(go);
            b.dispatchId(id);
        });

        assertThat(bothReady.await(5, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(executions.get()).isEqualTo(1);
        assertThat(commands.findById(id))
                .get()
                .extracting(NodeCommand::attempts, NodeCommand::result)
                .containsExactly(1, CommandResult.OK);
    }

    @Test
    @DisplayName("poll still delivers after the LISTEN connection is killed (CP-3)")
    void pollSurvivesKilledListener() {
        ControlPlane plane = plane(NODE_A);
        List<NodeCommand> seen = new CopyOnWriteArrayList<>();
        plane.register(CommandKind.UNLOAD_WORLD, command -> {
            seen.add(command);
            return CommandResult.ok();
        });

        // Establish then kill LISTEN so notifications are lost.
        try {
            // open via a throwaway start/stop is heavy; disconnect is enough once opened
            plane.disconnectListener();
        } catch (Exception ignored) {
            // not yet opened
        }

        ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
        ExecutorService listen = Executors.newSingleThreadExecutor();
        toClose.add(sched::shutdownNow);
        toClose.add(listen::shutdownNow);
        plane.start(sched, listen);
        plane.disconnectListener();

        long id = plane.enqueue(null, null, CommandKind.UNLOAD_WORLD, NodeCommand.EMPTY_PAYLOAD, TTL);

        // Do not wait on NOTIFY — poll is the contract. Manual pollOnce so the
        // test does not depend on the background schedule firing first.
        awaitUntil(
                () -> {
                    plane.pollOnce();
                    return !seen.isEmpty();
                },
                Duration.ofSeconds(5));

        assertThat(seen).hasSize(1);
        assertThat(seen.getFirst().id()).isEqualTo(id);
    }

    @Test
    @DisplayName("NOTIFY wakes the listener without waiting for the poll interval (CP-3)")
    void notifyWakesListener() throws Exception {
        CountDownLatch handled = new CountDownLatch(1);
        ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
        ExecutorService listen = Executors.newSingleThreadExecutor();
        // Long poll so a pass would miss the deadline if NOTIFY did not work.
        ControlPlane slowPoll = new ControlPlane(
                NODE_A, ControlChannels.forNode(NODE_A), settings, commands, Duration.ofMinutes(5), CLAIM_TIMEOUT);
        slowPoll.register(CommandKind.APPLY_SETTINGS, command -> {
            handled.countDown();
            return CommandResult.ok();
        });
        toClose.add(slowPoll);
        toClose.add(sched::shutdownNow);
        toClose.add(listen::shutdownNow);
        slowPoll.start(sched, listen);

        // Give LISTEN time to attach before the insert.
        Thread.sleep(300);
        slowPoll.enqueue(null, null, CommandKind.APPLY_SETTINGS, NodeCommand.EMPTY_PAYLOAD, TTL);

        assertThat(handled.await(5, TimeUnit.SECONDS))
                .as("handler should run via NOTIFY well under the 5-minute poll")
                .isTrue();
    }

    @Test
    @DisplayName("stale generation is discarded without calling the handler (CP-4)")
    void staleGenerationIsDiscarded() throws Exception {
        WorldId worldId = insertWorld(0L);
        ControlPlane plane = plane(NODE_A);
        AtomicInteger executions = new AtomicInteger();
        plane.register(CommandKind.EJECT_PLAYER, command -> {
            executions.incrementAndGet();
            return CommandResult.ok();
        });

        long id = plane.enqueue(worldId, 0L, CommandKind.EJECT_PLAYER, NodeCommand.EMPTY_PAYLOAD, TTL);
        bumpGeneration(worldId, 1L);

        assertThat(plane.pollOnce()).isEqualTo(1);
        assertThat(executions.get()).isZero();
        assertThat(commands.findById(id))
                .get()
                .extracting(NodeCommand::result)
                .isEqualTo(CommandResult.STALE_GENERATION);
    }

    @Test
    @DisplayName("unknown command is completed with an error rather than retried forever (CP-6)")
    void unknownCommandCompletesWithError() throws Exception {
        long id = commands.enqueue(
                NODE_A, null, null, "FUTURE_COMMAND", NodeCommand.EMPTY_PAYLOAD, TTL, ControlChannels.forNode(NODE_A));

        ControlPlane plane = plane(NODE_A);
        assertThat(plane.pollOnce()).isEqualTo(1);
        assertThat(commands.findById(id))
                .get()
                .extracting(NodeCommand::result, NodeCommand::isCompleted)
                .containsExactly(CommandResult.unknownCommand("FUTURE_COMMAND").wire(), true);
    }

    @Test
    @DisplayName("claim timeout allows a second consumer to retry (CP-5)")
    void claimTimeoutAllowsRetry() throws Exception {
        long id = commands.enqueue(
                NODE_A,
                null,
                null,
                CommandKind.KICK_MEMBER.name(),
                NodeCommand.EMPTY_PAYLOAD,
                TTL,
                ControlChannels.forNode(NODE_A));

        // First claim wins but never completes — simulates a crashed handler.
        Optional<NodeCommand> first = commands.claim(id, CLAIM_TIMEOUT);
        assertThat(first).isPresent();
        assertThat(first.get().attempts()).isEqualTo(1);

        // Still within the claim timeout: a second claim must lose.
        assertThat(commands.claim(id, CLAIM_TIMEOUT)).isEmpty();

        // Age the claim past the timeout using database time.
        ageClaim(id, Duration.ofSeconds(10));

        ControlPlane plane = plane(NODE_A);
        AtomicInteger executions = new AtomicInteger();
        plane.register(CommandKind.KICK_MEMBER, command -> {
            executions.incrementAndGet();
            return CommandResult.ok();
        });

        assertThat(plane.pollOnce()).isEqualTo(1);
        assertThat(executions.get()).isEqualTo(1);
        assertThat(commands.findById(id))
                .get()
                .extracting(NodeCommand::attempts, NodeCommand::result)
                .containsExactly(2, CommandResult.OK);
    }

    @Test
    @DisplayName("a second node does not claim another node's command")
    void commandsAreTargetScoped() throws Exception {
        long id = commands.enqueue(
                NODE_A,
                null,
                null,
                CommandKind.DRAIN_NODE.name(),
                NodeCommand.EMPTY_PAYLOAD,
                TTL,
                ControlChannels.forNode(NODE_A));

        ControlPlane other = plane(NODE_B);
        other.register(CommandKind.DRAIN_NODE, command -> CommandResult.ok());
        assertThat(other.pollOnce()).isZero();
        assertThat(commands.findById(id))
                .get()
                .extracting(NodeCommand::isCompleted)
                .isEqualTo(false);

        ControlPlane owner = plane(NODE_A);
        owner.register(CommandKind.DRAIN_NODE, command -> CommandResult.ok());
        assertThat(owner.pollOnce()).isEqualTo(1);
    }

    private ControlPlane plane(String nodeId) {
        ControlPlane plane = ControlPlane.forNode(nodeId, settings, commands, POLL, CLAIM_TIMEOUT);
        toClose.add(plane);
        return plane;
    }

    private WorldId insertWorld(long generation) throws SQLException {
        WorldId id = WorldId.random();
        UUID owner = UUID.randomUUID();
        database.inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO player_world (
                      id, owner_uuid, name, folder, seed, generation, state
                    ) VALUES (?, ?, ?, ?, 0, ?, 'READY')
                    """)) {
                statement.setObject(1, id.value());
                statement.setObject(2, owner);
                statement.setString(3, "w-" + id.value());
                statement.setString(4, id.folder());
                statement.setLong(5, generation);
                statement.executeUpdate();
            }
            return null;
        });
        return id;
    }

    private void bumpGeneration(WorldId worldId, long generation) throws SQLException {
        database.inTransaction(connection -> {
            try (PreparedStatement statement =
                    connection.prepareStatement("UPDATE player_world SET generation = ? WHERE id = ?")) {
                statement.setLong(1, generation);
                statement.setObject(2, worldId.value());
                statement.executeUpdate();
            }
            return null;
        });
    }

    private void ageClaim(long id, Duration age) throws SQLException {
        database.inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE node_command
                       SET claimed_at = now() - (? * INTERVAL '1 second')
                     WHERE id = ?
                    """)) {
                statement.setDouble(1, age.toMillis() / 1000.0);
                statement.setLong(2, id);
                statement.executeUpdate();
            }
            return null;
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("latch timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void awaitUntil(BooleanSupplier condition, Duration budget) {
        long deadline = System.nanoTime() + budget.toNanos();
        AssertionError last = new AssertionError("condition never became true");
        while (System.nanoTime() < deadline) {
            try {
                if (condition.getAsBoolean()) {
                    return;
                }
            } catch (AssertionError e) {
                last = e;
            } catch (RuntimeException e) {
                last = new AssertionError(e);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        throw last;
    }

    @FunctionalInterface
    private interface BooleanSupplier {
        boolean getAsBoolean();
    }
}
