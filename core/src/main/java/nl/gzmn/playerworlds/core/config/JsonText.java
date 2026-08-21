package nl.gzmn.playerworlds.core.config;

import java.util.List;
import java.util.Objects;

/**
 * Produces the JSONB text {@link nl.gzmn.playerworlds.core.db.NetworkSettings#put} expects.
 *
 * <p>{@code NetworkSettings.put} writes whatever text it is given straight into a {@code jsonb}
 * column; the caller is responsible for it already being valid JSON (see {@code
 * NetworkSettingsTest}, which hand-quotes: {@code "\"PUBLIC\""}). {@link NetworkPolicy}'s
 * unwrapping helpers only ever read that text back; nothing in {@code :core} produces it. Message
 * templates routinely contain quotes, backslashes and newlines (multi-line lore, {@code
 * <click:run_command:'...'>} arguments), so a caller string-concatenating quotes by hand is a
 * real correctness risk this exists to remove.
 */
public final class JsonText {

    private JsonText() {}

    /** A JSON string literal, e.g. {@code hi "there"} becomes {@code "hi \"there\""}. */
    public static String quoteString(String value) {
        Objects.requireNonNull(value, "value");
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        String hex = Integer.toHexString(c);
                        out.append("\\u");
                        for (int pad = hex.length(); pad < 4; pad++) {
                            out.append('0');
                        }
                        out.append(hex);
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
        return out.toString();
    }

    /** A JSON array of string literals, matching {@code NetworkPolicy}'s existing list keys. */
    public static String quoteStringList(List<String> values) {
        Objects.requireNonNull(values, "values");
        StringBuilder out = new StringBuilder();
        out.append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(quoteString(values.get(i)));
        }
        out.append(']');
        return out.toString();
    }
}
