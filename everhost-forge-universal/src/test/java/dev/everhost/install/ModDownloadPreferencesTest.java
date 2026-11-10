package dev.everhost.install;

import dev.everhost.universal.RequiredModsManifest.Requirement;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class ModDownloadPreferencesTest {
    @TempDir Path game;
    private static Requirement mod(String id) {
        return new Requirement(id, Set.of(id), "1", id + ".jar", 0, 0, "", "a".repeat(64), "", 1,
            "everhost://localhost:25571/mods/" + "a".repeat(64), "b".repeat(64));
    }
    @AfterEach void clear() { ModInstallCoordinator.clearOffers(); }

    @Test void fallbackIsOffUntilExplicitlyEnabled() throws Exception {
        assertFalse(ModDownloadPreferences.curseForgeFallback(game));
        Files.createDirectories(game.resolve("everhost"));
        Files.writeString(game.resolve("everhost/config.properties"), "curseForgeFallback=true\n");
        assertTrue(ModDownloadPreferences.curseForgeFallback(game));
    }

    @Test void requiredInstallAlwaysPrecedesOptionalAndResetsSavedChoice() throws Exception {
        String key = ModDownloadPreferences.serverKey("example.org");
        ModDownloadPreferences.rememberOptional(game, key, true);
        var missing = ModInstallCoordinator.assessCatalog(game, "example.org", List.of(mod("required")),
            List.of(mod("optional")), Set.of(), Set.of());
        assertEquals(1, missing.size());
        assertFalse(ModDownloadPreferences.optionalSuppressed(game, key));
        assertNull(ModInstallCoordinator.optionalOffer());
        assertFalse(ModInstallCoordinator.requiredOffer().optional());

        ModInstallCoordinator.assessCatalog(game, "example.org", List.of(mod("required")),
            List.of(mod("optional")), Set.of("required"), Set.of("a".repeat(64)));
        assertNull(ModInstallCoordinator.requiredOffer());
        assertEquals("optional", ModInstallCoordinator.optionalOffer().requirements().get(0).name());
    }

    @Test void checkedNoSurvivesRelaunchAndOtherServerRemainsIndependent() throws Exception {
        ModInstallCoordinator.assessCatalog(game, "one.example", List.of(), List.of(mod("optional")), Set.of(), Set.of());
        var offer = ModInstallCoordinator.optionalOffer();
        ModInstallCoordinator.dismissOptional(game, offer, true);
        ModInstallCoordinator.clearOffers();
        ModInstallCoordinator.assessCatalog(game, "ONE.EXAMPLE:25565", List.of(), List.of(mod("optional")), Set.of(), Set.of());
        assertNull(ModInstallCoordinator.optionalOffer());
        ModInstallCoordinator.assessCatalog(game, "two.example", List.of(), List.of(mod("optional")), Set.of(), Set.of());
        assertNotNull(ModInstallCoordinator.optionalOffer());
    }

    @Test void uncheckedNoAllowsLaterOfferButAlreadyInstalledExtrasNeverPrompt() throws Exception {
        ModInstallCoordinator.assessCatalog(game, "one.example", List.of(), List.of(mod("optional")), Set.of(), Set.of());
        ModInstallCoordinator.dismissOptional(game, ModInstallCoordinator.optionalOffer(), false);
        ModInstallCoordinator.assessCatalog(game, "one.example", List.of(), List.of(mod("optional")), Set.of(), Set.of());
        assertNotNull(ModInstallCoordinator.optionalOffer());
        ModInstallCoordinator.assessCatalog(game, "one.example", List.of(), List.of(mod("optional")), Set.of("optional"), Set.of("a".repeat(64)));
        assertNull(ModInstallCoordinator.optionalOffer());
    }

    @Test void anotherMissingRequiredModRearmsSuppressedOptionalOffer() throws Exception {
        String server = "same.example";
        String key = ModDownloadPreferences.serverKey(server);
        ModDownloadPreferences.rememberOptional(game, key, true);
        ModInstallCoordinator.assessCatalog(game, server, List.of(mod("new_required")), List.of(mod("extra")), Set.of(), Set.of());
        assertFalse(ModDownloadPreferences.optionalSuppressed(game, key));
        assertNotNull(ModInstallCoordinator.requiredOffer());
        assertNull(ModInstallCoordinator.optionalOffer());
        ModInstallCoordinator.assessCatalog(game, server, List.of(mod("new_required")), List.of(mod("extra")),
            Set.of("new_required"), Set.of("a".repeat(64)));
        assertNotNull(ModInstallCoordinator.optionalOffer());
    }
}
