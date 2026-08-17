package nl.gzmn.playerworlds.core.profile;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.jspecify.annotations.Nullable;

/**
 * Single-flight snapshot commits, one queue per world (plan 00 §9).
 *
 * <p>FR-15 triggers a commit on <em>every</em> player leaving the world. On a
 * busy public world that is a save, copy, hash, upload and transaction per quit,
 * and running them concurrently would have several commits racing for the same
 * sequence and the same files. So a commit already in flight absorbs subsequent
 * triggers, and exactly one follow-up is scheduled after it.
 *
 * <p>Absorbing rather than queueing each trigger is the important half. Ten
 * players leaving at once need one commit that captures all ten, not ten commits
 * — and because a commit captures live state at the moment it runs, a single
 * follow-up after the current one is enough to guarantee nothing that happened
 * during the in-flight commit is missed.
 *
 * <p>Plan 00 called this a foundation-level shape because retrofitting it means
 * rewriting every caller. It lands here, with milestone 4, as the first thing
 * that actually commits anything.
 */
public final class CommitQueue {

    private final Function<WorldId, CompletableFuture<Void>> commit;
    private final ConcurrentMap<WorldId, State> byWorld = new ConcurrentHashMap<>();

    /**
     * @param commit performs one commit for a world. Must not call back into
     *     {@link #request}, and must complete its future when done — a commit
     *     that never completes stops the world committing again.
     */
    public CommitQueue(Function<WorldId, CompletableFuture<Void>> commit) {
        this.commit = Objects.requireNonNull(commit, "commit");
    }

    /**
     * Asks for a commit of this world.
     *
     * @return a future completing when a commit that <em>started after this
     *     call</em> has finished. A caller that needs its own state durable —
     *     the kick path in specification section 9, waiting for the snapshot
     *     carrying a player's profile — can wait on it.
     */
    public CompletableFuture<Void> request(WorldId worldId) {
        Objects.requireNonNull(worldId, "worldId");
        State state = byWorld.computeIfAbsent(worldId, id -> new State());
        final CompletableFuture<Void> waiter;
        synchronized (state) {
            if (!state.running) {
                state.running = true;
                state.current = new CompletableFuture<>();
                waiter = state.current;
                start(worldId, state);
                return waiter;
            }
            // A commit is in flight. Its result cannot be ours: it may already
            // have captured state before the change that prompted this call, so
            // we wait on the follow-up instead.
            if (state.followUp == null) {
                state.followUp = new CompletableFuture<>();
            }
            return state.followUp;
        }
    }

    /** Whether a commit is currently running for this world. */
    public boolean isCommitting(WorldId worldId) {
        State state = byWorld.get(worldId);
        if (state == null) {
            return false;
        }
        synchronized (state) {
            return state.running;
        }
    }

    /** Worlds with a queue, for meters and tests. */
    public int trackedWorlds() {
        return byWorld.size();
    }

    /** Drops a world's queue once it has unloaded and will not commit again. */
    public void forget(WorldId worldId) {
        Objects.requireNonNull(worldId, "worldId");
        byWorld.remove(worldId);
    }

    private void start(WorldId worldId, State state) {
        CompletableFuture<Void> running;
        try {
            running = commit.apply(worldId);
        } catch (RuntimeException e) {
            running = CompletableFuture.failedFuture(e);
        }
        // Not retained: finish() is what advances the queue, and nothing waits
        // on this handle — callers wait on the future request() handed them.
        var _ = running.whenComplete((ignored, failure) -> finish(worldId, state, failure));
    }

    private void finish(WorldId worldId, State state, @Nullable Throwable failure) {
        final @Nullable CompletableFuture<Void> completing;
        final @Nullable CompletableFuture<Void> promoted;
        synchronized (state) {
            completing = state.current;
            promoted = state.followUp;
            state.followUp = null;
            if (promoted != null) {
                state.current = promoted;
                state.running = true;
            } else {
                state.current = null;
                state.running = false;
            }
        }

        // Completed outside the lock: a waiter's callback may call request again,
        // and completing while holding the lock would deadlock it.
        if (completing != null) {
            if (failure != null) {
                completing.completeExceptionally(failure);
            } else {
                completing.complete(null);
            }
        }
        if (promoted != null) {
            start(worldId, state);
        }
    }

    private static final class State {
        private boolean running;

        /** The commit currently in flight, or null when idle. */
        private @Nullable CompletableFuture<Void> current;

        /**
         * The single absorbed follow-up, or null when nothing has been requested
         * during the in-flight commit. At most one, which is the whole point:
         * ten players leaving at once need one more commit, not ten.
         */
        private @Nullable CompletableFuture<Void> followUp;
    }

    /** Snapshot of queue state, for tests and diagnostics. */
    public Map<WorldId, Boolean> committingByWorld() {
        return Map.copyOf(byWorld.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> {
                    synchronized (entry.getValue()) {
                        return entry.getValue().running;
                    }
                })));
    }
}
