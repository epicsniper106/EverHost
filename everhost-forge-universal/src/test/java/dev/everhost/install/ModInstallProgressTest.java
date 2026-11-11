package dev.everhost.install;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class ModInstallProgressTest {
    @TempDir Path game;

    @Test void repeatedInstallPreservesTheActivePlanAndResult() throws Exception {
        Path root = Files.createDirectories(game.resolve("everhost/mod-installer"));
        Path plan = root.resolve("pending.properties");
        Path result = root.resolve("result.properties");
        Files.writeString(plan, "active-plan");
        Files.writeString(result, "previous-result");
        ModInstallProgress.write(root, "DOWNLOADING", 3, 74, "Downloading mod 4 of 74");
        try (var channel = FileChannel.open(root.resolve("installer.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            var busy = assertThrows(ModInstallCoordinator.InstallationBusyException.class,
                () -> ModInstallCoordinator.launch(game, game.resolve("java"), game.resolve("mods/EverHost.jar"), null));
            assertTrue(busy.getMessage().contains("4 of 74"));
            assertEquals("active-plan", Files.readString(plan));
            assertTrue(ModInstallCoordinator.consumeResult(game).isEmpty());
            assertEquals("previous-result", Files.readString(result));
        }
        assertEquals("", ModInstallProgress.running(game));
    }

    @Test void aConcurrentLauncherCannotReplaceThePlanBeforeTheWorkerStarts() throws Exception {
        Path root = Files.createDirectories(game.resolve("everhost/mod-installer"));
        Files.writeString(root.resolve("pending.properties"), "launching-plan");
        try (var channel = FileChannel.open(root.resolve("launch.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            assertThrows(ModInstallCoordinator.InstallationBusyException.class,
                () -> ModInstallCoordinator.launch(game, game.resolve("java"), game.resolve("mods/EverHost.jar"), null));
            assertEquals("launching-plan", Files.readString(root.resolve("pending.properties")));
        }
    }
}
