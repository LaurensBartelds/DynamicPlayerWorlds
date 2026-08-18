package nl.gzmn.playerworlds.backend.world;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import nl.gzmn.playerworlds.core.model.WorldId;

/**
 * Ring buffer recording recent chat messages per player world for moderation reports (FR-39).
 *
 * <p>Retains the last {@value #BUFFER_SIZE} messages per world. Thread-safe for recording
 * during async chat events and dumping when a report is filed.
 */
public final class GroupChatBuffer {

    public static final int BUFFER_SIZE = 50;

    public record ChatEntry(Instant timestamp, UUID senderUuid, String senderName, String message) {
        public ChatEntry {
            Objects.requireNonNull(timestamp, "timestamp");
            Objects.requireNonNull(senderUuid, "senderUuid");
            Objects.requireNonNull(senderName, "senderName");
            Objects.requireNonNull(message, "message");
        }
    }

    private final java.time.Clock clock;
    private final Map<WorldId, Deque<ChatEntry>> buffers = new ConcurrentHashMap<>();

    public GroupChatBuffer() {
        this(java.time.Clock.systemUTC());
    }

    public GroupChatBuffer(java.time.Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Records a chat message in the world's buffer. */
    public void record(WorldId worldId, UUID senderUuid, String senderName, String message) {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(senderUuid, "senderUuid");
        Objects.requireNonNull(senderName, "senderName");
        Objects.requireNonNull(message, "message");

        buffers.compute(worldId, (_, existing) -> {
            Deque<ChatEntry> deque = existing != null ? existing : new ArrayDeque<>(BUFFER_SIZE);
            synchronized (deque) {
                if (deque.size() >= BUFFER_SIZE) {
                    deque.removeFirst();
                }
                deque.addLast(new ChatEntry(clock.instant(), senderUuid, senderName, message));
            }
            return deque;
        });
    }

    /** Returns a snapshot of recent chat for the given world as a JSON string. */
    public String snapshotJson(WorldId worldId) {
        Objects.requireNonNull(worldId, "worldId");
        Deque<ChatEntry> deque = buffers.get(worldId);
        if (deque == null) {
            return "[]";
        }
        List<ChatEntry> entries;
        synchronized (deque) {
            entries = new ArrayList<>(deque);
        }
        if (entries.isEmpty()) {
            return "[]";
        }

        StringBuilder sb = new StringBuilder(entries.size() * 128);
        sb.append("[");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            ChatEntry entry = entries.get(i);
            sb.append("{")
                    .append("\"time\":\"")
                    .append(entry.timestamp())
                    .append("\",")
                    .append("\"senderUuid\":\"")
                    .append(entry.senderUuid())
                    .append("\",")
                    .append("\"senderName\":\"")
                    .append(escapeJson(entry.senderName()))
                    .append("\",")
                    .append("\"message\":\"")
                    .append(escapeJson(entry.message()))
                    .append("\"")
                    .append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    public void evict(WorldId worldId) {
        Objects.requireNonNull(worldId, "worldId");
        buffers.remove(worldId);
    }

    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c <= 0x1F) {
                        sb.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
