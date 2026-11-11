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
	private static volatile Offer pendingOffer;
    private static volatile Offer pendingOptionalOffer;
    private static final java.util.concurrent.atomic.AtomicBoolean LAUNCHING = new java.util.concurrent.atomic.AtomicBoolean();

	private ModInstallCoordinator() {
	}

	public static List<Requirement> assess(
		List<Requirement> requirements, Set<String> installedModIds, Set<String> installedJarHashes
	) {
		List<Requirement> missing = requirements.stream()
			.filter(requirement -> !requirement.satisfiedBy(installedModIds, installedJarHashes))
			.toList();
		pendingOffer = missing.isEmpty() ? null : new Offer(List.copyOf(missing), "", false, System.currentTimeMillis());
		return missing;
	}

    public static synchronized List<Requirement> assessCatalog(Path game, String address,
        List<Requirement> required, List<Requirement> optional, Set<String> ids, Set<String> hashes) throws IOException {
        String key = ModDownloadPreferences.serverKey(address);
        pendingOptionalOffer = null;
        List<Requirement> missing = assess(required, ids, hashes);
        if (!missing.isEmpty()) {
            ModDownloadPreferences.requiredMissing(game, key);
            pendingOffer = new Offer(missing, key, false, System.currentTimeMillis());
        } else if (!ModDownloadPreferences.optionalSuppressed(game, key)) {
            List<Requirement> extras = optional.stream().filter(r -> !r.satisfiedBy(ids, hashes)).toList();
            if (!extras.isEmpty()) pendingOptionalOffer = new Offer(extras, key, true, System.currentTimeMillis());
        }
        return missing;
    }

    public static Offer requiredOffer() { return liveOffer(); }

    public static Offer optionalOffer() {
        Offer offer = pendingOptionalOffer;
        if (offer != null && System.currentTimeMillis() - offer.createdAt() > OFFER_LIFETIME_MILLIS)
            pendingOptionalOffer = null;
        return pendingOptionalOffer;
    }

    public static void dismissOptional(Path game, Offer offer, boolean suppress) throws IOException {
        if (offer == null || !offer.optional()) return;
        ModDownloadPreferences.rememberOptional(game, offer.serverKey(), suppress);
        if (pendingOptionalOffer == offer) pendingOptionalOffer = null;
    }

    public static void clearOffers() {
        pendingOffer = null;
        pendingOptionalOffer = null;
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
		Offer offer = liveOffer();
		return offer != null && !offer.requirements().isEmpty();
	}

	public static int pendingCount() {
		Offer offer = liveOffer();
		return offer == null ? 0 : offer.requirements().size();
	}

	public static Launch launch(Path gameDirectory, Path javaExecutable, Path everHostJar) throws IOException {
        return launch(gameDirectory, javaExecutable, everHostJar, liveOffer());
    }

    public static Launch launch(Path gameDirectory, Path javaExecutable, Path everHostJar, Offer offer) throws IOException {
        if (!LAUNCHING.compareAndSet(false, true)) throw new InstallationBusyException("A mod installation is already starting.");
        try {
            return launchOnce(gameDirectory, javaExecutable, everHostJar, offer);
        } catch (IOException | RuntimeException exception) {
            LAUNCHING.set(false);
            throw exception;
        }
    }

    private static Launch launchOnce(Path gameDirectory, Path javaExecutable, Path everHostJar, Offer offer) throws IOException {
        Path launchRoot = gameDirectory.toAbsolutePath().normalize().resolve("everhost/mod-installer");
        Files.createDirectories(launchRoot);
        try (var channel = java.nio.channels.FileChannel.open(launchRoot.resolve("launch.lock"),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)) {
            try (var lock = channel.tryLock()) {
                if (lock == null) throw new InstallationBusyException("A mod installation is already starting.");
                String running = ModInstallProgress.running(gameDirectory.toAbsolutePath().normalize());
                if (!running.isBlank()) throw new InstallationBusyException(running);
                return prepareLaunch(gameDirectory, javaExecutable, everHostJar, offer);
            } catch (java.nio.channels.OverlappingFileLockException busy) {
                throw new InstallationBusyException("A mod installation is already starting.");
            }
        }
    }

    private static Launch prepareLaunch(Path gameDirectory, Path javaExecutable, Path everHostJar, Offer offer) throws IOException {
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
            if (!ModDownloadPreferences.curseForgeFallback(game) && !requirement.hasServerSource())
                problem = requirement.name() + " is not available directly from the host. Ask the host to check mod delivery, or enable CurseForge fallback in Server > Mods.";
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
        Path ready = root.resolve("ready-" + java.util.UUID.randomUUID() + ".txt");
        String nonce = java.util.UUID.randomUUID().toString();

		Path launcher = javaExecutable.toAbsolutePath().normalize();
		if (isWindows()) {
			Path javaw = launcher.resolveSibling("javaw.exe");
			if (Files.isRegularFile(javaw)) launcher = javaw;
		}
        Process worker = new ProcessBuilder(
            launcher.toString(), "-cp", installed.toString(), ModInstallWorker.class.getName(),
            Long.toString(ProcessHandle.current().pid()), game.toString(), plan.toString(), result.toString(), log.toString(),
            ready.toString(), nonce
        ).directory(game.toFile()).redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
        long deadline = System.currentTimeMillis() + 15_000;
        try {
            while (System.currentTimeMillis() < deadline) {
                if (Files.isRegularFile(ready) && nonce.equals(Files.readString(ready).strip())) {
                    Files.deleteIfExists(ready);
                    if (pendingOffer == offer) pendingOffer = null;
                    if (pendingOptionalOffer == offer) pendingOptionalOffer = null;
                    return new Launch(offer.requirements().size(), root);
                }
                if (!worker.isAlive()) break;
                Thread.sleep(50);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        worker.destroy();
        try {
            if (!worker.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) worker.destroyForcibly();
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        Files.deleteIfExists(ready);
        Properties failure = new Properties();
        if (Files.isRegularFile(result)) {
            try (var reader = Files.newBufferedReader(result, StandardCharsets.UTF_8)) { failure.load(reader); }
        }
        String detail = failure.getProperty("message", "The installer did not confirm that it was ready.");
        throw new IOException(detail + " Minecraft was kept open. See everhost/mod-installer/installer.log.");

	}

	/** Reads and independently verifies the worker result on the next Minecraft launch. */
	public static Optional<Result> consumeResult(Path gameDirectory) {
		try { if (!ModInstallProgress.running(gameDirectory).isBlank()) return Optional.empty(); }
		catch (IOException unavailable) { return Optional.empty(); }
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
        plan.setProperty("curseForgeFallback", Boolean.toString(ModDownloadPreferences.curseForgeFallback(game)));
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

	private static Offer liveOffer() {
		Offer offer = pendingOffer;
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

    public record Offer(List<Requirement> requirements, String serverKey, boolean optional, long createdAt) {
        public Offer { requirements = List.copyOf(requirements); }
        public long totalBytes() { return requirements.stream().mapToLong(Requirement::fileSize).sum(); }
    }

	public static final class InstallationBusyException extends IOException {
		public InstallationBusyException(String message) { super(message); }
	}

	public record Launch(int modCount, Path installerDirectory) {
	}

	public record Result(boolean success, String title, String message) {
	}
}
