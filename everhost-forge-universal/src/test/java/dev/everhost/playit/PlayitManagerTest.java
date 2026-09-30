package dev.everhost.playit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;

final class PlayitManagerTest {
	@Test
	void recognizesExpiredAndInvalidClaimCodes() {
		assertTrue(PlayitManager.expiredClaim(new IOException("CodeExpired")));
		assertTrue(PlayitManager.expiredClaim(new IOException("invalid_code")));
		assertTrue(PlayitManager.expiredClaim(new IOException("Playit rejected the request: {error=InvalidCode}")));
		assertFalse(PlayitManager.expiredClaim(new IOException("Playit is rate limiting setup")));
		assertFalse(PlayitManager.expiredClaim(new IOException()));
	}

	@Test
	void recognizesRevokedCredentialsAndBuildsPlatformSocketPaths() {
		assertTrue(PlayitManager.invalidAgentKey(new IOException("Playit request returned HTTP 401")));
		assertTrue(PlayitManager.invalidAgentKey(new IOException("InvalidAgentKey")));
		assertFalse(PlayitManager.invalidAgentKey(new IOException("Playit request returned HTTP 500")));
		assertTrue(PlayitManager.readOnlyAgent(new IOException("NotAllowedWithReadOnly")));
		assertTrue(PlayitManager.readOnlyAgent(new IOException("The agent is read-only")));
		assertFalse(PlayitManager.readOnlyAgent(new IOException("Playit request returned HTTP 500")));
		assertTrue(PlayitManager.ipcSocketPath(Path.of("profile"), "Windows 11").startsWith("\\\\.\\pipe\\everhost-playitd-"));
		assertTrue(PlayitManager.ipcSocketPath(Path.of("profile"), "Linux").endsWith("playitd.sock"));
		assertTrue(PlayitManager.profileSlot("C:\\Profiles\\Fabric").startsWith("profile."));
		assertFalse(PlayitManager.profileSlot("C:\\Profiles\\Fabric")
			.equals(PlayitManager.profileSlot("C:\\Profiles\\Forge")));
	}

	@Test
	void keepsSeparateTunnelReservationsForEachProfile() {
		Properties config = new Properties();
		config.setProperty("tunnelId", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
		config.setProperty("address", "fabric.example.test");
		config.setProperty("modTunnelId", "cccccccc-cccc-cccc-cccc-cccccccccccc");
		config.setProperty("modAddress", "fabric-mod.example.test:30001");
		PlayitManager.selectProfile(config, "C:\\Profiles\\Fabric");
		PlayitManager.selectProfile(config, "C:\\Profiles\\Forge");
		assertTrue(config.getProperty("tunnelId", "").isBlank());
		config.setProperty("tunnelId", "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
		config.setProperty("address", "forge.example.test");
		PlayitManager.selectProfile(config, "C:\\Profiles\\Fabric");
		assertTrue(config.getProperty("tunnelId").startsWith("aaaaaaaa"));
		assertTrue(config.getProperty("address").startsWith("fabric"));
		assertTrue(config.getProperty("modTunnelId").startsWith("cccccccc"));
		assertTrue(config.getProperty("modAddress").startsWith("fabric-mod"));
		PlayitManager.selectProfile(config, "C:\\Profiles\\Forge");
		assertTrue(config.getProperty("tunnelId").startsWith("bbbbbbbb"));
		assertTrue(config.getProperty("address").startsWith("forge"));
	}

	@Test
	void acceptsOnlyExplicitPlayitTcpAddressesForModDelivery() {
		assertTrue(PlayitManager.deliveryBaseUrl("example.gl.ply.gg:30123").equals("everhost://example.gl.ply.gg:30123"));
		assertTrue(PlayitManager.deliveryBaseUrl("[2001:db8::1]:30123").equals("everhost://[2001:db8::1]:30123"));
		assertTrue(PlayitManager.deliveryBaseUrl("example.gl.ply.gg").isBlank());
		assertTrue(PlayitManager.deliveryBaseUrl("example.test/path:30123").isBlank());
		assertTrue(PlayitManager.routeAddress("route.tun.ply.gg", "213.ip.gl.ply.gg:30123")
			.equals("route.tun.ply.gg:30123"));
	}
}
