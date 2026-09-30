package dev.everhost.universal;

import dev.everhost.universal.Models.LoaderType;
import dev.everhost.universal.Models.ModInfo;
import dev.everhost.universal.Models.ModSide;
import dev.everhost.universal.Models.Profile;
import dev.everhost.universal.Models.WorldInfo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public final class ProfileScanner {
	private ProfileScanner() {
	}

	public static Path defaultInstancesRoot() {
		String home = System.getProperty("user.home", ".");
		return Path.of(home, "curseforge", "minecraft", "Instances").toAbsolutePath().normalize();
	}

	public static List<Profile> scan(Path instancesRoot) {
		if (!Files.isDirectory(instancesRoot)) {
			return List.of();
		}
		List<Profile> profiles = new ArrayList<>();
		try (Stream<Path> paths = Files.list(instancesRoot)) {
			for (Path path : paths.filter(Files::isDirectory).sorted().toList()) {
				try {
					Profile profile = scanProfile(path);
					if (profile.loader() != LoaderType.UNKNOWN && supported(profile.minecraftVersion())) {
						profiles.add(profile);
					}
				} catch (IOException | RuntimeException ignored) {
					// A damaged profile should not hide the remaining CurseForge profiles.
				}
			}
		} catch (IOException ignored) {
			return List.of();
		}
		profiles.sort(Comparator.comparing(Profile::name, String.CASE_INSENSITIVE_ORDER));
		return profiles;
	}

	private static Profile scanProfile(Path path) throws IOException {
		Path instanceJson = path.resolve("minecraftinstance.json");
		Map<String, Object> root = Files.isRegularFile(instanceJson) ? object(MiniJson.parse(instanceJson)) : Map.of();
		Map<String, Object> loaderObject = object(root.get("baseModLoader"));
		String profileName = first(string(root.get("name")), path.getFileName().toString());
		String loaderName = string(loaderObject.get("name"));
		String minecraftVersion = first(
			string(loaderObject.get("minecraftVersion")),
			string(root.get("gameVersion")),
			manifestMinecraftVersion(path.resolve("manifest.json"))
		);
		LoaderType loader = loaderName.toLowerCase(Locale.ROOT).startsWith("fabric-")
			? LoaderType.FABRIC
			: loaderName.toLowerCase(Locale.ROOT).startsWith("forge-") ? LoaderType.FORGE : LoaderType.UNKNOWN;
		String loaderVersion = loaderVersion(loaderName, minecraftVersion, loader);
		Map<String, Addon> addons = readAddons(root);
		List<ModInfo> mods = scanMods(path.resolve("mods"), loader, addons);
		resolveDependencies(mods);
		List<WorldInfo> worlds = scanWorlds(path.resolve("saves"));
		int pluginCount = countJars(path.resolve("plugins"));
		return new Profile(profileName, path.toAbsolutePath().normalize(), minecraftVersion, loader, loaderVersion, worlds, mods, pluginCount);
	}

	private static Map<String, Addon> readAddons(Map<String, Object> root) {
		Map<String, Addon> result = new HashMap<>();
		for (Object value : list(root.get("installedAddons"))) {
			Map<String, Object> addon = object(value);
			if (!booleanValue(addon.get("isEnabled"), true)) {
				continue;
			}
			Map<String, Object> category = object(addon.get("categorySection"));
			if (!"mods".equalsIgnoreCase(string(category.get("path")))) {
				continue;
			}
			Map<String, Object> installedFile = object(addon.get("installedFile"));
			String filename = first(string(addon.get("fileNameOnDisk")), string(installedFile.get("fileName")));
			if (filename.isBlank()) {
				continue;
			}
			Set<String> sideTags = new LinkedHashSet<>();
			for (Object gameVersion : list(installedFile.get("gameVersion"))) {
				String tag = string(gameVersion);
				if ("Client".equalsIgnoreCase(tag) || "Server".equalsIgnoreCase(tag)) {
					sideTags.add(tag.toLowerCase(Locale.ROOT));
				}
			}
			Set<Long> requiredDependencies = new LinkedHashSet<>();
			for (Object dependencyValue : list(installedFile.get("dependencies"))) {
				Map<String, Object> dependency = object(dependencyValue);
				if (longValue(dependency.get("type"), 0L) == 3L) {
					requiredDependencies.add(longValue(dependency.get("addonId"), 0L));
				}
			}
			String sha1 = "";
			for (Object hashValue : list(installedFile.get("hashes"))) {
				Map<String, Object> hash = object(hashValue);
				if (longValue(hash.get("type"), 0L) == 1L) {
					sha1 = string(hash.get("value"));
					break;
				}
			}
			result.put(filename.toLowerCase(Locale.ROOT), new Addon(
				first(string(addon.get("name")), filename),
				longValue(addon.get("addonID"), 0L),
				longValue(installedFile.get("id"), 0L),
				first(string(installedFile.get("displayName")), string(installedFile.get("fileName")), filename),
				string(installedFile.get("downloadUrl")),
				sha1,
				longValue(installedFile.get("fileLength"), 0L),
				booleanValue(addon.get("allowModDistribution"), false),
				sideTags,
				requiredDependencies
			));
		}
		return result;
	}

	private static List<ModInfo> scanMods(Path modsPath, LoaderType loader, Map<String, Addon> addons) throws IOException {
		if (!Files.isDirectory(modsPath)) {
			return new ArrayList<>();
		}
		List<ModInfo> result = new ArrayList<>();
		try (Stream<Path> paths = Files.list(modsPath)) {
			for (Path jar : paths.filter(Files::isRegularFile)
				.filter(file -> file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
				.sorted().toList()) {
				Addon addon = addons.get(jar.getFileName().toString().toLowerCase(Locale.ROOT));
				JarInspector.Inspection inspection = JarInspector.inspect(jar, loader);
				ModSide side = inspection.side();
				String reason = inspection.reason();
				if (side != ModSide.CLIENT && addon != null && addon.sideTags.contains("server")) {
					side = ModSide.SERVER;
					reason = "CurseForge marks this file for servers.";
				} else if (side == ModSide.UNCERTAIN && addon != null && addon.sideTags.contains("client") && !addon.sideTags.contains("server")) {
					side = ModSide.CLIENT;
					reason = "CurseForge marks this file as client-only.";
				}
				String name = addon == null ? jar.getFileName().toString() : addon.name;
				String normalized = (name + " " + jar.getFileName()).toLowerCase(Locale.ROOT);
				boolean bridge = normalized.contains("cardboard") || normalized.contains("arclight")
					|| normalized.contains("banner") || normalized.contains("spongeforge");
				if (bridge && side != ModSide.CLIENT) {
					side = ModSide.SERVER;
					reason = "Detected a modded-server plugin bridge.";
				}
				result.add(new ModInfo(
					name,
					jar.toAbsolutePath().normalize(),
					side,
					reason,
					inspection.modIds(),
					inspection.dependencies(),
					addon == null ? Set.of() : addon.requiredDependencies,
					addon == null ? 0L : addon.projectId,
					addon == null ? 0L : addon.fileId,
					addon == null ? jar.getFileName().toString() : addon.exactVersion,
					addon == null ? "" : addon.downloadUrl,
					addon == null ? "" : addon.sha1,
					addon == null ? 0L : addon.downloadSize,
					addon != null && addon.distributionAllowed,
					bridge,
					clientRequired(inspection, addon, normalized, side)
				));
			}
		}
		result.sort(Comparator.comparing(mod -> mod.name.toLowerCase(Locale.ROOT)));
		return result;
	}

	private static boolean clientRequired(JarInspector.Inspection inspection, Addon addon, String normalized, ModSide side) {
		if (side != ModSide.SERVER || normalized.contains("everhost") || normalized.contains("e4mc") || normalized.contains("lithium")
			|| normalized.contains("ferritecore") || normalized.contains("chunky") || normalized.contains("cardboard")
			|| normalized.contains("spongeforge") || normalized.contains("arclight")) {
			return false;
		}
		if (addon != null && addon.sideTags.contains("server") && !addon.sideTags.contains("client")) {
			return false;
		}
		return inspection.clientRequired() || (addon != null && addon.sideTags.contains("client"));
	}

	private static void resolveDependencies(List<ModInfo> mods) {
		Map<String, ModInfo> byModId = new LinkedHashMap<>();
		Map<Long, ModInfo> byProject = new LinkedHashMap<>();
		for (ModInfo mod : mods) {
			for (String id : mod.modIds) {
				byModId.putIfAbsent(id, mod);
			}
			if (mod.curseProjectId > 0L) {
				byProject.putIfAbsent(mod.curseProjectId, mod);
			}
		}
		Deque<ModInfo> queue = new ArrayDeque<>();
		for (ModInfo mod : mods) {
			if (mod.selected) {
				queue.add(mod);
			}
		}
		while (!queue.isEmpty()) {
			ModInfo mod = queue.removeFirst();
			for (String dependencyId : mod.fabricDependencies) {
				selectDependency(byModId.get(dependencyId), queue);
			}
			for (Long projectId : mod.curseDependencies) {
				selectDependency(byProject.get(projectId), queue);
			}
		}
	}

	private static void selectDependency(ModInfo dependency, Deque<ModInfo> queue) {
		if (dependency != null && dependency.side != ModSide.CLIENT && !dependency.selected) {
			dependency.selected = true;
			queue.addLast(dependency);
		}
	}

	private static List<WorldInfo> scanWorlds(Path saves) throws IOException {
		if (!Files.isDirectory(saves)) {
			return List.of();
		}
		try (Stream<Path> paths = Files.list(saves)) {
			return paths.filter(Files::isDirectory)
				.filter(path -> Files.isRegularFile(path.resolve("level.dat")))
				.map(path -> new WorldInfo(path.getFileName().toString(), path.toAbsolutePath().normalize(), modified(path.resolve("level.dat"))))
				.sorted(Comparator.comparingLong(WorldInfo::lastModified).reversed())
				.toList();
		}
	}

	private static int countJars(Path path) {
		if (!Files.isDirectory(path)) {
			return 0;
		}
		try (Stream<Path> paths = Files.list(path)) {
			return (int)paths.filter(Files::isRegularFile)
				.filter(file -> file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
				.count();
		} catch (IOException ignored) {
			return 0;
		}
	}

	private static String manifestMinecraftVersion(Path manifest) {
		if (!Files.isRegularFile(manifest)) {
			return "";
		}
		try {
			return string(object(object(MiniJson.parse(manifest)).get("minecraft")).get("version"));
		} catch (IOException ignored) {
			return "";
		}
	}

	private static String loaderVersion(String loaderName, String minecraftVersion, LoaderType loader) {
		String prefix = loader == LoaderType.FABRIC ? "fabric-" : loader == LoaderType.FORGE ? "forge-" : "";
		String value = loaderName.toLowerCase(Locale.ROOT).startsWith(prefix)
			? loaderName.substring(prefix.length())
			: loaderName;
		if (loader == LoaderType.FABRIC && value.endsWith("-" + minecraftVersion)) {
			value = value.substring(0, value.length() - minecraftVersion.length() - 1);
		}
		return value;
	}

	public static boolean supported(String version) {
		List<Integer> parts = versionParts(version);
		if (parts.size() < 2 || parts.get(0) != 1) {
			return false;
		}
		int minor = parts.get(1);
		int patch = parts.size() > 2 ? parts.get(2) : 0;
		return minor == 20 || (minor == 21 && patch <= 11);
	}

	public static int requiredJava(String version) {
		List<Integer> parts = versionParts(version);
		if (parts.size() < 2) {
			return 21;
		}
		int minor = parts.get(1);
		int patch = parts.size() > 2 ? parts.get(2) : 0;
		return minor == 20 && patch <= 4 ? 17 : 21;
	}

	private static List<Integer> versionParts(String version) {
		List<Integer> result = new ArrayList<>();
		for (String part : version.split("\\.")) {
			try {
				result.add(Integer.parseInt(part.replaceAll("[^0-9].*$", "")));
			} catch (NumberFormatException ignored) {
				break;
			}
		}
		return result;
	}

	private static long modified(Path path) {
		try {
			return Files.getLastModifiedTime(path).toMillis();
		} catch (IOException ignored) {
			return 0L;
		}
	}

	@SuppressWarnings("unchecked")
	static Map<String, Object> object(Object value) {
		return value instanceof Map<?, ?> ? (Map<String, Object>)value : Map.of();
	}

	@SuppressWarnings("unchecked")
	static List<Object> list(Object value) {
		return value instanceof List<?> ? (List<Object>)value : List.of();
	}

	static String string(Object value) {
		return value instanceof String string ? string : "";
	}

	private static long longValue(Object value, long fallback) {
		return value instanceof Number number ? number.longValue() : fallback;
	}

	private static boolean booleanValue(Object value, boolean fallback) {
		return value instanceof Boolean bool ? bool : fallback;
	}

	private static String first(String... values) {
		for (String value : values) {
			if (value != null && !value.isBlank()) {
				return value;
			}
		}
		return "";
	}

	private record Addon(
		String name,
		long projectId,
		long fileId,
		String exactVersion,
		String downloadUrl,
		String sha1,
		long downloadSize,
		boolean distributionAllowed,
		Set<String> sideTags,
		Set<Long> requiredDependencies
	) {
	}
}
