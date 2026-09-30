package dev.everhost.playit;

import dev.everhost.address.AddressFiles;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Owns EverHost's verified playitd process and a saved tunnel for each hosted profile. */
public final class PlayitManager implements AutoCloseable {
	private static final String MOD_DISTRIBUTION_FILE = "everhost-mod-distribution.properties";
	private static final String MOD_SOURCE_FILE = "everhost-mod-source.properties";
	private static final String VERSION = "1.0.10-signed";
	private static final URI BINARY_URI = URI.create(
		"https://github.com/playit-cloud/playit-agent/releases/download/v1.0.10/playit-windows-x86_64-signed.exe"
	);
	private static final String BINARY_SHA256 = "2dbdaad119844cbbc062cc9774b8b462afa5f1b4b7832a9fc5ef4676cae887cf";
	private final Path root;
	private final Path configFile;
	private final Path statusFile;
	private final Path secretFile;
	private final Path binary;
	private final String socket;
	private final Path logFile;
	private final Consumer<String> logger;
	private final ScheduledExecutorService worker;
	private final SecureRandom random = new SecureRandom();
	private volatile Process agentProcess;
	private volatile ProcessHandle agentHandle;
	private volatile boolean refreshRequested;
	private volatile boolean closed;
	private volatile long lastApiAt;

