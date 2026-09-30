package dev.everhost.playit;

import dev.everhost.universal.MiniJson;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Small dependency-free client for the agent-key endpoints used by playitd 1.0.10. */
public final class PlayitApi {
	private static final int MAX_RESPONSE = 64 * 1024;
	private final URI base;
	private final String secret;
	private final HttpClient http;

	public PlayitApi(String secret) {
		this(URI.create("https://api.playit.gg"), secret);
	}

	PlayitApi(URI base, String secret) {
		this.base = base;
		this.secret = secret == null ? "" : secret;
		this.http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(12))
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
	}

	public ClaimState claimSetup(String code) throws IOException, InterruptedException {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("code", code);
		body.put("agent_type", "self-managed");
		body.put("version", "playit 1.0.11-preview1");
		return ClaimState.valueOf(text(success(post("/claim/setup", body))).toUpperCase(Locale.ROOT));
	}

	public String claimExchange(String code) throws IOException, InterruptedException {
		Map<String, Object> data = object(success(post("/claim/exchange", Map.of("code", code))));
		String value = text(data.get("secret_key"));
		if (!value.matches("[a-fA-F0-9]{64}")) throw new IOException("Playit returned an invalid agent secret");
		return value;
	}

	public AgentInfo agentInfo() throws IOException, InterruptedException {
		Map<String, Object> data = object(success(post("/v1/agents/rundata", Map.of())));
		Map<String, Object> permissions = object(data.get("permissions"));
		return new AgentInfo(text(data.get("agent_id")), text(permissions.get("account_status")));
	}

	public List<Tunnel> tunnels() throws IOException, InterruptedException {
		Map<String, Object> data = object(success(post("/v1/tunnels/list", Map.of())));
		List<Tunnel> result = new ArrayList<>();
		for (Object item : array(data.get("tunnels"))) {
			Map<String, Object> tunnel = object(item);
			result.add(new Tunnel(
				text(tunnel.get("id")),
				text(tunnel.get("name")),
				text(tunnel.get("tunnel_type")),
				bestAddress(array(tunnel.get("connect_addresses"))),
				bestDirectAddress(array(tunnel.get("connect_addresses"))),
				booleanValue(tunnel.get("user_enabled")),
				tunnelConfig(tunnel, "local_ip"),
				integer(tunnelConfig(tunnel, "local_port"))
			));
		}
		return result;
	}

	public String createMinecraftTunnel(String agentId, String name, int localPort) throws IOException, InterruptedException {
		return createTunnel(agentId, name, localPort, Map.of("type", "tunnel-type", "details", "minecraft-java"));
	}

	public String createCustomTcpTunnel(String agentId, String name, int localPort) throws IOException, InterruptedException {
		return createTunnel(agentId, name, localPort, Map.of("type", "custom-tcp", "details", 1));
	}

	private String createTunnel(String agentId, String name, int localPort, Map<String, Object> protocol)
		throws IOException, InterruptedException {
		if (agentId == null || !agentId.matches("[a-fA-F0-9-]{36}")) {
			throw new IOException("Playit agent identity is invalid");
		}
		Map<String, Object> config = Map.of("fields", List.of(
			Map.of("name", "local_ip", "value", "127.0.0.1"),
			Map.of("name", "local_port", "value", Integer.toString(localPort))
		));
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("name", name);
		body.put("protocol", protocol);
		body.put("origin", Map.of("type", "agent", "data", Map.of("agent_id", agentId, "config", config)));
		body.put("endpoint", Map.of("type", "region", "details", nullableMap("region", "global", "port", null)));
		body.put("enabled", true);
		body.put("firewall_id", null);
		Map<String, Object> data = object(success(post("/v1/tunnels/create", body)));
		String tunnelId = text(data.get("id"));
		if (!tunnelId.matches("[a-fA-F0-9-]{36}")) throw new IOException("Playit returned an invalid tunnel identity");
		return tunnelId;
	}

	public void configureMinecraftTunnel(String tunnelId, String agentId, int localPort) throws IOException, InterruptedException {
		if (tunnelId == null || !tunnelId.matches("[a-fA-F0-9-]{36}")
			|| agentId == null || !agentId.matches("[a-fA-F0-9-]{36}")) {
			throw new IOException("Playit tunnel identity is invalid");
		}
		Map<String, Object> config = Map.of("fields", List.of(
			Map.of("name", "local_ip", "value", "127.0.0.1"),
			Map.of("name", "local_port", "value", Integer.toString(localPort))
		));
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("tunnel_id", tunnelId);
		body.put("new_agent_id", agentId);
		body.put("new_config", config);
		success(post("/v1/tunnels/config", body));
	}

	private Map<String, Object> post(String path, Map<String, Object> body) throws IOException, InterruptedException {
		URI endpoint = base.resolve(path);
		if (!endpoint.getScheme().equals(base.getScheme()) || !endpoint.getAuthority().equals(base.getAuthority())) {
			throw new IOException("Invalid Playit API endpoint");
		}
		HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
			.timeout(Duration.ofSeconds(25))
			.header("Content-Type", "application/json")
			.header("Accept", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(json(body)));
		if (!secret.isBlank()) request.header("Authorization", "Agent-Key " + secret);
		HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() == 429) throw new IOException("Playit is rate limiting setup; retrying shortly");
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			String details = compactResponse(response.body());
			throw new IOException("Playit request returned HTTP " + response.statusCode()
				+ (details.isBlank() ? "" : ": " + details));
		}
		if (response.body().length() > MAX_RESPONSE) throw new IOException("Playit response was unexpectedly large");
		return object(MiniJson.parse(response.body()));
	}

	private static Map<String, Object> nullableMap(String firstKey, Object firstValue,
		String secondKey, Object secondValue) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put(firstKey, firstValue);
		result.put(secondKey, secondValue);
		return result;
	}

	private static String compactResponse(String body) {
		if (body == null || body.isBlank()) return "";
		String compact = body.replace('\n', ' ').replace('\r', ' ').strip();
		return compact.length() > 240 ? compact.substring(0, 240) : compact;
	}

	private static Object success(Map<String, Object> response) throws IOException {
		String status = text(response.get("status"));
		if ("success".equals(status)) return response.get("data");
		if ("fail".equals(status)) throw new ApiFailure("Playit rejected the request: " + compact(response.get("data")));
		throw new IOException("Playit returned an API error");
	}

	private static String bestAddress(List<Object> addresses) throws IOException {
		String fallback = "";
		for (Object item : addresses) {
			Map<String, Object> address = object(item);
			Map<String, Object> value = object(address.get("value"));
			String type = text(address.get("type"));
			String display = text(value.get("address"));
			long defaultPort = longValue(value.get("default_port"));
			if ("ip6".equals(type) && !display.isBlank()) {
				display = "[" + display.replace("[", "").replace("]", "") + "]";
			}
			if (("ip4".equals(type) || "ip6".equals(type) || "domain".equals(type))
				&& !display.isBlank() && defaultPort > 0L && !hasExplicitPort(display)) {
				display += ":" + defaultPort;
			}
			if (display.isBlank()) continue;
			if ("domain".equals(type)) return display;
			if ("auto".equals(type) && fallback.isBlank()) fallback = display;
			else if (fallback.isBlank()) fallback = display;
		}
		return fallback;
	}

	private static String bestDirectAddress(List<Object> addresses) throws IOException {
		String fallback = "";
		for (Object item : addresses) {
			Map<String, Object> address = object(item);
			Map<String, Object> value = object(address.get("value"));
			String type = text(address.get("type"));
			String display = text(value.get("address"));
			long defaultPort = longValue(value.get("default_port"));
			if ("ip6".equals(type) && !display.isBlank()) {
				display = "[" + display.replace("[", "").replace("]", "") + "]";
			}
			if (("ip4".equals(type) || "ip6".equals(type) || "domain".equals(type))
				&& !display.isBlank() && defaultPort > 0L && !hasExplicitPort(display)) display += ":" + defaultPort;
			if (display.isBlank() || !hasExplicitPort(display)) continue;
			if (("auto".equals(type) || "ip4".equals(type)) && fallback.isBlank()) fallback = display;
			else if (fallback.isBlank()) fallback = display;
		}
		return fallback;
	}

	private static boolean hasExplicitPort(String address) {
		if (address.startsWith("[")) return address.matches("^\\[[^]]+](:[0-9]+)$");
		int first = address.indexOf(':');
		return first >= 0 && first == address.lastIndexOf(':');
	}

	private static String tunnelConfig(Map<String, Object> tunnel, String key) {
		Map<String, Object> origin = objectOrEmpty(tunnel.get("origin"));
		Map<String, Object> details = objectOrEmpty(origin.get("details"));
		if (details.isEmpty()) details = objectOrEmpty(origin.get("data"));
		Map<String, Object> config = objectOrEmpty(details.get("config_data"));
		if (config.isEmpty()) config = objectOrEmpty(details.get("config"));
		Object fieldsValue = config.get("fields");
		if (!(fieldsValue instanceof List<?> fields)) return "";
		for (Object fieldValue : fields) {
			Map<String, Object> field = objectOrEmpty(fieldValue);
			if (key.equals(text(field.get("name")))) return text(field.get("value"));
		}
		return "";
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> objectOrEmpty(Object value) {
		return value instanceof Map<?, ?> map ? (Map<String, Object>)map : Map.of();
	}

	private static int integer(String value) {
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException ignored) {
			return 0;
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> object(Object value) throws IOException {
		if (!(value instanceof Map<?, ?> map)) throw new IOException("Playit returned malformed data");
		return (Map<String, Object>)map;
	}

	@SuppressWarnings("unchecked")
	private static List<Object> array(Object value) throws IOException {
		if (value == null) return List.of();
		if (!(value instanceof List<?> list)) throw new IOException("Playit returned malformed list data");
		return (List<Object>)list;
	}

	private static String text(Object value) {
		return value instanceof String string ? string : "";
	}

	private static boolean booleanValue(Object value) {
		return value instanceof Boolean bool && bool;
	}

	private static long longValue(Object value) {
		return value instanceof Number number ? number.longValue() : 0L;
	}

	private static String compact(Object value) {
		String text = String.valueOf(value).replace('\n', ' ').replace('\r', ' ');
		return text.length() > 120 ? text.substring(0, 120) : text;
	}

	private static String json(Object value) {
		if (value == null) return "null";
		if (value instanceof Boolean || value instanceof Number) return value.toString();
		if (value instanceof String string) return quote(string);
		if (value instanceof Map<?, ?> map) {
			StringBuilder result = new StringBuilder("{");
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				if (result.length() > 1) result.append(',');
				result.append(quote(String.valueOf(entry.getKey()))).append(':').append(json(entry.getValue()));
			}
			return result.append('}').toString();
		}
		if (value instanceof Iterable<?> values) {
			StringBuilder result = new StringBuilder("[");
			for (Object item : values) {
				if (result.length() > 1) result.append(',');
				result.append(json(item));
			}
			return result.append(']').toString();
		}
		throw new IllegalArgumentException("Unsupported JSON value");
	}

	private static String quote(String value) {
		StringBuilder result = new StringBuilder("\"");
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			switch (character) {
				case '\"' -> result.append("\\\"");
				case '\\' -> result.append("\\\\");
				case '\b' -> result.append("\\b");
				case '\f' -> result.append("\\f");
				case '\n' -> result.append("\\n");
				case '\r' -> result.append("\\r");
				case '\t' -> result.append("\\t");
				default -> {
					if (character < 32) result.append(String.format("\\u%04x", (int)character));
					else result.append(character);
				}
			}
		}
		return result.append('\"').toString();
	}

	public enum ClaimState { WAITINGFORUSERVISIT, WAITINGFORUSER, USERACCEPTED, USERREJECTED }
	public record AgentInfo(String id, String accountStatus) {}
	public record Tunnel(String id, String name, String type, String address, String directAddress,
		boolean enabled, String localIp, int localPort) {}

	private static final class ApiFailure extends IOException {
		private ApiFailure(String message) {
			super(message);
		}
	}
}
