package dev.everhost.universal;

import dev.everhost.universal.Models.ModInfo;
import dev.everhost.universal.Models.Profile;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

public final class RequiredModsManifest {
	private static final String DATA_FILE = "everhost-required-client-mods.dat";
	private static final String SOURCE_FILE = "everhost-mod-source.properties";
	private static final String DATA_HEADER_V1 = "EVERHOST_REQUIRED_MODS_V1";
	private static final String DATA_HEADER_V2 = "EVERHOST_REQUIRED_MODS_V2";
	private static final String DATA_HEADER_V3 = "EVERHOST_REQUIRED_MODS_V3";
	private static final long MAX_MOD_BYTES = 512L * 1024L * 1024L;
	private static final Set<String> CURSEFORGE_HOSTS = Set.of(
		"edge.forgecdn.net", "mediafilez.forgecdn.net", "media.forgecdn.net", "www.curseforge.com"
	);

	private RequiredModsManifest() {
	}

	/** One exact client JAR required by the running server profile. */
	public record Requirement(
		String name,
		Set<String> modIds,
		String version,
		String fileName,
		long curseProjectId,
		long curseFileId,
		String downloadUrl,
		String sha256,
		String curseSha1,
		long fileSize,
		String serverDownloadUrl,
		String serverToken
	) {
		public Requirement {
			name = name == null || name.isBlank() ? "Unknown mod" : name;
			modIds = Collections.unmodifiableSet(new LinkedHashSet<>(modIds == null ? Set.of() : modIds));
			version = clean(version);
			fileName = clean(fileName);
			downloadUrl = clean(downloadUrl);
			sha256 = clean(sha256).toLowerCase(Locale.ROOT);
			curseSha1 = clean(curseSha1).toLowerCase(Locale.ROOT);
			serverDownloadUrl = clean(serverDownloadUrl);
			serverToken = clean(serverToken);
		}

		public Requirement(String name, Set<String> modIds) {
			this(name, modIds, "", "", 0L, 0L, "", "", "", 0L, "", "");
		}

		public Requirement(
			String name, Set<String> modIds, String version, String fileName, long curseProjectId,
			long curseFileId, String downloadUrl, String sha256, String curseSha1, long fileSize
		) {
			this(name, modIds, version, fileName, curseProjectId, curseFileId, downloadUrl, sha256,
				curseSha1, fileSize, "", "");
		}

		public boolean satisfiedBy(Set<String> installedModIds, Set<String> installedJarHashes) {
			if (!installedModIds.containsAll(modIds)) return false;
			return sha256.isBlank() || installedJarHashes.contains(sha256);
		}

		public boolean isAutomaticInstallable() {
			return automaticInstallProblem().isBlank();
		}

		public boolean hasServerSource() {
			return validServerUrl(serverDownloadUrl, sha256) && serverToken.matches("[0-9a-fA-F]{64}");
		}

		public boolean hasCurseForgeSource() {
			if (curseProjectId <= 0L || curseFileId <= 0L || downloadUrl.isBlank()) return false;
			try {
				URI uri = URI.create(downloadUrl);
				String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
				return "https".equalsIgnoreCase(uri.getScheme()) && CURSEFORGE_HOSTS.contains(host);
			} catch (IllegalArgumentException exception) {
				return false;
			}
		}

		public String sourceDescription() {
			if (hasServerSource()) return "directly from this EverHost server";
			if (hasCurseForgeSource()) return "from exact CurseForge file " + curseFileId;
			return "no automatic source is currently reachable";
		}

		public String automaticInstallProblem() {
			if (modIds.isEmpty()) return name + " has no loader mod ID in the server profile.";
			if (fileName.isBlank() || !safeFileName(fileName)) {
				return name + " has an unsafe or missing exact JAR filename.";
			}
			if (!sha256.matches("[0-9a-f]{64}")) {
				return name + " is missing its exact server SHA-256 fingerprint.";
			}
			if (fileSize < 0L || fileSize > MAX_MOD_BYTES) {
				return name + " reports an invalid download size.";
			}
			if (hasServerSource() || hasCurseForgeSource()) return "";
			return name + " is not available from the server and has no approved CurseForge download.";
		}
	}

	public static List<ModInfo> required(List<ModInfo> selected) {
		return selected.stream()
			.filter(mod -> mod.clientRequired)
			.sorted(Comparator.comparing(mod -> mod.name, String.CASE_INSENSITIVE_ORDER))
			.toList();
	}

