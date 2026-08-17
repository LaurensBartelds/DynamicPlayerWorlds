package nl.gzmn.playerworlds.core.storage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.jspecify.annotations.Nullable;

/**
 * Deterministic JSON codec for snapshot {@link Manifest} instances.
 *
 * <p>Manifests are encoded with entries sorted lexicographically by logical path to guarantee
 * byte-for-byte deterministic serialization across runs.
 */
public final class ManifestCodec {

    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    private ManifestCodec() {}

    /**
     * Encodes a manifest into a deterministic, formatted JSON string.
     *
     * @param manifest the manifest to serialize
     * @return deterministic JSON representation
     */
    public static String encode(Manifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        StringBuilder sb = new StringBuilder(1024);
        sb.append("{\n");
        sb.append("  \"worldId\": \"").append(manifest.worldId().value()).append("\",\n");
        sb.append("  \"generation\": ").append(manifest.generation()).append(",\n");
        sb.append("  \"sequence\": ").append(manifest.sequence()).append(",\n");
        sb.append("  \"dataVersion\": ").append(manifest.dataVersion()).append(",\n");
        sb.append("  \"mcVersion\": \"")
                .append(escapeJson(manifest.mcVersion()))
                .append("\",\n");
        sb.append("  \"createdAt\": \"").append(manifest.createdAt()).append("\",\n");

        if (manifest.entries().isEmpty()) {
            sb.append("  \"entries\": {}\n");
        } else {
            sb.append("  \"entries\": {\n");
            int count = 0;
            int size = manifest.entries().size();
            for (Map.Entry<String, ManifestEntry> entry : manifest.entries().entrySet()) {
                ManifestEntry val = entry.getValue();
                sb.append("    \"").append(escapeJson(entry.getKey())).append("\": {");
                sb.append("\"sha256\": \"").append(val.sha256Hex()).append("\", ");
                sb.append("\"sizeBytes\": ").append(val.sizeBytes()).append(", ");
                sb.append("\"lastModifiedMillis\": ").append(val.lastModifiedMillis());
                sb.append("}");
                if (++count < size) {
                    sb.append(",");
                }
                sb.append("\n");
            }
            sb.append("  }\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * Decodes a JSON string into a {@link Manifest}.
     *
     * @param json the raw JSON string
     * @return parsed manifest
     * @throws StorageException if parsing fails or required fields are missing/invalid
     */
    @SuppressWarnings("unchecked")
    public static Manifest decode(String json) {
        Objects.requireNonNull(json, "json");
        try {
            @Nullable Object parsed = new SimpleJsonParser(json).parse();
            if (!(parsed instanceof Map<?, ?> rootMap)) {
                throw new IllegalArgumentException("Root JSON must be an object");
            }
            Map<String, @Nullable Object> root = (Map<String, @Nullable Object>) rootMap;

            @Nullable Object worldIdVal = root.get("worldId");
            if (worldIdVal == null) {
                throw new IllegalArgumentException("Missing worldId");
            }
            UUID worldId = UUID.fromString(worldIdVal.toString());

            @Nullable Object generationVal = root.get("generation");
            if (generationVal == null) {
                throw new IllegalArgumentException("Missing generation");
            }
            long generation = ((Number) generationVal).longValue();

            @Nullable Object sequenceVal = root.get("sequence");
            if (sequenceVal == null) {
                throw new IllegalArgumentException("Missing sequence");
            }
            int sequence = ((Number) sequenceVal).intValue();

            @Nullable Object dataVersionVal = root.get("dataVersion");
            if (dataVersionVal == null) {
                throw new IllegalArgumentException("Missing dataVersion");
            }
            int dataVersion = ((Number) dataVersionVal).intValue();

            @Nullable Object mcVersionVal = root.get("mcVersion");
            if (mcVersionVal == null) {
                throw new IllegalArgumentException("Missing mcVersion");
            }
            String mcVersion = (String) mcVersionVal;

            @Nullable Object createdAtVal = root.get("createdAt");
            if (createdAtVal == null) {
                throw new IllegalArgumentException("Missing createdAt");
            }
            Instant createdAt = Instant.parse((String) createdAtVal);

            @Nullable Object entriesVal = root.get("entries");
            if (entriesVal == null) {
                throw new IllegalArgumentException("Missing entries");
            }
            if (!(entriesVal instanceof Map<?, ?> rawEntriesMap)) {
                throw new IllegalArgumentException("entries must be an object");
            }
            Map<String, @Nullable Object> rawEntries = (Map<String, @Nullable Object>) rawEntriesMap;

            Map<String, ManifestEntry> entries = new LinkedHashMap<>();
            for (Map.Entry<String, @Nullable Object> e : rawEntries.entrySet()) {
                String path = e.getKey();
                if (!(e.getValue() instanceof Map<?, ?> entryMap)) {
                    throw new IllegalArgumentException("Entry " + path + " must be an object");
                }
                Map<String, @Nullable Object> entryObj = (Map<String, @Nullable Object>) entryMap;

                @Nullable Object sha256Val = entryObj.get("sha256");
                @Nullable Object sizeBytesVal = entryObj.get("sizeBytes");
                @Nullable Object mtimeVal = entryObj.get("lastModifiedMillis");

                if (sha256Val == null || sizeBytesVal == null || mtimeVal == null) {
                    throw new IllegalArgumentException("Missing required fields in entry: " + path);
                }

                String sha256 = (String) sha256Val;
                long sizeBytes = ((Number) sizeBytesVal).longValue();
                long mtime = ((Number) mtimeVal).longValue();

                entries.put(path, new ManifestEntry(path, sha256, sizeBytes, mtime));
            }

            return new Manifest(new WorldId(worldId), generation, sequence, dataVersion, mcVersion, createdAt, entries);
        } catch (Exception e) {
            throw new StorageException("Failed to decode Manifest JSON", e);
        }
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
                    if (c < 0x20) {
                        sb.append("\\u")
                                .append(HEX_DIGITS[(c >> 12) & 0xF])
                                .append(HEX_DIGITS[(c >> 8) & 0xF])
                                .append(HEX_DIGITS[(c >> 4) & 0xF])
                                .append(HEX_DIGITS[c & 0xF]);
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static final class SimpleJsonParser {
        private final String src;
        private int pos = 0;

        SimpleJsonParser(String src) {
            this.src = Objects.requireNonNull(src, "src");
        }

        @Nullable
        Object parse() {
            skipWhitespace();
            if (pos >= src.length()) {
                throw new IllegalArgumentException("Empty JSON content");
            }
            @Nullable Object result = parseValue();
            skipWhitespace();
            if (pos < src.length()) {
                throw new IllegalArgumentException("Unexpected trailing character at pos " + pos);
            }
            return result;
        }

        @Nullable
        private Object parseValue() {
            skipWhitespace();
            if (pos >= src.length()) {
                throw new IllegalArgumentException("Unexpected end of JSON input");
            }
            char c = src.charAt(pos);
            if (c == '{') {
                return parseObject();
            } else if (c == '[') {
                return parseArray();
            } else if (c == '"') {
                return parseString();
            } else if (c == '-' || (c >= '0' && c <= '9')) {
                return parseNumber();
            } else if (c == 't' || c == 'f') {
                return parseBoolean();
            } else if (c == 'n') {
                return parseNull();
            }
            throw new IllegalArgumentException("Unexpected character '" + c + "' at position " + pos);
        }

        private Map<String, @Nullable Object> parseObject() {
            expect('{');
            Map<String, @Nullable Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (pos < src.length() && src.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (pos < src.length()) {
                skipWhitespace();
                if (pos >= src.length() || src.charAt(pos) != '"') {
                    throw new IllegalArgumentException("Expected string key at pos " + pos);
                }
                String key = parseString();
                skipWhitespace();
                expect(':');
                @Nullable Object val = parseValue();
                map.put(key, val);
                skipWhitespace();
                if (pos < src.length() && src.charAt(pos) == ',') {
                    pos++;
                } else if (pos < src.length() && src.charAt(pos) == '}') {
                    pos++;
                    return map;
                } else {
                    throw new IllegalArgumentException("Expected ',' or '}' at pos " + pos);
                }
            }
            throw new IllegalArgumentException("Unterminated object at pos " + pos);
        }

        private List<@Nullable Object> parseArray() {
            expect('[');
            List<@Nullable Object> list = new ArrayList<>();
            skipWhitespace();
            if (pos < src.length() && src.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (pos < src.length()) {
                list.add(parseValue());
                skipWhitespace();
                if (pos < src.length() && src.charAt(pos) == ',') {
                    pos++;
                } else if (pos < src.length() && src.charAt(pos) == ']') {
                    pos++;
                    return list;
                } else {
                    throw new IllegalArgumentException("Expected ',' or ']' at pos " + pos);
                }
            }
            throw new IllegalArgumentException("Unterminated array at pos " + pos);
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= src.length()) {
                        throw new IllegalArgumentException("Unterminated escape sequence");
                    }
                    char esc = src.charAt(pos++);
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > src.length()) {
                                throw new IllegalArgumentException("Invalid unicode escape");
                            }
                            String hex = src.substring(pos, pos + 4);
                            pos += 4;
                            sb.append((char) Integer.parseInt(hex, 16));
                        }
                        default -> throw new IllegalArgumentException("Invalid escape character: " + esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("Unterminated string");
        }

        private Number parseNumber() {
            int start = pos;
            if (src.charAt(pos) == '-') {
                pos++;
            }
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                pos++;
            }
            boolean isFloating = false;
            if (pos < src.length() && src.charAt(pos) == '.') {
                isFloating = true;
                pos++;
                while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                    pos++;
                }
            }
            if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
                isFloating = true;
                pos++;
                if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) {
                    pos++;
                }
                while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                    pos++;
                }
            }
            String numStr = src.substring(start, pos);
            if (isFloating) {
                return Double.parseDouble(numStr);
            }
            return Long.parseLong(numStr);
        }

        private Boolean parseBoolean() {
            if (src.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            } else if (src.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("Invalid boolean at pos " + pos);
        }

        @Nullable
        private Object parseNull() {
            if (src.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new IllegalArgumentException("Invalid null literal at pos " + pos);
        }

        private void expect(char expected) {
            if (pos >= src.length() || src.charAt(pos) != expected) {
                throw new IllegalArgumentException("Expected '" + expected + "' at pos " + pos + " but found "
                        + (pos >= src.length() ? "EOF" : "'" + src.charAt(pos) + "'"));
            }
            pos++;
        }

        private void skipWhitespace() {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }
    }
}
