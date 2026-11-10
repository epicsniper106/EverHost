package dev.everhost.install;

import dev.everhost.universal.MiniJson;
import dev.everhost.universal.Models.ModInfo;
import dev.everhost.universal.Models.ModSide;
import dev.everhost.universal.Models.Profile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/** Client downloads are independent of the dedicated server's runtime mod selection. */
public final class ModSharingCatalog {
    private static final Set<String> HOST_ONLY = Set.of("everhost", "e4mc", "arclight", "cardboard", "spongeforge");
    private static final Set<String> PLATFORM = Set.of("minecraft", "java", "forge", "neoforge", "fabricloader", "fabric-loader");
    private static final Pattern MOD_ID = Pattern.compile("(?m)^\\s*modId\\s*=\\s*[\"']([A-Za-z0-9_.-]+)[\"']");
    private static final Pattern MANDATORY = Pattern.compile("(?m)^\\s*mandatory\\s*=\\s*true\\b");
    private static final Pattern SERVER_DEPENDENCY = Pattern.compile("(?m)^\\s*side\\s*=\\s*[\"']SERVER[\"']", Pattern.CASE_INSENSITIVE);

    private ModSharingCatalog() {}

    public static boolean clientEligible(ModInfo mod) {
        return mod.clientCompatible && shareable(mod);
    }

    private static boolean shareable(ModInfo mod) {
        return !mod.pluginBridge && mod.modIds.stream().noneMatch(HOST_ONLY::contains);
    }

    public static boolean optionalByDefault(ModInfo mod) {
        return clientEligible(mod) && !mod.clientRequired && (mod.selected || mod.side == ModSide.CLIENT);
    }

    public static Catalog create(Profile profile, List<ModInfo> selected, Set<String> excluded) throws IOException {
        Map<String, ModInfo> byId = new LinkedHashMap<>();
        Map<Long, ModInfo> byProject = new LinkedHashMap<>();
        Path profileMods = profile.path().resolve("mods").toRealPath();
        for (ModInfo mod : profile.mods()) {
            // Never expand delivery to paths outside the selected profile, including symlink escapes.
            if (!Files.isRegularFile(mod.path) || !profileMods.equals(mod.path.toRealPath().getParent())) continue;
            for (String id : mod.modIds) byId.putIfAbsent(id, mod);
            if (mod.curseProjectId > 0) byProject.putIfAbsent(mod.curseProjectId, mod);
        }
        // A host's explicit Required choice takes precedence over imperfect distribution-side metadata.
        Set<ModInfo> required = closure(selected.stream().filter(m -> m.clientRequired && shareable(m)).toList(), byId, byProject);
        List<ModInfo> optionalRoots = profile.mods().stream().filter(ModSharingCatalog::optionalByDefault)
            .filter(m -> !required.contains(m) && !excluded.contains(m.path.getFileName().toString())).toList();
        Set<ModInfo> optional = closure(optionalRoots, byId, byProject);
        optional.removeAll(required);
        return new Catalog(sorted(required), sorted(optional));
    }

    private static Set<ModInfo> closure(List<ModInfo> roots, Map<String, ModInfo> byId, Map<Long, ModInfo> byProject) throws IOException {
        Set<ModInfo> result = new LinkedHashSet<>();
        ArrayDeque<ModInfo> queue = new ArrayDeque<>(roots);
        while (!queue.isEmpty()) {
            ModInfo mod = queue.removeFirst();
            if (!shareable(mod) || !byId.containsValue(mod) || !result.add(mod)) continue;
            for (String id : clientDependencies(mod.path)) {
                ModInfo dependency = byId.get(id);
                if (dependency != null && shareable(dependency)) queue.addLast(dependency);
            }
            for (Long id : mod.curseDependencies) {
                ModInfo dependency = byProject.get(id);
                if (dependency != null && clientEligible(dependency)) queue.addLast(dependency);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    static Set<String> clientDependencies(Path path) throws IOException {
        Set<String> result = new LinkedHashSet<>();
        try (ZipFile zip = new ZipFile(path.toFile())) {
            var forge = zip.getEntry("META-INF/mods.toml");
            var fabric = zip.getEntry("fabric.mod.json");
            if (forge != null) {
                if (forge.getSize() > 2 * 1024 * 1024) throw new IOException("Mod metadata is too large: " + path.getFileName());
                String toml;
                try (var stream = zip.getInputStream(forge)) {
                    toml = new String(stream.readNBytes(2 * 1024 * 1024), StandardCharsets.UTF_8);
                }
                for (String table : toml.split("(?m)^\\s*\\[\\[")) {
                    if (!table.startsWith("dependencies.") || !MANDATORY.matcher(table).find()
                        || SERVER_DEPENDENCY.matcher(table).find()) continue;
                    var id = MOD_ID.matcher(table);
                    if (id.find() && !PLATFORM.contains(id.group(1))) result.add(id.group(1));
                }
            } else if (fabric != null) {
                try (var stream = zip.getInputStream(fabric)) {
                    Object parsed = MiniJson.parse(new String(stream.readNBytes(2 * 1024 * 1024), StandardCharsets.UTF_8));
                    if (parsed instanceof Map<?, ?> root && root.get("depends") instanceof Map<?, ?> dependencies) {
                        for (Object id : dependencies.keySet()) if (id instanceof String key && !PLATFORM.contains(key)) result.add(key);
                    }
                }
            }
        }
        return result;
    }

    private static List<ModInfo> sorted(Set<ModInfo> mods) {
        return mods.stream().sorted(Comparator.comparing(m -> m.name, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    public record Catalog(List<ModInfo> required, List<ModInfo> optional) {}
}
