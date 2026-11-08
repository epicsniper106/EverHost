package dev.everhost.update;

import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;

/** Standalone, precompiled installer. Uses only Java 17 runtime modules, never the source-file launcher. */
public final class EverHostUpdateInstaller {
    private EverHostUpdateInstaller() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 13) throw new IllegalArgumentException("Expected the complete EverHost update plan.");
        long parentPid = Long.parseLong(args[0]);
        Path installed = Path.of(args[1]);
        Path staged = Path.of(args[2]);
        Path backups = Path.of(args[3]);
        String expected = args[4];
        Path log = Path.of(args[5]);
        Path marker = Path.of(args[6]);
        String version = args[7];
        Path result = Path.of(args[8]);
        boolean reopen = Boolean.parseBoolean(args[9]);
        Path ready = Path.of(args[10]);
        String nonce = args[11];
        String originalHash = args[12];
        boolean accepted = false;
        boolean succeeded = false;
        try {
            Files.createDirectories(backups);
            Files.createDirectories(result.getParent());
            Path lockPath = marker.resolveSibling("installer.lock");
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock lock = channel.tryLock()) {
                if (lock == null) throw new Exception("Another EverHost updater is already running.");
                verify(staged, expected, "The staged EverHost update failed its final checksum check.");
                verify(installed, originalHash, "The installed EverHost JAR changed before the update started.");
                // Verify backup-folder access before telling Minecraft it is safe to close.
                Path probe = Files.createTempFile(backups, "updater-write-check-", ".tmp");
                Files.delete(probe);
                accepted = true;
                Files.writeString(ready, nonce, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                append(log, "Installer ready; waiting for Minecraft to close.");
                while (ProcessHandle.of(parentPid).map(ProcessHandle::isAlive).orElse(false)) Thread.sleep(250L);
                verify(staged, expected, "The staged EverHost update failed its final checksum check.");
                verify(installed, originalHash, "The installed EverHost JAR changed while the update was waiting; keeping that build.");
                install(installed, staged, backups, expected, originalHash, log);
                writeResult(result, "success", version, expected, "EverHost " + version + " installed successfully.");
                succeeded = true;
                append(log, "Installed EverHost " + version + " successfully");
                Files.deleteIfExists(marker);
                Files.deleteIfExists(staged);
            }
        } catch (Throwable failure) {
            String message = safeMessage(failure);
            append(log, "EverHost update failed: " + message);
            writeResult(result, "error", version, expected, message);
            // A rejected second worker must not remove the first worker's plan.
            if (accepted) Files.deleteIfExists(marker);
        } finally {
            Files.deleteIfExists(ready);
            // Startup failures leave the game open and must not open another launcher window.
            if (reopen && succeeded) {
                String warning = reopenCurseForge();
                if (!warning.isBlank()) append(log, warning);
            }
        }
    }

    private static void install(Path installed, Path staged, Path backups, String expected,
                                String originalHash, Path log) throws Exception {
        Path backup = Files.createTempFile(backups, installed.getFileName() + ".", ".bak");
        Files.copy(installed, backup, StandardCopyOption.REPLACE_EXISTING);
        verify(backup, originalHash, "The original EverHost backup could not be verified; installation cancelled.");
        Path replacement = Files.createTempFile(installed.getParent(), "everhost-update-", ".tmp");
        try {
            Files.copy(staged, replacement, StandardCopyOption.REPLACE_EXISTING);
            verify(replacement, expected, "The update copy failed verification; the original JAR has been kept.");
            long deadline = System.currentTimeMillis() + 10L * 60L * 1000L;
            Exception lastFailure = null;
            while (System.currentTimeMillis() < deadline) {
                verify(installed, originalHash, "The installed EverHost JAR changed; refusing to overwrite it.");
                try {
                    // Keep the original in place until a complete, verified replacement is ready.
                    move(replacement, installed);
                } catch (Exception lockedOrBusy) {
                    lastFailure = lockedOrBusy;
                    Thread.sleep(1000L);
                    continue;
                }
                try {
                    verify(installed, expected, "The installed EverHost JAR failed its final checksum check.");
                } catch (Exception invalidInstall) {
                    Path restore = Files.createTempFile(installed.getParent(), "everhost-restore-", ".tmp");
                    try {
                        Files.copy(backup, restore, StandardCopyOption.REPLACE_EXISTING);
                        verify(restore, originalHash, "The restoration copy could not be verified. Original backup: " + backup);
                        move(restore, installed);
                    } finally { Files.deleteIfExists(restore); }
                    throw invalidInstall;
                }
                append(log, "Original EverHost preserved at " + backup);
                return;
            }
            throw new Exception("EverHost stayed in use after Minecraft closed. The original JAR is unchanged. " + safeMessage(lastFailure));
        } finally { Files.deleteIfExists(replacement); }
    }

    private static void verify(Path file, String expected, String message) throws Exception {
        if (expected.length() != 64 || !Files.isRegularFile(file) || !sha256(file).equalsIgnoreCase(expected))
            throw new Exception(message);
    }

    private static void writeResult(Path result, String status, String version, String expected, String message) throws Exception {
        Properties values = new Properties();
        values.setProperty("status", status);
        values.setProperty("version", version);
        values.setProperty("sha256", expected);
        values.setProperty("message", message);
        Path temp = Files.createTempFile(result.getParent(), "update-result-", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                values.store(writer, "EverHost automatic update result");
            }
            move(temp, result);
        } finally { Files.deleteIfExists(temp); }
    }

    private static String reopenCurseForge() {
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) return "";
        List<Path> candidates = new ArrayList<>();
        String x86 = System.getenv("ProgramFiles(x86)");
        String programFiles = System.getenv("ProgramFiles");
        if (x86 != null && !x86.isBlank()) candidates.add(Path.of(x86, "Overwolf", "OverwolfLauncher.exe"));
        if (programFiles != null && !programFiles.isBlank()) candidates.add(Path.of(programFiles, "Overwolf", "OverwolfLauncher.exe"));
        for (Path launcher : candidates) {
            if (!Files.isRegularFile(launcher)) continue;
            try {
                new ProcessBuilder(launcher.toString(), "-launchapp", "cchhcaiapeikjbdbpfplgmpobbcdkdaphclbmkbj").start();
                return "";
            } catch (Exception exception) {
                return "EverHost was installed, but CurseForge could not be reopened: " + safeMessage(exception);
            }
        }
        return "EverHost was installed, but the CurseForge Overwolf launcher was not found.";
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536];
            for (int read; (read = input.read(buffer)) >= 0;) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void move(Path source, Path target) throws Exception {
        try { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException unsupported) { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING); }
    }

    private static String safeMessage(Throwable failure) {
        if (failure == null) return "Unknown installation problem.";
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.toString() : message;
    }

    private static void append(Path log, String message) throws Exception {
        Files.writeString(log, Instant.now() + " " + message + System.lineSeparator(), StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
