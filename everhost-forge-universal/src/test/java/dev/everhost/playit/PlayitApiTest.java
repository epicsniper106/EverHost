package dev.everhost.playit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class PlayitApiTest {
	private HttpServer server;
	private URI base;

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
		server.start();
	}

	@AfterEach
	void stopServer() {
		server.stop(0);
	}

	@Test
	void claimAndAuthenticatedTunnelFlowMatchesAgentApi() throws Exception {
		AtomicReference<String> authorization = new AtomicReference<>("");
		AtomicReference<String> claimBody = new AtomicReference<>("");
		AtomicReference<String> createBody = new AtomicReference<>("");
		AtomicReference<String> configBody = new AtomicReference<>("");
		server.createContext("/claim/setup", exchange -> {
			claimBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			respond(exchange, "{\"status\":\"success\",\"data\":\"WaitingForUser\"}");
		});
		server.createContext("/claim/exchange", exchange -> respond(exchange,
			"{\"status\":\"success\",\"data\":{\"secret_key\":\"" + "a".repeat(64) + "\"}}"));
		server.createContext("/v1/agents/rundata", exchange -> {
			authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
			respond(exchange, "{\"status\":\"success\",\"data\":{\"agent_id\":\"11111111-1111-1111-1111-111111111111\",\"permissions\":{\"account_status\":\"verified\"}}}");
		});
		server.createContext("/v1/tunnels/list", exchange -> respond(exchange,
			"{\"status\":\"success\",\"data\":{\"tunnels\":[{\"id\":\"22222222-2222-2222-2222-222222222222\",\"name\":\"EverHost - Test\",\"user_enabled\":true,\"tunnel_type\":\"minecraft-java\",\"origin\":{\"type\":\"agent\",\"details\":{\"config_data\":{\"fields\":[{\"name\":\"local_ip\",\"value\":\"127.0.0.1\"},{\"name\":\"local_port\",\"value\":\"25570\"}]}}},\"connect_addresses\":[{\"type\":\"auto\",\"value\":{\"address\":\"steady.playit.gg\"}},{\"type\":\"auto\",\"value\":{\"address\":\"18.ip.gl.ply.gg:25565\"}}]}]}}"));
		server.createContext("/v1/tunnels/create", exchange -> {
			createBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			respond(exchange, "{\"status\":\"success\",\"data\":{\"id\":\"33333333-3333-3333-3333-333333333333\"}}");
		});
		server.createContext("/v1/tunnels/config", exchange -> {
			configBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			respond(exchange, "{\"status\":\"success\",\"data\":null}");
		});

		PlayitApi open = new PlayitApi(base, "");
		assertEquals(PlayitApi.ClaimState.WAITINGFORUSER, open.claimSetup("0123456789"));
		assertTrue(claimBody.get().contains("\"agent_type\":\"self-managed\""));
		String secret = open.claimExchange("0123456789");
		PlayitApi api = new PlayitApi(base, secret);
		assertEquals("verified", api.agentInfo().accountStatus());
		assertEquals("Agent-Key " + secret, authorization.get());
		assertEquals("steady.playit.gg", api.tunnels().get(0).address());
		assertEquals("18.ip.gl.ply.gg:25565", api.tunnels().get(0).directAddress());
		assertTrue(api.tunnels().get(0).enabled());
		assertEquals("127.0.0.1", api.tunnels().get(0).localIp());
		assertEquals(25570, api.tunnels().get(0).localPort());
		assertEquals("33333333-3333-3333-3333-333333333333",
			api.createMinecraftTunnel("11111111-1111-1111-1111-111111111111", "EverHost - Test", 25570));
		assertTrue(createBody.get().contains("\"protocol\":{"));
		assertTrue(createBody.get().contains("\"details\":\"minecraft-java\""));
		assertTrue(createBody.get().contains("\"endpoint\":{"));
		assertTrue(createBody.get().contains("\"region\":\"global\",\"port\":null"));
		assertTrue(createBody.get().contains("\"agent_id\":\"11111111-1111-1111-1111-111111111111\""));
		assertTrue(createBody.get().contains("\"name\":\"local_port\""));
		assertTrue(createBody.get().contains("\"value\":\"25570\""));
		assertEquals("33333333-3333-3333-3333-333333333333",
			api.createCustomTcpTunnel("11111111-1111-1111-1111-111111111111", "EverHost - Test - Mod Delivery", 25571));
		assertTrue(createBody.get().contains("\"type\":\"custom-tcp\""));
		assertTrue(createBody.get().contains("\"details\":1"));
		assertTrue(createBody.get().contains("\"value\":\"25571\""));
		api.configureMinecraftTunnel("22222222-2222-2222-2222-222222222222",
			"11111111-1111-1111-1111-111111111111", 25571);
		assertTrue(configBody.get().contains("\"tunnel_id\":\"22222222-2222-2222-2222-222222222222\""));
		assertTrue(configBody.get().contains("\"name\":\"local_port\""));
		assertTrue(configBody.get().contains("\"value\":\"25571\""));
	}

	@Test
	void preservesCurrentPlayitErrorDetails() {
		server.createContext("/v1/tunnels/create", exchange -> respond(exchange, 401,
			"{\"status\":\"error\",\"data\":{\"type\":\"auth\",\"message\":\"NotAllowedWithReadOnly\"}}"));
		IOException error = assertThrows(IOException.class, () -> new PlayitApi(base, "a".repeat(64))
			.createMinecraftTunnel("11111111-1111-1111-1111-111111111111", "EverHost - Test", 25570));
		assertTrue(error.getMessage().contains("NotAllowedWithReadOnly"));
	}

	private static void respond(HttpExchange exchange, String body) throws IOException {
		respond(exchange, 200, body);
	}

	private static void respond(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getRequestBody().readAllBytes();
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}
}
