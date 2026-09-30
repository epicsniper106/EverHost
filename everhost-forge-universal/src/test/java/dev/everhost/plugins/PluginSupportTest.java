package dev.everhost.plugins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.everhost.plugins.PluginSupport.Decision;
import dev.everhost.plugins.PluginSupport.PluginKind;
import dev.everhost.universal.Models.LoaderType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PluginSupportTest {
	@TempDir
	Path temporary;

	@Test
	void selectsVerifiedBridgeForEverySupportedEverHostBuild() {
		var modern = PluginSupport.bridgeFor(LoaderType.FABRIC, "1.21.11").orElseThrow();
		assertEquals("Cardboard 1.21.11-18", modern.displayName());
		assertEquals(2, modern.artifacts().size());
		assertFalse(modern.replacesServerLauncher());

		var legacy = PluginSupport.bridgeFor(LoaderType.FABRIC, "1.20.1").orElseThrow();
		assertEquals("Banner 1.20.1 build 145", legacy.displayName());
		assertEquals(1, legacy.artifacts().size());
		assertTrue(legacy.replacesServerLauncher());

		var forge = PluginSupport.bridgeFor(LoaderType.FORGE, "1.20.1").orElseThrow();
		assertEquals("Arclight 1.20.1-1.0.6", forge.displayName());
		assertTrue(forge.replacesServerLauncher());
		assertTrue(PluginSupport.bridgeFor(LoaderType.FORGE, "1.21.11").isEmpty());
	}

	@Test
	void classifiesGameServerProxyModAndUnknownJars() throws Exception {
		Path ready = jar("ready.jar", Map.of("plugin.yml", "name: Ready\nversion: 2.0\napi-version: '1.20'\n"));
		Path paper = jar("paper.jar", Map.of("paper-plugin.yml", "name: PaperOnly\nversion: 1\napi-version: '1.21'\n"));
		Path velocity = jar("velocity.jar", Map.of("velocity-plugin.json", "{\"name\":\"Proxy\",\"version\":\"4\"}"));
		Path bungee = jar("bungee.jar", Map.of("bungee.yml", "name: WaterfallThing\nversion: 1\n"));
		Path sponge = jar("sponge.jar", Map.of("META-INF/sponge_plugins.json", "{}"));
		Path fabric = jar("fabric.jar", Map.of("fabric.mod.json", "{\"id\":\"example\"}"));
		Path forge = jar("forge.jar", Map.of("META-INF/mods.toml", "modId=\"example\""));
		Path unknown = jar("unknown.jar", Map.of("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n"));

		assertPlugin(ready, PluginKind.BUKKIT, Decision.READY);
		assertPlugin(paper, PluginKind.PAPER, Decision.WARNING);
		assertPlugin(velocity, PluginKind.VELOCITY, Decision.BLOCKED);
		assertPlugin(bungee, PluginKind.BUNGEECORD, Decision.BLOCKED);
		assertPlugin(sponge, PluginKind.SPONGE, Decision.BLOCKED);
		assertPlugin(fabric, PluginKind.FABRIC_MOD, Decision.BLOCKED);
		assertPlugin(forge, PluginKind.FORGE_MOD, Decision.BLOCKED);
		assertPlugin(unknown, PluginKind.UNKNOWN, Decision.BLOCKED);
	}

	@Test
	void blocksNewerApiAndDuplicatePluginNamesButAllowsLegacyVersions() throws Exception {
		jar("old.jar", Map.of("plugin.yml", "name: OldEnough\nversion: 1\napi-version: 1.20\n"));
		jar("future.jar", Map.of("plugin.yml", "name: Future\nversion: 1\napi-version: 1.22\n"));
		jar("duplicate-a.jar", Map.of("plugin.yml", "name: Duplicate\nversion: 1\napi-version: 1.20\n"));
		jar("duplicate-b.jar", Map.of("plugin.yml", "name: duplicate\nversion: 2\napi-version: 1.20\n"));

		var scan = PluginSupport.scan(temporary, "1.21.11");
		assertEquals(1, scan.readyCount());
		assertEquals(0, scan.warningCount());
		assertEquals(3, scan.blockedCount());
		assertEquals(Decision.BLOCKED, scan.plugins().stream().filter(plugin -> plugin.name().equals("Future")).findFirst().orElseThrow().decision());
		assertEquals(2, scan.plugins().stream().filter(plugin -> plugin.name().equalsIgnoreCase("Duplicate"))
			.filter(plugin -> plugin.decision() == Decision.BLOCKED).count());
	}

	@Test
	void marksLegacyPluginWithoutApiVersionAsRunnableWarning() throws Exception {
		Path legacy = jar("legacy.jar", Map.of("plugin.yml", "name: Legacy\nversion: 1.0\nmain: sample.Legacy\n"));
		var result = PluginSupport.inspect(legacy, "1.20.1");
		assertEquals(Decision.WARNING, result.decision());
		assertTrue(result.runnable());
		assertTrue(result.detail().contains("compatibility mode"));
	}

	@Test
	void blocksMissingHardDependenciesAndWarnsAboutMissingOptionalDependencies() throws Exception {
		jar("needs-library.jar", Map.of("plugin.yml",
			"name: NeedsLibrary\nversion: 1\napi-version: 1.21\ndepend: [ProtocolLib]\nsoftdepend:\n  - Vault\n"));
		var missing = PluginSupport.scan(temporary, "1.21.11");
		assertEquals(Decision.BLOCKED, missing.plugins().get(0).decision());
		assertTrue(missing.plugins().get(0).detail().contains("ProtocolLib"));

		jar("protocol.jar", Map.of("plugin.yml", "name: ProtocolLib\nversion: 1\napi-version: 1.21\n"));
		var resolved = PluginSupport.scan(temporary, "1.21.11");
		var plugin = resolved.plugins().stream().filter(value -> value.name().equals("NeedsLibrary")).findFirst().orElseThrow();
		assertEquals(Decision.WARNING, plugin.decision());
		assertTrue(plugin.detail().contains("Vault"));
	}

	@Test
	void readsMainClassJavaVersionAndHonorsDisabledAndClassConflicts() throws Exception {
		Path modern = jarBytes("modern.jar", Map.of(
			"plugin.yml", "name: Modern\nversion: 1\napi-version: 1.21\nmain: sample.Modern\n".getBytes(StandardCharsets.UTF_8),
			"sample/Modern.class", new byte[]{(byte)0xCA, (byte)0xFE, (byte)0xBA, (byte)0xBE, 0, 0, 0, 69}
		));
		assertEquals(25, PluginSupport.inspect(modern, "1.21.11").requiredJava());
		assertEquals(Decision.BLOCKED,
			PluginSupport.scan(temporary, "1.21.11", Set.of(), 21, java.util.List.of()).plugins().get(0).decision());
		assertEquals(Decision.DISABLED,
			PluginSupport.scan(temporary, "1.21.11", Set.of("modern.jar"), 25, java.util.List.of()).plugins().get(0).decision());

		Path serverMod = jarBytes("server-mod.zip", Map.of("sample/Modern.class", new byte[]{1, 2, 3}));
		assertEquals(Decision.BLOCKED,
			PluginSupport.scan(temporary, "1.21.11", Set.of(), 25, java.util.List.of(serverMod)).plugins().get(0).decision());
	}

	private void assertPlugin(Path jar, PluginKind kind, Decision decision) {
		var plugin = PluginSupport.inspect(jar, "1.21.11");
		assertEquals(kind, plugin.kind());
		assertEquals(decision, plugin.decision());
	}

	private Path jar(String name, Map<String, String> entries) throws IOException {
		Map<String, byte[]> bytes = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : entries.entrySet()) bytes.put(entry.getKey(), entry.getValue().getBytes(StandardCharsets.UTF_8));
		return jarBytes(name, bytes);
	}

	private Path jarBytes(String name, Map<String, byte[]> entries) throws IOException {
		Path target = temporary.resolve(name);
		Map<String, byte[]> ordered = new LinkedHashMap<>(entries);
		try (ZipOutputStream zip = new ZipOutputStream(java.nio.file.Files.newOutputStream(target))) {
			for (Map.Entry<String, byte[]> entry : ordered.entrySet()) {
				zip.putNextEntry(new ZipEntry(entry.getKey()));
				zip.write(entry.getValue());
				zip.closeEntry();
			}
		}
		return target;
	}
}
