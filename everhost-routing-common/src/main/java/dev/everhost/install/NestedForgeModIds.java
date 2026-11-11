package dev.everhost.install;

import dev.everhost.universal.MiniJson;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/** Recognizes mod identities in Forge's declared Jar-in-Jar library containers. */
public final class NestedForgeModIds {
    private static final int LIMIT = 32 * 1024 * 1024;
    private static final Pattern MOD_ID = Pattern.compile("(?m)^\\s*modId\\s*=\\s*[\"']([A-Za-z0-9_.-]+)[\"']");

    private NestedForgeModIds() {}

    public static Set<String> read(ZipFile outer) throws IOException {
        Set<String> ids = new LinkedHashSet<>();
        var entry = outer.getEntry("META-INF/jarjar/metadata.json");
        if (entry == null) return ids;
        Object parsed;
        try (var input = outer.getInputStream(entry)) {
            byte[] bytes = input.readNBytes(2 * 1024 * 1024 + 1);
            if (bytes.length > 2 * 1024 * 1024) throw new IOException("Embedded mod metadata is too large");
            parsed = MiniJson.parse(new String(bytes, StandardCharsets.UTF_8));
        }
        if (!(parsed instanceof Map<?, ?> metadata) || !(metadata.get("jars") instanceof List<?> jars)) return ids;
        if (jars.size() > 64) throw new IOException("Too many embedded libraries");
        for (Object value : jars) {
            if (!(value instanceof Map<?, ?> jar) || !(jar.get("path") instanceof String path)
                || !path.matches("META-INF/jarjar/[A-Za-z0-9_.+-]+\\.jar")) continue;
            var nested = outer.getEntry(path);
            if (nested == null || nested.getSize() > LIMIT) continue;
            byte[] content;
            try (var input = outer.getInputStream(nested)) { content = input.readNBytes(LIMIT + 1); }
            if (content.length > LIMIT) throw new IOException("Embedded library is too large");
            try (var zip = new ZipInputStream(new ByteArrayInputStream(content))) {
                java.util.zip.ZipEntry file;
                while ((file = zip.getNextEntry()) != null) {
                    if (!file.getName().equals("META-INF/mods.toml")) continue;
                    byte[] bytes = zip.readNBytes(2 * 1024 * 1024 + 1);
                    if (bytes.length > 2 * 1024 * 1024) throw new IOException("Embedded mod metadata is too large");
                    String toml = new String(bytes, StandardCharsets.UTF_8).split("(?m)^\\s*\\[\\[dependencies\\.", 2)[0];
                    var matcher = MOD_ID.matcher(toml);
                    while (matcher.find()) ids.add(matcher.group(1));
                    break;
                }
            }
        }
        return Set.copyOf(ids);
    }
}
