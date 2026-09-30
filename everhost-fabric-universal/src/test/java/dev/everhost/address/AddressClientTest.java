package dev.everhost.address;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;

final class AddressClientTest {
	@Test
	void playitAddressIsAdvertisedOnlyWhileTunnelIsOnline() throws Exception {
		Path root = Files.createTempDirectory("everhost-address-test-");
		try {
			Properties config = new Properties();
			config.setProperty("enabled", "true");
			config.setProperty("address", "steady.playit.gg:25565");
			AddressFiles.write(root.resolve("playit.properties"), config, false);
			assertEquals("temporary.e4mc.link", AddressClient.shareAddress(root, "temporary.e4mc.link"));
			assertEquals("temporary.e4mc.link", AddressClient.shareAddress(root, "temporary.e4mc.link", "e4mc"));
			assertEquals("", AddressClient.shareAddress(root, "temporary.e4mc.link", "playit"));
			Properties status = new Properties();
			status.setProperty("state", "ONLINE");
			status.setProperty("agentRunning", "true");
			status.setProperty("address", "steady.playit.gg:25565");
			AddressFiles.write(root.resolve("playit-status.properties"), status, false);
			assertEquals("steady.playit.gg:25565", AddressClient.shareAddress(root, "temporary.e4mc.link"));
			assertEquals("steady.playit.gg:25565", AddressClient.shareAddress(root, "temporary.e4mc.link", "playit"));
			status.setProperty("agentRunning", "false");
			AddressFiles.write(root.resolve("playit-status.properties"), status, false);
			assertEquals("temporary.e4mc.link", AddressClient.shareAddress(root, "temporary.e4mc.link"));
			assertEquals("", AddressClient.shareAddress(root, "temporary.e4mc.link", "playit"));
			config.setProperty("enabled", "false");
			AddressFiles.write(root.resolve("playit.properties"), config, false);
			assertEquals("temporary.e4mc.link", AddressClient.shareAddress(root, "temporary.e4mc.link"));
		} finally {
			for (Path file : Files.list(root).toList()) Files.deleteIfExists(file);
			Files.deleteIfExists(root);
		}
	}
}
