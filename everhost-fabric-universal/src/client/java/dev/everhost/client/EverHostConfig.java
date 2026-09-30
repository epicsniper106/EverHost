package dev.everhost.client;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

public final class EverHostConfig {
	public String profilePath = "";
	public String profileName = "";
	public String minecraftVersion = "";
	public String loader = "";
	public String loaderVersion = "";
	public String worldId = "";
	public String worldName = "";
	public boolean syncProfileMods = true;
	public PluginMode pluginMode = PluginMode.OFF;
	public final Map<String, ModMode> modModes = new LinkedHashMap<>();
	public final Map<String, PluginState> pluginStates = new LinkedHashMap<>();
	public boolean eulaAccepted;
	public int minMemoryMb = 2048;
	public int maxMemoryMb = 8192;
	public int port = 25570;
	public int maxPlayers = 10;
	public int viewDistance = 32;
	public int simulationDistance = 12;
	public boolean distantHorizons = true;
	public int dhRealTimeRadius = 256;
	public int dhLodDistance = 4096;
	public int dhPlayerBandwidthKbps = 2000;
	public boolean dhAdaptiveTransfer = true;
	public int dhThreads = 16;
	public AddressMode addressMode = AddressMode.E4MC;
	public NetworkMode networkMode = NetworkMode.AUTO;
	public boolean lagGuard = true;
	public int lagWarnMspt = 65;
	public int lagPingMs = 350;
	public int lagMinViewDistance = 4;
	public int lagMinSimulationDistance = 4;
	public int lagRecoverySeconds = 45;
	public boolean lagJoinProtection = true;
	public boolean lagNotices = true;
	public Difficulty difficulty = Difficulty.NORMAL;
	public GameMode gameMode = GameMode.SURVIVAL;
	public boolean forceGamemode;
	public boolean pvp = true;
	public boolean allowFlight = true;
	public boolean commandBlocks;
	public int spawnProtection;
	public boolean onlineMode = true;
	public boolean secureProfiles = true;
	public boolean whitelist;
	public boolean enforceWhitelist;
	public boolean syncOps = true;
	public boolean syncWhitelist = true;
	public String motd = "EverHost persistent world";
	public boolean autoRestart = true;
	public int restartAttempts = 3;
	public int stopTimeoutSeconds = 60;
	public boolean backupBeforeStart;
	public int backupRetention = 5;
	public boolean autoStartWithMinecraft;
	public boolean startWithWindows;
	public boolean essentialNotifications = true;

