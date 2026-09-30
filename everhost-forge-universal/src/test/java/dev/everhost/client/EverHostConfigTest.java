package dev.everhost.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EverHostConfigTest {
	@TempDir
	Path root;

	@Test
	void migratesExistingPlayitSetupAndPersistsExplicitMode() throws Exception {
		Properties playit = new Properties();
		playit.setProperty("enabled", "true");
		try (var writer = Files.newBufferedWriter(root.resolve("playit.properties"))) {
			playit.store(writer, "test");
		}
		Path configFile = root.resolve("config.properties");
		EverHostConfig migrated = EverHostConfig.load(configFile);
		assertEquals(EverHostConfig.AddressMode.PLAYIT, migrated.addressMode);

		migrated.addressMode = EverHostConfig.AddressMode.E4MC;
		migrated.save(configFile);
		assertEquals(EverHostConfig.AddressMode.E4MC, EverHostConfig.load(configFile).addressMode);
	}

	@Test
	void legacyPluginSyncDoesNotSilentlyEnableHybridHosting() throws Exception {
		Properties legacy = new Properties();
		legacy.setProperty("syncPlugins", "true");
		Path configFile = root.resolve("config.properties");
		try (var writer = Files.newBufferedWriter(configFile)) {
			legacy.store(writer, "legacy EverHost configuration");
		}

		assertEquals(EverHostConfig.PluginMode.OFF, EverHostConfig.load(configFile).pluginMode);
	}

	@Test
	void automaticPluginModePersists() throws Exception {
		Path configFile = root.resolve("config.properties");
		EverHostConfig config = EverHostConfig.load(configFile);
		config.pluginMode = EverHostConfig.PluginMode.AUTO;
		config.pluginStates.put("Example Plugin.jar", EverHostConfig.PluginState.DISABLED);
		config.save(configFile);

		assertEquals(EverHostConfig.PluginMode.AUTO, EverHostConfig.load(configFile).pluginMode);
		assertEquals(EverHostConfig.PluginState.DISABLED, EverHostConfig.load(configFile).pluginStates.get("Example Plugin.jar"));
	}

	@Test
	void performanceResetIsScopedAndNewHostingControlsPersist() throws Exception {
		Path configFile = root.resolve("config.properties");
		EverHostConfig config = new EverHostConfig();
		config.profileName = "Keep this profile";
		config.dhThreads = 3;
		config.lagWarnMspt = 200;
		config.shutdownCountdownSeconds = 7;
		config.spawnMonsters = false;
		config.tutorialCompleted = true;
		config.resetPerformanceDefaults();

		assertEquals("Keep this profile", config.profileName);
		assertEquals(16, config.dhThreads);
		assertEquals(65, config.lagWarnMspt);
		config.save(configFile);

		EverHostConfig loaded = EverHostConfig.load(configFile);
		assertEquals(7, loaded.shutdownCountdownSeconds);
		assertEquals(false, loaded.spawnMonsters);
		assertTrue(loaded.tutorialCompleted);
	}
}
