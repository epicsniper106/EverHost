package dev.everhost.update;

import dev.everhost.universal.MiniJson;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Automatic, checksum-verified EverHost updates delivered through GitHub Releases. */
public final class EverHostUpdater {
	private static final java.util.concurrent.atomic.AtomicBoolean INSTALLING = new java.util.concurrent.atomic.AtomicBoolean();
	private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");
	private static final Pattern CHECKSUM = Pattern.compile("(?i)^([0-9a-f]{64})\\s+\\*?(.*)$");
	private static final long MAX_RELEASE_LIST_BYTES = 2L * 1024L * 1024L;
	private static final long MAX_CHECKSUM_BYTES = 512L * 1024L;
	private static final long MAX_MOD_BYTES = 128L * 1024L * 1024L;

	private EverHostUpdater() {}

	/** Checks GitHub on launch but leaves the decision to download and install to the player. */
	public static void check(Path gameDirectory, Path installedJar, Consumer<Notice> notices) {
		Thread worker = new Thread(() -> {
			try {
				BuildInfo build = BuildInfo.load();
				if (build.repository().isBlank()) return;
				validateInstalledLocation(gameDirectory, installedJar);
				UpdateCandidate candidate = fetchCandidate(build);
				if (candidate == null) return;
				notices.accept(new Notice(NoticeType.AVAILABLE,
					"EverHost " + candidate.version() + " is available.", candidate));
			} catch (Exception exception) {
				notices.accept(new Notice(NoticeType.FAILED,
					exception.getMessage() == null ? exception.toString() : exception.getMessage(), null));
			}
		}, "EverHost update check");
		worker.setDaemon(true);
		worker.start();
	}

	/** Downloads the selected release, verifies it, and arms the after-shutdown installer. */
	public static void install(
		Path gameDirectory, Path javaExecutable, Path installedJar, UpdateCandidate candidate, Consumer<Notice> notices
	) {

        if (!INSTALLING.compareAndSet(false, true)) {
            notices.accept(new Notice(NoticeType.FAILED, "An EverHost update is already being prepared.", candidate));
            return;
        }
		Thread worker = new Thread(() -> {
			try {
				BuildInfo build = BuildInfo.load();
				validateInstalledLocation(gameDirectory, installedJar);
				if (candidate == null || compareVersions(candidate.version(), build.version()) <= 0) {
					throw new IOException("The selected EverHost update is no longer newer than this build");
				}
				Path staged = downloadAndVerify(gameDirectory, build, candidate);
				launchInstaller(gameDirectory, javaExecutable, installedJar, staged, candidate.sha256(), candidate.version());
				notices.accept(new Notice(NoticeType.READY,
					"EverHost " + candidate.version() + " is verified and ready to install.", candidate));
			} catch (Exception exception) {
				INSTALLING.set(false);
				notices.accept(new Notice(NoticeType.FAILED,
					exception.getMessage() == null ? exception.toString() : exception.getMessage(), candidate));
			}
		}, "EverHost update download");
		worker.setDaemon(true);
		worker.start();
	}

