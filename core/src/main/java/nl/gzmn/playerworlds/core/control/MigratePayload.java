package nl.gzmn.playerworlds.core.control;

import java.util.Objects;
import java.util.Optional;

/**
 * Payload for {@link CommandKind#MIGRATE_WORLD} and {@link CommandKind#DRAIN_NODE}.
 *
 * <p>Both commands say the same two things to a node: <em>give this world up</em>
 * and <em>give the players this much warning first</em>. MN-19 makes the give-up
 * an ordered sequence — eject, commit, unload, release — and MN-21 requires the
 * warning and countdown be shown to anyone inside, because "this is several
 * seconds of visible interruption and is never done silently under a player".
 *
 * <p>{@code targetNode} is advisory to the source node, which never contacts the
 * target: it names the destination only so the countdown message and the log line
 * can say where the world is going. The lease on the target is acquired by the
 * proxy, after the source has released its own — MN-8 makes any other ordering a
 * world briefly leased twice.
 *
 * <p>Hand-formatted, like {@link EjectPayload}, so the control plane keeps no
 * JSON dependency. The field set is deliberately tiny for the same reason.
 *
 * @param targetNode the node the world is going to, or {@code null} for a drain,
 *     which unloads in place and lets MN-20 place the world fresh on the next join
 * @param countdownSeconds how long to warn players for before ejecting them
 *     (MN-21); zero for an immediate move
 */
public record MigratePayload(@org.jspecify.annotations.Nullable String targetNode, int countdownSeconds) {

    /** MN-21 shows a countdown; ten seconds is long enough to read and short enough not to be argued with. */
    public static final int DEFAULT_COUNTDOWN_SECONDS = 10;

    /** A countdown longer than this is a mistake, not a policy, and would outlive the command's TTL. */
    public static final int MAX_COUNTDOWN_SECONDS = 120;

    public MigratePayload {
        if (countdownSeconds < 0 || countdownSeconds > MAX_COUNTDOWN_SECONDS) {
            throw new IllegalArgumentException(
                    "countdownSeconds must be 0.." + MAX_COUNTDOWN_SECONDS + ", was: " + countdownSeconds);
        }
        if (targetNode != null && targetNode.isBlank()) {
            throw new IllegalArgumentException("targetNode must not be blank when present");
        }
    }

    /** Compact JSON, as stored in {@code node_command.payload}. */
    public String format() {
        String node = targetNode;
        if (node == null) {
            return "{\"countdownSeconds\":" + countdownSeconds + "}";
        }
        return "{\"targetNode\":\"" + escape(node) + "\",\"countdownSeconds\":" + countdownSeconds + "}";
    }

    /**
     * Parses a stored payload.
     *
     * <p>Empty on anything malformed rather than a default: a migration that ran
     * against a payload nobody could read would move a world somewhere the
     * operator did not ask for.
     */
    public static Optional<MigratePayload> parse(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        String trimmed = json.strip();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return Optional.empty();
        }

        String targetNode = stringField(trimmed, "targetNode");
        int countdown = DEFAULT_COUNTDOWN_SECONDS;
        Integer parsed = intField(trimmed, "countdownSeconds");
        if (parsed != null) {
            countdown = parsed;
        }
        if (countdown < 0 || countdown > MAX_COUNTDOWN_SECONDS) {
            return Optional.empty();
        }
        if (targetNode != null && targetNode.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new MigratePayload(targetNode, countdown));
    }

    private static @org.jspecify.annotations.Nullable String stringField(String json, String key) {
        int keyIndex = json.indexOf('"' + key + '"');
        if (keyIndex == -1) {
            return null;
        }
        int colon = json.indexOf(':', keyIndex + key.length() + 2);
        if (colon == -1) {
            return null;
        }
        int open = json.indexOf('"', colon);
        if (open == -1) {
            return null;
        }
        StringBuilder value = new StringBuilder();
        boolean escaped = false;
        for (int i = open + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                value.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                return value.toString();
            } else {
                value.append(c);
            }
        }
        return null;
    }

    private static @org.jspecify.annotations.Nullable Integer intField(String json, String key) {
        int keyIndex = json.indexOf('"' + key + '"');
        if (keyIndex == -1) {
            return null;
        }
        int colon = json.indexOf(':', keyIndex + key.length() + 2);
        if (colon == -1) {
            return null;
        }
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        if (end == start) {
            return null;
        }
        try {
            return Integer.valueOf(json.substring(start, end));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** A drain: unload in place, no destination (MN-22). */
    public static MigratePayload drain(int countdownSeconds) {
        return new MigratePayload(null, countdownSeconds);
    }

    /** A move to a named node (MN-19, MN-21). */
    public static MigratePayload to(String targetNode, int countdownSeconds) {
        Objects.requireNonNull(targetNode, "targetNode");
        return new MigratePayload(targetNode, countdownSeconds);
    }
}
