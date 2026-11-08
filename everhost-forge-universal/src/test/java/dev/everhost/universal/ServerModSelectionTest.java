package dev.everhost.universal;

import dev.everhost.daemon.ServerModSelection;
import dev.everhost.universal.Models.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ServerModSelectionTest {
    @Test void preservesSpixyForEverySideClassificationAndSavedOffState() {
        for (ModSide side : ModSide.values()) {
            ModInfo fix = new ModInfo("SPIXY", Path.of("SPIXY.jar"), side, "test", Set.of("spixy_wow_resource_fix"),
                Set.of(), Set.of(), 0, 0, "1.4.1", "", "", 0, true, false, false);
            ModInfo unrelated = new ModInfo("Unrelated client mod", Path.of("client.jar"), ModSide.CLIENT, "test", Set.of("other"),
                Set.of(), Set.of(), 0, 0, "1", "", "", 0, true, false, false);
            Profile profile = new Profile("Test", Path.of("."), "1.20.1", LoaderType.FORGE, "47.4.10", List.of(), List.of(fix, unrelated), 0);
            Properties config = new Properties();
            config.setProperty("modModes", "SPIXY.jar=OFF");
            assertEquals(List.of(fix), ServerModSelection.select(profile, config));
            assertFalse(fix.clientRequired);
        }
    }
}
