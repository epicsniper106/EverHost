package dev.everhost.playit;

import java.nio.file.Path;

/** Source restoration of the installed process-recovery patch; never adopts another profile's agent. */
public final class PlayitProcessRecovery {
    private PlayitProcessRecovery() {}

    public static boolean ensureRunning(Object manager) {
        if (manager == null) return false;
        try {
            Class<?> type = manager.getClass();
            var handleField = type.getDeclaredField("agentHandle");
            handleField.setAccessible(true);
            ProcessHandle current = (ProcessHandle) handleField.get(manager);
            if (current != null && current.isAlive()) return true;
            var binaryField = type.getDeclaredField("binary");
            var secretField = type.getDeclaredField("secretFile");
            binaryField.setAccessible(true);
            secretField.setAccessible(true);
            Path binary = ((Path) binaryField.get(manager)).toAbsolutePath().normalize();
            Path secret = ((Path) secretField.get(manager)).toAbsolutePath().normalize();
            try (var processes = ProcessHandle.allProcesses()) {
                var found = processes.filter(ProcessHandle::isAlive)
                    .filter(handle -> samePath(handle.info().command().orElse(""), binary))
                    .filter(handle -> {
                        for (String argument : handle.info().arguments().orElse(new String[0])) {
                            if (argument != null && (argument.equals(secret.toString()) || samePath(argument, secret))) return true;
                        }
                        return false;
                    }).findFirst();
                if (found.isPresent()) {
                    handleField.set(manager, found.get());
                    return true;
                }
            }
            // Windows Java 17 exposes the executable but often no process arguments at all.
            // Query Windows for this exact executable/profile instead of launching a duplicate.
            if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
                ProcessHandle found = findWindowsAgent(binary, secret);
                if (found != null) {
                    handleField.set(manager, found);
                    return true;
                }
            }
        } catch (IllegalStateException failure) {
            // Unknown ownership is not proof that no agent exists. Let the caller retry safely.
            throw failure;
        } catch (ReflectiveOperationException | SecurityException failure) {
            throw new IllegalStateException("Cannot verify ownership of existing Playit processes; no new agent was launched.", failure);
        }
        return false;
    }

    private static ProcessHandle findWindowsAgent(Path binary, Path secret) {
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot == null) throw new IllegalStateException("Cannot check existing Playit processes: Windows directory is unavailable.");
        Path powershell = Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        String script = """
            $ErrorActionPreference = 'Stop'
            $expected = $env:EVERHOST_RECOVERY_BINARY
            $secret = [regex]::Escape($env:EVERHOST_RECOVERY_SECRET)
            $pattern = '(^|\\s)("' + $secret + '"|' + $secret + ')(\\s|$)'
            Get-CimInstance -ClassName Win32_Process | Where-Object {
                $_.ExecutablePath -eq $expected -and
                [regex]::IsMatch($_.CommandLine, $pattern, [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
            } | ForEach-Object { [Console]::WriteLine($_.ProcessId) }
            """;
        Process query = null;
        try {
            // PowerShell's native -Command parsing consumes embedded quotes on Windows.
            // UTF-16LE preserves the exact quoted-path matcher for profiles containing spaces.
            String encoded = java.util.Base64.getEncoder().encodeToString(script.getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
            ProcessBuilder builder = new ProcessBuilder(powershell.toString(), "-NoLogo", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded);
            builder.environment().put("EVERHOST_RECOVERY_BINARY", binary.toString());
            builder.environment().put("EVERHOST_RECOVERY_SECRET", secret.toString());
            query = builder.redirectErrorStream(true).start();
            if (!query.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IllegalStateException("Checking existing Playit processes timed out; no new agent was launched.");
            }
            if (query.exitValue() != 0) throw new IllegalStateException("Windows could not check existing Playit processes; no new agent was launched.");
            String output = new String(query.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            for (String line : output.lines().toList()) {
                if (!line.strip().matches("[0-9]+")) continue;
                ProcessHandle handle = ProcessHandle.of(Long.parseLong(line.strip())).orElse(null);
                if (handle != null && handle.isAlive() && samePath(handle.info().command().orElse(""), binary)) return handle;
            }
            return null;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while checking existing Playit processes; no new agent was launched.", failure);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Windows could not check existing Playit processes; no new agent was launched.", failure);
        } finally {
            if (query != null && query.isAlive()) query.destroyForcibly();
        }
    }

    private static boolean samePath(String value, Path expected) {
        if (value == null || value.isBlank()) return false;
        try { return Path.of(value).toAbsolutePath().normalize().equals(expected); }
        catch (RuntimeException ignored) { return false; }
    }
}
