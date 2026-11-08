package dev.everhost.playit;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PlayitBinaryOverrideTest {
    @TempDir Path root;

    @Test void localAgentRequiresExactOwnerPinnedChecksum() throws Exception {
        assertNull(PlayitBinaryOverride.verifiedBinary(root));
        Files.createDirectories(root.resolve("tools"));
        Path binary = root.resolve("tools/compat.exe");
        Files.writeString(binary, "test executable bytes");
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(binary)));
        Files.writeString(root.resolve("playit-agent-binary.properties"), "binaryFile=compat.exe\nsha256=" + sha);
        assertEquals(binary, PlayitBinaryOverride.verifiedBinary(root));
        Files.writeString(binary, "changed bytes");
        assertThrows(IOException.class, () -> PlayitBinaryOverride.verifiedBinary(root));
    }

    @Test void missingAndOutsideToolsAgentsAreRejected() throws Exception {
        Path config = root.resolve("playit-agent-binary.properties");
        Files.writeString(config, "binaryFile=missing.exe\nsha256=" + "a".repeat(64));
        assertThrows(IOException.class, () -> PlayitBinaryOverride.verifiedBinary(root));
        Files.writeString(config, "binaryFile=../outside.exe\nsha256=" + "a".repeat(64));
        assertThrows(IOException.class, () -> PlayitBinaryOverride.verifiedBinary(root));
    }
}
