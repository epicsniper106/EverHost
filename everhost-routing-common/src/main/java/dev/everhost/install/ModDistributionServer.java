package dev.everhost.install;

import dev.everhost.universal.RequiredModsManifest;
import dev.everhost.universal.RequiredModsManifest.Requirement;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Serves exact required-mod JARs through a Playit-compatible Minecraft handshake followed by an EverHost stream. */
public final class ModDistributionServer {
	private static final System.Logger LOGGER = System.getLogger("EverHost-ModDelivery");
	private static final String CONFIG_FILE = "everhost-mod-distribution.properties";
	private static final String FORMAT = "EVERHOST_MOD_DISTRIBUTION_V1";
	private static final byte[] REQUEST_MAGIC = "EHMOD001".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] RESPONSE_MAGIC = "EHMR0001".getBytes(StandardCharsets.US_ASCII);
	private static final int BUFFER_SIZE = 128 * 1024;
	private static volatile ServerSocket server;
	private static volatile ExecutorService workers;
	private static volatile int boundPort;

	private ModDistributionServer() {
	}

	/** Starts only inside an EverHost generated server runtime. Client profiles do not contain the config file. */
	public static synchronized boolean start(Path runtime) {
		if (server != null) return true;
		Path root = runtime.toAbsolutePath().normalize();
		Path configFile = root.resolve(CONFIG_FILE);
		if (!Files.isRegularFile(configFile)) return false;
		try {
			Properties config = new Properties();
			try (var reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
				config.load(reader);
			}
			if (!FORMAT.equals(config.getProperty("format"))) return false;
			String token = config.getProperty("token", "").strip();
			if (!token.matches("[0-9a-fA-F]{64}")) throw new IOException("invalid delivery token");
			int port = Integer.parseInt(config.getProperty("port", "0"));
			if (port < 0 || port > 65535) throw new IOException("invalid delivery port");

			ServerSocket created = new ServerSocket();
			created.setReuseAddress(true);
			created.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 16);
			ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
				Thread thread = new Thread(runnable, "EverHost-ModDelivery-Worker");
				thread.setDaemon(true);
				return thread;
			});
			server = created;
			workers = executor;
			boundPort = created.getLocalPort();
			Thread acceptor = new Thread(() -> acceptLoop(created, executor, root, token), "EverHost-ModDelivery-Acceptor");
			acceptor.setDaemon(true);
			acceptor.start();
			LOGGER.log(System.Logger.Level.INFO, "Direct required-mod delivery is listening on loopback port " + boundPort);
			return true;
		} catch (Exception exception) {
			LOGGER.log(System.Logger.Level.ERROR, "Could not start direct required-mod delivery: " + safeMessage(exception));
			return false;
		}
	}

	private static void acceptLoop(ServerSocket socket, ExecutorService executor, Path runtime, String token) {
		while (!socket.isClosed()) {
			try {
				Socket client = socket.accept();
				executor.execute(() -> handle(client, runtime, token));
			} catch (IOException exception) {
				if (!socket.isClosed()) LOGGER.log(System.Logger.Level.WARNING, "Mod delivery accept failed: " + safeMessage(exception));
			} catch (RuntimeException exception) {
				LOGGER.log(System.Logger.Level.WARNING, "Mod delivery worker failed: " + safeMessage(exception));
			}
		}
	}

	private static void handle(Socket socket, Path runtime, String token) {
		try (socket;
			 DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
			 DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {
			socket.setSoTimeout(30_000);
			readMinecraftHandshake(input);
			byte[] magic = input.readNBytes(REQUEST_MAGIC.length);
			if (!MessageDigest.isEqual(magic, REQUEST_MAGIC)) {
				error(output, 1, "Unsupported EverHost transfer request");
				return;
			}
			String suppliedToken = new String(input.readNBytes(64), StandardCharsets.US_ASCII);
			String hash = new String(input.readNBytes(64), StandardCharsets.US_ASCII).toLowerCase(Locale.ROOT);
			if (!MessageDigest.isEqual(token.getBytes(StandardCharsets.US_ASCII), suppliedToken.getBytes(StandardCharsets.US_ASCII))) {
				error(output, 2, "Unauthorized");
				return;
			}
			if (!hash.matches("[0-9a-f]{64}")) {
				error(output, 3, "Invalid file fingerprint");
				return;
			}
			List<Requirement> requirements = RequiredModsManifest.read(runtime);
			Requirement requirement = requirements.stream().filter(item -> hash.equals(item.sha256())).findFirst().orElse(null);
			if (requirement == null) {
				error(output, 4, "The requested file is not required by this server");
				return;
			}
			Path mods = runtime.resolve("mods").toAbsolutePath().normalize();
			Path file = mods.resolve(requirement.fileName()).toAbsolutePath().normalize();
			if (!mods.equals(file.getParent()) || !Files.isRegularFile(file) || Files.size(file) != requirement.fileSize()
				|| !ModInstallCoordinator.sha256(file).equalsIgnoreCase(requirement.sha256())) {
				error(output, 5, "The server mod file changed; restart the server to refresh its manifest");
				return;
			}
			output.write(RESPONSE_MAGIC);
			output.writeByte(0);
			output.writeLong(Files.size(file));
			output.write(requirement.sha256().getBytes(StandardCharsets.US_ASCII));
			try (InputStream fileInput = Files.newInputStream(file)) {
				byte[] buffer = new byte[BUFFER_SIZE];
				for (int read; (read = fileInput.read(buffer)) >= 0;) if (read > 0) output.write(buffer, 0, read);
			}
			output.flush();
		} catch (Exception exception) {
			LOGGER.log(System.Logger.Level.WARNING, "Direct mod transfer failed: " + safeMessage(exception));
		}
	}

	private static void readMinecraftHandshake(DataInputStream input) throws IOException {
		int packetLength = readVarInt(input);
		if (packetLength < 1 || packetLength > 4096) throw new IOException("invalid Minecraft handshake length");
		byte[] packet = input.readNBytes(packetLength);
		if (packet.length != packetLength) throw new IOException("incomplete Minecraft handshake");
		try (DataInputStream handshake = new DataInputStream(new ByteArrayInputStream(packet))) {
			if (readVarInt(handshake) != 0) throw new IOException("invalid Minecraft handshake packet");
			readVarInt(handshake);
			int addressLength = readVarInt(handshake);
			if (addressLength < 1 || addressLength > 1024 || handshake.readNBytes(addressLength).length != addressLength) {
				throw new IOException("invalid Minecraft handshake address");
			}
			handshake.readUnsignedShort();
			if (readVarInt(handshake) != 1) throw new IOException("invalid Minecraft handshake state");
		}
	}

	private static int readVarInt(DataInputStream input) throws IOException {
		int value = 0;
		for (int position = 0; position < 5; position++) {
			int current = input.readUnsignedByte();
			value |= (current & 0x7F) << (position * 7);
			if ((current & 0x80) == 0) return value;
		}
		throw new IOException("VarInt is too large");
	}

	private static void error(DataOutputStream output, int status, String message) throws IOException {
		output.write(RESPONSE_MAGIC);
		output.writeByte(status);
		output.writeUTF(message.length() > 240 ? message.substring(0, 240) : message);
		output.flush();
	}

	static synchronized void stopForTests() {
		if (server != null) try { server.close(); } catch (IOException ignored) {}
		if (workers != null) workers.shutdownNow();
		server = null;
		workers = null;
		boundPort = 0;
	}

	static int boundPortForTests() {
		return boundPort;
	}

	private static String safeMessage(Exception exception) {
		String message = exception.getMessage();
		return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
	}
}