	/** Reads and independently verifies the result left by the after-shutdown installer. */
	public static Optional<Result> consumeResult(Path gameDirectory, Path installedJar) {
		Path updateRoot = gameDirectory.resolve("everhost").resolve("updates");
		Path resultFile = updateRoot.resolve("result.properties");
		if (!Files.isRegularFile(resultFile)) return Optional.empty();
		Properties values = new Properties();
		try (var reader = Files.newBufferedReader(resultFile, StandardCharsets.UTF_8)) {
			values.load(reader);
		} catch (Exception exception) {
			return Optional.of(new Result(false, "EverHost update check failed",
				"The update result could not be read: " + safeMessage(exception)));
		}
		try {
			Files.move(resultFile, updateRoot.resolve("last-result.properties"), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException ignored) {
			try { Files.deleteIfExists(resultFile); } catch (IOException ignoredAgain) { }
		}
		if (!"success".equalsIgnoreCase(values.getProperty("status", ""))) {
			return Optional.of(new Result(false, "EverHost update failed",
				values.getProperty("message", "The update installer did not finish successfully.")));
		}
		String version = values.getProperty("version", "").trim();
		String expected = values.getProperty("sha256", "").trim();
		try {
			if (!Files.isRegularFile(installedJar) || expected.length() != 64 || !sha256(installedJar).equalsIgnoreCase(expected)) {
				return Optional.of(new Result(false, "EverHost update verification failed",
					"The installed EverHost JAR does not match the verified GitHub release."));
			}
			BuildInfo build = BuildInfo.load();
			verifyJar(installedJar, build, version);
			return Optional.of(new Result(true, "EverHost updated",
				"EverHost " + version + " was installed successfully."));
		} catch (Exception exception) {
			return Optional.of(new Result(false, "EverHost update verification failed", safeMessage(exception)));
		}
	}

	static UpdateCandidate selectCandidate(String releasesJson, BuildInfo build) throws IOException {
		Object parsed = MiniJson.parse(releasesJson);
		if (!(parsed instanceof List<?> releases)) throw new IOException("GitHub returned an invalid release list");
		UpdateCandidate selected = null;
		for (Object rawRelease : releases) {
			Map<String, Object> release = object(rawRelease);
			if (Boolean.TRUE.equals(release.get("draft"))) continue;
			String version = string(release.get("tag_name"));
			if (version.startsWith("v") || version.startsWith("V")) version = version.substring(1);
			if (compareVersions(version, build.version()) <= 0) continue;
			URI jar = null;
			URI sums = null;
			for (Object rawAsset : list(release.get("assets"))) {
				Map<String, Object> asset = object(rawAsset);
				String name = string(asset.get("name"));
				if (name.equals(build.assetName())) jar = secureUri(string(asset.get("browser_download_url")));
				if (name.equals("SHA256SUMS.txt")) sums = secureUri(string(asset.get("browser_download_url")));
			}
			if (jar == null || sums == null) continue;
			UpdateCandidate candidate = new UpdateCandidate(version, jar, sums, "");
			if (selected == null || compareVersions(candidate.version(), selected.version()) > 0) selected = candidate;
		}
		return selected;
	}

	static String checksumFor(String checksums, String assetName) throws IOException {
		for (String line : checksums.lines().toList()) {
			Matcher matcher = CHECKSUM.matcher(line.trim());
			if (matcher.matches() && matcher.group(2).trim().equals(assetName)) {
				return matcher.group(1).toLowerCase(Locale.ROOT);
			}
		}
		throw new IOException("The release does not contain a checksum for " + assetName);
	}

	static int compareVersions(String left, String right) {
		Version a = Version.parse(left);
		Version b = Version.parse(right);
		for (int index = 0; index < Math.max(a.numbers().size(), b.numbers().size()); index++) {
			BigInteger av = index < a.numbers().size() ? a.numbers().get(index) : BigInteger.ZERO;
			BigInteger bv = index < b.numbers().size() ? b.numbers().get(index) : BigInteger.ZERO;
			int compared = av.compareTo(bv);
			if (compared != 0) return compared;
		}
		if (a.preRelease().isEmpty() && b.preRelease().isEmpty()) return 0;
		if (a.preRelease().isEmpty()) return 1;
		if (b.preRelease().isEmpty()) return -1;
		for (int index = 0; index < Math.max(a.preRelease().size(), b.preRelease().size()); index++) {
			if (index >= a.preRelease().size()) return -1;
			if (index >= b.preRelease().size()) return 1;
			String av = a.preRelease().get(index);
			String bv = b.preRelease().get(index);
			boolean an = av.chars().allMatch(Character::isDigit);
			boolean bn = bv.chars().allMatch(Character::isDigit);
			int compared = an && bn ? new BigInteger(av).compareTo(new BigInteger(bv))
				: an != bn ? (an ? -1 : 1) : av.compareToIgnoreCase(bv);
			if (compared != 0) return compared;
		}
		return 0;
	}

	private static UpdateCandidate fetchCandidate(BuildInfo build) throws Exception {
		URI releases = URI.create("https://api.github.com/repos/" + build.repository() + "/releases?per_page=20");
		HttpClient http = httpClientBuilder().connectTimeout(Duration.ofSeconds(15))
			.followRedirects(HttpClient.Redirect.NORMAL).build();
		String body = getText(http, releases, MAX_RELEASE_LIST_BYTES, build.version());
		UpdateCandidate candidate = selectCandidate(body, build);
		if (candidate == null) return null;
		String sums = getText(http, candidate.checksumUri(), MAX_CHECKSUM_BYTES, build.version());
		return new UpdateCandidate(candidate.version(), candidate.downloadUri(), candidate.checksumUri(),
			checksumFor(sums, build.assetName()));
	}

	private static Path downloadAndVerify(Path gameDirectory, BuildInfo build, UpdateCandidate candidate) throws Exception {
		Path updates = gameDirectory.resolve("everhost").resolve("updates");
		Files.createDirectories(updates);
		Path temporary = updates.resolve(build.assetName() + ".download");
		Path staged = updates.resolve(build.assetName() + ".pending");
		HttpClient http = httpClientBuilder().connectTimeout(Duration.ofSeconds(20))
			.followRedirects(HttpClient.Redirect.NORMAL).build();
		HttpRequest request = request(candidate.downloadUri(), build.version()).timeout(Duration.ofMinutes(3)).build();
		HttpResponse<Path> response = http.send(request, HttpResponse.BodyHandlers.ofFile(temporary));
		if (response.statusCode() != 200) throw new IOException("GitHub download failed with HTTP " + response.statusCode());
		if (Files.size(temporary) > MAX_MOD_BYTES) throw new IOException("The downloaded EverHost JAR is unexpectedly large");
		String actual = sha256(temporary);
		if (!MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII), candidate.sha256().getBytes(StandardCharsets.US_ASCII))) {
			Files.deleteIfExists(temporary);
			throw new IOException("The downloaded EverHost checksum did not match the release");
		}
		verifyJar(temporary, build, candidate.version());
		move(temporary, staged);
		return staged;
	}

	private static HttpClient.Builder httpClientBuilder() {
		HttpClient.Builder builder = HttpClient.newBuilder();
		// Use the OS trust roots on Windows, as the mod installer does. This retains
		// certificate and hostname validation without changing the game's global TLS settings.
		if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")
			|| System.getProperty("javax.net.ssl.trustStore") != null) return builder;
		try {
			java.security.KeyStore roots = java.security.KeyStore.getInstance("Windows-ROOT");
			roots.load(null, null);
			javax.net.ssl.TrustManagerFactory managers = javax.net.ssl.TrustManagerFactory.getInstance(
				javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
			managers.init(roots);
			javax.net.ssl.SSLContext context = javax.net.ssl.SSLContext.getInstance("TLS");
			context.init(null, managers.getTrustManagers(), new java.security.SecureRandom());
			return builder.sslContext(context);
		} catch (Exception unavailable) {
			return builder; // Java's normal validating trust manager remains the fallback.
		}
	}

	private static void verifyJar(Path jarPath, BuildInfo build, String expectedVersion) throws Exception {
		try (JarFile jar = new JarFile(jarPath.toFile())) {
			if ("fabric".equals(build.loader())) {
				var entry = jar.getJarEntry("fabric.mod.json");
				if (entry == null) throw new IOException("The downloaded file is not an EverHost Fabric JAR");
				try (InputStream input = jar.getInputStream(entry)) {
					Map<String, Object> metadata = object(MiniJson.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8)));
					if (!"everhost".equals(string(metadata.get("id"))) || !expectedVersion.equals(string(metadata.get("version")))) {
						throw new IOException("The downloaded Fabric JAR identity does not match its release");
					}
				}
			} else {
				var entry = jar.getJarEntry("META-INF/mods.toml");
				if (entry == null) throw new IOException("The downloaded file is not an EverHost Forge JAR");
				try (InputStream input = jar.getInputStream(entry)) {
					String metadata = new String(input.readAllBytes(), StandardCharsets.UTF_8);
					if (!metadata.matches("(?s).*modId\\s*=\\s*\"everhost\".*")) {
						throw new IOException("The downloaded Forge JAR identity does not match EverHost");
					}
				}
				String implementationVersion = jar.getManifest() == null ? ""
					: jar.getManifest().getMainAttributes().getValue("Implementation-Version");
				if (!expectedVersion.equals(implementationVersion)) {
					throw new IOException("The downloaded Forge JAR version does not match its release");
				}
			}
			var updateEntry = jar.getJarEntry("everhost-update.properties");
			if (updateEntry == null) throw new IOException("The downloaded JAR has no EverHost update identity");
			Properties identity = new Properties();
			try (InputStream input = jar.getInputStream(updateEntry)) { identity.load(input); }
			if (!expectedVersion.equals(identity.getProperty("version", "").trim())
				|| !build.loader().equals(identity.getProperty("loader", "").trim())
				|| !build.minecraft().equals(identity.getProperty("minecraft", "").trim())
				|| !build.repository().equals(identity.getProperty("repository", "").trim())
				|| !build.assetName().equals(identity.getProperty("asset", "").trim())) {
				throw new IOException("The downloaded JAR targets a different EverHost build");
			}
		}
	}


    static void launchInstaller(
        Path gameDirectory, Path javaExecutable, Path installedJar, Path staged, String expectedSha256, String version
    ) throws Exception {
        Path updateRoot = gameDirectory.resolve("everhost").resolve("updates");
        Path installer = writeInstaller(updateRoot);
        Path backups = gameDirectory.resolve("everhost").resolve("previous-mod-jars").resolve("automatic-updates");
        Path log = updateRoot.resolve("installer.log");
        Path marker = updateRoot.resolve("pending.properties");
        Path result = updateRoot.resolve("result.properties");
        String nonce = java.util.UUID.randomUUID().toString();
        Path ready = updateRoot.resolve("installer-" + nonce + ".ready");
        Files.createDirectories(backups);
        // The lock survives client shutdown in the worker. Do not replace a live installer's plan.
        try (var channel = java.nio.channels.FileChannel.open(updateRoot.resolve("installer.lock"),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
             var lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Another EverHost updater is already waiting for Minecraft to close.");
            Files.deleteIfExists(result);
            Properties pending = new Properties();
            pending.setProperty("installed", installedJar.toString());
            pending.setProperty("staged", staged.toString());
            pending.setProperty("sha256", expectedSha256);
            pending.setProperty("version", version);
            try (var writer = Files.newBufferedWriter(marker, StandardCharsets.UTF_8)) {
                pending.store(writer, "EverHost automatic update");
            }
        }
        Path launcher = javaExecutable;
        if (isWindows()) {
            Path javaw = javaExecutable.resolveSibling("javaw.exe");
            if (Files.isRegularFile(javaw)) launcher = javaw;
        }
        Process process = new ProcessBuilder(
            launcher.toString(), "-cp", installer.toString(), EverHostUpdateInstaller.class.getName(),
            Long.toString(ProcessHandle.current().pid()), installedJar.toString(), staged.toString(),
            backups.toString(), expectedSha256, log.toString(), marker.toString(), version, result.toString(),
            "true", ready.toString(), nonce, sha256(installedJar)
        ).directory(gameDirectory.toFile()).redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
        try {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
            while (System.nanoTime() < deadline) {
                if (!process.isAlive()) {
                    String message = "The EverHost installer could not start. See everhost/updates/installer.log.";
                    if (Files.isRegularFile(result)) {
                        Properties values = new Properties();
                        try (var reader = Files.newBufferedReader(result, StandardCharsets.UTF_8)) { values.load(reader); }
                        message = values.getProperty("message", message);
                    }
                    throw new IOException(message + " Minecraft has been left open.");
                }
                if (Files.isRegularFile(ready) && nonce.equals(Files.readString(ready, StandardCharsets.UTF_8))) return;
                Thread.sleep(100L);
            }
            throw new IOException("The EverHost installer did not become ready. Minecraft has been left open.");
        } catch (Exception failure) {
            process.destroy();
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly();
            Files.deleteIfExists(marker);
            throw failure;
        } finally { Files.deleteIfExists(ready); }
    }

    static Path writeInstaller(Path updateRoot) throws Exception {
        Files.createDirectories(updateRoot);
        String entry = EverHostUpdateInstaller.class.getName().replace('.', '/') + ".class";
        byte[] compiled;
        try (InputStream input = EverHostUpdater.class.getResourceAsStream("/" + entry)) {
            if (input == null) throw new IOException("The precompiled EverHost installer is missing from this build.");
            compiled = input.readAllBytes();
        }
        String id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(compiled)).substring(0, 16);
        Path installer = updateRoot.resolve("everhost-installer-" + id + ".jar");
        if (Files.isRegularFile(installer)) {
            try (JarFile existing = new JarFile(installer.toFile())) {
                var stored = existing.getJarEntry(entry);
                if (stored != null) {
                    try (InputStream input = existing.getInputStream(stored)) {
                        if (java.util.Arrays.equals(compiled, input.readAllBytes())) return installer;
                    }
                }
            } catch (IOException ignored) { }
        }
        Path temporary = Files.createTempFile(updateRoot, "everhost-installer-", ".tmp");
        try {
            try (var jar = new java.util.jar.JarOutputStream(Files.newOutputStream(temporary))) {
                jar.putNextEntry(new java.util.jar.JarEntry(entry));
                jar.write(compiled);
                jar.closeEntry();
            }
            move(temporary, installer);
        } finally { Files.deleteIfExists(temporary); }
        return installer;
    }

	private static String getText(HttpClient http, URI uri, long maximumBytes, String version) throws Exception {
		HttpResponse<byte[]> response = http.send(request(uri, version).timeout(Duration.ofSeconds(45)).build(),
			HttpResponse.BodyHandlers.ofByteArray());
		if (response.statusCode() != 200) throw new IOException("GitHub update check failed with HTTP " + response.statusCode());
		if (response.body().length > maximumBytes) throw new IOException("GitHub returned an unexpectedly large update response");
		return new String(response.body(), StandardCharsets.UTF_8);
	}

	private static HttpRequest.Builder request(URI uri, String version) {
		return HttpRequest.newBuilder(uri)
			.header("Accept", "application/vnd.github+json")
			.header("User-Agent", "EverHost/" + version);
	}

	private static void validateInstalledLocation(Path gameDirectory, Path installedJar) throws IOException {
		Path mods = gameDirectory.toAbsolutePath().normalize().resolve("mods");
		Path installed = installedJar.toAbsolutePath().normalize();
		if (!Files.isRegularFile(installed) || !mods.equals(installed.getParent())) {
			throw new IOException("EverHost automatic updates require the installed JAR to be directly inside the profile's mods folder");
		}
	}

	private static String sha256(Path path) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		try (InputStream input = Files.newInputStream(path)) {
			byte[] buffer = new byte[64 * 1024];
			for (int read; (read = input.read(buffer)) >= 0;) digest.update(buffer, 0, read);
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	private static URI secureUri(String value) throws IOException {
		try {
			URI uri = URI.create(value);
			if (!"https".equalsIgnoreCase(uri.getScheme()) || !"github.com".equalsIgnoreCase(uri.getHost())) {
				throw new IOException("Release URL is not an official GitHub download");
			}
			return uri;
		} catch (IllegalArgumentException exception) {
			throw new IOException("GitHub returned an invalid release URL", exception);
		}
	}

	private static void move(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException exception) {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> object(Object value) {
		return value instanceof Map<?, ?> ? (Map<String, Object>)value : Map.of();
	}

	@SuppressWarnings("unchecked")
	private static List<Object> list(Object value) {
		return value instanceof List<?> ? (List<Object>)value : List.of();
	}

	private static String string(Object value) {
		return value instanceof String text ? text : "";
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}

	private static String safeMessage(Throwable exception) {
		String message = exception.getMessage();
		return message == null || message.isBlank() ? exception.toString() : message;
	}

	public enum NoticeType { AVAILABLE, READY, FAILED }
	public record Notice(NoticeType type, String message, UpdateCandidate candidate) {}
	public record Result(boolean success, String title, String message) {}
	public record BuildInfo(String version, String loader, String minecraft, String repository, String assetName) {
		static BuildInfo load() throws IOException {
			Properties values = new Properties();
			try (InputStream input = EverHostUpdater.class.getResourceAsStream("/everhost-update.properties")) {
				if (input == null) throw new IOException("EverHost update metadata is missing");
				values.load(input);
			}
			String repository = values.getProperty("repository", "").trim();
			if (!repository.isBlank() && !REPOSITORY.matcher(repository).matches()) {
				throw new IOException("EverHost's GitHub repository setting is invalid");
			}
			return new BuildInfo(values.getProperty("version", "").trim(), values.getProperty("loader", "").trim(),
				values.getProperty("minecraft", "").trim(), repository, values.getProperty("asset", "").trim());
		}
	}

	public record UpdateCandidate(String version, URI downloadUri, URI checksumUri, String sha256) {}

	private record Version(List<BigInteger> numbers, List<String> preRelease) {
		static Version parse(String raw) {
			String value = raw == null ? "" : raw.trim();
			if (value.startsWith("v") || value.startsWith("V")) value = value.substring(1);
			int metadata = value.indexOf('+');
			if (metadata >= 0) value = value.substring(0, metadata);
			String[] halves = value.split("-", 2);
			List<BigInteger> numbers = new ArrayList<>();
			for (String token : halves[0].split("\\.")) {
				try { numbers.add(new BigInteger(token)); }
				catch (NumberFormatException exception) { numbers.add(BigInteger.ZERO); }
			}
			List<String> preRelease = halves.length == 1 ? List.of() : List.of(halves[1].split("[.-]"));
			return new Version(numbers, preRelease);
		}
	}

}