	public static Path write(Path runtime, Profile profile, List<ModInfo> selected) throws IOException {
		List<Requirement> requirements = exactRequirements(selected);
		List<String> lines = new ArrayList<>();
		lines.add("EverHost required client mods");
		lines.add("Profile: " + profile.name());
		lines.add("Minecraft: " + profile.minecraftVersion());
		lines.add("Loader: " + profile.loader() + " " + profile.loaderVersion());
		lines.add("");
		if (requirements.isEmpty()) {
			lines.add("No client-side mods are currently marked as required.");
		} else {
			lines.add("EverHost will offer these exact server files to clients that need them:");
			for (Requirement requirement : requirements) {
				String source = requirement.hasCurseForgeSource()
					? "CurseForge fallback: project " + requirement.curseProjectId() + ", file " + requirement.curseFileId()
					: "Server direct delivery";
				lines.add("- " + requirement.name() + "  [" + requirement.fileName() + "]  " + source);
			}
		}
		lines.add("");
		lines.add("Direct files are served only after the player chooses Install Required Mods.");
		lines.add("Every download must match the SHA-256 of the exact JAR used by this server.");
		lines.add("Server-only optimization, hosting, and plugin-bridge mods are intentionally excluded.");
		Path target = runtime.resolve("everhost-required-client-mods.txt");
		Files.write(target, lines, StandardCharsets.UTF_8);
		writeData(runtime.resolve(DATA_FILE), requirements);
		return target;
	}

	public static List<Requirement> read(Path runtime) throws IOException {
		Path source = runtime.resolve(DATA_FILE);
		if (!Files.isRegularFile(source)) return List.of();
		List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
		if (lines.isEmpty()) return List.of();
		List<Requirement> requirements;
		if (DATA_HEADER_V1.equals(lines.get(0))) requirements = readV1(lines);
		else if (DATA_HEADER_V2.equals(lines.get(0)) || DATA_HEADER_V3.equals(lines.get(0))) requirements = readExact(lines);
		else return List.of();
		return attachServerSource(runtime, requirements);
	}

	public static String missingDisconnectMessage(List<Requirement> missing) {
		StringBuilder message = new StringBuilder("Missing or outdated required mods:\n");
		for (Requirement requirement : missing) {
			message.append("- ").append(requirement.name());
			if (!requirement.version().isBlank()) message.append(" (").append(requirement.version()).append(")");
			message.append("\n");
		}
		message.append("\nChoose Install Required Mods below. EverHost can copy exact files directly from this server, including private and custom mods.");
		return message.toString();
	}

	public static String disconnectMessage(Profile profile, List<ModInfo> selected) {
		List<ModInfo> required = required(selected);
		if (required.isEmpty()) {
			return "Your client mod set does not match the EverHost server profile " + profile.name() + ".";
		}
		StringBuilder message = new StringBuilder("Missing required mods for ")
			.append(profile.name()).append(" (Minecraft ").append(profile.minecraftVersion()).append("):\n");
		for (ModInfo mod : required) message.append("- ").append(mod.name).append("\n");
		return message.toString().stripTrailing();
	}

	private static List<Requirement> exactRequirements(List<ModInfo> selected) throws IOException {
		List<Requirement> result = new ArrayList<>();
		for (ModInfo mod : required(selected)) {
			result.add(new Requirement(
				mod.name, mod.modIds, mod.exactVersion, mod.path.getFileName().toString(),
				mod.curseProjectId, mod.curseFileId, mod.downloadUrl, sha256(mod.path), mod.curseSha1,
				Files.size(mod.path), "", ""
			));
		}
		return List.copyOf(result);
	}

	private static void writeData(Path target, List<Requirement> requirements) throws IOException {
		Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
		List<String> lines = new ArrayList<>();
		lines.add(DATA_HEADER_V3);
		for (Requirement requirement : requirements) {
			String ids = requirement.modIds().stream()
				.sorted(String.CASE_INSENSITIVE_ORDER)
				.map(id -> encode(encoder, id))
				.reduce((left, right) -> left + "," + right)
				.orElse("");
			if (ids.isBlank()) continue;
			lines.add(String.join("\t",
				encode(encoder, requirement.name()), ids, encode(encoder, requirement.version()),
				encode(encoder, requirement.fileName()), Long.toString(requirement.curseProjectId()),
				Long.toString(requirement.curseFileId()), encode(encoder, requirement.downloadUrl()),
				encode(encoder, requirement.sha256()), encode(encoder, requirement.curseSha1()),
				Long.toString(requirement.fileSize())
			));
		}
		Files.write(target, lines, StandardCharsets.UTF_8);
	}

