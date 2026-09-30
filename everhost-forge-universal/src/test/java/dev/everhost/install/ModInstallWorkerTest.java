package dev.everhost.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.everhost.universal.RequiredModsManifest.Requirement;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ModInstallWorkerTest {
	@TempDir Path temporary;

	@Test
	void exactHashDistinguishesOutdatedJarWithSameModId() {
		Requirement requirement = new Requirement("Example", Set.of("example"), "1.0", "example.jar",
			1L, 2L, "https://edge.forgecdn.net/files/0/2/example.jar", "a".repeat(64), "", 1L);
		assertEquals(1, ModInstallCoordinator.assess(List.of(requirement), Set.of("example"), Set.of("b".repeat(64))).size());
		assertTrue(ModInstallCoordinator.hasPendingOffer());
		assertTrue(ModInstallCoordinator.assess(List.of(requirement), Set.of("example"), Set.of("a".repeat(64))).isEmpty());
		assertFalse(ModInstallCoordinator.hasPendingOffer());
	}

	@Test
	void transactionArchivesOldVersionAndInstallsExactJar() throws Exception {
		Path mods = Files.createDirectories(temporary.resolve("mods"));
		Path backups = temporary.resolve("backups");
		Path old = mods.resolve("example-old.jar");
		Path staged = temporary.resolve("example-new.jar");
		writeFabricJar(old, "example", "old");
		writeFabricJar(staged, "example", "new");
		String hash = ModInstallCoordinator.sha256(staged);
		var artifact = new ModInstallWorker.Artifact("Example", Set.of("example"), "example.jar", 1L, 2L,
			"https://edge.forgecdn.net/files/0/2/example.jar", hash, "", Files.size(staged), "", "");

		var summary = ModInstallWorker.applyPrepared(mods, backups,
			List.of(new ModInstallWorker.PreparedArtifact(artifact, staged, false)));

		assertEquals(1, summary.installed());
		assertEquals(1, summary.archived());
		assertFalse(Files.exists(old));
		assertEquals(hash, ModInstallCoordinator.sha256(mods.resolve("example.jar")));
		try (var files = Files.list(backups)) {
			assertEquals(1L, files.count());
		}
	}

	@Test
	void nextLaunchRechecksInstalledHashBeforeSuccessNotification() throws Exception {
		Path game = temporary.resolve("profile");
		Path mods = Files.createDirectories(game.resolve("mods"));
		Path installed = mods.resolve("example.jar");
		writeFabricJar(installed, "example", "exact");
		String hash = ModInstallCoordinator.sha256(installed);
		Path root = Files.createDirectories(game.resolve("everhost").resolve("mod-installer"));
		Properties values = new Properties();
		values.setProperty("status", "success");
		values.setProperty("count", "1");
		values.setProperty("mod.0.name", "Example");
		values.setProperty("mod.0.file", "example.jar");
		values.setProperty("mod.0.sha256", hash);
		try (var writer = Files.newBufferedWriter(root.resolve("result.properties"), StandardCharsets.UTF_8)) {
			values.store(writer, "test");
		}

		var result = ModInstallCoordinator.consumeResult(game).orElseThrow();
		assertTrue(result.success());
		assertEquals("All mods have been successfully installed.", result.message());
		assertTrue(Files.isRegularFile(root.resolve("last-result.properties")));
	}

	@Test
	void downloadsPrivateJarDirectlyWithBearerToken() throws Exception {
		Path source = temporary.resolve("private.jar");
		writeFabricJar(source, "private_example", "private");
		byte[] bytes = Files.readAllBytes(source);
		String hash = ModInstallCoordinator.sha256(source);
		String token = "d".repeat(64);
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/mods/" + hash, exchange -> {
			if (!("Bearer " + token).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
				exchange.sendResponseHeaders(401, -1);
			} else {
				exchange.getResponseHeaders().set("X-EverHost-SHA256", hash);
				exchange.sendResponseHeaders(200, bytes.length);
				exchange.getResponseBody().write(bytes);
			}
			exchange.close();
		});
		server.start();
		try {
			String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/mods/" + hash;
			var artifact = new ModInstallWorker.Artifact("Private Example", Set.of("private_example"),
				"private.jar", 0L, 0L, "", hash, "", bytes.length, url, token);
			Path target = temporary.resolve("downloaded.jar");
			assertTrue(ModInstallWorker.downloadForTests(target, artifact).contains("hosting EverHost server"));
			assertEquals(hash, ModInstallCoordinator.sha256(target));
		} finally {
			server.stop(0);
		}
	}

	private static void writeFabricJar(Path target, String id, String marker) throws Exception {
		try (OutputStream output = Files.newOutputStream(target); JarOutputStream jar = new JarOutputStream(output)) {
			jar.putNextEntry(new JarEntry("fabric.mod.json"));
			jar.write(("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1\"}")
				.getBytes(StandardCharsets.UTF_8));
			jar.closeEntry();
			jar.putNextEntry(new JarEntry("marker.txt"));
			jar.write(marker.getBytes(StandardCharsets.UTF_8));
			jar.closeEntry();
		}
	}
}
