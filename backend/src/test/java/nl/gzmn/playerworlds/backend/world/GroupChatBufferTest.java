package nl.gzmn.playerworlds.backend.world;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GroupChatBufferTest {

    @Test
    @DisplayName("records chat messages and produces JSON snapshot (FR-39)")
    void recordAndSnapshot() {
        GroupChatBuffer buffer = new GroupChatBuffer();
        WorldId worldId = WorldId.random();
        UUID player1 = UUID.randomUUID();
        UUID player2 = UUID.randomUUID();

        assertThat(buffer.snapshotJson(worldId)).isEqualTo("[]");

        buffer.record(worldId, player1, "Alice", "Hello world!");
        buffer.record(worldId, player2, "Bob", "Hi Alice!");

        String snapshot = buffer.snapshotJson(worldId);
        assertThat(snapshot)
                .contains("Alice")
                .contains("Hello world!")
                .contains("Bob")
                .contains("Hi Alice!");
    }

    @Test
    @DisplayName("caps entries at buffer size (50)")
    void capsBufferSize() {
        GroupChatBuffer buffer = new GroupChatBuffer();
        WorldId worldId = WorldId.random();
        UUID sender = UUID.randomUUID();

        for (int i = 0; i < 60; i++) {
            buffer.record(worldId, sender, "Spammer", "Message " + i);
        }

        String snapshot = buffer.snapshotJson(worldId);
        assertThat(snapshot).doesNotContain("Message 0");
        assertThat(snapshot).doesNotContain("Message 9");
        assertThat(snapshot).contains("Message 10");
        assertThat(snapshot).contains("Message 59");
    }

    @Test
    @DisplayName("evict removes buffer for world")
    void evictRemovesBuffer() {
        GroupChatBuffer buffer = new GroupChatBuffer();
        WorldId worldId = WorldId.random();
        buffer.record(worldId, UUID.randomUUID(), "Alice", "test");
        assertThat(buffer.snapshotJson(worldId)).contains("Alice");

        buffer.evict(worldId);
        assertThat(buffer.snapshotJson(worldId)).isEqualTo("[]");
    }
}
