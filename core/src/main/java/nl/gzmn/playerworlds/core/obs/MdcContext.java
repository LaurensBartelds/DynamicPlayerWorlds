package nl.gzmn.playerworlds.core.obs;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

/**
 * Scoped MDC put/restore so a worker thread cannot leak one world's identity
 * into the next task on the same pool thread.
 *
 * <p>Use try-with-resources around the work that should carry the keys:
 *
 * <pre>{@code
 * try (MdcContext ignored = MdcContext.open()
 *         .nodeId(nodeId)
 *         .worldId(worldId)
 *         .op("sync")) {
 *     // logs here carry the keys
 * }
 * }</pre>
 */
public final class MdcContext implements AutoCloseable {

    private final List<Entry> entries = new ArrayList<>(8);
    private boolean closed;

    private MdcContext() {}

    public static MdcContext open() {
        return new MdcContext();
    }

    public MdcContext put(String key, @Nullable String value) {
        Objects.requireNonNull(key, "key");
        ensureOpen();
        if (value == null) {
            return this;
        }
        String previous = MDC.get(key);
        MDC.put(key, value);
        entries.add(new Entry(key, previous));
        return this;
    }

    public MdcContext nodeId(@Nullable String nodeId) {
        return put(MdcKeys.NODE_ID, nodeId);
    }

    public MdcContext worldId(@Nullable WorldId worldId) {
        return put(MdcKeys.WORLD_ID, worldId == null ? null : worldId.value().toString());
    }

    public MdcContext worldId(@Nullable UUID worldId) {
        return put(MdcKeys.WORLD_ID, worldId == null ? null : worldId.toString());
    }

    public MdcContext generation(@Nullable Long generation) {
        return put(MdcKeys.GENERATION, generation == null ? null : Long.toString(generation));
    }

    public MdcContext playerUuid(@Nullable UUID playerUuid) {
        return put(MdcKeys.PLAYER_UUID, playerUuid == null ? null : playerUuid.toString());
    }

    public MdcContext op(@Nullable String op) {
        return put(MdcKeys.OP, op);
    }

    public MdcContext traceId(@Nullable String traceId) {
        return put(MdcKeys.TRACE_ID, traceId);
    }

    public MdcContext event(LogEvent event) {
        Objects.requireNonNull(event, "event");
        return put(MdcKeys.EVENT, event.key());
    }

    /**
     * Runs {@code work} with this context open and closes it afterwards. Handy
     * when the caller does not want a try-with-resources block of its own.
     */
    public <T> T call(Supplier<T> work) {
        Objects.requireNonNull(work, "work");
        try {
            return work.get();
        } finally {
            close();
        }
    }

    public void run(Runnable work) {
        Objects.requireNonNull(work, "work");
        try {
            work.run();
        } finally {
            close();
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        // Restore in reverse order so nested puts of the same key unwind correctly.
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry entry = entries.get(i);
            if (entry.previous == null) {
                MDC.remove(entry.key);
            } else {
                MDC.put(entry.key, entry.previous);
            }
        }
        entries.clear();
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("MdcContext is closed");
        }
    }

    private record Entry(String key, @Nullable String previous) {}
}
