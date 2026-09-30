package dev.everhost.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EverHostUpdaterTest {
	private static final EverHostUpdater.BuildInfo BUILD = new EverHostUpdater.BuildInfo(
		"2.2.0-beta.7", "fabric", "1.21.11", "owner/EverHost", "EverHost.2.2.Fabric.1.21.11.Full.jar"
	);

	@Test
	void selectsNewestCompatibleReleaseIncludingPrereleases() throws Exception {
		String json = """
			[
			  {"tag_name":"v2.2.0-beta.8","draft":false,"assets":[
			    {"name":"EverHost.2.2.Fabric.1.21.11.Full.jar","browser_download_url":"https://github.com/owner/EverHost/releases/download/v2.2.0-beta.8/fabric.jar"},
			    {"name":"SHA256SUMS.txt","browser_download_url":"https://github.com/owner/EverHost/releases/download/v2.2.0-beta.8/SHA256SUMS.txt"}
			  ]},
			  {"tag_name":"v2.2.0-beta.9","draft":true,"assets":[]},
			  {"tag_name":"v2.2.0-beta.7","draft":false,"assets":[]}
			]
			""";

		assertEquals("2.2.0-beta.8", EverHostUpdater.selectCandidate(json, BUILD).version());
	}

	@Test
	void ignoresNewReleaseWithoutTheMatchingBuild() throws Exception {
		String json = """
			[{"tag_name":"v2.2.0-beta.8","draft":false,"assets":[
			  {"name":"EverHost.2.2.Forge.1.20.1.jar","browser_download_url":"https://github.com/owner/EverHost/releases/download/v2.2.0-beta.8/forge.jar"},
			  {"name":"SHA256SUMS.txt","browser_download_url":"https://github.com/owner/EverHost/releases/download/v2.2.0-beta.8/SHA256SUMS.txt"}
			]}]
			""";

		assertNull(EverHostUpdater.selectCandidate(json, BUILD));
	}

	@Test
	void readsOnlyTheExactAssetChecksum() throws Exception {
		String expected = "a".repeat(64);
		String sums = "b".repeat(64) + "  other.jar\n" + expected + " *" + BUILD.assetName() + "\n";
		assertEquals(expected, EverHostUpdater.checksumFor(sums, BUILD.assetName()));
	}

	@Test
	void comparesBetaAndStableVersionsNaturally() {
		assertEquals(1, Integer.signum(EverHostUpdater.compareVersions("2.2.0-beta.8", "2.2.0-beta.7")));
		assertEquals(1, Integer.signum(EverHostUpdater.compareVersions("2.2.0", "2.2.0-beta.99")));
		assertEquals(-1, Integer.signum(EverHostUpdater.compareVersions("2.1.9", "2.2.0-beta.1")));
	}

	@Test
	void installerReplacesJarAndPreservesBackup(@TempDir Path root) throws Exception {
		Path installed = root.resolve("EverHost.jar");
		Path staged = root.resolve("EverHost.jar.pending");
		Path backups = root.resolve("backups");
		Path log = root.resolve("installer.log");
		Path marker = root.resolve("pending.properties");
		Path result = root.resolve("result.properties");
		Path source = root.resolve("EverHostUpdateInstaller.java");
		Files.writeString(installed, "old build", StandardCharsets.UTF_8);
		Files.writeString(staged, "new build", StandardCharsets.UTF_8);
		Files.writeString(marker, "pending", StandardCharsets.UTF_8);
		Files.writeString(source, EverHostUpdater.installerSourceForTest(), StandardCharsets.UTF_8);
		String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
			.digest(Files.readAllBytes(staged)));
		Path java = Path.of(System.getProperty("java.home"), "bin",
			System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java");

		Process process = new ProcessBuilder(java.toString(), source.toString(), Long.toString(Long.MAX_VALUE),
			installed.toString(), staged.toString(), backups.toString(), checksum, log.toString(), marker.toString(),
			"2.2.0-beta.8", result.toString(), "false").start();
		assertTrue(process.waitFor(30, TimeUnit.SECONDS));
		assertEquals(0, process.exitValue());
		assertEquals("new build", Files.readString(installed));
		assertFalse(Files.exists(marker));
		Path backup = Files.list(backups).findFirst().orElseThrow();
		assertEquals("old build", Files.readString(backup));
		assertTrue(Files.readString(log).contains("Installed EverHost 2.2.0-beta.8 successfully"));
		assertTrue(Files.readString(result).contains("status=success"));
		assertTrue(Files.readString(result).contains("version=2.2.0-beta.8"));
	}

	@Test
	void installerRejectsAChangedStagedJarAndReportsTheExactProblem(@TempDir Path root) throws Exception {
		Path installed = root.resolve("EverHost.jar");
		Path staged = root.resolve("EverHost.jar.pending");
		Path backups = root.resolve("backups");
		Path log = root.resolve("installer.log");
		Path marker = root.resolve("pending.properties");
		Path result = root.resolve("result.properties");
		Path source = root.resolve("EverHostUpdateInstaller.java");
		Files.writeString(installed, "old build", StandardCharsets.UTF_8);
		Files.writeString(staged, "changed download", StandardCharsets.UTF_8);
		Files.writeString(marker, "pending", StandardCharsets.UTF_8);
		Files.writeString(source, EverHostUpdater.installerSourceForTest(), StandardCharsets.UTF_8);
		String wrongChecksum = "0".repeat(64);
		Path java = Path.of(System.getProperty("java.home"), "bin",
			System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java");

		Process process = new ProcessBuilder(java.toString(), source.toString(), Long.toString(Long.MAX_VALUE),
			installed.toString(), staged.toString(), backups.toString(), wrongChecksum, log.toString(), marker.toString(),
			"2.2.0-beta.8", result.toString(), "false").start();
		assertTrue(process.waitFor(30, TimeUnit.SECONDS));
		assertEquals(0, process.exitValue());
		assertEquals("old build", Files.readString(installed));
		assertFalse(Files.exists(marker));
		assertTrue(Files.readString(result).contains("status=error"));
		assertTrue(Files.readString(result).contains("final checksum check"));
	}
}
