package dev.everhost.plugins;

import dev.everhost.universal.MiniJson;
import dev.everhost.universal.Models.LoaderType;
import dev.everhost.universal.Models.Profile;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Loader-independent plugin inspection and bridge selection for EverHost servers. */
public final class PluginSupport {
	private static final int MAX_DESCRIPTOR_BYTES = 1_048_576;
	private static final Pattern YAML_VALUE = Pattern.compile("(?m)^%s\\s*:\\s*([^#\\r\\n]+)");

	private static final Artifact ICOMMON_108 = new Artifact(
		"everhost-icommon-108.jar",
		URI.create("https://cdn.modrinth.com/data/SVKv1SZo/versions/7UUgrDDA/iCommon-Fabric-bundle.jar"),
		"9ff6d62cb7b3f19b108a3efd7b4bbc4be2be4e58492b1ae3d6f6a66fb8c2f7d7"
	);

	private static final BridgePlan CARDBOARD_12111 = new BridgePlan(
		"cardboard-1.21.11-18",
		"Cardboard 1.21.11-18",
		LoaderType.FABRIC,
		"1.21.11",
		false,
		List.of(
			new Artifact(
				"everhost-cardboard-1.21.11-18.jar",
				URI.create("https://cdn.modrinth.com/data/MLYQ9VGP/versions/DAoifce4/Cardboard-1.21.11.jar"),
				"7cbc0addceee63e56ce249d590bc75ca6844302d8c60ffe1718bb95572c92a24"
			),
			ICOMMON_108
		),
		"Bukkit, Spigot, and most ordinary Paper plugins. Paper-internal and NMS plugins may still be incompatible."
	);

	private static final BridgePlan BANNER_1201 = new BridgePlan(
		"banner-1.20.1-145",
		"Banner 1.20.1 build 145",
		LoaderType.FABRIC,
		"1.20.1",
		true,
		List.of(new Artifact(
			"everhost-banner-fabric-1.20.1-145.jar",
			URI.create("https://api.mohistmc.com/project/banner/1.20.1/builds/145/download"),
			"412d6d8a9b1d00d9f2c2adba27871980e89acfcc473ae7c97c2bb1f44666f834"
		)),
		"Bukkit, Spigot, and Paper plugins on Fabric 1.20.1. Plugins that modify server internals may still be incompatible."
	);

	private static final BridgePlan ARCLIGHT_1201 = new BridgePlan(
		"arclight-trials-1.0.6",
		"Arclight 1.20.1-1.0.6",
		LoaderType.FORGE,
		"1.20.1",
		true,
		List.of(new Artifact(
			"everhost-arclight-forge-1.20.1-1.0.6.jar",
			URI.create("https://github.com/IzzelAliz/Arclight/releases/download/Trials/1.0.6/arclight-forge-1.20.1-1.0.6.jar"),
			"40132f12fee21b4487cc995bb00b91b358df4b8162a48e98127a0368cf7e8c91"
		)),
		"Bukkit, Spigot, and Paper plugins on Forge. This Arclight release embeds Forge 47.3.22, so newer Forge mods may conflict."
	);

	private PluginSupport() {
	}

	public static Optional<BridgePlan> bridgeFor(Profile profile) {
		return bridgeFor(profile.loader(), profile.minecraftVersion());
	}

	public static Optional<BridgePlan> bridgeFor(LoaderType loader, String minecraftVersion) {
		if (loader == LoaderType.FABRIC && "1.21.11".equals(minecraftVersion)) return Optional.of(CARDBOARD_12111);
		if (loader == LoaderType.FABRIC && "1.20.1".equals(minecraftVersion)) return Optional.of(BANNER_1201);
		if (loader == LoaderType.FORGE && "1.20.1".equals(minecraftVersion)) return Optional.of(ARCLIGHT_1201);
		return Optional.empty();
	}

	public static ScanResult scan(Path directory, String minecraftVersion) {
		return scan(directory, minecraftVersion, Set.of(), Integer.MAX_VALUE, List.of());
	}

