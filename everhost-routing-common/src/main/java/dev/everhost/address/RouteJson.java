package dev.everhost.address;

import dev.everhost.universal.MiniJson;
import java.io.IOException;
import java.util.Map;
import java.util.stream.Collectors;

public final class RouteJson {
    private RouteJson() {}
    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(String input) throws IOException {
        Object result = MiniJson.parse(input);
        if (!(result instanceof Map)) throw new IOException("Expected a JSON object");
        return (Map<String, Object>) result;
    }
    public static String text(Map<String, Object> object, String key) {
        return object.get(key) instanceof String value ? value : "";
    }
    public static String encode(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        if (value instanceof Map<?, ?> map) return map.entrySet().stream()
            .map(entry -> quote(entry.getKey().toString()) + ":" + encode(entry.getValue()))
            .collect(Collectors.joining(",", "{", "}"));
        if (value instanceof Iterable<?> items) {
            StringBuilder out = new StringBuilder("[");
            for (Object item : items) { if (out.length() > 1) out.append(','); out.append(encode(item)); }
            return out.append(']').toString();
        }
        return quote(value.toString());
    }
    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char ch : value.toCharArray()) {
            if (ch == '"' || ch == '\\') out.append('\\').append(ch);
            else if (ch < 32 || Character.isSurrogate(ch)) out.append(String.format("\\u%04x", (int) ch));
            else out.append(ch);
        }
        return out.append('"').toString();
    }
}
