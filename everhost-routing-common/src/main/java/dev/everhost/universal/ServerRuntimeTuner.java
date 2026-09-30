package dev.everhost.universal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** Applies EverHost's non-optional compatibility defaults to a managed server runtime. */
public final class ServerRuntimeTuner {
	public static final double UNRESTRICTED_MOVEMENT_LIMIT = 1_000_000_000.0D;

	private ServerRuntimeTuner() {}

	public static void configureUnrestrictedMovement(Path runtime) throws IOException {
		Files.createDirectories(runtime);
		Path config = runtime.resolve("spigot.yml");
		List<String> lines = Files.isRegularFile(config)
			? new ArrayList<>(Files.readAllLines(config, StandardCharsets.UTF_8))
			: new ArrayList<>();

		int settingsStart = topLevelSection(lines, "settings:");
		if (settingsStart < 0) {
			if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) lines.add("");
			settingsStart = lines.size();
			lines.add("settings:");
		}
		int settingsEnd = sectionEnd(lines, settingsStart);
		settingsEnd = putSetting(lines, settingsStart, settingsEnd, "moved-wrongly-threshold");
		putSetting(lines, settingsStart, settingsEnd, "moved-too-quickly-multiplier");

		String newline = Files.isRegularFile(config) && Files.readString(config, StandardCharsets.UTF_8).contains("\r\n")
			? "\r\n" : System.lineSeparator();
		String rendered = String.join(newline, lines) + newline;
		Path temporary = config.resolveSibling(config.getFileName() + ".tmp");
		Files.writeString(temporary, rendered, StandardCharsets.UTF_8);
		try {
			Files.move(temporary, config, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException exception) {
			Files.move(temporary, config, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static int topLevelSection(List<String> lines, String section) {
		for (int index = 0; index < lines.size(); index++) {
			String line = lines.get(index);
			if (!line.isEmpty() && !Character.isWhitespace(line.charAt(0)) && line.trim().equals(section)) return index;
		}
		return -1;
	}

	private static int sectionEnd(List<String> lines, int sectionStart) {
		for (int index = sectionStart + 1; index < lines.size(); index++) {
			String line = lines.get(index);
			String trimmed = line.trim();
			if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
			if (!Character.isWhitespace(line.charAt(0))) return index;
		}
		return lines.size();
	}

	private static int putSetting(List<String> lines, int sectionStart, int sectionEnd, String key) {
		String replacement = "  " + key + ": " + UNRESTRICTED_MOVEMENT_LIMIT;
		for (int index = sectionStart + 1; index < sectionEnd; index++) {
			if (lines.get(index).trim().startsWith(key + ":")) {
				lines.set(index, replacement);
				return sectionEnd;
			}
		}
		lines.add(sectionStart + 1, replacement);
		return sectionEnd + 1;
	}
}