	public static ScanResult scan(
		Path directory,
		String minecraftVersion,
		Set<String> disabledFiles,
		int maximumJava,
		List<Path> serverModJars
	) {
		List<PluginInfo> plugins = new ArrayList<>();
		if (Files.isDirectory(directory)) {
			try (Stream<Path> paths = Files.list(directory)) {
				for (Path path : paths.filter(Files::isRegularFile)
					.filter(file -> file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
					.sorted(Comparator.comparing(file -> file.getFileName().toString().toLowerCase(Locale.ROOT)))
					.toList()) {
					plugins.add(inspect(path, minecraftVersion));
				}
			} catch (IOException exception) {
				plugins.add(basic(directory, "Plugin folder", "", PluginKind.UNKNOWN, Decision.BLOCKED,
					"EverHost could not read the plugins folder: " + safeMessage(exception)));
			}
		}

		Set<String> disabled = new HashSet<>();
		for (String file : disabledFiles) disabled.add(file.toLowerCase(Locale.ROOT));
		Set<String> conflictingClasses = classesPresentIn(serverModJars,
			plugins.stream().map(PluginInfo::mainClass).filter(value -> !value.isBlank()).toList());
		List<PluginInfo> configured = new ArrayList<>(plugins.size());
		for (PluginInfo plugin : plugins) {
			String fileName = plugin.path().getFileName() == null ? "" : plugin.path().getFileName().toString();
			if (disabled.contains(fileName.toLowerCase(Locale.ROOT))) {
				configured.add(plugin.withDecision(Decision.DISABLED, "Disabled in EverHost. The jar is preserved and will not be copied to the server."));
			} else if (!plugin.mainClass().isBlank() && conflictingClasses.contains(plugin.mainClass())) {
				configured.add(plugin.withDecision(Decision.BLOCKED,
					"A selected server mod already contains this plugin's main class. Keep the mod version and disable this duplicate plugin jar."));
			} else if (plugin.requiredJava() > maximumJava) {
				configured.add(plugin.withDecision(Decision.BLOCKED,
					"Requires Java " + plugin.requiredJava() + ", but this Minecraft/bridge combination safely supports up to Java " + maximumJava + "."));
			} else {
				configured.add(plugin);
			}
		}
		plugins = configured;

		Map<String, Integer> names = new HashMap<>();
		for (PluginInfo plugin : plugins) {
			if (plugin.runnable()) names.merge(plugin.name().toLowerCase(Locale.ROOT), 1, Integer::sum);
		}
		List<PluginInfo> normalized = new ArrayList<>(plugins.size());
		for (PluginInfo plugin : plugins) {
			if (plugin.runnable() && names.getOrDefault(plugin.name().toLowerCase(Locale.ROOT), 0) > 1) {
				normalized.add(plugin.withDecision(Decision.BLOCKED,
					"Another plugin declares the same name. Remove the duplicate before starting."));
			} else {
				normalized.add(plugin);
			}
		}
		Set<String> available = new HashSet<>();
		for (PluginInfo plugin : normalized) {
			if (plugin.runnable()) available.add(plugin.name().toLowerCase(Locale.ROOT));
		}
		List<PluginInfo> resolved = new ArrayList<>(normalized.size());
		for (PluginInfo plugin : normalized) {
			if (!plugin.runnable()) {
				resolved.add(plugin);
				continue;
			}
			List<String> missing = plugin.dependencies().stream()
				.filter(name -> !available.contains(name.toLowerCase(Locale.ROOT))).toList();
			if (!missing.isEmpty()) {
				resolved.add(plugin.withDecision(Decision.BLOCKED,
					"Missing required plugin" + (missing.size() == 1 ? ": " : "s: ") + String.join(", ", missing) + ". Install or enable the missing dependency first."));
				continue;
			}
			List<String> optional = plugin.optionalDependencies().stream()
				.filter(name -> !available.contains(name.toLowerCase(Locale.ROOT))).toList();
			if (!optional.isEmpty() && plugin.decision() == Decision.READY) {
				resolved.add(plugin.withDecision(Decision.WARNING,
					plugin.detail() + " Optional integrations not installed: " + String.join(", ", optional) + "."));
			} else {
				resolved.add(plugin);
			}
		}
		return new ScanResult(List.copyOf(resolved));
	}

	public static PluginInfo inspect(Path jar, String minecraftVersion) {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry paper = zip.getEntry("paper-plugin.yml");
			ZipEntry bukkit = zip.getEntry("plugin.yml");
			if (paper != null || bukkit != null) {
				boolean paperOnly = paper != null && bukkit == null;
				String descriptor = read(zip, paper != null ? paper : bukkit);
				String name = first(yaml(descriptor, "name"), baseName(jar));
				String version = yaml(descriptor, "version");
				String apiVersion = yaml(descriptor, "api-version");
				String mainClass = yaml(descriptor, "main");
				List<String> dependencies = yamlList(descriptor, "depend");
				List<String> optionalDependencies = yamlList(descriptor, "softdepend");
				int requiredJava = classJava(zip, mainClass);
				if (isNewerApi(apiVersion, minecraftVersion)) {
					return new PluginInfo(jar, name, version, paperOnly ? PluginKind.PAPER : PluginKind.BUKKIT,
						Decision.BLOCKED, "Targets Minecraft API " + apiVersion + ", newer than this " + minecraftVersion + " server.",
						mainClass, dependencies, optionalDependencies, requiredJava);
				}
				if (paperOnly) {
					return new PluginInfo(jar, name, version, PluginKind.PAPER, Decision.WARNING,
						"Paper-only descriptor detected. EverHost will try it, but hybrid bridges do not implement every Paper feature.",
						mainClass, dependencies, optionalDependencies, requiredJava);
				}
				if (apiVersion.isBlank()) {
					return new PluginInfo(jar, name, version, PluginKind.BUKKIT, Decision.WARNING,
						"Legacy Bukkit/Spigot plugin with no API version. The bridge will use compatibility mode.",
						mainClass, dependencies, optionalDependencies, requiredJava);
				}
				return new PluginInfo(jar, name, version, PluginKind.BUKKIT, Decision.READY,
					"Bukkit/Spigot plugin targeting API " + apiVersion + ".",
					mainClass, dependencies, optionalDependencies, requiredJava);
			}

			ZipEntry velocity = zip.getEntry("velocity-plugin.json");
			if (velocity != null) {
				Map<String, Object> metadata = object(MiniJson.parse(read(zip, velocity)));
				return blocked(jar, string(metadata.get("name")), string(metadata.get("version")), PluginKind.VELOCITY,
					"Velocity plugins run on a separate proxy, not inside a Minecraft game server.");
			}
			if (zip.getEntry("bungee.yml") != null) {
				String descriptor = read(zip, zip.getEntry("bungee.yml"));
				return blocked(jar, yaml(descriptor, "name"), yaml(descriptor, "version"), PluginKind.BUNGEECORD,
					"BungeeCord and Waterfall plugins run on a separate proxy, not inside a Minecraft game server.");
			}
			if (zip.getEntry("META-INF/sponge_plugins.json") != null) {
				return blocked(jar, "", "", PluginKind.SPONGE,
					"Sponge plugins require a Sponge server. They cannot share this Bukkit-family bridge mode.");
			}
			if (zip.getEntry("fabric.mod.json") != null) {
				return blocked(jar, "", "", PluginKind.FABRIC_MOD,
					"This is a Fabric mod. Put it in the profile's mods folder and manage it from EverHost's Mods tab.");
			}
			if (zip.getEntry("META-INF/mods.toml") != null || zip.getEntry("META-INF/neoforge.mods.toml") != null) {
				return blocked(jar, "", "", PluginKind.FORGE_MOD,
					"This is a Forge or NeoForge mod. Put it in the matching profile's mods folder.");
			}
			return blocked(jar, "", "", PluginKind.UNKNOWN,
				"No supported plugin descriptor was found. EverHost will not execute an unidentified jar.");
		} catch (IOException | RuntimeException exception) {
			return blocked(jar, "", "", PluginKind.UNKNOWN,
				"The jar is damaged or unreadable: " + safeMessage(exception));
		}
	}

