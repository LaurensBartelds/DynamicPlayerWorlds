package nl.gzmn.playerworlds.core.obs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LogEventTest {

    @Test
    @DisplayName("every NFR-6 event has a unique stable key")
    void keysAreUnique() {
        Set<String> keys = new HashSet<>();
        for (LogEvent event : LogEvent.values()) {
            assertThat(event.key()).isNotBlank();
            assertThat(keys.add(event.key()))
                    .as("duplicate key %s", event.key())
                    .isTrue();
        }
        // Guard the required NFR-6 set so a rename is a deliberate test change.
        assertThat(Arrays.stream(LogEvent.values()).map(LogEvent::key).toList())
                .contains(
                        "world.create",
                        "world.join",
                        "world.invite",
                        "world.kick",
                        "world.unload",
                        "world.delete",
                        "lease.acquire",
                        "lease.release",
                        "lease.lost",
                        "lease.self_fence",
                        "sync.start",
                        "sync.finish",
                        "commit.fenced");
    }
}
