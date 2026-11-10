package dev.everhost.playit;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class PlayitProcessRecoveryTest {
    private static final class AgentOwner {
        private ProcessHandle agentHandle;
        private final Path binary;
        private final Path secretFile;
        AgentOwner(Path binary, Path secretFile) { this.binary = binary; this.secretFile = secretFile; }
    }

    @Test void findsOwnedProcessWithoutSavedPidAndDoesNotAdoptOtherProfile(@TempDir Path root) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        Path ownSecret = root.resolve("profile with spaces/own-agent.toml");
        String classpath = Path.of(Sleeper.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Process owned = new ProcessBuilder(java.toString(), "-cp", classpath, Sleeper.class.getName(), ownSecret.toString()).redirectErrorStream(true).start();
        try {
            var reader = owned.inputReader();
            String startup = CompletableFuture.supplyAsync(() -> {
                try { String line; while ((line = reader.readLine()) != null) if (line.equals("READY")) return line; return "Exited before READY"; }
                catch (java.io.IOException exception) { return exception.toString(); }
            }).get(10, TimeUnit.SECONDS);
            assertEquals("READY", startup);
            AgentOwner owner = new AgentOwner(java, ownSecret);
            assertTrue(PlayitProcessRecovery.ensureRunning(owner));
            assertEquals(owned.pid(), owner.agentHandle.pid());
            owner.agentHandle = null;
            assertTrue(PlayitProcessRecovery.ensureRunning(owner));
            assertEquals(owned.pid(), owner.agentHandle.pid());
            assertFalse(PlayitProcessRecovery.ensureRunning(new AgentOwner(java, root.resolve("other-agent.toml"))));
        } finally { owned.destroy(); assertTrue(owned.waitFor(5, TimeUnit.SECONDS)); }
    }

    public static final class Sleeper {
        public static void main(String[] args) throws Exception { System.out.println("READY"); Thread.sleep(30000L); }
    }
}
