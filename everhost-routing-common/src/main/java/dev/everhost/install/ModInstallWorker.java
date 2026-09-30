package dev.everhost.install;

import java.io.IOException;
import java.io.InputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** Standalone Java entry point that runs after Minecraft releases every mod JAR. */
public final class ModInstallWorker {
	private static final long MAX_MOD_BYTES = 512L * 1024L * 1024L;
	private static final long MAX_METADATA_BYTES = 2L * 1024L * 1024L;
	private static final Set<String> CURSEFORGE_HOSTS = Set.of(
		"edge.forgecdn.net", "mediafilez.forgecdn.net", "media.forgecdn.net", "www.curseforge.com"
	);
	private static final Pattern FABRIC_ID = Pattern.compile("\\\"id\\\"\\s*:\\s*\\\"([A-Za-z0-9_.-]+)\\\"");
	private static final Pattern FORGE_ID = Pattern.compile("(?m)^\\s*modId\\s*=\\s*[\\\"']([A-Za-z0-9_.-]+)[\\\"']");
	private static final String CURSEFORGE_APP_ID = "cchhcaiapeikjbdbpfplgmpobbcdkdaphclbmkbj";
	private static final byte[] DIRECT_REQUEST_MAGIC = "EHMOD001".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] DIRECT_RESPONSE_MAGIC = "EHMR0001".getBytes(StandardCharsets.US_ASCII);

	private ModInstallWorker() {
	}

	public static void main(String[] args) {
		if (args.length != 5) return;
		long parentPid;
		try {
			parentPid = Long.parseLong(args[0]);
		} catch (NumberFormatException exception) {
			return;
		}
		Path game = Path.of(args[1]).toAbsolutePath().normalize();
		Path plan = Path.of(args[2]).toAbsolutePath().normalize();
		Path result = Path.of(args[3]).toAbsolutePath().normalize();
		Path log = Path.of(args[4]).toAbsolutePath().normalize();
		Path root = result.getParent();
		try {
			Files.createDirectories(root);
			Path lockPath = root.resolve("installer.lock");
			try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
				 FileLock lock = channel.tryLock()) {
				if (lock == null) return;
				run(parentPid, game, plan, result, log);
			}
		} catch (Exception exception) {
			try {
				append(log, "FATAL " + safeMessage(exception));
				writeFailure(result, "INSTALLER", safeMessage(exception));
			} catch (Exception ignored) {
			}
		}
	}

	private static void run(long parentPid, Path game, Path planPath, Path result, Path log) throws Exception {
		boolean reopen = true;
		try {
			waitForExit(parentPid);
			Plan plan = readPlan(game, planPath);
			reopen = plan.reopenCurseForge();
			append(log, "Preparing " + plan.artifacts().size() + " exact required mod file(s)");
			Path staging = result.getParent().resolve("staging");
			deleteDirectoryContents(staging);
			Files.createDirectories(staging);
			List<PreparedArtifact> prepared = new ArrayList<>();
			for (int index = 0; index < plan.artifacts().size(); index++) {
				prepared.add(prepare(game.resolve("mods"), staging, plan.artifacts().get(index), index, log));
			}
			Path backups = game.resolve("everhost").resolve("mod-archive")
				.resolve(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
					.format(java.time.LocalDateTime.now()));
			InstallSummary summary = applyPrepared(game.resolve("mods"), backups, prepared);
			String reopenWarning = reopen ? reopenCurseForge() : "";
			writeSuccess(result, plan.artifacts(), summary, reopenWarning);
			archivePlan(planPath);
			append(log, "SUCCESS installed and verified " + plan.artifacts().size() + " required mod file(s)");
			if (!reopenWarning.isBlank()) append(log, "REOPEN " + reopenWarning);
		} catch (Failure failure) {
			append(log, failure.code + " " + safeMessage(failure));
			writeFailure(result, failure.code, safeMessage(failure));
			archivePlan(planPath);
			if (reopen) reopenCurseForge();
		} catch (Exception exception) {
			append(log, "INSTALL " + safeMessage(exception));
			writeFailure(result, "INSTALL", safeMessage(exception));
			archivePlan(planPath);
			if (reopen) reopenCurseForge();
		}
	}

	private static PreparedArtifact prepare(
		Path mods, Path staging, Artifact artifact, int index, Path log
	) throws Exception {
		Path target = safeChild(mods, artifact.fileName());
		if (target == null) throw new Failure("PLAN", artifact.name() + " has an unsafe target filename.");
		if (Files.isRegularFile(target) && sha256(target).equalsIgnoreCase(artifact.sha256())) {
			verifyJar(target, artifact);
			append(log, "READY " + artifact.name() + " is already the exact server file");
			return new PreparedArtifact(artifact, null, true);
		}
		Path staged = staging.resolve(String.format(Locale.ROOT, "%04d.jar", index));
		Files.deleteIfExists(staged);
		Path localExact = findExactJar(mods, artifact.sha256());
		if (localExact != null) {
			Files.copy(localExact, staged, StandardCopyOption.REPLACE_EXISTING);
			append(log, "READY reused exact local file for " + artifact.name());
		} else {
			String source = download(staged, artifact);
			append(log, "DOWNLOADED " + artifact.name() + " " + source);
		}
		verifyDownloaded(staged, artifact);
		return new PreparedArtifact(artifact, staged, false);
	}

	private static String download(Path target, Artifact artifact) throws Exception {
		Failure directFailure = null;
		if (validDirectSource(artifact.serverUrl(), artifact.serverToken(), artifact.sha256())) {
			try {
				downloadDirect(target, artifact);
				return "directly from the hosting EverHost server";
			} catch (Failure failure) {
				directFailure = failure;
				Files.deleteIfExists(target);
			}
		}
		if (hasCurseForgeSource(artifact)) {
			downloadCurseForge(target, artifact);
			return "from CurseForge project " + artifact.projectId() + " file " + artifact.fileId()
				+ (directFailure == null ? "" : " after the direct server route was unavailable");
		}
		if (directFailure != null) throw directFailure;
		throw new Failure("DOWNLOAD", artifact.name() + " has no reachable approved download source.");
	}

	static String downloadForTests(Path target, Artifact artifact) throws Exception {
		return download(target, artifact);
	}

	private static void downloadDirect(Path target, Artifact artifact) throws Exception {
		URI uri;
		try {
			uri = URI.create(artifact.serverUrl());
		} catch (IllegalArgumentException exception) {
			throw new Failure("DIRECT_DOWNLOAD", "The server supplied an invalid direct address for " + artifact.name() + ".");
		}
		if (!validDirectSource(artifact.serverUrl(), artifact.serverToken(), artifact.sha256())) {
			throw new Failure("DIRECT_DOWNLOAD", "The server supplied invalid direct-download credentials for " + artifact.name() + ".");
		}
		if ("everhost".equalsIgnoreCase(uri.getScheme())) {
			downloadEverHost(target, artifact, uri);
			return;
		}
		HttpClient client = httpClientBuilder().connectTimeout(Duration.ofSeconds(20))
			.followRedirects(HttpClient.Redirect.NEVER).build();
		HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(10))
			.header("Authorization", "Bearer " + artifact.serverToken())
			.header("User-Agent", "EverHost/2.2.0-beta.11")
			.header("Accept", "application/java-archive, application/octet-stream;q=0.9")
			.build();
		HttpResponse<Path> response;
		try {
			response = client.send(request, HttpResponse.BodyHandlers.ofFile(target));
		} catch (Exception exception) {
			throw new Failure("DIRECT_DOWNLOAD", "Could not copy " + artifact.name()
				+ " from the hosting server: " + safeMessage(exception));
		}
		if (response.statusCode() != 200) {
			Files.deleteIfExists(target);
			throw new Failure("DIRECT_DOWNLOAD", "The hosting server returned HTTP " + response.statusCode()
				+ " for " + artifact.name() + ".");
		}
		if (!uri.equals(response.uri())) {
			Files.deleteIfExists(target);
			throw new Failure("DIRECT_DOWNLOAD", "The hosting server tried to redirect " + artifact.name() + ".");
		}
		String advertisedHash = response.headers().firstValue("X-EverHost-SHA256").orElse("");
		if (!advertisedHash.equalsIgnoreCase(artifact.sha256())) {
			Files.deleteIfExists(target);
			throw new Failure("DIRECT_DOWNLOAD", "The hosting server did not confirm the exact file for " + artifact.name() + ".");
		}
	}

	private static void downloadEverHost(Path target, Artifact artifact, URI uri) throws Exception {
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), 20_000);
			socket.setSoTimeout(10 * 60 * 1000);
			try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
				 DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {
				writeMinecraftHandshake(output, uri.getHost(), uri.getPort());
				output.write(DIRECT_REQUEST_MAGIC);
				output.write(artifact.serverToken().getBytes(StandardCharsets.US_ASCII));
				output.write(artifact.sha256().getBytes(StandardCharsets.US_ASCII));
				output.flush();

				byte[] magic = input.readNBytes(DIRECT_RESPONSE_MAGIC.length);
				if (!MessageDigest.isEqual(magic, DIRECT_RESPONSE_MAGIC)) {
					throw new Failure("DIRECT_DOWNLOAD", "The hosting server returned an invalid transfer response for " + artifact.name() + ".");
				}
				int status = input.readUnsignedByte();
				if (status != 0) throw new Failure("DIRECT_DOWNLOAD", input.readUTF());
				long size = input.readLong();
				if (size <= 0L || size > MAX_MOD_BYTES || (artifact.size() > 0L && size != artifact.size())) {
					throw new Failure("SIZE", artifact.name() + " did not match the exact server file size.");
				}
				String responseHash = new String(input.readNBytes(64), StandardCharsets.US_ASCII);
				if (!responseHash.equalsIgnoreCase(artifact.sha256())) {
					throw new Failure("DIRECT_DOWNLOAD", "The hosting server did not confirm the exact file for " + artifact.name() + ".");
				}
				try (OutputStream file = Files.newOutputStream(target, StandardOpenOption.CREATE,
					StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
					byte[] buffer = new byte[128 * 1024];
					long remaining = size;
					while (remaining > 0L) {
						int read = input.read(buffer, 0, (int)Math.min(buffer.length, remaining));
						if (read < 0) throw new Failure("DIRECT_DOWNLOAD", "The hosting server ended the transfer early for " + artifact.name() + ".");
						file.write(buffer, 0, read);
						remaining -= read;
					}
				}
			}
		} catch (Failure failure) {
			Files.deleteIfExists(target);
			throw failure;
		} catch (Exception exception) {
			Files.deleteIfExists(target);
			throw new Failure("DIRECT_DOWNLOAD", "Could not copy " + artifact.name()
				+ " from the hosting server: " + safeMessage(exception));
		}
	}

	private static void writeMinecraftHandshake(DataOutputStream output, String host, int port) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream packet = new DataOutputStream(bytes)) {
			writeVarInt(packet, 0);
			writeVarInt(packet, 763);
			byte[] address = host.getBytes(StandardCharsets.UTF_8);
			writeVarInt(packet, address.length);
			packet.write(address);
			packet.writeShort(port);
			writeVarInt(packet, 1);
		}
		writeVarInt(output, bytes.size());
		bytes.writeTo(output);
	}

	private static void writeVarInt(DataOutputStream output, int value) throws IOException {
		do {
			int current = value & 0x7F;
			value >>>= 7;
			if (value != 0) current |= 0x80;
			output.writeByte(current);
		} while (value != 0);
	}

	private static void downloadCurseForge(Path target, Artifact artifact) throws Exception {
		URI uri;
		try {
			uri = URI.create(artifact.url());
		} catch (IllegalArgumentException exception) {
			throw new Failure("DOWNLOAD", "CurseForge supplied an invalid download address for " + artifact.name() + ".");
		}
		if (!approved(uri)) throw new Failure("DOWNLOAD", "The download for " + artifact.name() + " is not on an approved CurseForge host.");
		HttpClient client = httpClientBuilder().connectTimeout(Duration.ofSeconds(20))
			.followRedirects(HttpClient.Redirect.NORMAL).build();
		HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5))
			.header("User-Agent", "EverHost/2.2.0-beta.11")
			.header("Accept", "application/java-archive, application/octet-stream;q=0.9, */*;q=0.1")
			.build();
		HttpResponse<Path> response;
		try {
			response = client.send(request, HttpResponse.BodyHandlers.ofFile(target));
		} catch (Exception exception) {
			throw new Failure("DOWNLOAD", "Could not download " + artifact.name() + " from CurseForge: " + safeMessage(exception));
		}
		if (response.statusCode() != 200) {
			Files.deleteIfExists(target);
			throw new Failure("DOWNLOAD", "CurseForge returned HTTP " + response.statusCode() + " for " + artifact.name() + ".");
		}
		if (!approved(response.uri())) {
			Files.deleteIfExists(target);
			throw new Failure("DOWNLOAD", "CurseForge redirected " + artifact.name() + " to an unapproved host.");
		}
	}

	private static void verifyDownloaded(Path file, Artifact artifact) throws Exception {
		long actualSize = Files.size(file);
		if (actualSize <= 0L || actualSize > MAX_MOD_BYTES) {
			Files.deleteIfExists(file);
			throw new Failure("DOWNLOAD", artifact.name() + " downloaded with an invalid file size.");
		}
		if (artifact.size() > 0L && actualSize != artifact.size()) {
			Files.deleteIfExists(file);
			throw new Failure("SIZE", artifact.name() + " did not match the exact server file size.");
		}
		String actualSha256 = sha256(file);
		if (!MessageDigest.isEqual(actualSha256.getBytes(StandardCharsets.US_ASCII),
			artifact.sha256().getBytes(StandardCharsets.US_ASCII))) {
			Files.deleteIfExists(file);
			throw new Failure("CHECKSUM", artifact.name() + " did not match the server's SHA-256 fingerprint.");
		}
		if (!artifact.sha1().isBlank()) {
			String actualSha1 = digest(file, "SHA-1");
			if (!MessageDigest.isEqual(actualSha1.getBytes(StandardCharsets.US_ASCII),
				artifact.sha1().getBytes(StandardCharsets.US_ASCII))) {
				Files.deleteIfExists(file);
				throw new Failure("CHECKSUM", artifact.name() + " did not match CurseForge's SHA-1 fingerprint.");
			}
		}
		verifyJar(file, artifact);
	}

	private static void verifyJar(Path file, Artifact artifact) throws Exception {
		Set<String> ids = jarModIds(file);
		if (!ids.containsAll(artifact.modIds())) {
			throw new Failure("IDENTITY", artifact.name() + " did not contain the mod IDs advertised by the server.");
		}
	}

	static InstallSummary applyPrepared(Path mods, Path backups, List<PreparedArtifact> prepared) throws Exception {
		Files.createDirectories(mods);
		Map<Path, Set<String>> jarIds = scanJarIds(mods);
		LinkedHashSet<Path> conflicts = new LinkedHashSet<>();
		for (PreparedArtifact item : prepared) {
			if (item.alreadyExact()) continue;
			Path target = mods.resolve(item.artifact().fileName()).toAbsolutePath().normalize();
			if (Files.isRegularFile(target)) conflicts.add(target);
			for (Map.Entry<Path, Set<String>> entry : jarIds.entrySet()) {
				if (!Collections.disjoint(entry.getValue(), item.artifact().modIds())) conflicts.add(entry.getKey());
			}
		}

		List<MoveRecord> archived = new ArrayList<>();
		List<Path> installed = new ArrayList<>();
		try {
			if (!conflicts.isEmpty()) Files.createDirectories(backups);
			int sequence = 0;
			for (Path original : conflicts) {
				if (!Files.isRegularFile(original)) continue;
				Path backup = uniqueBackup(backups, original.getFileName().toString(), sequence++);
				move(original, backup);
				archived.add(new MoveRecord(original, backup));
			}
			for (PreparedArtifact item : prepared) {
				if (item.alreadyExact()) continue;
				Path target = mods.resolve(item.artifact().fileName()).toAbsolutePath().normalize();
				move(item.staged(), target);
				installed.add(target);
				if (!sha256(target).equalsIgnoreCase(item.artifact().sha256())) {
					throw new IOException("Installed checksum changed for " + item.artifact().name());
				}
			}
			return new InstallSummary(installed.size(), archived.size(), backups);
		} catch (Exception failure) {
			for (int index = installed.size() - 1; index >= 0; index--) Files.deleteIfExists(installed.get(index));
			List<String> rollbackFailures = new ArrayList<>();
			for (int index = archived.size() - 1; index >= 0; index--) {
				MoveRecord record = archived.get(index);
				try {
					move(record.backup(), record.original());
				} catch (Exception rollbackFailure) {
					rollbackFailures.add(record.original().getFileName() + ": " + safeMessage(rollbackFailure));
				}
			}
			if (!rollbackFailures.isEmpty()) {
				throw new Failure("ROLLBACK", "Installation failed and these files could not be restored: "
					+ String.join("; ", rollbackFailures));
			}
			throw new Failure("INSTALL", "No changes were kept because installation failed: " + safeMessage(failure));
		}
	}

	private static Plan readPlan(Path expectedGame, Path path) throws Exception {
		Properties values = new Properties();
		try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			values.load(reader);
		} catch (IOException exception) {
			throw new Failure("PLAN", "The pending installation plan could not be read.");
		}
		String format = values.getProperty("format", "");
		if (!"EVERHOST_MOD_INSTALL_V1".equals(format) && !"EVERHOST_MOD_INSTALL_V2".equals(format)) {
			throw new Failure("PLAN", "The pending installation plan has an unsupported format.");
		}
		Path plannedGame;
		try {
			plannedGame = Path.of(values.getProperty("gameDirectory", "")).toAbsolutePath().normalize();
		} catch (RuntimeException exception) {
			throw new Failure("PLAN", "The pending installation plan has an invalid profile path.");
		}
		if (!plannedGame.equals(expectedGame)) throw new Failure("PLAN", "The installation plan belongs to a different CurseForge profile.");
		int count = integer(values.getProperty("count"), -1);
		if (count < 1 || count > 8192) throw new Failure("PLAN", "The installation plan has an invalid mod count.");
		List<Artifact> artifacts = new ArrayList<>();
		Set<String> fileNames = new HashSet<>();
		for (int index = 0; index < count; index++) {
			String prefix = "mod." + index + ".";
			String name = values.getProperty(prefix + "name", "Required mod").trim();
			Set<String> ids = new LinkedHashSet<>();
			for (String id : values.getProperty(prefix + "ids", "").split(",")) if (!id.isBlank()) ids.add(id.trim());
			String fileName = values.getProperty(prefix + "file", "").trim();
			String sha256 = values.getProperty(prefix + "sha256", "").trim().toLowerCase(Locale.ROOT);
			String sha1 = values.getProperty(prefix + "sha1", "").trim().toLowerCase(Locale.ROOT);
			String url = values.getProperty(prefix + "url", "").trim();
			String serverUrl = values.getProperty(prefix + "serverUrl", "").trim();
			String serverToken = values.getProperty(prefix + "serverToken", "").trim();
			long projectId = longValue(values.getProperty(prefix + "projectId"), -1L);
			long fileId = longValue(values.getProperty(prefix + "fileId"), -1L);
			long size = longValue(values.getProperty(prefix + "size"), -1L);
			if (ids.isEmpty() || safeChild(expectedGame.resolve("mods"), fileName) == null
				|| !fileNames.add(fileName.toLowerCase(Locale.ROOT)) || !sha256.matches("[0-9a-f]{64}")
				|| (!sha1.isBlank() && !sha1.matches("[0-9a-f]{40}"))
				|| size < 0L || size > MAX_MOD_BYTES) {
				throw new Failure("PLAN", "The exact server metadata for " + name + " is incomplete or invalid.");
			}
			boolean direct = validDirectSource(serverUrl, serverToken, sha256);
			boolean curseForge = projectId > 0L && fileId > 0L && approvedUrl(url);
			if (!direct && !curseForge) {
				throw new Failure("PLAN", name + " has no valid direct server or CurseForge download source.");
			}
			artifacts.add(new Artifact(name, Set.copyOf(ids), fileName, projectId, fileId, url, sha256, sha1, size,
				serverUrl, serverToken));
		}
		return new Plan(List.copyOf(artifacts), Boolean.parseBoolean(values.getProperty("reopenCurseForge", "true")));
	}

	private static Map<Path, Set<String>> scanJarIds(Path mods) throws IOException {
		Map<Path, Set<String>> result = new LinkedHashMap<>();
		if (!Files.isDirectory(mods)) return result;
		try (var paths = Files.list(mods)) {
			for (Path path : paths.filter(Files::isRegularFile)
				.filter(file -> file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
				.sorted(Comparator.comparing(file -> file.getFileName().toString(), String.CASE_INSENSITIVE_ORDER)).toList()) {
				try {
					result.put(path.toAbsolutePath().normalize(), jarModIds(path));
				} catch (IOException ignored) {
					result.put(path.toAbsolutePath().normalize(), Set.of());
				}
			}
		}
		return result;
	}

	private static Set<String> jarModIds(Path jar) throws IOException {
		Set<String> ids = new LinkedHashSet<>();
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry fabric = zip.getEntry("fabric.mod.json");
			if (fabric != null) {
				String json = readEntry(zip, fabric);
				Matcher matcher = FABRIC_ID.matcher(json);
				if (matcher.find()) ids.add(matcher.group(1));
			}
			ZipEntry forge = zip.getEntry("META-INF/mods.toml");
			if (forge == null) forge = zip.getEntry("META-INF/neoforge.mods.toml");
			if (forge != null) {
				String toml = readEntry(zip, forge);
				int dependencies = toml.indexOf("[[dependencies.");
				if (dependencies >= 0) toml = toml.substring(0, dependencies);
				Matcher matcher = FORGE_ID.matcher(toml);
				while (matcher.find()) ids.add(matcher.group(1));
			}
		}
		return Set.copyOf(ids);
	}

	private static String readEntry(ZipFile zip, ZipEntry entry) throws IOException {
		if (entry.getSize() > MAX_METADATA_BYTES) throw new IOException("Mod metadata is unexpectedly large");
		try (InputStream input = zip.getInputStream(entry)) {
			byte[] bytes = input.readNBytes((int)MAX_METADATA_BYTES + 1);
			if (bytes.length > MAX_METADATA_BYTES) throw new IOException("Mod metadata is unexpectedly large");
			return new String(bytes, StandardCharsets.UTF_8);
		}
	}

	private static Path findExactJar(Path mods, String expectedHash) throws IOException {
		if (!Files.isDirectory(mods)) return null;
		try (var paths = Files.list(mods)) {
			for (Path jar : paths.filter(Files::isRegularFile)
				.filter(file -> file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")).toList()) {
				try {
					if (sha256(jar).equalsIgnoreCase(expectedHash)) return jar;
				} catch (IOException ignored) {
				}
			}
		}
		return null;
	}

	private static void writeSuccess(
		Path result, List<Artifact> artifacts, InstallSummary summary, String reopenWarning
	) throws IOException {
		Properties values = new Properties();
		values.setProperty("status", "success");
		values.setProperty("completedAt", Instant.now().toString());
		values.setProperty("message", "All mods have been successfully installed.");
		values.setProperty("count", Integer.toString(artifacts.size()));
		values.setProperty("installed", Integer.toString(summary.installed()));
		values.setProperty("archived", Integer.toString(summary.archived()));
		if (!reopenWarning.isBlank()) values.setProperty("reopenWarning", reopenWarning);
		for (int index = 0; index < artifacts.size(); index++) {
			Artifact artifact = artifacts.get(index);
			values.setProperty("mod." + index + ".name", artifact.name());
			values.setProperty("mod." + index + ".file", artifact.fileName());
			values.setProperty("mod." + index + ".sha256", artifact.sha256());
		}
		store(result, values, "EverHost automatic mod installation result");
	}

	private static void writeFailure(Path result, String code, String message) throws IOException {
		Properties values = new Properties();
		values.setProperty("status", "failure");
		values.setProperty("completedAt", Instant.now().toString());
		values.setProperty("code", code);
		values.setProperty("message", message == null ? "Unknown installer failure." : message);
		store(result, values, "EverHost automatic mod installation failure");
	}

	private static void store(Path target, Properties values, String comment) throws IOException {
		Files.createDirectories(target.getParent());
		Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
		try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
			values.store(writer, comment);
		}
		move(temporary, target);
	}

	private static void waitForExit(long parentPid) throws Exception {
		long deadline = System.currentTimeMillis() + 30L * 60L * 1000L;
		while (ProcessHandle.of(parentPid).map(ProcessHandle::isAlive).orElse(false)) {
			if (System.currentTimeMillis() >= deadline) {
				throw new Failure("SHUTDOWN", "Minecraft did not close within 30 minutes, so no mods were changed.");
			}
			Thread.sleep(500L);
		}
		Thread.sleep(750L);
	}

	private static String reopenCurseForge() {
		List<Path> candidates = new ArrayList<>();
		String x86 = System.getenv("ProgramFiles(x86)");
		if (x86 != null && !x86.isBlank()) candidates.add(Path.of(x86, "Overwolf", "OverwolfLauncher.exe"));
		String programFiles = System.getenv("ProgramFiles");
		if (programFiles != null && !programFiles.isBlank()) candidates.add(Path.of(programFiles, "Overwolf", "OverwolfLauncher.exe"));
		for (Path launcher : candidates) {
			if (!Files.isRegularFile(launcher)) continue;
			try {
				new ProcessBuilder(launcher.toString(), "-launchapp", CURSEFORGE_APP_ID).start();
				return "";
			} catch (IOException exception) {
				return "Mods installed, but CurseForge could not be reopened: " + safeMessage(exception);
			}
		}
		return "Mods installed, but the CurseForge Overwolf launcher was not found.";
	}

	private static HttpClient.Builder httpClientBuilder() {
		HttpClient.Builder builder = HttpClient.newBuilder();
		if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return builder;
		try {
			KeyStore windowsRoots = KeyStore.getInstance("Windows-ROOT");
			windowsRoots.load(null, null);
			TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
			trustManagers.init(windowsRoots);
			SSLContext context = SSLContext.getInstance("TLS");
			context.init(null, trustManagers.getTrustManagers(), new SecureRandom());
			return builder.sslContext(context);
		} catch (Exception ignored) {
			return builder;
		}
	}

	private static void archivePlan(Path plan) {
		if (!Files.isRegularFile(plan)) return;
		try {
			move(plan, plan.resolveSibling("last-plan.properties"));
		} catch (IOException ignored) {
		}
	}

	private static Path uniqueBackup(Path backups, String fileName, int sequence) {
		String prefix = String.format(Locale.ROOT, "%04d-", sequence);
		Path candidate = backups.resolve(prefix + fileName);
		int duplicate = 1;
		while (Files.exists(candidate)) candidate = backups.resolve(prefix + duplicate++ + "-" + fileName);
		return candidate;
	}

	private static Path safeChild(Path parent, String fileName) {
		if (fileName == null || fileName.isBlank()) return null;
		try {
			Path base = parent.toAbsolutePath().normalize();
			Path child = base.resolve(fileName).toAbsolutePath().normalize();
			return base.equals(child.getParent()) && fileName.toLowerCase(Locale.ROOT).endsWith(".jar") ? child : null;
		} catch (RuntimeException exception) {
			return null;
		}
	}

	private static boolean approved(URI uri) {
		String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
		return "https".equalsIgnoreCase(uri.getScheme()) && CURSEFORGE_HOSTS.contains(host);
	}

	private static boolean approvedUrl(String value) {
		try {
			return approved(URI.create(value));
		} catch (IllegalArgumentException exception) {
			return false;
		}
	}

	private static boolean hasCurseForgeSource(Artifact artifact) {
		return artifact.projectId() > 0L && artifact.fileId() > 0L && approvedUrl(artifact.url());
	}

	private static boolean validDirectSource(String value, String token, String sha256) {
		if (value == null || token == null || sha256 == null || !token.matches("[0-9a-fA-F]{64}")) return false;
		try {
			URI uri = URI.create(value);
			boolean supportedScheme = "everhost".equalsIgnoreCase(uri.getScheme())
				|| "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
			return supportedScheme
				&& uri.getHost() != null && !uri.getHost().isBlank() && uri.getUserInfo() == null
				&& uri.getPort() > 0
				&& uri.getQuery() == null && uri.getFragment() == null
				&& ("/mods/" + sha256.toLowerCase(Locale.ROOT)).equals(uri.getPath());
		} catch (IllegalArgumentException exception) {
			return false;
		}
	}

	private static String sha256(Path path) throws IOException {
		return digest(path, "SHA-256");
	}

	private static String digest(Path path, String algorithm) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance(algorithm);
			try (InputStream input = Files.newInputStream(path)) {
				byte[] buffer = new byte[64 * 1024];
				for (int read; (read = input.read(buffer)) >= 0;) {
					if (read > 0) digest.update(buffer, 0, read);
				}
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException exception) {
			throw new IOException("This Java runtime does not provide " + algorithm, exception);
		}
	}

	private static void deleteDirectoryContents(Path directory) throws IOException {
		if (!Files.isDirectory(directory)) return;
		try (var paths = Files.walk(directory)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				if (!path.equals(directory)) Files.deleteIfExists(path);
			}
		}
	}

	private static void move(Path source, Path target) throws IOException {
		Files.createDirectories(target.getParent());
		try {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException exception) {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static void append(Path log, String message) throws IOException {
		Files.createDirectories(log.getParent());
		Files.writeString(log, Instant.now() + " " + message + System.lineSeparator(), StandardCharsets.UTF_8,
			StandardOpenOption.CREATE, StandardOpenOption.APPEND);
	}

	private static int integer(String value, int fallback) {
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException exception) {
			return fallback;
		}
	}

	private static long longValue(String value, long fallback) {
		try {
			return Long.parseLong(value);
		} catch (NumberFormatException exception) {
			return fallback;
		}
	}

	private static String safeMessage(Exception exception) {
		return exception.getMessage() == null ? exception.toString() : exception.getMessage();
	}

	static record Artifact(
		String name, Set<String> modIds, String fileName, long projectId, long fileId,
		String url, String sha256, String sha1, long size, String serverUrl, String serverToken
	) {
	}

	static record PreparedArtifact(Artifact artifact, Path staged, boolean alreadyExact) {
	}

	static record InstallSummary(int installed, int archived, Path backupDirectory) {
	}

	private record Plan(List<Artifact> artifacts, boolean reopenCurseForge) {
	}

	private record MoveRecord(Path original, Path backup) {
	}

	private static final class Failure extends IOException {
		private final String code;

		private Failure(String code, String message) {
			super(message);
			this.code = code;
		}
	}
}
