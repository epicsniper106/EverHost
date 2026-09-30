package dev.everhost.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ModDistributionServerTest {
	@TempDir Path temporary;

	@AfterEach
	void stop() {
		ModDistributionServer.stopForTests();
	}

	@Test
	void requiresTokenAndServesOnlyTheManifestHash() throws Exception {
		Path mods = Files.createDirectories(temporary.resolve("mods"));
		Path jar = mods.resolve("private.jar");
		writeJar(jar, "one");
		byte[] expected = Files.readAllBytes(jar);
		String hash = ModInstallCoordinator.sha256(jar);
		writeManifest(temporary, hash, expected.length);
		String token = "e".repeat(64);
		Properties config = new Properties();
		config.setProperty("format", "EVERHOST_MOD_DISTRIBUTION_V1");
		config.setProperty("port", "0");
		config.setProperty("token", token);
		try (var writer = Files.newBufferedWriter(temporary.resolve("everhost-mod-distribution.properties"), StandardCharsets.UTF_8)) {
			config.store(writer, "test");
		}

		ModDistributionServer.start(temporary);
		String uri = "everhost://127.0.0.1:" + ModDistributionServer.boundPortForTests() + "/mods/" + hash;
		var artifact = new ModInstallWorker.Artifact("Private Example", java.util.Set.of("private_example"),
			"private.jar", 0L, 0L, "", hash, "", expected.length, uri, token);
		Path downloaded = temporary.resolve("downloaded.jar");
		ModInstallWorker.downloadForTests(downloaded, artifact);
		assertEquals(hash, ModInstallCoordinator.sha256(downloaded));

		var unauthorized = new ModInstallWorker.Artifact("Private Example", java.util.Set.of("private_example"),
			"private.jar", 0L, 0L, "", hash, "", expected.length, uri, "f".repeat(64));
		assertThrows(Exception.class, () -> ModInstallWorker.downloadForTests(temporary.resolve("unauthorized.jar"), unauthorized));

		writeJar(jar, "two");
		assertThrows(Exception.class, () -> ModInstallWorker.downloadForTests(temporary.resolve("changed.jar"), artifact));
	}

	private static void writeManifest(Path runtime, String hash, long size) throws Exception {
		Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
		String line = String.join("\t", encode(encoder, "Private Example"), encode(encoder, "private_example"),
			encode(encoder, "1.0"), encode(encoder, "private.jar"), "0", "0", "", encode(encoder, hash), "",
			Long.toString(size));
		Files.writeString(runtime.resolve("everhost-required-client-mods.dat"),
			"EVERHOST_REQUIRED_MODS_V3\n" + line + "\n", StandardCharsets.UTF_8);
	}

	private static String encode(Base64.Encoder encoder, String value) {
		return encoder.encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}

	private static void writeJar(Path target, String marker) throws Exception {
		try (OutputStream output = Files.newOutputStream(target); JarOutputStream jar = new JarOutputStream(output)) {
			jar.putNextEntry(new JarEntry("fabric.mod.json"));
			jar.write("{\"schemaVersion\":1,\"id\":\"private_example\",\"version\":\"1\"}".getBytes(StandardCharsets.UTF_8));
			jar.closeEntry();
			jar.putNextEntry(new JarEntry("marker.txt"));
			jar.write(marker.getBytes(StandardCharsets.UTF_8));
			jar.closeEntry();
		}
	}
}