	public static void writeReport(Path path, Profile profile, BridgePlan bridge, ScanResult scan) throws IOException {
		List<String> lines = new ArrayList<>();
		lines.add("EverHost plugin compatibility report");
		lines.add("Profile: " + profile.name());
		lines.add("Server: Minecraft " + profile.minecraftVersion() + " " + profile.loader());
		lines.add("Bridge: " + bridge.displayName());
		lines.add("Ready: " + scan.readyCount() + ", warnings: " + scan.warningCount() + ", blocked: " + scan.blockedCount()
			+ ", disabled: " + scan.disabledCount());
		lines.add("");
		for (PluginInfo plugin : scan.plugins()) {
			lines.add("[" + plugin.decision() + "] " + plugin.name() + versionSuffix(plugin.version())
				+ " | " + plugin.kind().displayName + " | Java " + (plugin.requiredJava() == 0 ? "unknown" : plugin.requiredJava())
				+ " | required: " + (plugin.dependencies().isEmpty() ? "none" : String.join(", ", plugin.dependencies()))
				+ " | " + plugin.detail() + " | " + plugin.path().getFileName());
		}
		Files.createDirectories(path.toAbsolutePath().getParent());
		Files.write(path, lines, StandardCharsets.UTF_8);
	}

	public static Properties reportProperties(Profile profile, BridgePlan bridge, ScanResult scan) {
		Properties values = new Properties();
		values.setProperty("profile", profile.name());
		values.setProperty("minecraftVersion", profile.minecraftVersion());
		values.setProperty("loader", profile.loader().name().toLowerCase(Locale.ROOT));
		values.setProperty("bridgeId", bridge.id());
		values.setProperty("bridgeName", bridge.displayName());
		values.setProperty("ready", Integer.toString(scan.readyCount()));
		values.setProperty("warnings", Integer.toString(scan.warningCount()));
		values.setProperty("blocked", Integer.toString(scan.blockedCount()));
		values.setProperty("disabled", Integer.toString(scan.disabledCount()));
		return values;
	}

