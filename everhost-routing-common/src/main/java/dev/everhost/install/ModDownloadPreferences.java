package dev.everhost.install;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Properties;

/** Local download choices, scoped to the actual server address and this Minecraft profile. */
public final class ModDownloadPreferences {
    private ModDownloadPreferences() {}

    public static boolean curseForgeFallback(Path game) {
        return Boolean.parseBoolean(read(game.resolve("everhost/config.properties")).getProperty("curseForgeFallback", "false"));
    }

    public static String serverKey(String address) {
        String normalized = address == null ? "" : address.strip().toLowerCase(Locale.ROOT);
        if (normalized.endsWith(":25565")) normalized = normalized.substring(0, normalized.length() - 6);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static boolean optionalSuppressed(Path game, String key) {
        return Boolean.parseBoolean(read(stateFile(game, key)).getProperty("suppressOptional", "false"));
    }

    public static void requiredMissing(Path game, String key) throws IOException {
        // Reset only when this client really needs another required install, not on every server restart.
        if (optionalSuppressed(game, key)) rememberOptional(game, key, false);
    }

    public static void rememberOptional(Path game, String key, boolean suppress) throws IOException {
        Path target = stateFile(game, key);
        Files.createDirectories(target.getParent());
        Properties values = new Properties();
        values.setProperty("suppressOptional", Boolean.toString(suppress));
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            values.store(writer, "EverHost optional downloads for this server");
        }
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static Path stateFile(Path game, String key) {
        if (key == null || !key.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid server preference key");
        return game.resolve("everhost/mod-installer/servers").resolve(key + ".properties");
    }

    private static Properties read(Path path) {
        Properties values = new Properties();
        if (Files.isRegularFile(path)) {
            try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                values.load(reader);
            } catch (IOException ignored) {}
        }
        return values;
    }
}
