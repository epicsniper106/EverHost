package dev.everhost.daemon;

import dev.everhost.universal.Models.ModInfo;
import dev.everhost.universal.Models.Profile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Source restoration of the installed SPIXY compatibility patch. */
public final class ServerModSelection {
    private ServerModSelection() {}

    public static List<ModInfo> select(Profile profile, Properties config) {
        Map<String, String> overrides = decodeModModes(config.getProperty("modModes", ""));
        List<ModInfo> selected = new ArrayList<>();
        for (ModInfo mod : profile.mods()) {
            String mode = overrides.get(mod.path.getFileName().toString());
            if (mode != null) {
                mod.selected = !"OFF".equals(mode);
                mod.clientRequired = "REQUIRED".equals(mode);
            }
            // This BOTH-side coremod is essential to datapack loading, even when heuristics disagree.
            if (mod.modIds.contains("spixy_wow_resource_fix")) mod.selected = true;
            if (mod.selected) selected.add(mod);
        }
        return selected;
    }

    private static Map<String, String> decodeModModes(String encoded) {
        Map<String, String> result = new LinkedHashMap<>();
        if (encoded == null || encoded.isBlank()) return result;
        for (String entry : encoded.split(";")) {
            int separator = entry.lastIndexOf('=');
            if (separator <= 0 || separator >= entry.length() - 1) continue;
            result.put(entry.substring(0, separator), entry.substring(separator + 1));
        }
        return result;
    }
}
