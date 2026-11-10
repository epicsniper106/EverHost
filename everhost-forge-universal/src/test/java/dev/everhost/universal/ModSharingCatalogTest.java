package dev.everhost.universal;

import dev.everhost.install.ModSharingCatalog;
import dev.everhost.universal.Models.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.jar.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class ModSharingCatalogTest {
    @TempDir Path profileRoot;

    private ModInfo jar(String id, ModSide side, boolean required, String dependency) throws Exception {
        Path file = Files.createDirectories(profileRoot.resolve("mods")).resolve(id + ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(file))) {
            output.putNextEntry(new JarEntry("META-INF/mods.toml"));
            String text = "modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"" + id + "\"\nversion=\"1\"\n"
                + (dependency == null ? "" : "[[dependencies." + id + "]]\nmodId=\"" + dependency + "\"\nmandatory=true\nversionRange=\"[1,)\"\nside=\"CLIENT\"\n");
            output.write(text.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return new ModInfo(id, file, side, "test", Set.of(id), Set.of(), Set.of(),
            0, 0, "1", "", "", 0, true, false, required);
    }

    private Profile profile(List<ModInfo> mods) {
        return new Profile("Test", profileRoot, "1.20.1", LoaderType.FORGE, "47.4.10", List.of(), mods, 0);
    }

    @Test void requiredIncludesClientDependenciesAndOptionalNeverLoadsOnServer() throws Exception {
        ModInfo required = jar("required", ModSide.SERVER, true, "client_library");
        ModInfo library = jar("client_library", ModSide.CLIENT, false, null);
        library.clientCompatible = false; // Client-side mandatory dependency is stronger evidence than catalog tags.
        ModInfo extra = jar("optional", ModSide.CLIENT, false, null);
        var catalog = ModSharingCatalog.create(profile(List.of(required, library, extra)), List.of(required), Set.of());
        assertEquals(Set.of(required, library), Set.copyOf(catalog.required()));
        assertEquals(List.of(extra), catalog.optional());
        assertFalse(library.selected);
        assertFalse(extra.selected);
    }

    @Test void excludesHostToolsServerOnlyAndExplicitlyUnsharedExtras() throws Exception {
        ModInfo host = jar("everhost", ModSide.SERVER, false, null);
        ModInfo server = jar("server_only", ModSide.SERVER, false, null);
        server.clientCompatible = false;
        ModInfo extra = jar("extra", ModSide.CLIENT, false, null);
        var catalog = ModSharingCatalog.create(profile(List.of(host, server, extra)), List.of(host, server), Set.of("extra.jar"));
        assertTrue(catalog.optional().isEmpty());
        assertTrue(catalog.required().isEmpty());
    }

    @Test void explicitRequiredChoiceIsNeverSilentlyDroppedBySideMetadata() throws Exception {
        ModInfo required = jar("explicit_required", ModSide.SERVER, true, null);
        required.clientCompatible = false;
        assertEquals(List.of(required), ModSharingCatalog.create(profile(List.of(required)), List.of(required), Set.of()).required());
    }

    @Test void manifestSeparatesListsAndStagesExactOptionalFilesOutsideModsFolder() throws Exception {
        ModInfo required = jar("required", ModSide.SERVER, true, null);
        ModInfo extra = jar("extra", ModSide.CLIENT, false, null);
        Path runtime = Files.createDirectories(profileRoot.resolve("runtime"));
        RequiredModsManifest.write(runtime, profile(List.of(required, extra)), List.of(required));
        assertEquals("required", RequiredModsManifest.read(runtime).get(0).name());
        assertEquals("extra", RequiredModsManifest.readOptional(runtime).get(0).name());
        assertTrue(Files.isRegularFile(runtime.resolve("everhost-delivery-mods/extra.jar")));
        assertFalse(Files.exists(runtime.resolve("mods/extra.jar")));
    }
}
