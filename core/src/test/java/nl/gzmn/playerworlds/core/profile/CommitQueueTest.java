package nl.gzmn.playerworlds.core.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Single-flight commits (plan 00 §9).
 *
 * <p>Driven with futures the test completes by hand rather than with sleeps, so
 * the ordering being asserted is the real one rather than a timing coincidence.
 */
class CommitQueueTest {

    private final WorldId world = WorldId.random();
    private final ConcurrentLinkedQueue<CompletableFuture<Void>> started = new ConcurrentLinkedQueue<>();
    private final AtomicInteger commits = new AtomicInteger();

    private CommitQueue queue() {
        return new CommitQueue(id -> {
            commits.incrementAndGet();
            CompletableFuture<Void> running = new CompletableFuture<>();
            started.add(running);
            return running;
        });
    }

    @Test
    @DisplayName("the first request starts a commit immediately")
    void firstRequestStartsACommit() {
        CommitQueue queue = queue();

        CompletableFuture<Void> waiter = queue.request(world);

        assertThat(commits).hasValue(1);
        assertThat(queue.isCommitting(world)).isTrue();
        assertThat(waiter).isNotDone();

        started.poll().complete(null);
        assertThat(waiter).isCompleted();
        assertThat(queue.isCommitting(world)).isFalse();
    }

    @Test
    @DisplayName("triggers during a commit are absorbed into one follow-up (FR-15)")
    void concurrentTriggersCollapseIntoOneFollowUp() {
        CommitQueue queue = queue();
        var unused = queue.request(world);

        // Ten players leaving at once need one more commit, not ten.
        CompletableFuture<Void> first = queue.request(world);
        for (int i = 0; i < 9; i++) {
            var unusedLoop = queue.request(world);
        }
        assertThat(commits).as("no new commit while one is in flight").hasValue(1);

        started.poll().complete(null);

        assertThat(commits).as("exactly one follow-up").hasValue(2);
        assertThat(first).isNotDone();

        started.poll().complete(null);
        assertThat(first).isCompleted();
        assertThat(commits).hasValue(2);
    }

    @Test
    @DisplayName("a request during a commit waits for the next one, not the running one")
    void aRequestNeverGetsAnAlreadyStartedCommit() {
        // The running commit may have captured state before the change that
        // prompted this call, so its completion would be a false promise of
        // durability to whoever is waiting.
        CommitQueue queue = queue();
        CompletableFuture<Void> firstWaiter = queue.request(world);
        CompletableFuture<Void> secondWaiter = queue.request(world);

        assertThat(firstWaiter).isNotSameAs(secondWaiter);

        started.poll().complete(null);
        assertThat(firstWaiter).isCompleted();
        assertThat(secondWaiter).isNotDone();
    }

    @Test
    @DisplayName("a failed commit fails its waiters and still lets the world commit again")
    void failureDoesNotWedgeTheQueue() {
        CommitQueue queue = queue();
        CompletableFuture<Void> waiter = queue.request(world);

        started.poll().completeExceptionally(new IllegalStateException("storage is down"));

        assertThat(waiter).isCompletedExceptionally();
        assertThat(queue.isCommitting(world)).isFalse();

        // A world that could never commit again after one failure would lose
        // every subsequent change silently.
        var unused = queue.request(world);
        assertThat(commits).hasValue(2);
    }

    @Test
    @DisplayName("a commit function that throws is a failed commit, not a wedged queue")
    void aThrowingCommitIsHandled() {
        CommitQueue queue = new CommitQueue(id -> {
            throw new IllegalStateException("could not even start");
        });

        CompletableFuture<Void> waiter = queue.request(world);

        assertThat(waiter).isCompletedExceptionally();
        assertThat(queue.isCommitting(world)).isFalse();
    }

    @Test
    @DisplayName("worlds have independent queues")
    void worldsDoNotBlockEachOther() {
        CommitQueue queue = queue();
        WorldId other = WorldId.random();

        var unused1 = queue.request(world);
        var unused2 = queue.request(other);

        assertThat(commits).hasValue(2);
        assertThat(queue.isCommitting(world)).isTrue();
        assertThat(queue.isCommitting(other)).isTrue();
        assertThat(queue.trackedWorlds()).isEqualTo(2);

        queue.forget(world);
        assertThat(queue.trackedWorlds()).isEqualTo(1);
    }

    @Test
    @DisplayName("a waiter may request another commit from its own callback")
    void completionCallbacksMayRequestAgain() throws Exception {
        // Completing inside the lock would deadlock here. The unload path does
        // exactly this: commit, then on completion decide whether to commit once
        // more before dropping the world.
        CommitQueue queue = queue();
        CompletableFuture<Void> reentrant = queue.request(world).thenCompose(ignored -> queue.request(world));

        started.poll().complete(null);
        started.poll().complete(null);

        reentrant.get(5, TimeUnit.SECONDS);
        assertThat(commits).hasValue(2);
    }
}
