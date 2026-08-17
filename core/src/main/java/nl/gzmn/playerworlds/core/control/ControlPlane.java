package nl.gzmn.playerworlds.core.control;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import nl.gzmn.playerworlds.core.db.DatabaseSettings;
import nl.gzmn.playerworlds.core.db.NodeCommandRepository;
import nl.gzmn.playerworlds.core.db.PgNotificationListener;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Consumes {@code node_command} rows for one target (CP-2 to CP-6).
 *
 * <p>Holds a dedicated {@link PgNotificationListener} and a poll loop. The poll
 * is the contract; NOTIFY only shortens the wait (CP-3). Claim is a conditional
 * {@code UPDATE}, so two consumers never both run the same row (CP-5).
 *
 * <p>No feature handlers ship here — register them with {@link #register}. An
 * unrecognised kind is completed with an error so it does not retry forever
 * (CP-6).
 */
public final class ControlPlane implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ControlPlane.class);

    private static final int POLL_BATCH = 64;
    private static final Duration LISTEN_WAIT = Duration.ofSeconds(1);
    private static final Duration RECONNECT_PAUSE = Duration.ofSeconds(2);

    private final String targetNode;
    private final String listenChannel;
    private final NodeCommandRepository commands;
    private final PgNotificationListener listener;
    private final Duration pollInterval;
    private final Duration claimTimeout;
    private final Map<CommandKind, CommandHandler> handlers = new EnumMap<>(CommandKind.class);

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object startLock = new Object();

    private @Nullable ScheduledFuture<?> pollFuture;
    private @Nullable Thread listenThread;

    /**
     * @param targetNode value matched against {@code node_command.target_node}
     * @param listenChannel {@link ControlChannels#forNode(String)} or
     *     {@link ControlChannels#PROXY}
     * @param databaseSettings credentials for the dedicated LISTEN connection
     * @param commands repository bound to the shared pool
     * @param pollInterval {@code control.poll-seconds}
     * @param claimTimeout {@code control.claim-timeout-seconds}
     */
    public ControlPlane(
            String targetNode,
            String listenChannel,
            DatabaseSettings databaseSettings,
            NodeCommandRepository commands,
            Duration pollInterval,
            Duration claimTimeout) {
        this.targetNode = Objects.requireNonNull(targetNode, "targetNode");
        this.listenChannel = Objects.requireNonNull(listenChannel, "listenChannel");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
        this.claimTimeout = Objects.requireNonNull(claimTimeout, "claimTimeout");
        if (targetNode.isBlank()) {
            throw new IllegalArgumentException("targetNode must not be blank");
        }
        if (listenChannel.isBlank()) {
            throw new IllegalArgumentException("listenChannel must not be blank");
        }
        if (pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("pollInterval must be positive, was: " + pollInterval);
        }
        if (claimTimeout.isNegative() || claimTimeout.isZero()) {
            throw new IllegalArgumentException("claimTimeout must be positive, was: " + claimTimeout);
        }
        this.listener = new PgNotificationListener(databaseSettings, listenChannel);
    }

    /** Node-scoped consumer: listens on {@code gzmn_node_<nodeId>}. */
    public static ControlPlane forNode(
            String nodeId,
            DatabaseSettings databaseSettings,
            NodeCommandRepository commands,
            Duration pollInterval,
            Duration claimTimeout) {
        return new ControlPlane(
                nodeId, ControlChannels.forNode(nodeId), databaseSettings, commands, pollInterval, claimTimeout);
    }

    /** Proxy-scoped consumer: listens on {@link ControlChannels#PROXY}. */
    public static ControlPlane forProxy(
            String proxyId,
            DatabaseSettings databaseSettings,
            NodeCommandRepository commands,
            Duration pollInterval,
            Duration claimTimeout) {
        return new ControlPlane(proxyId, ControlChannels.PROXY, databaseSettings, commands, pollInterval, claimTimeout);
    }

    public String targetNode() {
        return targetNode;
    }

    public String listenChannel() {
        return listenChannel;
    }

    /** Registers or replaces the handler for {@code kind}. */
    public void register(CommandKind kind, CommandHandler handler) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(handler, "handler");
        handlers.put(kind, handler);
    }

    /**
     * Enqueues a command addressed to this plane's target and notifies its
     * channel. Producer convenience for tests and same-process callers.
     */
    public long enqueue(
            @Nullable WorldId worldId, @Nullable Long generation, CommandKind kind, String payloadJson, Duration ttl) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(payloadJson, "payloadJson");
        Objects.requireNonNull(ttl, "ttl");
        try {
            return commands.enqueue(targetNode, worldId, generation, kind.name(), payloadJson, ttl, listenChannel);
        } catch (Exception e) {
            throw new ControlException("enqueue failed for target " + targetNode, e);
        }
    }

    /**
     * Claims and dispatches every currently claimable command for this target.
     *
     * <p>Safe to call from tests without {@link #start}. Returns how many rows
     * were claimed (not how many handlers succeeded).
     */
    public int pollOnce() {
        List<Long> ids;
        try {
            ids = commands.findClaimableIds(targetNode, claimTimeout, POLL_BATCH);
        } catch (Exception e) {
            throw new ControlException("findClaimableIds failed for target " + targetNode, e);
        }
        int claimed = 0;
        for (Long id : ids) {
            if (dispatchId(id)) {
                claimed++;
            }
        }
        return claimed;
    }

    /**
     * Claims and dispatches one id if still claimable. Used by the LISTEN path
     * when a notification carries an id.
     *
     * @return true when this caller won the claim
     */
    public boolean dispatchId(long id) {
        Optional<NodeCommand> claimed;
        try {
            claimed = commands.claim(id, claimTimeout);
        } catch (Exception e) {
            throw new ControlException("claim failed for command " + id, e);
        }
        if (claimed.isEmpty()) {
            return false;
        }
        NodeCommand command = claimed.get();
        // Defence in depth: a NOTIFY on a shared channel (proxy) must not run
        // another target's row if the id was guessed or misrouted.
        if (!targetNode.equals(command.targetNode())) {
            log.warn(
                    "claimed command {} targeted {} but this plane is {}; completing as error",
                    id,
                    command.targetNode(),
                    targetNode);
            completeQuietly(id, CommandResult.error("target mismatch"));
            return true;
        }
        completeWithHandler(command);
        return true;
    }

    /**
     * Starts the poll schedule and the LISTEN loop.
     *
     * @param scheduler typically {@code PluginExecutors.sched()}
     * @param listenExecutor runs the blocking LISTEN loop; a single thread is
     *     enough — one connection per process
     */
    public void start(ScheduledExecutorService scheduler, Executor listenExecutor) {
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(listenExecutor, "listenExecutor");
        synchronized (startLock) {
            if (!running.compareAndSet(false, true)) {
                throw new IllegalStateException("ControlPlane already started for " + targetNode);
            }
            long pollMillis = pollInterval.toMillis();
            pollFuture =
                    scheduler.scheduleWithFixedDelay(this::safePoll, pollMillis, pollMillis, TimeUnit.MILLISECONDS);
            try {
                listenExecutor.execute(this::listenLoop);
            } catch (RejectedExecutionException e) {
                running.set(false);
                cancelPoll();
                throw e;
            }
            log.info(
                    "control plane started target={} channel={} poll={} claimTimeout={}",
                    targetNode,
                    listenChannel,
                    pollInterval,
                    claimTimeout);
        }
    }

    /**
     * Test seam: force-drop the LISTEN connection without stopping the plane.
     * Poll must still deliver commands (CP-3).
     */
    public void disconnectListener() {
        listener.disconnect();
    }

    @Override
    public void close() {
        running.set(false);
        synchronized (startLock) {
            cancelPoll();
        }
        listener.close();
        Thread thread = listenThread;
        Thread current = Thread.currentThread();
        if (thread != null && !sameThread(thread, current)) {
            try {
                thread.join(TimeUnit.SECONDS.toMillis(2));
            } catch (InterruptedException e) {
                current.interrupt();
            }
        }
        log.info("control plane stopped target={}", targetNode);
    }

    private void cancelPoll() {
        ScheduledFuture<?> future = pollFuture;
        pollFuture = null;
        if (future != null) {
            future.cancel(false);
        }
    }

    private void safePoll() {
        if (!running.get()) {
            return;
        }
        try {
            pollOnce();
        } catch (Exception e) {
            log.warn("control plane poll failed target={}: {}", targetNode, e.toString());
        }
    }

    private void listenLoop() {
        listenThread = Thread.currentThread();
        while (running.get()) {
            try {
                listener.ensureListening();
                Optional<String> payload = listener.await(LISTEN_WAIT);
                if (payload.isEmpty() || !running.get()) {
                    continue;
                }
                long id;
                try {
                    id = Long.parseLong(payload.get().strip());
                } catch (NumberFormatException e) {
                    log.warn(
                            "control plane ignoring non-numeric NOTIFY payload on {}: {}",
                            listenChannel,
                            payload.get());
                    continue;
                }
                dispatchId(id);
            } catch (Exception e) {
                if (!running.get()) {
                    return;
                }
                log.warn("control plane LISTEN failed on {}, will reconnect: {}", listenChannel, e.toString());
                listener.disconnect();
                sleep(RECONNECT_PAUSE);
            }
        }
    }

    private void completeWithHandler(NodeCommand command) {
        // CP-4: discard when the world has moved on since the command was issued.
        if (command.worldId() != null && command.generation() != null) {
            Optional<Long> current;
            try {
                current = commands.worldGeneration(command.worldId().value());
            } catch (Exception e) {
                throw new ControlException("worldGeneration failed for command " + command.id(), e);
            }
            if (current.isEmpty() || !command.generation().equals(current.get())) {
                completeQuietly(command.id(), CommandResult.staleGeneration());
                log.info(
                        "discarded stale command id={} world={} generation={}",
                        command.id(),
                        command.worldId(),
                        command.generation());
                return;
            }
        }

        Optional<CommandKind> kind = command.kind();
        if (kind.isEmpty()) {
            completeQuietly(command.id(), CommandResult.unknownCommand(command.command()));
            log.warn("unknown control command id={} name={}", command.id(), command.command());
            return;
        }

        CommandHandler handler = handlers.get(kind.get());
        if (handler == null) {
            // Known kind, no handler registered yet in this process. Complete
            // with error rather than retry forever — same visibility rule as
            // CP-6's unknown-kind case. Features register handlers at enable.
            completeQuietly(
                    command.id(),
                    CommandResult.error("no handler for " + kind.get().name()));
            log.warn("no handler for control command id={} kind={}", command.id(), kind.get());
            return;
        }

        CommandResult result;
        try {
            result = handler.handle(command);
            if (result == null) {
                result = CommandResult.error("handler returned null");
            }
        } catch (Exception e) {
            log.warn("control handler failed id={} kind={}: {}", command.id(), kind.get(), e.toString());
            String message = e.getMessage();
            result = CommandResult.error(e.getClass().getSimpleName() + ": " + (message == null ? "" : message));
        }
        completeQuietly(command.id(), result);
    }

    private void completeQuietly(long id, CommandResult result) {
        try {
            commands.complete(id, result);
        } catch (Exception e) {
            throw new ControlException("complete failed for command " + id, e);
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Thread identity; Error Prone rejects {@code ==} unless named. */
    @SuppressWarnings("ReferenceEquality")
    private static boolean sameThread(Thread a, Thread b) {
        return a == b;
    }
}
