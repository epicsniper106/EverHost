package dev.everhost.playit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;

/** Optional owner-installed compatibility agent, pinned to a local checksum. */
final class PlayitBinaryOverride {
    private PlayitBinaryOverride() {}

    static Path verifiedBinary(Path root) throws IOException {
        Path config = root.resolve("playit-agent-binary.properties");
        if (!Files.exists(config)) return null;
        Properties values = new Properties();
        try (var input = Files.newInputStream(config)) { values.load(input); }
        String filename = values.getProperty("binaryFile", "").strip();
        String expected = values.getProperty("sha256", "").strip();
        if (!filename.matches("[A-Za-z0-9][A-Za-z0-9._-]*\\.exe") || !expected.matches("[a-fA-F0-9]{64}")) {
            throw new IOException("Invalid local Playit agent override: an executable filename and SHA-256 are required.");
        }
        Path binary = root.resolve("tools").resolve(filename).toAbsolutePath().normalize();
        if (!Files.isRegularFile(binary)) throw new IOException("The configured local Playit compatibility agent is missing.");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(binary)) {
                byte[] buffer = new byte[65536];
                for (int count; (count = input.read(buffer)) >= 0;) digest.update(buffer, 0, count);
            }
            if (!expected.equalsIgnoreCase(HexFormat.of().formatHex(digest.digest()))) {
                throw new IOException("The local Playit compatibility agent checksum does not match; it was not launched.");
            }
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
        return binary;
    }
}
