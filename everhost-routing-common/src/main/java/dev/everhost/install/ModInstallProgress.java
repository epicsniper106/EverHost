package dev.everhost.install;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Properties;

/** Non-secret progress shared by the worker and a client reopened early. */
public final class ModInstallProgress {
    private ModInstallProgress() {}

    public static String running(Path game) throws IOException {
        Path root = game.resolve("everhost/mod-installer");
        if (!Files.isDirectory(root)) return "";
        try (var channel = FileChannel.open(root.resolve("installer.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            try (var lock = channel.tryLock()) {
                if (lock != null) return "";
            } catch (OverlappingFileLockException runningHere) { }
        }
        Properties progress = new Properties();
        try (var reader = Files.newBufferedReader(root.resolve("progress.properties"), StandardCharsets.UTF_8)) {
            progress.load(reader);
        } catch (IOException unavailable) { }
        return progress.getProperty("message", "The mod installer is still working.")
            + " Keep Minecraft closed until installation finishes.";
    }

    static void write(Path root, String phase, int completed, int total, String message) throws IOException {
        Properties values = new Properties();
        values.setProperty("phase", phase);
        values.setProperty("completed", Integer.toString(completed));
        values.setProperty("total", Integer.toString(total));
        values.setProperty("message", message);
        Path temporary = root.resolve("progress.properties.tmp");
        try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            values.store(writer, "EverHost installation progress");
        }
        Files.move(temporary, root.resolve("progress.properties"), StandardCopyOption.REPLACE_EXISTING);
    }
}