	private static PluginInfo blocked(Path jar, String name, String version, PluginKind kind, String detail) {
		return basic(jar, first(name, baseName(jar)), version, kind, Decision.BLOCKED, detail);
	}

	private static PluginInfo basic(Path jar, String name, String version, PluginKind kind, Decision decision, String detail) {
		return new PluginInfo(jar, name, version, kind, decision, detail, "", List.of(), List.of(), 0);
	}

	private static String read(ZipFile zip, ZipEntry entry) throws IOException {
		if (entry.getSize() > MAX_DESCRIPTOR_BYTES) throw new IOException("Plugin descriptor is unexpectedly large");
		try (InputStream input = zip.getInputStream(entry)) {
			byte[] bytes = input.readNBytes(MAX_DESCRIPTOR_BYTES + 1);
			if (bytes.length > MAX_DESCRIPTOR_BYTES) throw new IOException("Plugin descriptor is unexpectedly large");
			return new String(bytes, StandardCharsets.UTF_8);
		}
	}

	private static String yaml(String contents, String key) {
		Matcher matcher = Pattern.compile(String.format(YAML_VALUE.pattern(), Pattern.quote(key)), Pattern.CASE_INSENSITIVE).matcher(contents);
		if (!matcher.find()) return "";
		String value = matcher.group(1).trim();
		if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
			|| (value.startsWith("'") && value.endsWith("'")))) {
			value = value.substring(1, value.length() - 1).trim();
		}
		return value;
	}

	private static List<String> yamlList(String contents, String key) {
		String inline = yaml(contents, key);
		LinkedHashSet<String> values = new LinkedHashSet<>();
		if (!inline.isBlank()) {
			String body = inline.startsWith("[") && inline.endsWith("]")
				? inline.substring(1, inline.length() - 1)
				: inline;
			for (String value : body.split(",")) addYamlListValue(values, value);
		}
		Pattern block = Pattern.compile("(?mi)^" + Pattern.quote(key) + "\\s*:\\s*(?:#.*)?(?:\\R[ \\t]+-[ \\t]*[^#\\r\\n]+)+");
		Matcher matcher = block.matcher(contents);
		if (matcher.find()) {
			Matcher item = Pattern.compile("(?m)^[ \\t]+-[ \\t]*([^#\\r\\n]+)").matcher(matcher.group());
			while (item.find()) addYamlListValue(values, item.group(1));
		}
		return List.copyOf(values);
	}

	private static void addYamlListValue(Set<String> values, String raw) {
		String value = raw == null ? "" : raw.trim();
		if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
			|| (value.startsWith("'") && value.endsWith("'")))) value = value.substring(1, value.length() - 1).trim();
		String normalized = value;
		if (!normalized.isBlank() && values.stream().noneMatch(existing -> existing.equalsIgnoreCase(normalized))) values.add(normalized);
	}

	private static int classJava(ZipFile zip, String mainClass) throws IOException {
		if (mainClass == null || mainClass.isBlank()) return 0;
		ZipEntry entry = zip.getEntry(mainClass.replace('.', '/') + ".class");
		if (entry == null) return 0;
		try (InputStream input = zip.getInputStream(entry)) {
			byte[] header = input.readNBytes(8);
			if (header.length < 8 || ByteBuffer.wrap(header, 0, 4).getInt() != 0xCAFEBABE) return 0;
			int classMajor = ByteBuffer.wrap(header, 6, 2).order(ByteOrder.BIG_ENDIAN).getShort() & 0xFFFF;
			return classMajor >= 45 ? classMajor - 44 : 0;
		}
	}

	private static Set<String> classesPresentIn(List<Path> jars, List<String> classNames) {
		if (jars.isEmpty() || classNames.isEmpty()) return Set.of();
		Set<String> resources = new HashSet<>();
		for (String name : classNames) resources.add(name.replace('.', '/') + ".class");
		Set<String> found = new HashSet<>();
		for (Path jar : jars) {
			if (!Files.isRegularFile(jar)) continue;
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				for (String resource : resources) {
					if (zip.getEntry(resource) != null) found.add(resource.substring(0, resource.length() - 6).replace('/', '.'));
				}
			} catch (IOException ignored) {
			}
		}
		return found;
	}

	private static boolean isNewerApi(String apiVersion, String minecraftVersion) {
		int[] api = versionParts(apiVersion);
		int[] minecraft = versionParts(minecraftVersion);
		if (api.length < 2 || minecraft.length < 2) return false;
		for (int index = 0; index < Math.max(api.length, minecraft.length); index++) {
			int left = index < api.length ? api[index] : 0;
			int right = index < minecraft.length ? minecraft[index] : 0;
			if (left != right) return left > right;
		}
		return false;
	}

	private static int[] versionParts(String value) {
		if (value == null || value.isBlank()) return new int[0];
		String[] pieces = value.trim().split("\\.");
		int[] result = new int[pieces.length];
		for (int index = 0; index < pieces.length; index++) {
			Matcher number = Pattern.compile("^(\\d+)").matcher(pieces[index]);
			if (!number.find()) return new int[0];
			try {
				result[index] = Integer.parseInt(number.group(1));
			} catch (NumberFormatException exception) {
				return new int[0];
			}
		}
		return result;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> object(Object value) {
		return value instanceof Map<?, ?> ? (Map<String, Object>)value : Map.of();
	}

	private static String string(Object value) {
		return value instanceof String string ? string : "";
	}

	private static String first(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value.trim();
	}

	private static String baseName(Path path) {
		String name = path.getFileName().toString();
		return name.toLowerCase(Locale.ROOT).endsWith(".jar") ? name.substring(0, name.length() - 4) : name;
	}

	private static String versionSuffix(String value) {
		return value == null || value.isBlank() ? "" : " " + value;
	}

	private static String safeMessage(Exception exception) {
		String message = exception.getMessage();
		return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
	}

	public enum PluginKind {
		BUKKIT("Bukkit / Spigot"),
		PAPER("Paper"),
		BUNGEECORD("BungeeCord / Waterfall proxy"),
		VELOCITY("Velocity proxy"),
		SPONGE("Sponge"),
		FABRIC_MOD("Fabric mod"),
		FORGE_MOD("Forge / NeoForge mod"),
		UNKNOWN("Unknown jar");

		private final String displayName;

		PluginKind(String displayName) {
			this.displayName = displayName;
		}

		public String displayName() {
			return displayName;
		}
	}

	public enum Decision {
		READY, WARNING, BLOCKED, DISABLED
	}

	public record PluginInfo(
		Path path,
		String name,
		String version,
		PluginKind kind,
		Decision decision,
		String detail,
		String mainClass,
		List<String> dependencies,
		List<String> optionalDependencies,
		int requiredJava
	) {
		public boolean runnable() {
			return decision == Decision.READY || decision == Decision.WARNING;
		}

		PluginInfo withDecision(Decision updated, String updatedDetail) {
			return new PluginInfo(path, name, version, kind, updated, updatedDetail,
				mainClass, dependencies, optionalDependencies, requiredJava);
		}
	}

	public record ScanResult(List<PluginInfo> plugins) {
		public int readyCount() {
			return (int)plugins.stream().filter(plugin -> plugin.decision() == Decision.READY).count();
		}

		public int warningCount() {
			return (int)plugins.stream().filter(plugin -> plugin.decision() == Decision.WARNING).count();
		}

		public int blockedCount() {
			return (int)plugins.stream().filter(plugin -> plugin.decision() == Decision.BLOCKED).count();
		}

		public int disabledCount() {
			return (int)plugins.stream().filter(plugin -> plugin.decision() == Decision.DISABLED).count();
		}

		public int runnableCount() {
			return readyCount() + warningCount();
		}

		public int maximumRequiredJava() {
			return plugins.stream().filter(PluginInfo::runnable).mapToInt(PluginInfo::requiredJava).max().orElse(0);
		}
	}

	public record Artifact(String fileName, URI uri, String sha256) {
	}

	public record BridgePlan(
		String id,
		String displayName,
		LoaderType loader,
		String minecraftVersion,
		boolean replacesServerLauncher,
		List<Artifact> artifacts,
		String compatibilityNote
	) {
	}
}
