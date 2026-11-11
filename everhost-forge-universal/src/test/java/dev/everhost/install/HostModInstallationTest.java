package dev.everhost.install;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real TCP host -> bundled Minecraft JRE -> profile replacement -> next-launch verification. */
final class HostModInstallationTest {
    @TempDir Path temporary;
    @AfterEach void stopHost() { ModDistributionServer.stopForTests(); }

    private Path jar(Path folder, String file, String id, String marker) throws Exception {
        Files.createDirectories(folder);
        Path target = folder.resolve(file);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(target))) {
            output.putNextEntry(new JarEntry("META-INF/mods.toml"));
            output.write(("modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\""
                + id + "\"\nversion=\"1\"\n").getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new JarEntry("marker.txt"));
            output.write(marker.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return target;
    }

    private String line(Path jar, String id) throws Exception {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        java.util.function.Function<String,String> b64 = value -> enc.encodeToString(value.getBytes(StandardCharsets.UTF_8));
        return String.join("\t", b64.apply(id), b64.apply(id), b64.apply("1"), b64.apply(jar.getFileName().toString()),
            "0","0","",b64.apply(ModInstallCoordinator.sha256(jar)),"",Long.toString(Files.size(jar)));
    }

    private void properties(Path file, Properties values) throws Exception {
        Files.createDirectories(file.getParent());
        try (var writer = Files.newBufferedWriter(file)) { values.store(writer, "isolated test"); }
    }

    @Test void missingCustomRequiredThenOptionalInstallOverActualHostProtocol() throws Exception {
        Path host = Files.createDirectories(temporary.resolve("host"));
        Path required = jar(host.resolve("everhost-delivery-mods"), "custom-required.jar", "custom_required", "new");
        Path optional = jar(host.resolve("everhost-delivery-mods"), "custom-optional.jar", "custom_optional", "extra");
        Files.writeString(host.resolve("everhost-required-client-mods.dat"), "EVERHOST_REQUIRED_MODS_V3\n" + line(required,"custom_required") + "\n");
        Files.writeString(host.resolve("everhost-optional-client-mods.dat"), "EVERHOST_REQUIRED_MODS_V3\n" + line(optional,"custom_optional") + "\n");
        String token = "c".repeat(64);
        Properties config = new Properties();
        config.setProperty("format","EVERHOST_MOD_DISTRIBUTION_V1");
        config.setProperty("port","0");
        config.setProperty("token",token);
        properties(host.resolve("everhost-mod-distribution.properties"),config);
        assertTrue(ModDistributionServer.start(host));

        Path game = Files.createDirectories(temporary.resolve("client profile with spaces"));
        Path old = jar(game.resolve("mods"),"old-required.jar","custom_required","old");
        String oldHash = ModInstallCoordinator.sha256(old);
        runWorker(game, required, "custom_required", token);
        assertFalse(Files.exists(old));
        assertTrue(ModInstallCoordinator.consumeResult(game).orElseThrow().success());
        assertFalse(Files.exists(game.resolve("mods/custom-optional.jar")), "required phase must not install optional extras");
        try (var backups = Files.walk(game.resolve("everhost/mod-archive"))) {
            assertTrue(backups.filter(Files::isRegularFile).anyMatch(path -> {
                try { return ModInstallCoordinator.sha256(path).equals(oldHash); } catch (Exception failure) { return false; }
            }));
        }
        runWorker(game, optional, "custom_optional", token);
        assertTrue(ModInstallCoordinator.consumeResult(game).orElseThrow().success());
        assertEquals(ModInstallCoordinator.sha256(optional), ModInstallCoordinator.sha256(game.resolve("mods/custom-optional.jar")));
        assertEquals(ModInstallCoordinator.sha256(required), ModInstallCoordinator.sha256(game.resolve("mods/custom-required.jar")));
    }

    private void runWorker(Path game, Path source, String id, String token) throws Exception {
        Path root = Files.createDirectories(game.resolve("everhost/mod-installer"));
        Path plan = root.resolve("pending.properties"), result=root.resolve("result.properties");
        Properties values = new Properties();
        values.setProperty("format","EVERHOST_MOD_INSTALL_V2");
        values.setProperty("gameDirectory",game.toString());
        values.setProperty("reopenCurseForge","false");
        values.setProperty("curseForgeFallback","false");
        values.setProperty("count","1");
        String hash = ModInstallCoordinator.sha256(source);
        values.setProperty("mod.0.name",id);
        values.setProperty("mod.0.ids",id);
        values.setProperty("mod.0.file",source.getFileName().toString());
        values.setProperty("mod.0.sha256",hash);
        values.setProperty("mod.0.sha1","f".repeat(40)); // Old CurseForge metadata must not reject the host\'s modified exact JAR.
        values.setProperty("mod.0.size",Long.toString(Files.size(source)));
        values.setProperty("mod.0.serverUrl","everhost://127.0.0.1:" + ModDistributionServer.boundPortForTests()+"/mods/"+hash);
        values.setProperty("mod.0.serverToken",token);
        properties(plan,values);

        Path executable = Path.of(System.getProperty("user.home"),"curseforge/minecraft/Install/java/java-runtime-gamma/bin/java.exe");
        if (!Files.isRegularFile(executable)) executable = Path.of(System.getProperty("java.home"),"bin","java");
        String classes = Path.of(ModInstallWorker.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Process worker = new ProcessBuilder(executable.toString(), "-Djava.awt.headless=true", "-cp",classes,ModInstallWorker.class.getName(),
            "0",game.toString(),plan.toString(),result.toString(),root.resolve("installer.log").toString())
            .redirectErrorStream(true).redirectOutput(root.resolve("process.log").toFile()).start();
        assertTrue(worker.waitFor(30,TimeUnit.SECONDS), "installer should finish");
        assertEquals(0,worker.exitValue(), Files.readString(root.resolve("process.log")));
        Properties outcome = new Properties();
        try (var reader=Files.newBufferedReader(result)) {outcome.load(reader);}
        assertEquals("success",outcome.getProperty("status"),outcome.getProperty("message"));
    }

    @Test void hostFailureDoesNotSilentlyUseCurseForgeWhenDisabled() throws Exception {
        var artifact = new ModInstallWorker.Artifact("Custom",Set.of("custom"),"custom.jar",1,2,
            "https://edge.forgecdn.net/files/0/2/custom.jar","a".repeat(64),"",1,"everhost://127.0.0.1:1/mods/"+ "a".repeat(64),"b".repeat(64));
        Exception failure = assertThrows(Exception.class, () -> ModInstallWorker.downloadForTests(temporary.resolve("file.jar"),artifact));
        assertTrue(failure.getMessage().contains("CurseForge fallback is OFF"));
        assertFalse(Files.exists(temporary.resolve("file.jar")));
    }
}
