package dev.everhost;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

record LagGuardSettings(
	boolean enabled,
	boolean notices,
	boolean joinProtection,
	int normalView,
	int normalSimulation,
	int minimumView,
	int minimumSimulation,
	int warningMspt,
	int warningPingMs,
	int recoverySeconds
) {
	static LagGuardSettings load(Path path) {
		Properties values = new Properties();
		if (Files.isRegularFile(path)) {
			try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				values.load(reader);
			} catch (IOException ignored) {
			}
		}
		boolean enabled = bool(values, "enabled", false);
		String mode = values.getProperty("networkMode", "auto").strip().toLowerCase(Locale.ROOT);
		int mspt = integer(values, "warningMspt", 65, 50, 250);
		int ping = integer(values, "warningPingMs", 350, 100, 2000);
		if (mode.equals("slow_wifi")) {
			mspt = Math.min(mspt, 60);
			ping = Math.min(ping, 250);
		}
		return new LagGuardSettings(
			enabled,
			bool(values, "notices", true),
			bool(values, "joinProtection", true),
			integer(values, "normalView", 32, 2, 32),
			integer(values, "normalSimulation", 12, 2, 32),
			integer(values, "minimumView", 4, 2, 32),
			integer(values, "minimumSimulation", 4, 2, 32),
			mspt,
			ping,
			integer(values, "recoverySeconds", 45, 10, 180)
		);
	}

	private static int integer(Properties values, String key, int fallback, int minimum, int maximum) {
		try {
			return Math.max(minimum, Math.min(maximum, Integer.parseInt(values.getProperty(key, Integer.toString(fallback)))));
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	private static boolean bool(Properties values, String key, boolean fallback) {
		return Boolean.parseBoolean(values.getProperty(key, Boolean.toString(fallback)));
	}
}
