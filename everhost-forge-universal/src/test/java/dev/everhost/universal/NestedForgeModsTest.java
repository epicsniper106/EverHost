package dev.everhost.universal;

import dev.everhost.install.ModInstallWorker;
import dev.everhost.install.ModSharingCatalog;
import dev.everhost.universal.Models.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class NestedForgeModsTest {
    @TempDir Path root;

    private byte[] jar(String name, byte[] content) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var output = new JarOutputStream(bytes)) {
            output.putNextEntry(new JarEntry(name));
            output.write(content);
            output.closeEntry();
        }
        return bytes.toByteArray();
    }

    private ModInfo library() throws Exception {
        Path file = Files.createDirectories(root.resolve("mods")).resolve("kotlin-container.jar");
        byte[] nested = jar("META-INF/mods.toml", "[[mods]]\nmodId=\"kotlinforforge\"\n[[dependencies.kotlinforforge]]\nmodId=\"forge\"\n".getBytes(StandardCharsets.UTF_8));
        try (var output = new JarOutputStream(Files.newOutputStream(file))) {
            output.putNextEntry(new JarEntry("META-INF/jarjar/metadata.json"));
            output.write("{\"jars\":[{\"path\":\"META-INF/jarjar/kffmod.jar\"}]}".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new JarEntry("META-INF/jarjar/kffmod.jar"));
            output.write(nested);
            output.closeEntry();
        }
        var inspected = JarInspector.inspect(file, LoaderType.FORGE);
        assertEquals(Set.of("kotlinforforge"), inspected.modIds());
        assertTrue(inspected.clientCompatible());
        return new ModInfo("Kotlin library", file, ModSide.UNCERTAIN, "test", inspected.modIds(), Set.of(), Set.of(),
            0, 0, "1", "", "", 0, true, false, false);
    }

    @Test void requiredClientDependencyIncludesItsOuterLibraryJarAndCanBeVerified() throws Exception {
        ModInfo library = library();
        Path file = root.resolve("mods/content.jar");
        Files.write(file, jar("META-INF/mods.toml", ("modLoader=\"javafml\"\n[[mods]]\nmodId=\"content\"\n"
            + "[[dependencies.content]]\nmodId=\"kotlinforforge\"\nmandatory=true\nside=\"BOTH\"\n").getBytes(StandardCharsets.UTF_8)));
        ModInfo content = new ModInfo("Content", file, ModSide.SERVER, "test", Set.of("content"), Set.of(), Set.of(),
            0, 0, "1", "", "", 0, true, false, true);
        var profile = new Profile("Test", root, "1.20.1", LoaderType.FORGE, "47.4.10", List.of(), List.of(content, library), 0);
        assertEquals(Set.of(content, library), Set.copyOf(ModSharingCatalog.create(profile, List.of(content), Set.of()).required()));
        var inspect = ModInstallWorker.class.getDeclaredMethod("jarModIds", Path.class);
        inspect.setAccessible(true);
        assertEquals(Set.of("kotlinforforge"), inspect.invoke(null, library.path));
    }
}
