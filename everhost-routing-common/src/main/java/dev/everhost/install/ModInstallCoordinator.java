package dev.everhost.install;

import dev.everhost.universal.RequiredModsManifest.Requirement;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/** Holds the last server offer and starts the out-of-process exact-mod installer. */
public final class ModInstallCoordinator {
	private static final long OFFER_LIFETIME_MILLIS = 5L * 60L * 1000L;
	private static volatile PendingOffer pendingOffer;

	private ModInstallCoordinator() {
	}

	public static List<Requirement> assess(
		List<Requirement> requirements, Set<String> installedModIds, Set<String> installedJarHashes
	) {
		List<Requirement> missing = requirements.stream()
			.filter(requirement -> !requirement.satisfiedBy(installedModIds, installedJarHashes))
			.toList();
		pendingOffer = missing.isEmpty() ? null : new PendingOffer(List.copyOf(missing), System.currentTimeMillis());
		return missing;
	}

	public static Set<String> hashes(Collection<Path> paths) {
		Set<String> hashes = new LinkedHashSet<>();
		Set<Path> visited = new HashSet<>();
		for (Path raw : paths) {
			if (raw == null) continue;
			Path path = raw.toAbsolutePath().normalize();
			if (!visited.add(path) || !Files.isRegularFile(path)) continue;
			try {
				hashes.add(sha256(path));
			} catch (IOException ignored) {
				// An unreadable loader path cannot prove that an exact server JAR is installed.
			}
		}
		return Set.copyOf(hashes);
	}

	public static boolean hasPendingOffer() {
		PendingOffer offer = liveOffer();
		return offer != null && !offer.requirements().isEmpty();
	}

	public static int pendingCount() {
		PendingOffer offer = liveOffer();
		return offer == null ? 0 : offer.requirements().size();
	}

	public static Launch launch(Path gameDirectory, Path javaExecutable, Path everHostJar) throws IOException {
		PendingOffer offer = liveOffer();
		if (offer == null || offer.requirements().isEmpty()) {
			throw new IOException("The server did not provide a current mod installation offer. Reconnect and try again.");
		}
		Path game = gameDirectory.toAbsolutePath().normalize();
		Path mods = game.resolve("mods");
		Path instance = game.resolve("minecraftinstance.json");
		if (!Files.isRegularFile(instance) || !Files.isDirectory(mods)) {
			throw new IOException("Automatic installation requires this game to be launched from a CurseForge profile.");
		}
		Path installed = everHostJar.toAbsolutePath().normalize();
		if (!Files.isRegularFile(installed) || !mods.equals(installed.getParent())) {
			throw new IOException("EverHost must be installed directly in this CurseForge profile's mods folder.");
		}
		Set<String> fileNames = new HashSet<>();
		for (Requirement requirement : offer.requirements()) {
			String problem = requirement.automaticInstallProblem();
			if (!problem.isBlank()) throw new IOException(problem);
			if (!fileNames.add(requirement.fileName().toLowerCase(Locale.ROOT))) {
				throw new IOException("The server supplied the same target filename for more than one required mod.");
			}
		}

		Path root = game.resolve("everhost").resolve("mod-installer");
		Path plan = root.resolve("pending.properties");
		Path result = root.resolve("result.properties");
		Path log = root.resolve("installer.log");
		Files.createDirectories(root);
		Files.deleteIfExists(result);
		writePlan(plan, game, offer.requirements());

		Path launcher = javaExecutable.toAbsolutePath().normalize();
		if (isWindows()) {
			Path javaw = launcher.resolveSibling("javaw.exe");
			if (Files.isRegularFile(javaw)) launcher = javaw;
		}
		new ProcessBuilder(
			launcher.toString(), "-cp", installed.toString(), ModInstallWorker.class.getName(),
			Long.toString(ProcessHandle.current().pid()), game.toString(), plan.toString(), result.toString(), log.toString()
		).directory(game.toFile()).redirectErrorStream(true)
			.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
		pendingOffer = null;
		return new Launch(offer.requirements().size(), root);
	}