	public PlayitManager(Path root, Consumer<String> logger) {
		this.root = root.toAbsolutePath().normalize();
		this.configFile = this.root.resolve("playit.properties");
		this.statusFile = this.root.resolve("playit-status.properties");
		this.secretFile = this.root.resolve("playit-agent.toml");
		this.binary = this.root.resolve("tools").resolve("playitd-" + VERSION + ".exe");
		this.socket = ipcSocketPath(this.root, System.getProperty("os.name", ""));
		this.logFile = this.root.resolve("logs").resolve("playitd.log");
		this.logger = logger;
		this.worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "EverHost-Playit");
			thread.setDaemon(true);
			return thread;
		});
	}

	public void start() {
		adoptPreviousAgent();
		worker.scheduleWithFixedDelay(this::tickSafely, 0L, 2L, TimeUnit.SECONDS);
	}

	public synchronized void beginSetup(String label, String profileKey, int port) throws IOException {
		Properties config = config();
		selectProfile(config, profileKey);
		config.setProperty("enabled", "true");
		config.setProperty("serverLabel", normalizeLabel(label));
		config.setProperty("localPort", Integer.toString(clampPort(port)));
		if (readSecret().isBlank()) {
			config.setProperty("claimCode", claimCode());
			config.remove("address");
			config.remove("tunnelId");
		}
		saveConfig(config);
		refreshRequested = true;
		writeStatus(config, "SETUP", "Open the one-time approval page to connect the owner account", "");
	}

	public synchronized void beginSetup(String label, int port) throws IOException {
		beginSetup(label, label, port);
	}

	public synchronized void setEnabled(boolean enabled, int port) throws IOException {
		Properties config = config();
		config.setProperty("enabled", Boolean.toString(enabled));
		config.setProperty("localPort", Integer.toString(clampPort(port)));
		saveConfig(config);
		refreshRequested = true;
		if (!enabled) {
			stopAgent();
			writeStatus(config, "OFF", "Permanent Playit address is off; the reservation is kept", "");
		}
	}

	public synchronized void updateLocalPort(int port) throws IOException {
		Properties config = config();
		String value = Integer.toString(clampPort(port));
		if (!value.equals(config.getProperty("localPort"))) {
			config.setProperty("localPort", value);
			saveConfig(config);
			refreshRequested = true;
		}
	}

	/** Prepares a fresh per-start download token and asks Playit to expose the runtime's local delivery port. */
	public synchronized void prepareModDelivery(Path runtime, int port) throws IOException {
		Path expected = root.resolve("runtime").toAbsolutePath().normalize();
		Path target = runtime.toAbsolutePath().normalize();
		if (!expected.equals(target)) throw new IOException("The mod delivery runtime is outside this EverHost profile");
		Files.createDirectories(target);
		byte[] tokenBytes = new byte[32];
		random.nextBytes(tokenBytes);
		String token = HexFormat.of().formatHex(tokenBytes);
		Properties delivery = new Properties();
		delivery.setProperty("format", "EVERHOST_MOD_DISTRIBUTION_V1");
		delivery.setProperty("port", Integer.toString(clampPort(port)));
		delivery.setProperty("token", token);
		AddressFiles.write(target.resolve(MOD_DISTRIBUTION_FILE), delivery, true);
		Files.deleteIfExists(target.resolve(MOD_SOURCE_FILE));

		Properties config = config();
		config.setProperty("modRuntime", target.toString());
		config.setProperty("modLocalPort", Integer.toString(clampPort(port)));
		config.setProperty("modToken", token);
		config.remove("modAddress");
		config.remove("modError");
		config.remove("modRetryAt");
		saveConfig(config);
		refreshRequested = true;
	}

	public void refresh() {
		refreshRequested = true;
	}

	private void tickSafely() {
		if (closed) return;
		try {
			tick();
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		} catch (Exception exception) {
			try {
				Properties config = config();
				writeStatus(config, "RETRYING", safeMessage(exception), config.getProperty("accountStatus", ""));
			} catch (IOException ignored) {
			}
			logger.accept("Playit update deferred: " + safeMessage(exception));
		}
	}

	private synchronized void tick() throws Exception {
		Properties config = config();
		if (!Boolean.parseBoolean(config.getProperty("enabled", "false"))) {
			stopAgent();
			clearModSource(config);
			writeStatus(config, "OFF", "Permanent Playit address is off; the reservation is kept", "");
			return;
		}
		long now = System.currentTimeMillis();
		if (!refreshRequested && now - lastApiAt < 5000L) return;
		refreshRequested = false;
		lastApiAt = now;

		String secret = readSecret();
		if (secret.isBlank()) {
			String code = config.getProperty("claimCode", "").strip();
			if (!code.matches("[a-f0-9]{10}")) {
				code = claimCode();
				config.setProperty("claimCode", code);
				saveConfig(config);
			}
			PlayitApi.ClaimState state;
			try {
				state = new PlayitApi("").claimSetup(code);
			} catch (IOException exception) {
				if (!expiredClaim(exception)) throw exception;
				config.setProperty("claimCode", claimCode());
				saveConfig(config);
				writeStatus(config, "SETUP", "The approval link expired; a new one-time code is ready", "");
				return;
			}
			if (state == PlayitApi.ClaimState.USERREJECTED) {
				config.setProperty("claimCode", claimCode());
				saveConfig(config);
				writeStatus(config, "SETUP", "Approval was declined; a new one-time code is ready", "");
				return;
			}
			if (state != PlayitApi.ClaimState.USERACCEPTED) {
				writeStatus(config, "SETUP", state == PlayitApi.ClaimState.WAITINGFORUSERVISIT
					? "Open the one-time approval page" : "Approve EverHost in the Playit page", "");
				return;
			}
			try {
				secret = new PlayitApi("").claimExchange(code);
			} catch (IOException exception) {
				if (!expiredClaim(exception)) throw exception;
				config.setProperty("claimCode", claimCode());
				saveConfig(config);
				writeStatus(config, "SETUP", "The approval link expired; a new one-time code is ready", "");
				return;
			}
			writeSecret(secret);
			config.remove("claimCode");
			saveConfig(config);
			logger.accept("Playit owner approval completed; the private agent key was saved locally");
		}

		ensureBinary();
		PlayitApi api = new PlayitApi(secret);
		PlayitApi.AgentInfo agent;
		try {
			agent = api.agentInfo();
		} catch (IOException exception) {
			if (!invalidAgentKey(exception)) throw exception;
			stopAgent();
			Files.deleteIfExists(secretFile);
			config.remove("agentId");
			config.remove("agentPid");
			config.remove("address");
			config.remove("tunnelId");
			config.setProperty("claimCode", claimCode());
			saveConfig(config);
			writeStatus(config, "SETUP", "Playit needs owner approval again; a new one-time code is ready", "");
			return;
		}
		if (!agent.id().matches("[a-fA-F0-9-]{36}")) throw new IOException("Playit returned an invalid agent identity");
		config.setProperty("agentId", agent.id());
		config.setProperty("accountStatus", agent.accountStatus());
		ensureAgent(secret, config);
		int port = clampPort(integer(config, "localPort", 25570));
		String tunnelId = config.getProperty("tunnelId", "");
		String expectedName = "EverHost - " + normalizeLabel(config.getProperty("serverLabel", "My Server"));
		PlayitApi.Tunnel selected = null;
		List<PlayitApi.Tunnel> tunnels = api.tunnels();
		for (PlayitApi.Tunnel tunnel : tunnels) {
			if (tunnel.id().equals(tunnelId)) selected = tunnel;
		}
		if (selected == null) {
			for (PlayitApi.Tunnel tunnel : tunnels) {
				if ("minecraft-java".equals(tunnel.type()) && expectedName.equals(tunnel.name())) {
					selected = tunnel;
					break;
				}
			}
		}
		if (selected == null) {
			writeStatus(config, "CREATING", "EverHost is creating this profile's Minecraft tunnel", agent.accountStatus());
			String createdId;
			try {
				createdId = api.createMinecraftTunnel(agent.id(), expectedName, port);
			} catch (IOException exception) {
				if (!readOnlyAgent(exception)) throw exception;
				prepareWritableApproval(config);
				return;
			}
			config.setProperty("tunnelId", createdId);
			config.remove("address");
			rememberProfile(config);
			saveConfig(config);
			return;
		}

		if (!selected.enabled()) {
			writeStatus(config, "ACTION_REQUIRED", "Enable the EverHost tunnel in the Playit dashboard", agent.accountStatus());
			return;
		}
		if (!"127.0.0.1".equals(selected.localIp()) || selected.localPort() != port) {
			writeStatus(config, "CONFIGURING", "EverHost is connecting the tunnel to this profile", agent.accountStatus());
			try {
				api.configureMinecraftTunnel(selected.id(), agent.id(), port);
			} catch (IOException exception) {
				if (!readOnlyAgent(exception)) throw exception;
				prepareWritableApproval(config);
			}
			return;
		}
		config.setProperty("tunnelId", selected.id());
		if (!selected.address().isBlank()) config.setProperty("address", selected.address());
		ensureModTunnel(api, agent, config, tunnels, expectedName);
		config.setProperty("agentPid", Long.toString(agentHandle == null ? 0L : agentHandle.pid()));
		rememberProfile(config);
		saveConfig(config);
		String address = config.getProperty("address", "");
		writeStatus(config, address.isBlank() ? "ALLOCATING" : "ONLINE",
			address.isBlank() ? "Waiting for Playit to assign the address" : "Permanent address is ready and stays reserved while offline",
			agent.accountStatus());
	}

	private void ensureModTunnel(PlayitApi api, PlayitApi.AgentInfo agent, Properties config,
		List<PlayitApi.Tunnel> tunnels, String gameTunnelName) throws IOException, InterruptedException {
		Path modRuntime = modRuntime(config);
		int modPort = clampPort(integer(config, "modLocalPort", 25571));
		String token = config.getProperty("modToken", "").strip();
		if (modRuntime == null || !token.matches("[0-9a-fA-F]{64}")) {
			clearModSource(config);
			return;
		}
		String tunnelId = config.getProperty("modTunnelId", "");
		String expectedName = gameTunnelName + " - Mod Delivery";
		PlayitApi.Tunnel selected = null;
		for (PlayitApi.Tunnel tunnel : tunnels) if (tunnel.id().equals(tunnelId)) selected = tunnel;
		if (selected == null) {
			for (PlayitApi.Tunnel tunnel : tunnels) {
				if (("custom-tcp".equals(tunnel.type()) || "minecraft-java".equals(tunnel.type()))
					&& expectedName.equals(tunnel.name())) {
					selected = tunnel;
					break;
				}
			}
		}
		if (selected == null) {
			clearModSource(config);
			long retryAt = longValue(config.getProperty("modRetryAt"), 0L);
			if (System.currentTimeMillis() < retryAt) return;
			try {
				String created;
				try {
					created = api.createCustomTcpTunnel(agent.id(), expectedName, modPort);
				} catch (IOException customFailure) {
					created = api.createMinecraftTunnel(agent.id(), expectedName, modPort);
					logger.accept("Playit custom TCP needs a paid plan; using a Minecraft-routed TCP allocation for mod delivery");
				}
				config.setProperty("modTunnelId", created);
				config.remove("modAddress");
				config.remove("modError");
				config.remove("modRetryAt");
				rememberProfile(config);
				logger.accept("Created the private required-mod delivery tunnel for this profile");
			} catch (IOException exception) {
				String message = safeMessage(exception);
				config.setProperty("modError", message);
				config.setProperty("modRetryAt", Long.toString(System.currentTimeMillis() + 5L * 60L * 1000L));
				logger.accept("Direct custom-mod delivery is unavailable: " + message);
			}
			return;
		}
		if (!selected.enabled()) {
			clearModSource(config);
			config.setProperty("modError", "Enable the EverHost Mod Delivery tunnel in the Playit dashboard");
			return;
		}
		if (!"127.0.0.1".equals(selected.localIp()) || selected.localPort() != modPort) {
			clearModSource(config);
			try {
				api.configureMinecraftTunnel(selected.id(), agent.id(), modPort);
				config.remove("modError");
			} catch (IOException exception) {
				config.setProperty("modError", safeMessage(exception));
			}
			return;
		}
		String deliveryAddress = routeAddress(selected.address(), selected.directAddress());
		String baseUrl = deliveryBaseUrl(deliveryAddress);
		if (baseUrl.isBlank()) {
			clearModSource(config);
			config.setProperty("modError", "Waiting for Playit to assign the mod delivery address");
			return;
		}
		Properties source = new Properties();
		source.setProperty("format", "EVERHOST_MOD_SOURCE_V1");
		source.setProperty("baseUrl", baseUrl);
		source.setProperty("token", token);
		AddressFiles.write(modRuntime.resolve(MOD_SOURCE_FILE), source, true);
		config.setProperty("modTunnelId", selected.id());
		config.setProperty("modAddress", deliveryAddress);
		config.remove("modError");
		rememberProfile(config);
	}

	private Path modRuntime(Properties config) {
		try {
			Path value = Path.of(config.getProperty("modRuntime", "")).toAbsolutePath().normalize();
			return value.equals(root.resolve("runtime").toAbsolutePath().normalize()) ? value : null;
		} catch (RuntimeException exception) {
			return null;
		}
	}

	private void clearModSource(Properties config) {
		Path runtime = modRuntime(config);
		if (runtime == null) return;
		try {
			Files.deleteIfExists(runtime.resolve(MOD_SOURCE_FILE));
		} catch (IOException ignored) {
		}
	}

	static String deliveryBaseUrl(String address) {
		if (address == null || address.isBlank() || address.contains("/") || address.contains("@")) return "";
		try {
			URI uri = URI.create("everhost://" + address.strip());
			return uri.getHost() != null && !uri.getHost().isBlank() && uri.getPort() > 0 ? uri.toString() : "";
		} catch (IllegalArgumentException exception) {
			return "";
		}
	}

	static String routeAddress(String routeHost, String directAddress) {
		String direct = directAddress == null ? "" : directAddress.strip();
		String preferred = routeHost == null ? "" : routeHost.strip();
		if (preferred.isBlank()) return direct;
		if (!deliveryBaseUrl(preferred).isBlank()) return preferred;
		try {
			URI directUri = URI.create("everhost://" + direct);
			if (directUri.getPort() <= 0 || preferred.contains("/") || preferred.contains("@")) return direct;
			String candidate = preferred + ":" + directUri.getPort();
			return deliveryBaseUrl(candidate).isBlank() ? direct : candidate;
		} catch (IllegalArgumentException exception) {
			return direct;
		}
	}

	private void ensureBinary() throws Exception {
		if (Files.isRegularFile(binary) && BINARY_SHA256.equalsIgnoreCase(sha256(binary))) return;
		Files.createDirectories(binary.getParent());
		Path temporary = binary.resolveSibling(binary.getFileName() + ".download");
		Files.deleteIfExists(temporary);
		writeSimpleStatus("DOWNLOADING", "Downloading and verifying the official Playit agent");
		HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
			.followRedirects(HttpClient.Redirect.NORMAL).build();
		HttpRequest request = HttpRequest.newBuilder(BINARY_URI).timeout(Duration.ofMinutes(5)).GET().build();
		HttpResponse<Path> response = http.send(request, HttpResponse.BodyHandlers.ofFile(temporary));
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			Files.deleteIfExists(temporary);
			throw new IOException("Playit agent download returned HTTP " + response.statusCode());
		}
		if (!BINARY_SHA256.equalsIgnoreCase(sha256(temporary))) {
			Files.deleteIfExists(temporary);
			throw new IOException("Playit agent checksum verification failed");
		}
		moveAtomic(temporary, binary);
		logger.accept("Verified Playit agent " + VERSION + " at " + binary);
	}

	private void ensureAgent(String secret, Properties config) throws IOException {
		if (agentRunning()) return;
		writeSecret(secret);
		Files.createDirectories(logFile.getParent());
		ProcessBuilder builder = new ProcessBuilder(
			binary.toString(),
			"--secret-path", secretFile.toString(),
			"--socket-path", socket,
			"--log-path", logFile.toString()
		);
		builder.directory(binary.getParent().toFile());
		builder.redirectErrorStream(true);
		builder.redirectOutput(ProcessBuilder.Redirect.appendTo(root.resolve("logs").resolve("playit-process.log").toFile()));
		agentProcess = builder.start();
		agentHandle = agentProcess.toHandle();
		try {
			if (agentProcess.waitFor(750, TimeUnit.MILLISECONDS)) {
				int exitCode = agentProcess.exitValue();
				agentProcess = null;
				agentHandle = null;
				throw new IOException("Playit agent exited during startup with code " + exitCode + "; check the Playit log");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			agentHandle.destroyForcibly();
			agentProcess = null;
			agentHandle = null;
			throw new IOException("Interrupted while starting the Playit agent", exception);
		}
		config.setProperty("agentPid", Long.toString(agentHandle.pid()));
		saveConfig(config);
		logger.accept("Playit agent started with PID " + agentHandle.pid());
	}

	private void adoptPreviousAgent() {
		try {
			Properties config = config();
			long pid = Long.parseLong(config.getProperty("agentPid", "0"));
			if (pid <= 0L) return;
			ProcessHandle.of(pid).ifPresent(handle -> {
				String command = handle.info().command().orElse("");
				String arguments = String.join(" ", handle.info().arguments().orElse(new String[0]));
				if (handle.isAlive() && samePath(command, binary) && arguments.contains(secretFile.toString())) {
					agentHandle = handle;
					logger.accept("Reconnected to the existing EverHost Playit agent PID " + pid);
				}
			});
		} catch (Exception ignored) {
		}
	}

	private boolean agentRunning() {
		return agentHandle != null && agentHandle.isAlive();
	}

	private void stopAgent() {
		ProcessHandle current = agentHandle;
		if (current != null && current.isAlive()) {
			current.destroy();
			try {
				if (agentProcess != null && !agentProcess.waitFor(5, TimeUnit.SECONDS)) current.destroyForcibly();
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				current.destroyForcibly();
			}
		}
		agentProcess = null;
		agentHandle = null;
		try {
			Properties config = config();
			config.remove("agentPid");
			saveConfig(config);
		} catch (IOException ignored) {
		}
	}

	private void writeSecret(String secret) throws IOException {
		Properties values = new Properties();
		values.setProperty("secret_key", "\"" + secret.replace("\"", "") + "\"");
		AddressFiles.write(secretFile, values, true);
	}

	private String readSecret() throws IOException {
		Properties values = AddressFiles.read(secretFile);
		String value = values.getProperty("secret_key", "").strip();
		if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) value = value.substring(1, value.length() - 1);
		return value.matches("[a-fA-F0-9]{64}") ? value : "";
	}

	private void prepareWritableApproval(Properties config) throws IOException {
		stopAgent();
		Path backup = root.resolve("playit-agent.read-only-backup.toml");
		if (Files.isRegularFile(secretFile) && !Files.exists(backup)) Files.copy(secretFile, backup);
		Files.deleteIfExists(secretFile);
		config.remove("agentId");
		config.remove("agentPid");
		config.setProperty("claimCode", claimCode());
		saveConfig(config);
		writeStatus(config, "SETUP", "One quick approval upgrades this older Playit connection", "");
		logger.accept("Saved the older read-only Playit credential and prepared a writable owner approval");
	}

	private Properties config() throws IOException {
		return AddressFiles.read(configFile);
	}

	private void saveConfig(Properties config) throws IOException {
		AddressFiles.write(configFile, config, false);
	}

	static void selectProfile(Properties config, String profileKey) {
		String selected = normalizeProfileKey(profileKey);
		String previous = normalizeProfileKey(config.getProperty("profileKey", ""));
		if (selected.equals(previous)) return;
		if (!previous.isBlank()) {
			rememberProfile(config);
		}
		config.setProperty("profileKey", selected);
		String slot = profileSlot(selected);
		String savedTunnel = config.getProperty(slot + ".tunnelId", "");
		String savedAddress = config.getProperty(slot + ".address", "");
		String savedModTunnel = config.getProperty(slot + ".modTunnelId", "");
		String savedModAddress = config.getProperty(slot + ".modAddress", "");
		if (previous.isBlank() && !config.getProperty("tunnelId", "").isBlank()) {
			rememberProfile(config);
			return;
		}
		if (savedTunnel.isBlank()) config.remove("tunnelId");
		else config.setProperty("tunnelId", savedTunnel);
		if (savedAddress.isBlank()) config.remove("address");
		else config.setProperty("address", savedAddress);
		if (savedModTunnel.isBlank()) config.remove("modTunnelId");
		else config.setProperty("modTunnelId", savedModTunnel);
		if (savedModAddress.isBlank()) config.remove("modAddress");
		else config.setProperty("modAddress", savedModAddress);
	}

	private static void rememberProfile(Properties config) {
		String profileKey = normalizeProfileKey(config.getProperty("profileKey", ""));
		if (profileKey.isBlank()) return;
		String slot = profileSlot(profileKey);
		String tunnelId = config.getProperty("tunnelId", "");
		String address = config.getProperty("address", "");
		String modTunnelId = config.getProperty("modTunnelId", "");
		String modAddress = config.getProperty("modAddress", "");
		if (!tunnelId.isBlank()) config.setProperty(slot + ".tunnelId", tunnelId);
		if (!address.isBlank()) config.setProperty(slot + ".address", address);
		if (!modTunnelId.isBlank()) config.setProperty(slot + ".modTunnelId", modTunnelId);
		if (!modAddress.isBlank()) config.setProperty(slot + ".modAddress", modAddress);
	}

	private static String normalizeProfileKey(String value) {
		if (value == null) return "";
		return value.strip().replace('\\', '/').toLowerCase(Locale.ROOT);
	}

	static String profileSlot(String profileKey) {
		String normalized = normalizeProfileKey(profileKey);
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
			return "profile." + HexFormat.of().formatHex(digest, 0, 8);
		} catch (Exception ignored) {
			return "profile." + Integer.toUnsignedString(normalized.hashCode(), 36);
		}
	}

	private void writeSimpleStatus(String state, String message) throws IOException {
		writeStatus(config(), state, message, "");
	}

	private void writeStatus(Properties config, String state, String message, String accountStatus) throws IOException {
		Properties status = new Properties();
		status.setProperty("provider", "Playit");
		status.setProperty("enabled", config.getProperty("enabled", "false"));
		status.setProperty("state", state);
		status.setProperty("message", message);
		status.setProperty("address", config.getProperty("address", ""));
		status.setProperty("claimUrl", claimUrl(config));
		status.setProperty("serverLabel", config.getProperty("serverLabel", "My Server"));
		status.setProperty("profileKey", config.getProperty("profileKey", ""));
		status.setProperty("tunnelId", config.getProperty("tunnelId", ""));
		status.setProperty("modDeliveryAddress", config.getProperty("modAddress", ""));
		status.setProperty("modDeliveryError", config.getProperty("modError", ""));
		status.setProperty("accountStatus", accountStatus);
		status.setProperty("agentRunning", Boolean.toString(agentRunning()));
		status.setProperty("updatedAt", Instant.now().toString());
		AddressFiles.write(statusFile, status, false);
	}

	private static String claimUrl(Properties config) {
		String code = config.getProperty("claimCode", "");
		return code.matches("[a-f0-9]{10}") ? "https://playit.gg/claim/" + code : "";
	}

	private String claimCode() {
		byte[] bytes = new byte[5];
		random.nextBytes(bytes);
		return HexFormat.of().formatHex(bytes);
	}

	private static String normalizeLabel(String value) {
		String label = value == null ? "" : value.strip().replaceAll("[^A-Za-z0-9 _.-]", "");
		if (label.isBlank()) label = "My Server";
		return label.length() > 40 ? label.substring(0, 40).strip() : label;
	}

	private static int integer(Properties values, String key, int fallback) {
		try {
			return Integer.parseInt(values.getProperty(key, Integer.toString(fallback)));
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	private static long longValue(String value, long fallback) {
		try {
			return Long.parseLong(value);
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	private static int clampPort(int value) {
		return Math.max(1024, Math.min(65535, value));
	}

	private static String sha256(Path file) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		try (var input = Files.newInputStream(file)) {
			byte[] buffer = new byte[128 * 1024];
			for (int read; (read = input.read(buffer)) >= 0;) digest.update(buffer, 0, read);
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	private static void moveAtomic(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException exception) {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static boolean samePath(String command, Path expected) {
		try {
			return Path.of(command).toAbsolutePath().normalize().equals(expected.toAbsolutePath().normalize());
		} catch (RuntimeException exception) {
			return false;
		}
	}

	private static String safeMessage(Exception exception) {
		String message = exception.getMessage();
		if (message == null || message.isBlank()) return "Playit setup failed; retrying shortly";
		message = message.replace('\n', ' ').replace('\r', ' ');
		return message.length() > 180 ? message.substring(0, 180) : message;
	}

	static boolean expiredClaim(IOException exception) {
		String message = exception.getMessage();
		if (message == null) return false;
		String normalized = message.toLowerCase(Locale.ROOT).replace("_", "").replace(" ", "");
		return normalized.contains("codeexpired") || normalized.contains("invalidcode");
	}

	static boolean invalidAgentKey(IOException exception) {
		String message = exception.getMessage();
		if (message == null) return false;
		String normalized = message.toLowerCase(Locale.ROOT).replace("_", "").replace(" ", "");
		return normalized.contains("http401") || normalized.contains("invalidagentkey")
			|| normalized.contains("nolongervalid");
	}

	static boolean readOnlyAgent(IOException exception) {
		String message = exception.getMessage();
		if (message == null) return false;
		String normalized = message.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "").replace(" ", "");
		return normalized.contains("notallowedwithreadonly") || normalized.contains("readonly");
	}

	static String ipcSocketPath(Path root, String osName) {
		if (osName != null && osName.toLowerCase(Locale.ROOT).contains("win")) {
			String identity = root.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
			return "\\\\.\\pipe\\everhost-playitd-" + Integer.toUnsignedString(identity.hashCode(), 36);
		}
		return root.resolve("playitd.sock").toString();
	}

	@Override
	public synchronized void close() {
		closed = true;
		worker.shutdownNow();
		stopAgent();
	}
}
