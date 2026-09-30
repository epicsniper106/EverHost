package dev.everhost.universal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JavaLocator {
	private static final Pattern VERSION = Pattern.compile("version \\\"(?:1\\.)?([0-9]+)");

	private JavaLocator() {
	}

	public static JavaRuntime locate(int requiredMajor) throws IOException {
		for (Path candidate : candidates()) {
			int major = probe(candidate);
			if (major == requiredMajor || requiredMajor == 17 && major > 17 && major < 21) {
				return new JavaRuntime(candidate, major);
			}
		}
		throw new IOException("Java " + requiredMajor + " is required but was not found. Install it through CurseForge's Minecraft settings.");
	}

	public static JavaRuntime locateAtLeast(int minimumMajor, int maximumMajor) throws IOException {
		JavaRuntime best = null;
		for (Path candidate : candidates()) {
			int major = probe(candidate);
			if (major < minimumMajor || major > maximumMajor) continue;
			if (best == null || major < best.major()) best = new JavaRuntime(candidate, major);
		}
		if (best != null) return best;
		throw new IOException("Java " + minimumMajor + (maximumMajor == minimumMajor ? "" : " through " + maximumMajor)
			+ " is required but was not found. Install it through CurseForge's Minecraft settings.");
	}

	private static List<Path> candidates() {
		Set<Path> paths = new LinkedHashSet<>();
		String home = System.getProperty("java.home", "");
		if (!home.isBlank()) {
			paths.add(executable(Path.of(home)));
		}
		String javaHome = System.getenv("JAVA_HOME");
		if (javaHome != null && !javaHome.isBlank()) {
			paths.add(executable(Path.of(javaHome)));
		}
		Path curseRuntimes = Path.of(System.getProperty("user.home", "."), "curseforge", "minecraft", "Install", "java");
		if (Files.isDirectory(curseRuntimes)) {
			try (var children = Files.list(curseRuntimes)) {
				children.filter(Files::isDirectory).sorted().forEach(path -> paths.add(executable(path)));
			} catch (IOException ignored) {
			}
		}
		return new ArrayList<>(paths);
	}

	private static Path executable(Path home) {
		return home.resolve("bin").resolve(isWindows() ? "java.exe" : "java").toAbsolutePath().normalize();
	}

	private static int probe(Path executable) {
		if (!Files.isRegularFile(executable)) {
			return -1;
		}
		try {
			Process process = new ProcessBuilder(executable.toString(), "-version").redirectErrorStream(true).start();
			String first;
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
				first = reader.readLine();
			}
			process.waitFor(5, TimeUnit.SECONDS);
			Matcher matcher = VERSION.matcher(first == null ? "" : first);
			return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
		} catch (InterruptedException ignored) {
			Thread.currentThread().interrupt();
			return -1;
		} catch (IOException | NumberFormatException ignored) {
			return -1;
		}
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}

	public record JavaRuntime(Path executable, int major) {
	}
}
