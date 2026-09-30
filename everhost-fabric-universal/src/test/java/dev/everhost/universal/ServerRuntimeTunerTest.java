package dev.everhost.universal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ServerRuntimeTunerTest {
	@TempDir
	Path temporary;

	@Test
	void createsUnrestrictedMovementDefaultsForANewRuntime() throws Exception {
		ServerRuntimeTuner.configureUnrestrictedMovement(temporary);

		String config = Files.readString(temporary.resolve("spigot.yml"), StandardCharsets.UTF_8);
		assertTrue(config.contains("settings:"));
		assertTrue(config.contains("moved-wrongly-threshold: 1.0E9"));
		assertTrue(config.contains("moved-too-quickly-multiplier: 1.0E9"));
	}

	@Test
	void replacesMovementLimitsAndPreservesOtherSettings() throws Exception {
		Path config = temporary.resolve("spigot.yml");
		Files.writeString(config, "settings:\n  debug: false\n  moved-wrongly-threshold: 0.0625\n"
			+ "  moved-too-quickly-multiplier: 10.0\nworld-settings:\n  default:\n    verbose: true\n", StandardCharsets.UTF_8);

		ServerRuntimeTuner.configureUnrestrictedMovement(temporary);
		ServerRuntimeTuner.configureUnrestrictedMovement(temporary);

		String updated = Files.readString(config, StandardCharsets.UTF_8);
		assertTrue(updated.contains("  debug: false"));
		assertTrue(updated.contains("world-settings:"));
		assertTrue(updated.lines().anyMatch(line -> line.equals("  default:")));
		assertEquals(1, count(updated, "moved-wrongly-threshold:"));
		assertEquals(1, count(updated, "moved-too-quickly-multiplier:"));
	}

	private static int count(String value, String token) {
		return value.split(java.util.regex.Pattern.quote(token), -1).length - 1;
	}
}