	public static EverHostConfig load(Path path) {
		EverHostConfig config = new EverHostConfig();
		Properties values = new Properties();
		if (Files.isRegularFile(path)) {
			try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				values.load(reader);
			} catch (IOException ignored) {
			}
		}
		config.profilePath = values.getProperty("profilePath", config.profilePath);
		config.profileName = values.getProperty("profileName", config.profileName);
		config.minecraftVersion = values.getProperty("minecraftVersion", config.minecraftVersion);
		config.loader = values.getProperty("loader", config.loader);
		config.loaderVersion = values.getProperty("loaderVersion", config.loaderVersion);
		config.worldId = values.getProperty("worldId", config.worldId);
		config.worldName = values.getProperty("worldName", config.worldName);
		config.syncProfileMods = bool(values, "syncProfileMods", config.syncProfileMods);
		config.pluginMode = enumeration(values, "pluginMode", PluginMode.class, config.pluginMode);
		decodeModModes(values.getProperty("modModes", ""), config.modModes);
		decodePluginStates(values.getProperty("pluginStates", ""), config.pluginStates);
		config.eulaAccepted = bool(values, "eulaAccepted", config.eulaAccepted);
		config.minMemoryMb = integer(values, "minMemoryMb", config.minMemoryMb);
		config.maxMemoryMb = integer(values, "maxMemoryMb", config.maxMemoryMb);
		config.port = integer(values, "port", config.port);
		config.maxPlayers = integer(values, "maxPlayers", config.maxPlayers);
		config.viewDistance = integer(values, "viewDistance", config.viewDistance);
		config.simulationDistance = integer(values, "simulationDistance", config.simulationDistance);
		config.distantHorizons = bool(values, "distantHorizons", config.distantHorizons);
		config.dhRealTimeRadius = integer(values, "dhRealTimeRadius", config.dhRealTimeRadius);
		config.dhLodDistance = integer(values, "dhLodDistance", config.dhLodDistance);
		config.dhPlayerBandwidthKbps = integer(values, "dhPlayerBandwidthKbps", config.dhPlayerBandwidthKbps);
		config.dhAdaptiveTransfer = bool(values, "dhAdaptiveTransfer", config.dhAdaptiveTransfer);
		config.dhThreads = integer(values, "dhThreads", config.dhThreads);
		config.addressMode = values.containsKey("addressMode")
			? enumeration(values, "addressMode", AddressMode.class, config.addressMode)
			: legacyAddressMode(path);
		config.networkMode = enumeration(values, "networkMode", NetworkMode.class, config.networkMode);
		config.lagGuard = bool(values, "lagGuard", config.lagGuard);
		config.lagWarnMspt = integer(values, "lagWarnMspt", config.lagWarnMspt);
		config.lagPingMs = integer(values, "lagPingMs", config.lagPingMs);
		config.lagMinViewDistance = integer(values, "lagMinViewDistance", config.lagMinViewDistance);
		config.lagMinSimulationDistance = integer(values, "lagMinSimulationDistance", config.lagMinSimulationDistance);
		config.lagRecoverySeconds = integer(values, "lagRecoverySeconds", config.lagRecoverySeconds);
		config.lagJoinProtection = bool(values, "lagJoinProtection", config.lagJoinProtection);
		config.lagNotices = bool(values, "lagNotices", config.lagNotices);
		config.difficulty = enumeration(values, "difficulty", Difficulty.class, config.difficulty);
		config.gameMode = enumeration(values, "gamemode", GameMode.class, config.gameMode);
		config.forceGamemode = bool(values, "forceGamemode", config.forceGamemode);
		config.pvp = bool(values, "pvp", config.pvp);
		config.allowFlight = bool(values, "allowFlight", config.allowFlight);
		config.commandBlocks = bool(values, "commandBlocks", config.commandBlocks);
		config.spawnProtection = integer(values, "spawnProtection", config.spawnProtection);
		config.onlineMode = bool(values, "onlineMode", config.onlineMode);
		config.secureProfiles = bool(values, "secureProfiles", config.secureProfiles);
		config.whitelist = bool(values, "whitelist", config.whitelist);
		config.enforceWhitelist = bool(values, "enforceWhitelist", config.enforceWhitelist);
		config.syncOps = bool(values, "syncOps", config.syncOps);
		config.syncWhitelist = bool(values, "syncWhitelist", config.syncWhitelist);
		config.motd = values.getProperty("motd", config.motd);
		config.autoRestart = bool(values, "autoRestart", config.autoRestart);
		config.restartAttempts = integer(values, "restartAttempts", config.restartAttempts);
		config.stopTimeoutSeconds = integer(values, "stopTimeoutSeconds", config.stopTimeoutSeconds);
		config.backupBeforeStart = bool(values, "backupBeforeStart", config.backupBeforeStart);
		config.backupRetention = integer(values, "backupRetention", config.backupRetention);
		config.autoStartWithMinecraft = bool(values, "autoStartWithMinecraft", config.autoStartWithMinecraft);
		config.startWithWindows = bool(values, "startWithWindows", config.startWithWindows);
		config.essentialNotifications = bool(values, "essentialNotifications", config.essentialNotifications);
		config.normalize();
		return config;
	}

	public void save(Path path) {
		normalize();
		Properties values = new Properties();
		values.setProperty("profilePath", profilePath);
		values.setProperty("profileName", profileName);
		values.setProperty("minecraftVersion", minecraftVersion);
		values.setProperty("loader", loader);
		values.setProperty("loaderVersion", loaderVersion);
		values.setProperty("worldId", worldId);
		values.setProperty("worldName", worldName);
		values.setProperty("syncProfileMods", Boolean.toString(syncProfileMods));
		values.setProperty("pluginMode", pluginMode.name().toLowerCase(Locale.ROOT));
		values.setProperty("modModes", encodeModModes(modModes));
		values.setProperty("pluginStates", encodePluginStates(pluginStates));
		values.setProperty("eulaAccepted", Boolean.toString(eulaAccepted));
		values.setProperty("minMemoryMb", Integer.toString(minMemoryMb));
		values.setProperty("maxMemoryMb", Integer.toString(maxMemoryMb));
		values.setProperty("port", Integer.toString(port));
		values.setProperty("maxPlayers", Integer.toString(maxPlayers));
		values.setProperty("viewDistance", Integer.toString(viewDistance));
		values.setProperty("simulationDistance", Integer.toString(simulationDistance));
		values.setProperty("distantHorizons", Boolean.toString(distantHorizons));
		values.setProperty("dhRealTimeRadius", Integer.toString(dhRealTimeRadius));
		values.setProperty("dhLodDistance", Integer.toString(dhLodDistance));
		values.setProperty("dhPlayerBandwidthKbps", Integer.toString(dhPlayerBandwidthKbps));
		values.setProperty("dhAdaptiveTransfer", Boolean.toString(dhAdaptiveTransfer));
		values.setProperty("dhThreads", Integer.toString(dhThreads));
		values.setProperty("addressMode", addressMode.name().toLowerCase(Locale.ROOT));
		values.setProperty("networkMode", networkMode.name().toLowerCase(Locale.ROOT));
		values.setProperty("lagGuard", Boolean.toString(lagGuard));
		values.setProperty("lagWarnMspt", Integer.toString(lagWarnMspt));
		values.setProperty("lagPingMs", Integer.toString(lagPingMs));
		values.setProperty("lagMinViewDistance", Integer.toString(lagMinViewDistance));
		values.setProperty("lagMinSimulationDistance", Integer.toString(lagMinSimulationDistance));
		values.setProperty("lagRecoverySeconds", Integer.toString(lagRecoverySeconds));
		values.setProperty("lagJoinProtection", Boolean.toString(lagJoinProtection));
		values.setProperty("lagNotices", Boolean.toString(lagNotices));
		values.setProperty("difficulty", difficulty.name().toLowerCase(Locale.ROOT));
		values.setProperty("gamemode", gameMode.name().toLowerCase(Locale.ROOT));
		values.setProperty("forceGamemode", Boolean.toString(forceGamemode));
		values.setProperty("pvp", Boolean.toString(pvp));
		values.setProperty("allowFlight", Boolean.toString(allowFlight));
		values.setProperty("commandBlocks", Boolean.toString(commandBlocks));
		values.setProperty("spawnProtection", Integer.toString(spawnProtection));
		values.setProperty("onlineMode", Boolean.toString(onlineMode));
		values.setProperty("secureProfiles", Boolean.toString(secureProfiles));
		values.setProperty("whitelist", Boolean.toString(whitelist));
		values.setProperty("enforceWhitelist", Boolean.toString(enforceWhitelist));
		values.setProperty("syncOps", Boolean.toString(syncOps));
		values.setProperty("syncWhitelist", Boolean.toString(syncWhitelist));
		values.setProperty("motd", motd);
		values.setProperty("autoRestart", Boolean.toString(autoRestart));
		values.setProperty("restartAttempts", Integer.toString(restartAttempts));
		values.setProperty("stopTimeoutSeconds", Integer.toString(stopTimeoutSeconds));
		values.setProperty("backupBeforeStart", Boolean.toString(backupBeforeStart));
		values.setProperty("backupRetention", Integer.toString(backupRetention));
		values.setProperty("autoStartWithMinecraft", Boolean.toString(autoStartWithMinecraft));
		values.setProperty("startWithWindows", Boolean.toString(startWithWindows));
		values.setProperty("essentialNotifications", Boolean.toString(essentialNotifications));
		try {
			Files.createDirectories(path.toAbsolutePath().getParent());
			Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
			try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
				values.store(writer, "EverHost settings");
			}
			try {
				Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException exception) {
				Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException exception) {
			throw new IllegalStateException("Could not save EverHost settings", exception);
		}
	}

	public EverHostConfig copy() {
		EverHostConfig copy = new EverHostConfig();
		copy.profilePath = profilePath;
		copy.profileName = profileName;
		copy.minecraftVersion = minecraftVersion;
		copy.loader = loader;
		copy.loaderVersion = loaderVersion;
		copy.worldId = worldId;
		copy.worldName = worldName;
		copy.syncProfileMods = syncProfileMods;
		copy.pluginMode = pluginMode;
		copy.modModes.putAll(modModes);
		copy.pluginStates.putAll(pluginStates);
		copy.eulaAccepted = eulaAccepted;
		copy.minMemoryMb = minMemoryMb;
		copy.maxMemoryMb = maxMemoryMb;
		copy.port = port;
		copy.maxPlayers = maxPlayers;
		copy.viewDistance = viewDistance;
		copy.simulationDistance = simulationDistance;
		copy.distantHorizons = distantHorizons;
		copy.dhRealTimeRadius = dhRealTimeRadius;
		copy.dhLodDistance = dhLodDistance;
		copy.dhPlayerBandwidthKbps = dhPlayerBandwidthKbps;
		copy.dhAdaptiveTransfer = dhAdaptiveTransfer;
		copy.dhThreads = dhThreads;
		copy.addressMode = addressMode;
		copy.networkMode = networkMode;
		copy.lagGuard = lagGuard;
		copy.lagWarnMspt = lagWarnMspt;
		copy.lagPingMs = lagPingMs;
		copy.lagMinViewDistance = lagMinViewDistance;
		copy.lagMinSimulationDistance = lagMinSimulationDistance;
		copy.lagRecoverySeconds = lagRecoverySeconds;
		copy.lagJoinProtection = lagJoinProtection;
		copy.lagNotices = lagNotices;
		copy.difficulty = difficulty;
		copy.gameMode = gameMode;
		copy.forceGamemode = forceGamemode;
		copy.pvp = pvp;
		copy.allowFlight = allowFlight;
		copy.commandBlocks = commandBlocks;
		copy.spawnProtection = spawnProtection;
		copy.onlineMode = onlineMode;
		copy.secureProfiles = secureProfiles;
		copy.whitelist = whitelist;
		copy.enforceWhitelist = enforceWhitelist;
		copy.syncOps = syncOps;
		copy.syncWhitelist = syncWhitelist;
		copy.motd = motd;
		copy.autoRestart = autoRestart;
		copy.restartAttempts = restartAttempts;
		copy.stopTimeoutSeconds = stopTimeoutSeconds;
		copy.backupBeforeStart = backupBeforeStart;
		copy.backupRetention = backupRetention;
		copy.autoStartWithMinecraft = autoStartWithMinecraft;
		copy.startWithWindows = startWithWindows;
		copy.essentialNotifications = essentialNotifications;
		return copy;
	}

	public void normalize() {
		profilePath = profilePath == null ? "" : profilePath.trim();
		profileName = profileName == null ? "" : profileName.trim();
		minecraftVersion = minecraftVersion == null ? "" : minecraftVersion.trim();
		loader = loader == null ? "" : loader.trim().toLowerCase(Locale.ROOT);
		loaderVersion = loaderVersion == null ? "" : loaderVersion.trim();
		worldId = worldId == null ? "" : worldId.trim();
		worldName = worldName == null ? "" : worldName.trim();
		pluginMode = pluginMode == null ? PluginMode.OFF : pluginMode;
		minMemoryMb = clamp(minMemoryMb, 512, 32768);
		maxMemoryMb = clamp(maxMemoryMb, minMemoryMb, 65536);
		port = clamp(port, 1024, 65535);
		maxPlayers = clamp(maxPlayers, 1, 500);
		viewDistance = clamp(viewDistance, 2, 32);
		simulationDistance = clamp(simulationDistance, 2, 32);
		dhRealTimeRadius = clamp(dhRealTimeRadius, 64, 512);
		dhLodDistance = clamp(dhLodDistance, 512, 4096);
		dhPlayerBandwidthKbps = clamp(dhPlayerBandwidthKbps, 250, 8000);
		dhThreads = clamp(dhThreads, 2, 24);
		addressMode = addressMode == null ? AddressMode.E4MC : addressMode;
		lagWarnMspt = clamp(lagWarnMspt, 50, 250);
		lagPingMs = clamp(lagPingMs, 100, 2000);
		lagMinViewDistance = clamp(lagMinViewDistance, 2, viewDistance);
		lagMinSimulationDistance = clamp(lagMinSimulationDistance, 2, simulationDistance);
		lagRecoverySeconds = clamp(lagRecoverySeconds, 10, 180);
		spawnProtection = clamp(spawnProtection, 0, 64);
		restartAttempts = clamp(restartAttempts, 0, 10);
		stopTimeoutSeconds = clamp(stopTimeoutSeconds, 10, 300);
		backupRetention = clamp(backupRetention, 1, 30);
		motd = motd == null || motd.isBlank() ? "EverHost persistent world" : motd.trim();
	}

	private static int clamp(int value, int minimum, int maximum) {
		return Math.max(minimum, Math.min(maximum, value));
	}

	private static int integer(Properties values, String key, int fallback) {
		try {
			return Integer.parseInt(values.getProperty(key, Integer.toString(fallback)));
		} catch (NumberFormatException exception) {
			return fallback;
		}
	}

	private static boolean bool(Properties values, String key, boolean fallback) {
		return Boolean.parseBoolean(values.getProperty(key, Boolean.toString(fallback)));
	}

	private static AddressMode legacyAddressMode(Path configPath) {
		Path root = configPath.toAbsolutePath().getParent();
		if (root == null) return AddressMode.E4MC;
		if (enabled(root.resolve("playit.properties"))) return AddressMode.PLAYIT;
		if (enabled(root.resolve("permanent-address.properties"))) return AddressMode.CUSTOM;
		return AddressMode.E4MC;
	}

	private static boolean enabled(Path path) {
		Properties values = new Properties();
		if (!Files.isRegularFile(path)) return false;
		try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			values.load(reader);
			return Boolean.parseBoolean(values.getProperty("enabled", "false"));
		} catch (IOException ignored) {
			return false;
		}
	}

	private static String encodeModModes(Map<String, ModMode> modes) {
		StringBuilder encoded = new StringBuilder();
		for (Map.Entry<String, ModMode> entry : modes.entrySet()) {
			if (encoded.length() > 0) encoded.append(';');
			encoded.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
				.append('=')
				.append(entry.getValue().name());
		}
		return encoded.toString();
	}

	private static void decodeModModes(String encoded, Map<String, ModMode> target) {
		if (encoded == null || encoded.isBlank()) return;
		for (String value : encoded.split(";")) {
			int separator = value.lastIndexOf('=');
			if (separator <= 0) continue;
			try {
				String name = URLDecoder.decode(value.substring(0, separator), StandardCharsets.UTF_8);
				ModMode mode = ModMode.valueOf(value.substring(separator + 1));
				target.put(name, mode);
			} catch (IllegalArgumentException ignored) {
			}
		}
	}

	private static String encodePluginStates(Map<String, PluginState> states) {
		StringBuilder encoded = new StringBuilder();
		for (Map.Entry<String, PluginState> entry : states.entrySet()) {
			if (encoded.length() > 0) encoded.append(';');
			encoded.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
				.append('=')
				.append(entry.getValue().name());
		}
		return encoded.toString();
	}

	private static void decodePluginStates(String encoded, Map<String, PluginState> target) {
		if (encoded == null || encoded.isBlank()) return;
		for (String value : encoded.split(";")) {
			int separator = value.lastIndexOf('=');
			if (separator <= 0) continue;
			try {
				String name = URLDecoder.decode(value.substring(0, separator), StandardCharsets.UTF_8);
				target.put(name, PluginState.valueOf(value.substring(separator + 1)));
			} catch (IllegalArgumentException ignored) {
			}
		}
	}

	private static <T extends Enum<T>> T enumeration(Properties values, String key, Class<T> type, T fallback) {
		try {
			return Enum.valueOf(type, values.getProperty(key, fallback.name()).toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException exception) {
			return fallback;
		}
	}

	public enum Difficulty { PEACEFUL, EASY, NORMAL, HARD }
	public enum GameMode { SURVIVAL, CREATIVE, ADVENTURE, SPECTATOR }
	public enum ModMode { OFF, SERVER, REQUIRED }
	public enum PluginState { ENABLED, DISABLED }
	public enum PluginMode { OFF, AUTO }
	public enum AddressMode { E4MC, PLAYIT, CUSTOM }
	public enum NetworkMode { AUTO, SLOW_WIFI, BALANCED, FAST }
}