	/** Reads and independently verifies the worker result on the next Minecraft launch. */
	public static Optional<Result> consumeResult(Path gameDirectory) {
		Path root = gameDirectory.toAbsolutePath().normalize().resolve("everhost").resolve("mod-installer");
		Path resultFile = root.resolve("result.properties");
		if (!Files.isRegularFile(resultFile)) return Optional.empty();
		Properties values = new Properties();
		try (var reader = Files.newBufferedReader(resultFile, StandardCharsets.UTF_8)) {
			values.load(reader);
			String status = values.getProperty("status", "failure");
			if (!"success".equals(status)) {
				return Optional.of(archiveResult(root, resultFile, new Result(false,
					"Automatic mod installation failed", detail(values, "The installer did not report a reason."))));
			}
			int count = integer(values.getProperty("count"), -1);
			if (count < 0 || count > 8192) {
				return Optional.of(archiveResult(root, resultFile, new Result(false,
					"Mod verification failed", "The install result file is damaged.")));
			}
			List<String> failures = new ArrayList<>();
			Path mods = gameDirectory.toAbsolutePath().normalize().resolve("mods");
			for (int index = 0; index < count; index++) {
				String name = values.getProperty("mod." + index + ".name", "Required mod");
				String fileName = values.getProperty("mod." + index + ".file", "");
				String expected = values.getProperty("mod." + index + ".sha256", "");
				Path file = safeChild(mods, fileName);
				if (file == null || !Files.isRegularFile(file)) {
					failures.add(name + " is missing from the mods folder.");
					continue;
				}
				try {
					if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
						sha256(file).getBytes(StandardCharsets.US_ASCII))) {
						failures.add(name + " does not match the server's exact file.");
					}
				} catch (IOException exception) {
					failures.add(name + " could not be checked: " + safeMessage(exception));
				}
			}
			if (!failures.isEmpty()) {
				return Optional.of(archiveResult(root, resultFile, new Result(false,
					"Mod verification failed", String.join(" ", failures))));
			}
			return Optional.of(archiveResult(root, resultFile, new Result(true,
				"EverHost", "All mods have been successfully installed.")));
		} catch (Exception exception) {
			try {
				return Optional.of(archiveResult(root, resultFile, new Result(false,
					"Mod verification failed", safeMessage(exception))));
			} catch (IOException ignored) {
				return Optional.of(new Result(false, "Mod verification failed", safeMessage(exception)));
			}
		}
	}

	public static String sha256(Path path) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (InputStream input = Files.newInputStream(path)) {
				byte[] buffer = new byte[64 * 1024];
				for (int read; (read = input.read(buffer)) >= 0;) {
					if (read > 0) digest.update(buffer, 0, read);
				}
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException exception) {
			throw new IOException("This Java runtime does not provide SHA-256", exception);
		}
	}

	private static void writePlan(Path target, Path game, List<Requirement> requirements) throws IOException {
		Properties plan = new Properties();
		plan.setProperty("format", "EVERHOST_MOD_INSTALL_V2");
		plan.setProperty("createdAt", Instant.now().toString());
		plan.setProperty("gameDirectory", game.toString());
		plan.setProperty("reopenCurseForge", "true");
		plan.setProperty("count", Integer.toString(requirements.size()));
		for (int index = 0; index < requirements.size(); index++) {
			Requirement requirement = requirements.get(index);
			String prefix = "mod." + index + ".";
			plan.setProperty(prefix + "name", requirement.name());
			plan.setProperty(prefix + "ids", String.join(",", requirement.modIds()));
			plan.setProperty(prefix + "version", requirement.version());
			plan.setProperty(prefix + "file", requirement.fileName());
			plan.setProperty(prefix + "projectId", Long.toString(requirement.curseProjectId()));
			plan.setProperty(prefix + "fileId", Long.toString(requirement.curseFileId()));
			plan.setProperty(prefix + "url", requirement.downloadUrl());
			plan.setProperty(prefix + "sha256", requirement.sha256());
			plan.setProperty(prefix + "sha1", requirement.curseSha1());
			plan.setProperty(prefix + "size", Long.toString(requirement.fileSize()));
			plan.setProperty(prefix + "serverUrl", requirement.serverDownloadUrl());
			plan.setProperty(prefix + "serverToken", requirement.serverToken());
		}
		Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
		try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
			plan.store(writer, "EverHost exact required-mod installation plan");
		}
		move(temporary, target);
	}

	private static PendingOffer liveOffer() {
		PendingOffer offer = pendingOffer;
		if (offer != null && System.currentTimeMillis() - offer.createdAt() > OFFER_LIFETIME_MILLIS) {
			pendingOffer = null;
			return null;
		}
		return offer;
	}

	private static String detail(Properties values, String fallback) {
		String message = values.getProperty("message", "").trim();
		String code = values.getProperty("code", "").trim();
		if (message.isBlank()) message = fallback;
		return code.isBlank() ? message : code + ": " + message;
	}

	private static Result archiveResult(Path root, Path source, Result result) throws IOException {
		Path history = root.resolve("last-result.properties");
		move(source, history);
		return result;
	}

	private static Path safeChild(Path parent, String fileName) {
		if (fileName == null || fileName.isBlank()) return null;
		try {
			Path child = parent.resolve(fileName).toAbsolutePath().normalize();
			return parent.toAbsolutePath().normalize().equals(child.getParent()) ? child : null;
		} catch (RuntimeException exception) {
			return null;
		}
	}

	private static int integer(String value, int fallback) {
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException exception) {
			return fallback;
		}
	}

	private static void move(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException exception) {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static String safeMessage(Exception exception) {
		return exception.getMessage() == null ? exception.toString() : exception.getMessage();
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}

	private record PendingOffer(List<Requirement> requirements, long createdAt) {
	}

	public record Launch(int modCount, Path installerDirectory) {
	}

	public record Result(boolean success, String title, String message) {
	}
}
