package nl.gzmn.playerworlds.core.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import nl.gzmn.playerworlds.core.config.messages.MessageKey;
import nl.gzmn.playerworlds.core.config.messages.MessageRegistry;

/**
 * Admin-configurable, MiniMessage-formatted player-facing text (NFR-5), loaded from {@code
 * network_setting} under the {@code messages.} key prefix.
 *
 * <p>Shaped differently from {@link NetworkPolicy}: policy is a closed ~45-field record because
 * its shape is fixed, but the message catalog is open-ended (one entry per distinct player-facing
 * string, currently several hundred), so this is a registry ({@link MessageRegistry#ALL}, built
 * from the small per-area files under {@code core.config.messages}) plus a sparse override map —
 * an admin only ever writes the keys they have actually changed; every other key reads its coded
 * default straight from {@link MessageKey#defaultTemplate()}.
 *
 * <p>Holds only raw template strings, never a {@code Component} — {@code :core} is shaded into
 * both the Paper and Velocity plugins and must not carry Adventure/MiniMessage classes (see
 * {@code ArchitectureTest}). Parsing happens per-platform against the strings this returns.
 */
public record MessageCatalog(Map<String, String> overrides) {

    public MessageCatalog {
        Objects.requireNonNull(overrides, "overrides");
        overrides = Map.copyOf(overrides);
    }

    public static MessageCatalog defaults() {
        return new MessageCatalog(Map.of());
    }

    /**
     * The template for {@code key}: the admin override if one was written, otherwise the coded
     * default.
     *
     * @throws ConfigException if {@code key} is not a declared {@link MessageKey}, since that is
     *     always a programmer error (a call site referencing a key it never registered) rather
     *     than something an admin's input can cause
     */
    public String get(String key) {
        Objects.requireNonNull(key, "key");
        return overrides.getOrDefault(key, keyOf(key).defaultTemplate());
    }

    /** The lore lines for a {@link MessageKey#lore} key: the admin override, or the coded default. */
    public List<String> getLore(String key) {
        Objects.requireNonNull(key, "key");
        String raw = overrides.get(key);
        if (raw == null) {
            return keyOf(key).defaultLoreLines();
        }
        return stringList(raw, key);
    }

    private static MessageKey keyOf(String key) {
        MessageKey def = MessageRegistry.ALL.get(key);
        if (def == null) {
            throw new ConfigException("unknown message key: " + key + " (not declared in MessageRegistry)");
        }
        return def;
    }

    /**
     * Builds a catalog from raw {@code network_setting} JSONB texts keyed by setting name.
     *
     * <p>Only {@code messages.*} keys are consulted; everything else in the map (policy keys) is
     * ignored, so this can be called with the same snapshot {@link NetworkPolicy#fromRaw} uses.
     * An override for a key {@link MessageRegistry} does not declare is kept as-is rather than
     * rejected: a node running an older build must not refuse to boot over a newer key a mixed
     * deploy already wrote (spec section 12.9); it simply never being read is silently correct
     * (see {@link #get}, which will throw for it, but nothing calls {@link #get} with a key that
     * is not that node's own).
     */
    public static MessageCatalog fromRaw(Map<String, String> rawJsonByKey) {
        Objects.requireNonNull(rawJsonByKey, "rawJsonByKey");
        Map<String, String> filtered = new java.util.HashMap<>();
        for (Map.Entry<String, String> entry : rawJsonByKey.entrySet()) {
            if (entry.getKey().startsWith("messages.")) {
                filtered.put(entry.getKey(), unwrapJsonString(entry.getValue()));
            }
        }
        return new MessageCatalog(filtered);
    }

    private List<String> stringList(String raw, String key) {
        String body = raw.strip();
        if (body.equals("[]")) {
            return List.of();
        }
        if (!body.startsWith("[") || !body.endsWith("]")) {
            throw new ConfigException("network_setting '" + key + "' must be a JSON array of strings, was: " + raw);
        }
        String inner = body.substring(1, body.length() - 1).strip();
        if (inner.isEmpty()) {
            return List.of();
        }
        List<String> parts = splitOnComma(inner);
        List<String> parsed = new ArrayList<>(parts.size());
        for (String part : parts) {
            parsed.add(unwrapJsonString(part));
        }
        return List.copyOf(parsed);
    }

    /**
     * Splits on top-level {@code ','} only, respecting quoted strings so a comma or a lore
     * line's own {@code \,} escape does not split mid-value. Message text is free-form (unlike
     * {@code NetworkPolicy}'s command names and globs), so {@code NetworkPolicy}'s naive
     * comma-split is not safe to reuse here.
     */
    private static List<String> splitOnComma(String inner) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                inString = !inString;
            } else if (c == ',' && !inString) {
                parts.add(inner.substring(start, i).strip());
                start = i + 1;
            }
        }
        parts.add(inner.substring(start).strip());
        return parts;
    }

    private static String unwrapJsonString(String json) {
        String stripped = json.strip();
        if (stripped.length() < 2 || stripped.charAt(0) != '"' || stripped.charAt(stripped.length() - 1) != '"') {
            // A hand-written row without quotes still works, matching NetworkPolicy's leniency.
            return stripped;
        }
        StringBuilder out = new StringBuilder(stripped.length() - 2);
        for (int i = 1; i < stripped.length() - 1; i++) {
            char c = stripped.charAt(i);
            if (c == '\\' && i + 1 < stripped.length() - 1) {
                char next = stripped.charAt(i + 1);
                switch (next) {
                    case '"' -> {
                        out.append('"');
                        i++;
                    }
                    case '\\' -> {
                        out.append('\\');
                        i++;
                    }
                    case 'n' -> {
                        out.append('\n');
                        i++;
                    }
                    case 'r' -> {
                        out.append('\r');
                        i++;
                    }
                    case 't' -> {
                        out.append('\t');
                        i++;
                    }
                    default -> out.append(c);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
