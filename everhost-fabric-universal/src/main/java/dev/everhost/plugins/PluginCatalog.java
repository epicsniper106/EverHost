package dev.everhost.plugins;

import dev.everhost.universal.MiniJson;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A small, curated Modrinth-backed catalog for dependable in-game plugin installs. */
public final class PluginCatalog {
	private static final long MAX_PLUGIN_BYTES = 64L * 1024L * 1024L;
	private static final DateTimeFormatter ARCHIVE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
	private static final List<CatalogEntry> ENTRIES = List.of(
		new CatalogEntry("luckperms", "LuckPerms", "LuckPerms", true,
			"Ranks and permissions. Useful when unfamiliar players join, because staff access can be granted without making everyone an operator."),
		new CatalogEntry("coreprotect", "CoreProtect", "CoreProtect", true,
			"Records block and container changes so staff can inspect damage and roll back griefing."),
		new CatalogEntry("griefprevention", "GriefPrevention", "GriefPrevention", true,
			"Lets players protect land with a simple claim system and reduces casual griefing."),
		new CatalogEntry("packetevents", "PacketEvents", "packetevents", false,
			"Packet compatibility library required by some anti-cheat and network plugins, including the installed NovaAC build."),
		new CatalogEntry("dead-chest", "DeadChest", "DeadChest", false,
			"Keeps a player's items in a recoverable chest after death. Installing again replaces an older catalog version while archiving its jar.")
	);

	private PluginCatalog() {
	}

	public static List<CatalogEntry> entries(String minecraftVersion) {
		return "1.21.11".equals(minecraftVersion) || "1.20.1".equals(minecraftVersion) ? ENTRIES : List.of();
	}

	public static InstallResult install(Path pluginsDirectory, Path archiveDirectory, String minecraftVersion, CatalogEntry entry)
		throws IOException, InterruptedException {
		Files.createDirectories(pluginsDirectory);
		Release release = resolve(minecraftVersion, entry);
		String safeFileName = Path.of(release.fileName()).getFileName().toString();
		if (!safeFileName.equals(release.fileName()) || !safeFileName.toLowerCase(Locale.ROOT).endsWith(".jar")) {
			throw new IOException("The catalog returned an unsafe plugin filename");
		}
		Path temporary = pluginsDirectory.resolve("." + safeFileName + ".everhost-download");
		try {
			HttpRequest request = HttpRequest.newBuilder(release.uri())
				.timeout(Duration.ofMinutes(2))
				.header("User-Agent", "EverHost/2.2")
				.GET().build();
			HttpResponse<Path> response = client().send(request, HttpResponse.BodyHandlers.ofFile(temporary));
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				throw new IOException("Plugin download failed with HTTP " + response.statusCode());
			}
			if (Files.size(temporary) > MAX_PLUGIN_BYTES) throw new IOException("The downloaded plugin is unexpectedly large");
			String actualHash = sha512(temporary);
			if (!actualHash.equalsIgnoreCase(release.sha512())) throw new IOException("The downloaded plugin failed its SHA-512 integrity check");

			PluginSupport.PluginInfo downloaded = PluginSupport.inspect(temporary, minecraftVersion);
			if (downloaded.kind() != PluginSupport.PluginKind.BUKKIT && downloaded.kind() != PluginSupport.PluginKind.PAPER) {
				throw new IOException("The download is not a Bukkit/Paper server plugin");
			}
			if (!downloaded.name().equalsIgnoreCase(entry.pluginName())) {
				throw new IOException("The download identified itself as " + downloaded.name() + " instead of " + entry.pluginName());
			}

			List<Path> replaced = archiveExisting(pluginsDirectory, archiveDirectory, minecraftVersion, downloaded.name(), temporary);
			Path target = pluginsDirectory.resolve(safeFileName);
			Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
			return new InstallResult(entry, release.version(), target, replaced.size());
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private static Release resolve(String minecraftVersion, CatalogEntry entry) throws IOException, InterruptedException {
		String game = URLEncoder.encode("[\"" + minecraftVersion + "\"]", StandardCharsets.UTF_8);
		String loaders = URLEncoder.encode("[\"paper\",\"spigot\",\"bukkit\"]", StandardCharsets.UTF_8);
		URI uri = URI.create("https://api.modrinth.com/v2/project/" + entry.projectSlug()
			+ "/version?game_versions=" + game + "&loaders=" + loaders);
		HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
			.header("Accept", "application/json").header("User-Agent", "EverHost/2.2").GET().build();
		HttpResponse<String> response = client().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			throw new IOException("Modrinth catalog lookup failed with HTTP " + response.statusCode());
		}
		for (Object rawVersion : list(MiniJson.parse(response.body()))) {
			Map<String, Object> version = object(rawVersion);
			List<Object> files = list(version.get("files"));
			if (files.isEmpty()) continue;
			Map<String, Object> chosen = object(files.get(0));
			for (Object rawFile : files) {
				Map<String, Object> file = object(rawFile);
				if (Boolean.TRUE.equals(file.get("primary"))) {
					chosen = file;
					break;
				}
			}
			String url = string(chosen.get("url"));
			String fileName = string(chosen.get("filename"));
			String sha512 = string(object(chosen.get("hashes")).get("sha512"));
			if (!url.isBlank() && !fileName.isBlank() && !sha512.isBlank()) {
				URI download = URI.create(url);
				if (!"https".equalsIgnoreCase(download.getScheme()) || !"cdn.modrinth.com".equalsIgnoreCase(download.getHost())) {
					throw new IOException("The catalog returned an untrusted download address");
				}
				return new Release(string(version.get("version_number")), fileName, download, sha512);
			}
		}
		throw new IOException("No compatible " + entry.displayName() + " release was found for Minecraft " + minecraftVersion);
	}

	private static List<Path> archiveExisting(Path plugins, Path archiveRoot, String minecraftVersion, String pluginName, Path temporary)
		throws IOException {
		List<Path> matches = new ArrayList<>();
		try (var paths = Files.list(plugins)) {
			for (Path path : paths.filter(Files::isRegularFile)
				.filter(file -> file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
				.filter(file -> !file.equals(temporary)).toList()) {
				if (PluginSupport.inspect(path, minecraftVersion).name().equalsIgnoreCase(pluginName)) matches.add(path);
			}
		}
		if (matches.isEmpty()) return matches;
		Path archive = archiveRoot.resolve(ARCHIVE_TIME.format(LocalDateTime.now()));
		Files.createDirectories(archive);
		for (Path previous : matches) {
			Files.move(previous, archive.resolve(previous.getFileName()), StandardCopyOption.REPLACE_EXISTING);
		}
		return matches;
	}

	private static HttpClient client() {
		return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();
	}

	private static String sha512(Path path) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-512");
			try (var input = Files.newInputStream(path)) {
				byte[] buffer = new byte[64 * 1024];
				for (int read; (read = input.read(buffer)) >= 0; ) if (read > 0) digest.update(buffer, 0, read);
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (java.security.NoSuchAlgorithmException exception) {
			throw new IOException("SHA-512 is unavailable", exception);
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
		return value instanceof String string ? string : "";
	}

	public record CatalogEntry(String projectSlug, String displayName, String pluginName, boolean recommended, String description) {
	}

	public record InstallResult(CatalogEntry entry, String version, Path path, int archivedVersions) {
	}

	private record Release(String version, String fileName, URI uri, String sha512) {
	}
}