	private static List<Requirement> readExact(List<String> lines) {
		Base64.Decoder decoder = Base64.getUrlDecoder();
		List<Requirement> requirements = new ArrayList<>();
		for (int index = 1; index < lines.size(); index++) {
			String[] parts = lines.get(index).split("\t", -1);
			if (parts.length != 10) continue;
			try {
				Set<String> ids = decodeIds(decoder, parts[1]);
				if (ids.isEmpty()) continue;
				requirements.add(new Requirement(
					decode(decoder, parts[0]), ids, decode(decoder, parts[2]), decode(decoder, parts[3]),
					Long.parseLong(parts[4]), Long.parseLong(parts[5]), decode(decoder, parts[6]),
					decode(decoder, parts[7]), decode(decoder, parts[8]), Long.parseLong(parts[9]), "", ""
				));
			} catch (IllegalArgumentException ignored) {
				// Ignore one damaged line without disabling every other requirement.
			}
		}
		return List.copyOf(requirements);
	}

	private static List<Requirement> attachServerSource(Path runtime, List<Requirement> requirements) {
		Properties source = new Properties();
		Path sourceFile = runtime.resolve(SOURCE_FILE);
		if (!Files.isRegularFile(sourceFile)) return requirements;
		try (Reader reader = Files.newBufferedReader(sourceFile, StandardCharsets.UTF_8)) {
			source.load(reader);
			String base = clean(source.getProperty("baseUrl"));
			String token = clean(source.getProperty("token"));
			if (!validBaseUrl(base) || !token.matches("[0-9a-fA-F]{64}")) return requirements;
			List<Requirement> result = new ArrayList<>();
			for (Requirement requirement : requirements) {
				String url = base + "/mods/" + requirement.sha256();
				result.add(new Requirement(requirement.name(), requirement.modIds(), requirement.version(),
					requirement.fileName(), requirement.curseProjectId(), requirement.curseFileId(),
					requirement.downloadUrl(), requirement.sha256(), requirement.curseSha1(),
					requirement.fileSize(), url, token));
			}
			return List.copyOf(result);
		} catch (IOException ignored) {
			return requirements;
		}
	}

	private static List<Requirement> readV1(List<String> lines) {
		Base64.Decoder decoder = Base64.getUrlDecoder();
		List<Requirement> requirements = new ArrayList<>();
		for (int index = 1; index < lines.size(); index++) {
			String[] parts = lines.get(index).split("\t", 2);
			if (parts.length != 2) continue;
			try {
				Set<String> ids = decodeIds(decoder, parts[1]);
				if (!ids.isEmpty()) requirements.add(new Requirement(decode(decoder, parts[0]), ids));
			} catch (IllegalArgumentException ignored) {
			}
		}
		return List.copyOf(requirements);
	}

	private static Set<String> decodeIds(Base64.Decoder decoder, String value) {
		Set<String> ids = new LinkedHashSet<>();
		for (String encodedId : value.split(",")) if (!encodedId.isBlank()) ids.add(decode(decoder, encodedId));
		return ids;
	}

	private static boolean validBaseUrl(String value) {
		try {
			URI uri = URI.create(value);
			return ("everhost".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())
				|| "https".equalsIgnoreCase(uri.getScheme()))
				&& uri.getHost() != null && !uri.getHost().isBlank() && uri.getUserInfo() == null
				&& uri.getPort() > 0
				&& uri.getQuery() == null && uri.getFragment() == null && (uri.getPath().isEmpty() || "/".equals(uri.getPath()));
		} catch (IllegalArgumentException exception) {
			return false;
		}
	}

	private static boolean validServerUrl(String value, String hash) {
		try {
			URI uri = URI.create(value);
			return validBaseUrl(uri.getScheme() + "://" + uri.getRawAuthority())
				&& ("/mods/" + hash).equals(uri.getPath()) && uri.getQuery() == null && uri.getFragment() == null;
		} catch (IllegalArgumentException exception) {
			return false;
		}
	}

	private static String sha256(Path path) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (InputStream input = Files.newInputStream(path)) {
				byte[] buffer = new byte[64 * 1024];
				for (int read; (read = input.read(buffer)) >= 0;) if (read > 0) digest.update(buffer, 0, read);
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException exception) {
			throw new IOException("This Java runtime does not provide SHA-256", exception);
		}
	}

	private static String encode(Base64.Encoder encoder, String value) {
		return encoder.encodeToString(clean(value).getBytes(StandardCharsets.UTF_8));
	}

	private static String decode(Base64.Decoder decoder, String value) {
		return new String(decoder.decode(value), StandardCharsets.UTF_8);
	}

	private static boolean safeFileName(String value) {
		try {
			Path path = Path.of(value);
			return value.toLowerCase(Locale.ROOT).endsWith(".jar") && path.getNameCount() == 1
				&& !value.equals(".") && !value.equals("..") && value.length() <= 255;
		} catch (RuntimeException exception) {
			return false;
		}
	}

	private static String clean(String value) {
		return value == null ? "" : value.trim();
	}
}
