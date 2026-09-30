package dev.everhost.daemon;

import dev.everhost.address.AddressClient;
import dev.everhost.playit.PlayitManager;
import dev.everhost.plugins.PluginSupport;
import dev.everhost.plugins.PluginSupport.BridgePlan;
import dev.everhost.plugins.PluginSupport.PluginInfo;
import dev.everhost.plugins.PluginSupport.ScanResult;
import dev.everhost.universal.JavaLocator;
import dev.everhost.universal.Models.LoaderType;
import dev.everhost.universal.Models.ModInfo;
import dev.everhost.universal.Models.Profile;
import dev.everhost.universal.ProfileScanner;
import dev.everhost.universal.RequiredModsManifest;
import dev.everhost.universal.ServerRuntimeTuner;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** A JDK-only process that remains alive after the Minecraft client exits. */
public final class HostDaemon {
	private static final String MINECRAFT_VERSION = "1.21.11";
	private static final String LOADER_VERSION = "0.19.3";
	private static final URI SERVER_URI = URI.create(
		"https://piston-data.mojang.com/v1/objects/64bb6d763bed0a9f1d632ec347938594144943ed/server.jar"
	);
	private static final String SERVER_SHA1 = "64bb6d763bed0a9f1d632ec347938594144943ed";
	private static final Pattern DOMAIN_PATTERN = Pattern.compile("Domain assigned: ([A-Za-z0-9.-]+)");
	private static final Pattern JOIN_PATTERN = Pattern.compile(": ([A-Za-z0-9_]{1,16}) joined the game");
	private static final Pattern LEAVE_PATTERN = Pattern.compile(": ([A-Za-z0-9_]{1,16}) left the game");
	private static final Pattern PLUGIN_ENABLE_PATTERN = Pattern.compile("(?i)\\bEnabling\\s+([A-Za-z0-9_.-]+)\\s+v[^\\s]+");
	private static final Pattern PLUGIN_ENABLE_BRACKET_PATTERN = Pattern.compile("\\[([A-Za-z0-9_.-]+)] Enabling ");
	private static final Pattern PLUGIN_FAILURE_PATTERN = Pattern.compile(
		"(?i)(?:Error occurred while enabling|Could not load(?: plugin)?)\\s+['\"]?(?:plugins[\\\\/])?([^'\"\\s\\\\/]+)"
	);
	private static final Pattern PLUGIN_TASK_FAILURE_PATTERN = Pattern.compile("(?i)Task #\\d+ for ([A-Za-z0-9_.-]+).*exception");
	private static final Pattern PLUGIN_RUNTIME_FAILURE_PATTERN = Pattern.compile(
		"(?i)\\[([A-Za-z0-9_.-]+)]\\s+Plugin\\s+[^\\s]+.*(?:failed to register|has been disabled|encountered a fatal)"
	);
	private static final DateTimeFormatter BACKUP_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
		.withZone(ZoneId.systemDefault());

	private static final List<Library> LIBRARIES = List.of(
		new Library("org.ow2.asm", "asm", "9.10.1", "ada2141c0cc52ee8f5c48cd5fa4ce0e794f22236"),
		new Library("org.ow2.asm", "asm-analysis", "9.10.1", "8d49f14d51f632cb1d87c88d1ceaf50db0d8af1b"),
		new Library("org.ow2.asm", "asm-commons", "9.10.1", "4229e4c55fd8e01c23f9fe9884075cc628aacc50"),
		new Library("org.ow2.asm", "asm-tree", "9.10.1", "e244332a17564c1d1572449399a842de35881be2"),
		new Library("org.ow2.asm", "asm-util", "9.10.1", "7bb9d450e8d4cbf9f9e04096c44bbfe7fba80b15"),
		new Library("net.fabricmc", "sponge-mixin", "0.17.3+mixin.0.8.7", "41c4a3984a80f4679e759fb9f495587acc5cdac7"),
		new Library("net.fabricmc", "intermediary", MINECRAFT_VERSION, ""),
		new Library("net.fabricmc", "fabric-loader", LOADER_VERSION, "")
	);

	private final Path root;
	private final Path instance;
	private final Path runtime;
	private final Path statusFile;
	private final Path configFile;
	private final Path commandsDir;
	private final Path daemonLog;
	private final String javaExecutable;
	private final String bootSource;
	private final PlayitManager playit;
	private final Object logLock = new Object();
	private final Map<String, String> status = new LinkedHashMap<>();
	private final Set<String> players = new LinkedHashSet<>();
	private final Set<String> loadedPlugins = new LinkedHashSet<>();
	private final Set<String> failedPlugins = new LinkedHashSet<>();
	private final Set<String> expectedPlugins = new LinkedHashSet<>();
	private final Map<String, String> pluginNamesByFile = new LinkedHashMap<>();

	private volatile Process server;
	private volatile BufferedWriter serverInput;
	private volatile BufferedWriter consoleOutput;
	private volatile boolean stopRequested;
	private volatile boolean serverReady;
	private volatile long lastCrashAt;
	private volatile int crashAttempts;

	private HostDaemon(Path root, Path instance, String javaExecutable, String bootSource) {
		this.root = root.toAbsolutePath().normalize();
		this.instance = instance.toAbsolutePath().normalize();
		this.runtime = this.root.resolve("runtime");
		this.statusFile = this.root.resolve("status.properties");
		this.configFile = this.root.resolve("config.properties");
		this.commandsDir = this.root.resolve("commands");
		this.daemonLog = this.root.resolve("logs").resolve("daemon.log");
		this.javaExecutable = javaExecutable;
		this.bootSource = bootSource;
		this.playit = new PlayitManager(this.root, message -> log("[Playit] " + message));
	}

	public static void main(String[] args) {
		Map<String, String> values = parseArgs(args);
		Path root = Path.of(required(values, "root"));
		Path instance = Path.of(required(values, "instance"));
		String javaExecutable = required(values, "java");
		String bootSource = values.getOrDefault("boot", "client");

		try {
			new HostDaemon(root, instance, javaExecutable, bootSource).run();
		} catch (Throwable throwable) {
			try {
				Files.createDirectories(root.resolve("logs"));
				Files.writeString(
					root.resolve("logs").resolve("daemon-fatal.log"),
					Instant.now() + " " + throwable + System.lineSeparator(),
					StandardCharsets.UTF_8,
					StandardOpenOption.CREATE,
					StandardOpenOption.APPEND
				);
			} catch (IOException ignored) {
			}
		}
	}

	private void run() throws Exception {
		Files.createDirectories(root);
		Files.createDirectories(commandsDir);
		Files.createDirectories(daemonLog.getParent());
		Path lockPath = root.resolve("daemon.lock");

		try (FileChannel lockChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
			 FileLock ignored = tryDaemonLock(lockChannel)) {
			if (ignored == null) {
				return;
			}

			setState("OFFLINE", "Ready to host");
			status.put("daemonPid", Long.toString(ProcessHandle.current().pid()));
			status.put("bootSource", bootSource);
			writeStatus();
			log("Daemon started from " + bootSource + " using " + javaExecutable);
			var addressPublisher = new dev.everhost.address.RoutePublisher(root);
			addressPublisher.start();
			playit.start();
			Runtime.getRuntime().addShutdownHook(new Thread(() -> {
				addressPublisher.close();
				playit.close();
			}, "EverHost-Network-Shutdown"));

			Properties config = loadProperties(configFile);
			boolean shouldAutoStart = "windows".equals(bootSource)
				? bool(config, "startWithWindows", false)
				: bool(config, "autoStartWithMinecraft", false);
			if (shouldAutoStart && bool(config, "eulaAccepted", false) && !config.getProperty("worldId", "").isBlank()) {
				startServer();
			}

			while (true) {
				processCommands();
				watchServer();
				refreshPublicAddressStatus();
				status.put("heartbeat", Long.toString(System.currentTimeMillis()));
				status.put("daemonPid", Long.toString(ProcessHandle.current().pid()));
				writeStatusBestEffort();
				Thread.sleep(500L);
			}
		}
	}

	private static FileLock tryDaemonLock(FileChannel channel) throws IOException {
		try {
			return channel.tryLock();
		} catch (OverlappingFileLockException exception) {
			return null;
		}
	}

	private void processCommands() {
		try (Stream<Path> paths = Files.list(commandsDir)) {
			paths.filter(path -> path.getFileName().toString().endsWith(".properties"))
				.sorted(Comparator.comparing(path -> path.getFileName().toString()))
				.forEach(this::processCommand);
		} catch (IOException exception) {
			log("Could not scan commands: " + exception.getMessage());
		}
	}

	private void processCommand(Path path) {
		try {
			Properties command = loadProperties(path);
			String action = command.getProperty("action", "").toUpperCase(Locale.ROOT);
			log("Command received: " + action);
			switch (action) {
			case "START" -> startServer();
				case "STOP" -> stopServer(false, StopReason.STOP);
				case "RESTART" -> {
					stopServer(false, StopReason.RESTART);
					startServer();
				}
				case "BACKUP" -> backupNow();
				case "CONSOLE" -> executeConsoleCommand(command.getProperty("command", ""));
				case "PLAYIT_SETUP" -> playit.beginSetup(
					command.getProperty("serverLabel", "My Server"),
					command.getProperty("profileKey", command.getProperty("serverLabel", "My Server")),
					integer(command, "port", 25570, 1024, 65535)
				);
				case "PLAYIT_START" -> playit.setEnabled(true, integer(command, "port", 25570, 1024, 65535));
				case "PLAYIT_STOP" -> playit.setEnabled(false, integer(command, "port", 25570, 1024, 65535));
				case "PLAYIT_REFRESH" -> playit.refresh();
				case "SHUTDOWN_DAEMON" -> {
					stopServer(false, StopReason.UPDATE);
					playit.close();
					setState("OFFLINE", "Daemon stopped");
					writeStatus();
					Files.deleteIfExists(path);
					System.exit(0);
				}
				default -> log("Ignored unknown command: " + action);
			}
		} catch (Exception exception) {
			setError("Command failed: " + exception.getMessage());
			log("Command failed: " + exception);
		} finally {
			try {
				Files.deleteIfExists(path);
			} catch (IOException ignored) {
			}
		}
	}

	private synchronized void startServer() throws Exception {
		if (server != null && server.isAlive()) {
			setState("ONLINE", "Server is already running");
			return;
		}

		Properties config = loadProperties(configFile);
		int serverPort = integer(config, "port", 25570, 1024, 65535);
		playit.updateLocalPort(serverPort);
		if (!bool(config, "eulaAccepted", false)) {
			throw new IllegalStateException("Accept the Minecraft server EULA in EverHost before starting");
		}

		Profile profile = selectedProfile(config);
		String worldId = config.getProperty("worldId", "").trim();
		if (worldId.isBlank()) {
			throw new IllegalStateException("Choose a world before starting");
		}
		Path saves = profile.path().resolve("saves").toAbsolutePath().normalize();
		Path world = saves.resolve(worldId).normalize();
		if (!world.startsWith(saves) || !Files.isRegularFile(world.resolve("level.dat"))) {
			throw new IllegalStateException("The selected world is missing or invalid: " + worldId);
		}
		int defaultDeliveryPort = serverPort == 65535 ? 65534 : serverPort + 1;
		int deliveryPort = integer(config, "modDeliveryPort", defaultDeliveryPort, 1024, 65535);
		if (deliveryPort == serverPort) deliveryPort = defaultDeliveryPort;
		playit.prepareModDelivery(runtime, deliveryPort);

		setState("PROVISIONING", "Preparing " + profile.loader() + " " + profile.minecraftVersion());
		status.put("profileName", profile.name());
		status.put("minecraftVersion", profile.minecraftVersion());
		status.put("loader", profile.loader().toString());
		status.put("worldId", worldId);
		status.put("worldName", config.getProperty("worldName", worldId));
		status.put("domain", "");
		status.put("playerCount", "0");
		status.put("players", "");
		players.clear();
		loadedPlugins.clear();
		failedPlugins.clear();
		status.put("pluginLoaded", "0");
		status.put("pluginFailed", "0");
		status.put("loadedPlugins", "");
		status.put("failedPlugins", "");
		writeStatus();

		ensureWorldAvailable(world);
		List<ModInfo> selectedMods = selectedMods(profile, config);
		boolean pluginsEnabled = "auto".equalsIgnoreCase(config.getProperty("pluginMode", "off"));
		int maximumJava = maximumPluginJava(profile);
		ScanResult pluginScan = pluginsEnabled
			? PluginSupport.scan(profile.path().resolve("plugins"), profile.minecraftVersion(), disabledPluginFiles(config),
				maximumJava, selectedMods.stream().map(mod -> mod.path).toList())
			: new ScanResult(List.of());
		int minimumJava = Math.max(ProfileScanner.requiredJava(profile.minecraftVersion()), pluginScan.maximumRequiredJava());
		JavaLocator.JavaRuntime javaRuntime = JavaLocator.locateAtLeast(minimumJava, maximumJava);
		status.put("serverJava", Integer.toString(javaRuntime.major()));
		List<String> loaderCommand = ensureRuntime(profile, selectedMods, config, javaRuntime, pluginScan);
		if (bool(config, "backupBeforeStart", false)) {
			createBackup(world, config);
		}
		writeServerFiles(profile, world, config);
		ServerRuntimeTuner.configureUnrestrictedMovement(runtime);
		log("Extreme modded movement is unrestricted for this server");

		int minMemory = integer(config, "minMemoryMb", 2048, 512, 65536);
		int maxMemory = integer(config, "maxMemoryMb", 8192, minMemory, 65536);
		List<String> command = new ArrayList<>();
		command.add(javaRuntime.executable().toString());
		command.add("-Djavax.net.ssl.trustStoreType=Windows-ROOT");
		command.add("-Djavax.net.ssl.trustStore=NONE");
		if (javaRuntime.major() >= 21) {
			command.add("-XX:+UseZGC");
			command.add("-XX:+ZGenerational");
		}
		command.add("-Xms" + minMemory + "M");
		command.add("-Xmx" + maxMemory + "M");
		command.addAll(loaderCommand);

		ProcessBuilder builder = new ProcessBuilder(command);
		builder.directory(runtime.toFile());
		builder.redirectErrorStream(true);
		stopRequested = false;
		serverReady = false;
		server = builder.start();
		serverInput = new BufferedWriter(new OutputStreamWriter(server.getOutputStream(), StandardCharsets.UTF_8));
		status.put("serverPid", Long.toString(server.pid()));
		status.put("startedAt", Long.toString(System.currentTimeMillis()));
		status.put("port", config.getProperty("port", "25570"));
		setState("STARTING", "Loading " + status.get("worldName"));
		writeStatus();
		startLogReader(server);
		log("Server process started with PID " + server.pid());
	}

	private List<String> ensureRuntime(
		Profile profile,
		List<ModInfo> selectedMods,
		Properties config,
		JavaLocator.JavaRuntime javaRuntime,
		ScanResult pluginScan
	) throws Exception {
		Files.createDirectories(runtime);
		Files.createDirectories(runtime.resolve("mods"));
		Files.createDirectories(runtime.resolve("plugins"));
		Files.createDirectories(runtime.resolve("config"));
		boolean pluginsEnabled = "auto".equalsIgnoreCase(config.getProperty("pluginMode", "off"));
		List<ModInfo> runtimeMods = pluginsEnabled
			? selectedMods.stream().filter(mod -> !mod.pluginBridge).toList()
			: selectedMods;
		syncProfileMods(runtimeMods);
		PluginDeployment plugins = preparePlugins(profile, pluginsEnabled, pluginScan);
		copyProfileConfigs(profile);
		RequiredModsManifest.write(runtime, profile, selectedMods);
		status.put("serverModCount", Integer.toString(runtimeMods.size()));
		status.put("requiredModCount", Long.toString(selectedMods.stream().filter(mod -> mod.clientRequired).count()));

		boolean distantHorizonsInstalled = selectedMods.stream().anyMatch(mod ->
			mod.path.getFileName().toString().toLowerCase(Locale.ROOT).contains("distanthorizons"));
		if (distantHorizonsInstalled) configureDistantHorizons(config);

		if (pluginsEnabled && plugins.bridge().replacesServerLauncher()) {
			if (profile.loader() == LoaderType.FORGE) {
				log("Arclight compatibility mode: EverHost will manage the hybrid server externally");
			}
			Path hybridLauncher = provisionPluginBridge(plugins.bridge());
			return List.of("-jar", hybridLauncher.toString(), "nogui");
		}
		if (profile.loader() == LoaderType.FABRIC) {
			if (pluginsEnabled) provisionPluginBridge(plugins.bridge());
			Path launcher = runtime.resolve("fabric-server-launch-" + profile.minecraftVersion() + "-" + profile.loaderVersion() + ".jar");
			download(fabricServerUri(profile), launcher, "");
			return List.of("-jar", launcher.toString(), "nogui");
		}
		if (profile.loader() == LoaderType.FORGE) {
			copyEverHostServerBridge();
			Path args = prepareForge(profile, javaRuntime);
			return List.of("@" + runtime.relativize(args), "nogui");
		}
		throw new IOException("Unsupported server loader: " + profile.loader());
	}

	private Profile selectedProfile(Properties config) {
		String configuredPath = config.getProperty("profilePath", "").trim();
		if (configuredPath.isBlank()) throw new IllegalStateException("Choose a CurseForge profile before starting");
		Path path;
		try {
			path = Path.of(configuredPath).toAbsolutePath().normalize();
		} catch (RuntimeException exception) {
			throw new IllegalStateException("The selected CurseForge profile path is invalid", exception);
		}
		return ProfileScanner.scan(ProfileScanner.defaultInstancesRoot()).stream()
			.filter(profile -> profile.path().equals(path))
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("The selected CurseForge profile is missing or unsupported"));
	}

	private List<ModInfo> selectedMods(Profile profile, Properties config) {
		Map<String, String> overrides = decodeModModes(config.getProperty("modModes", ""));
		List<ModInfo> selected = new ArrayList<>();
		for (ModInfo mod : profile.mods()) {
			String mode = overrides.get(mod.path.getFileName().toString());
			if (mode != null) {
				mod.selected = !"OFF".equals(mode);
				mod.clientRequired = "REQUIRED".equals(mode);
			}
			if (mod.selected) selected.add(mod);
		}
		return selected;
	}

	private static Map<String, String> decodeModModes(String encoded) {
		Map<String, String> result = new LinkedHashMap<>();
		if (encoded == null || encoded.isBlank()) return result;
		for (String entry : encoded.split(";")) {
			int separator = entry.lastIndexOf('=');
			if (separator <= 0) continue;
			try {
				result.put(URLDecoder.decode(entry.substring(0, separator), StandardCharsets.UTF_8), entry.substring(separator + 1));
			} catch (IllegalArgumentException ignored) {
			}
		}
		return result;
	}

	private static Set<String> disabledPluginFiles(Properties config) {
		Set<String> disabled = new LinkedHashSet<>();
		for (Map.Entry<String, String> entry : decodeModModes(config.getProperty("pluginStates", "")).entrySet()) {
			if ("DISABLED".equalsIgnoreCase(entry.getValue())) disabled.add(entry.getKey());
		}
		return disabled;
	}

	private static int maximumPluginJava(Profile profile) {
		return profile.loader() == LoaderType.FABRIC && "1.21.11".equals(profile.minecraftVersion())
			? 25 : ProfileScanner.requiredJava(profile.minecraftVersion());
	}

	private void syncProfileMods(List<ModInfo> selectedMods) throws IOException {
		Path mods = runtime.resolve("mods");
		try (Stream<Path> paths = Files.list(mods)) {
			for (Path existing : paths.filter(Files::isRegularFile)
				.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")).toList()) {
				Files.deleteIfExists(existing);
			}
		}
		for (ModInfo mod : selectedMods) {
			Files.copy(mod.path, mods.resolve(mod.path.getFileName()), StandardCopyOption.REPLACE_EXISTING);
		}
		log("Synced " + selectedMods.size() + " selected server mods");
	}

	private PluginDeployment preparePlugins(Profile profile, boolean enabled, ScanResult scan) throws IOException {
		Path plugins = runtime.resolve("plugins");
		expectedPlugins.clear();
		pluginNamesByFile.clear();
		try (Stream<Path> paths = Files.list(plugins)) {
			for (Path existing : paths.filter(Files::isRegularFile)
				.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")).toList()) {
				Files.deleteIfExists(existing);
			}
		}
		if (!enabled) {
			status.put("pluginMode", "Off");
			status.put("pluginBridge", "Disabled");
			status.put("pluginReady", "0");
			status.put("pluginWarnings", "0");
			status.put("pluginBlocked", "0");
			status.put("pluginDisabled", "0");
			return new PluginDeployment(null, new ScanResult(List.of()));
		}
		BridgePlan bridge = PluginSupport.bridgeFor(profile)
			.orElseThrow(() -> new IOException("No plugin bridge is available for " + profile.minecraftVersion() + " " + profile.loader()));
		for (PluginInfo plugin : scan.plugins()) {
			if (plugin.runnable()) {
				Files.copy(plugin.path(), plugins.resolve(plugin.path().getFileName()), StandardCopyOption.REPLACE_EXISTING);
				expectedPlugins.add(plugin.name());
				pluginNamesByFile.put(plugin.path().getFileName().toString().toLowerCase(Locale.ROOT), plugin.name());
				log("Plugin accepted: " + plugin.name() + " (" + plugin.kind().displayName() + ")");
			} else {
				log("Plugin blocked: " + plugin.path().getFileName() + " - " + plugin.detail());
			}
		}
		PluginSupport.writeReport(root.resolve("plugin-report.txt"), profile, bridge, scan);
		storeProperties(root.resolve("plugin-report.properties"), PluginSupport.reportProperties(profile, bridge, scan),
			"EverHost plugin compatibility report");
		status.put("pluginMode", "Automatic");
		status.put("pluginBridge", bridge.displayName());
		status.put("pluginReady", Integer.toString(scan.readyCount()));
		status.put("pluginWarnings", Integer.toString(scan.warningCount()));
		status.put("pluginBlocked", Integer.toString(scan.blockedCount()));
		status.put("pluginDisabled", Integer.toString(scan.disabledCount()));
		log("Plugin plan: " + bridge.displayName() + ", " + scan.runnableCount() + " runnable, " + scan.blockedCount() + " blocked");
		return new PluginDeployment(bridge, scan);
	}

	private Path provisionPluginBridge(BridgePlan bridge) throws Exception {
		if (bridge == null) throw new IOException("Plugin support was enabled without a compatible bridge");
		Path primary = null;
		for (int index = 0; index < bridge.artifacts().size(); index++) {
			var artifact = bridge.artifacts().get(index);
			Path directory = bridge.replacesServerLauncher()
				? runtime.resolve(".everhost").resolve("bridges")
				: runtime.resolve("mods");
			Path target = directory.resolve(artifact.fileName());
			download(artifact.uri(), target, artifact.sha256());
			if (index == 0) primary = target;
		}
		status.put("pluginBridge", bridge.displayName());
		log("Plugin bridge ready: " + bridge.displayName());
		return primary;
	}

	private void copyProfileConfigs(Profile profile) throws IOException {
		Path source = profile.path().resolve("config");
		Path target = runtime.resolve("config");
		if (!Files.isDirectory(source)) return;
		try (Stream<Path> paths = Files.walk(source)) {
			for (Path path : paths.toList()) {
				Path destination = target.resolve(source.relativize(path).toString());
				if (Files.isDirectory(path)) Files.createDirectories(destination);
				else if (Files.isRegularFile(path)) {
					Files.createDirectories(destination.getParent());
					Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
	}

	private static URI fabricServerUri(Profile profile) {
		return URI.create("https://meta.fabricmc.net/v2/versions/loader/"
			+ encode(profile.minecraftVersion()) + "/" + encode(profile.loaderVersion()) + "/1.1.0/server/jar");
	}

	private static URI forgeInstallerUri(Profile profile) {
		String version = profile.minecraftVersion() + "-" + profile.loaderVersion();
		return URI.create("https://maven.minecraftforge.net/net/minecraftforge/forge/" + version + "/forge-" + version + "-installer.jar");
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
	}

	private Path prepareForge(Profile profile, JavaLocator.JavaRuntime javaRuntime) throws Exception {
		Path args = findForgeArgs(profile);
		if (args != null) return args;
		Path installer = runtime.resolve("forge-installer-" + profile.minecraftVersion() + "-" + profile.loaderVersion() + ".jar");
		download(forgeInstallerUri(profile), installer, "");
		setState("PROVISIONING", "Installing Forge " + profile.loaderVersion());
		writeStatus();
		Process process = new ProcessBuilder(javaRuntime.executable().toString(), "-jar", installer.toString(), "--installServer")
			.directory(runtime.toFile()).redirectErrorStream(true).start();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) log("[Forge installer] " + line);
		}
		if (process.waitFor() != 0) throw new IOException("Forge installer exited with code " + process.exitValue());
		args = findForgeArgs(profile);
		if (args == null) throw new IOException("Forge installed, but its server launch arguments were not found");
		return args;
	}

	private Path findForgeArgs(Profile profile) throws IOException {
		Path forgeRoot = runtime.resolve("libraries").resolve("net").resolve("minecraftforge").resolve("forge")
			.resolve(profile.minecraftVersion() + "-" + profile.loaderVersion());
		if (!Files.isDirectory(forgeRoot)) return null;
		try (Stream<Path> paths = Files.walk(forgeRoot, 2)) {
			return paths.filter(Files::isRegularFile)
				.filter(path -> path.getFileName().toString().equals("win_args.txt") || path.getFileName().toString().equals("unix_args.txt"))
				.sorted(Comparator.comparing(path -> path.getFileName().toString().startsWith("win") ? 0 : 1))
				.findFirst().orElse(null);
		}
	}

	private void ensureRuntime(Properties config) throws Exception {
		Files.createDirectories(runtime);
		Files.createDirectories(runtime.resolve("mods"));
		Files.createDirectories(runtime.resolve("config").resolve("e4mc"));
		Files.createDirectories(runtime.resolve(".fabric").resolve("libraries"));

		download(SERVER_URI, runtime.resolve("server.jar"), SERVER_SHA1);
		for (Library library : LIBRARIES) {
			download(library.uri(), runtime.resolve(".fabric").resolve("libraries").resolve(library.relativePath()), library.sha1());
		}

		copyRequiredMod("e4mc", "e4mc");
		copyRequiredMod("fabric-api", "fabric-api");
		copyEverHostServerBridge();
		boolean distantHorizonsInstalled = copyOptionalMod("distanthorizons", "Distant Horizons");
		copyOptionalMod("lithium", "Lithium");
		copyOptionalMod("ferritecore", "FerriteCore");
		copyOptionalMod("chunky", "Chunky");
		if (distantHorizonsInstalled) {
			configureDistantHorizons(config);
		}
		Path e4mcConfig = instance.resolve("config").resolve("e4mc").resolve("e4mc.toml");
		if (Files.isRegularFile(e4mcConfig)) {
			Files.copy(e4mcConfig, runtime.resolve("config").resolve("e4mc").resolve("e4mc.toml"), StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private void copyRequiredMod(String filePrefix, String label) throws IOException {
		Path source;
		try (Stream<Path> paths = Files.list(instance.resolve("mods"))) {
			source = paths.filter(Files::isRegularFile)
				.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).startsWith(filePrefix))
				.filter(path -> path.getFileName().toString().endsWith(".jar"))
				.findFirst()
				.orElseThrow(() -> new IOException(label + " is not installed in this modpack"));
		}
		copyModToRuntime(source, filePrefix);
	}

	private boolean copyOptionalMod(String filePrefix, String label) throws IOException {
		Path source;
		try (Stream<Path> paths = Files.list(instance.resolve("mods"))) {
			source = paths.filter(Files::isRegularFile)
				.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).startsWith(filePrefix))
				.filter(path -> path.getFileName().toString().endsWith(".jar"))
				.findFirst()
				.orElse(null);
		}
		if (source == null) {
			log(label + " is not installed; dedicated-server integration skipped");
			return false;
		}
		copyModToRuntime(source, filePrefix);
		return true;
	}

	private void configureDistantHorizons(Properties config) throws IOException {
		Path target = runtime.resolve("config").resolve("DistantHorizons.toml");
		Path source = instance.resolve("config").resolve("DistantHorizons.toml");
		Files.createDirectories(target.getParent());
		if (!Files.isRegularFile(target)) {
			if (Files.isRegularFile(source)) {
				Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
			} else {
				Files.writeString(target, defaultDistantHorizonsConfig(), StandardCharsets.UTF_8);
			}
		}

		boolean enabled = bool(config, "distantHorizons", true);
		int realTimeRadius = integer(config, "dhRealTimeRadius", 256, 64, 512);
		int lodDistance = integer(config, "dhLodDistance", 4096, 512, 4096);
		int playerBandwidth = integer(config, "dhPlayerBandwidthKbps", 2000, 250, 8000);
		String networkMode = choice(config, "networkMode", "auto", "auto", "slow_wifi", "balanced", "fast");
		if (networkMode.equals("slow_wifi")) playerBandwidth = Math.min(playerBandwidth, 500);
		else if (networkMode.equals("auto")) playerBandwidth = Math.min(playerBandwidth, 1000);
		int threads = integer(config, "dhThreads", 16, 2, 24);
		String identityHash = stableIdentityHash(config);

		String contents = Files.readString(target, StandardCharsets.UTF_8);
		contents = setTomlValue(contents, "realTimeUpdateDistanceRadiusInChunks", Integer.toString(realTimeRadius));
		contents = setTomlValue(contents, "maxSyncOnLoadRequestDistance", Integer.toString(lodDistance));
		contents = setTomlValue(contents, "synchronizeOnLoad", Boolean.toString(enabled));
		contents = setTomlValue(contents, "serverKey", "\"everhost-" + identityHash.substring(0, 24) + "\"");
		contents = setTomlValue(contents, "maxGenerationRequestDistance", Integer.toString(lodDistance));
		contents = setTomlValue(contents, "enableServerGeneration", Boolean.toString(enabled));
		contents = setTomlValue(contents, "sendLevelKeys", "true");
		contents = setTomlValue(contents, "serverId", Integer.toString(stableServerId(identityHash)));
		contents = setTomlValue(contents, "generationRequestRateLimit", "40");
		contents = setTomlValue(contents, "syncOnLoadRateLimit", "100");
		contents = setTomlValue(contents, "enableRealTimeUpdates", Boolean.toString(enabled));
		contents = setTomlValue(contents, "globalBandwidthLimit", "0");
		contents = setTomlValue(contents, "playerBandwidthLimit", Integer.toString(playerBandwidth));
		contents = setTomlValue(contents, "enableAdaptiveTransferSpeed", Boolean.toString(bool(config, "dhAdaptiveTransfer", true)));
		contents = setTomlValue(contents, "numberOfThreads", Integer.toString(threads));
		writeStringAtomic(target, contents);
	}

	private String stableIdentityHash(Properties config) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			String identity = config.getProperty("profilePath", instance.toString()) + "\n" + config.getProperty("worldId", "");
			return java.util.HexFormat.of().formatHex(digest.digest(identity.getBytes(StandardCharsets.UTF_8)));
		} catch (java.security.NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available", exception);
		}
	}

	private static int stableServerId(String identityHash) {
		int value = (int)(Long.parseLong(identityHash.substring(0, 8), 16) & 0x7FFFFFFFL);
		return value == 0 ? 1 : value;
	}

	private static String setTomlValue(String contents, String key, String value) throws IOException {
		Pattern pattern = Pattern.compile("(?m)^(\\s*" + Pattern.quote(key) + "\\s*=\\s*).*$");
		Matcher matcher = pattern.matcher(contents);
		if (!matcher.find()) {
			throw new IOException("Distant Horizons setting is missing: " + key);
		}
		return contents.substring(0, matcher.start()) + matcher.group(1) + value + contents.substring(matcher.end());
	}

	private static String defaultDistantHorizonsConfig() {
		return """
			_version = 4

			[server]
				realTimeUpdateDistanceRadiusInChunks = 256
				levelKeyPrefix = ""
				maxSyncOnLoadRequestDistance = 4096
				synchronizeOnLoad = true
				serverKey = ""
				maxGenerationRequestDistance = 4096
				enableServerGeneration = true
				sendLevelKeys = true
				serverId = 1
				generationRequestRateLimit = 20
				syncOnLoadRateLimit = 50
				enableRealTimeUpdates = true
				globalBandwidthLimit = 0
				playerBandwidthLimit = 500
				enableAdaptiveTransferSpeed = false

				[server.experimental]
					enableNSizedGeneration = false

			[common.multiThreading]
				numberOfThreads = 16
				threadRunTimeRatio = "1.0"
				threadPriority = 5
			""";
	}

	private void copyEverHostServerBridge() throws IOException {
		var codeSource = HostDaemon.class.getProtectionDomain().getCodeSource();
		if (codeSource == null) {
			throw new IOException("Could not locate the EverHost server compatibility bridge");
		}

		Path source;
		try {
			source = Path.of(codeSource.getLocation().toURI());
		} catch (java.net.URISyntaxException exception) {
			throw new IOException("Could not resolve the EverHost mod path", exception);
		}
		if (!Files.isRegularFile(source) || !source.getFileName().toString().endsWith(".jar")) {
			log("Development classes detected; server compatibility bridge copy skipped");
			return;
		}
		copyModToRuntime(source, "everhost");
	}

	private void copyModToRuntime(Path source, String filePrefix) throws IOException {
		Path mods = runtime.resolve("mods");
		Path destination = mods.resolve(source.getFileName());
		String normalizedPrefix = filePrefix.toLowerCase(Locale.ROOT);
		boolean destinationIsCurrent = Files.isRegularFile(destination)
			&& Files.size(source) == Files.size(destination)
			&& Files.mismatch(source, destination) == -1L;
		try (Stream<Path> paths = Files.list(mods)) {
			for (Path existing : paths.filter(Files::isRegularFile)
				.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).startsWith(normalizedPrefix))
				.filter(path -> path.getFileName().toString().endsWith(".jar"))
				.filter(path -> !path.equals(destination))
				.toList()) {
				Files.deleteIfExists(existing);
			}
		}
		if (destinationIsCurrent) {
			return;
		}
		Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
	}

	private void ensureWorldAvailable(Path world) throws IOException {
		Path lockPath = world.resolve("session.lock");
		try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
			try (FileLock lock = channel.tryLock()) {
				if (lock == null) {
					throw new IOException("That world is already open in Minecraft or another server");
				}
			} catch (OverlappingFileLockException exception) {
				throw new IOException("That world is already open in Minecraft or another server", exception);
			}
		} catch (IOException exception) {
			throw new IOException("That world is already open in Minecraft or another server", exception);
		}
	}

	private void writeServerFiles(Profile profile, Path world, Properties config) throws IOException {
		String networkMode = choice(config, "networkMode", "auto", "auto", "slow_wifi", "balanced", "fast");
		String compression = networkMode.equals("fast") ? "256" : networkMode.equals("slow_wifi") ? "64" : "128";
		Properties serverProperties = new Properties();
		serverProperties.setProperty("level-name", world.toString());
		serverProperties.setProperty("server-ip", "127.0.0.1");
		serverProperties.setProperty("server-port", Integer.toString(integer(config, "port", 25570, 1024, 65535)));
		serverProperties.setProperty("max-players", Integer.toString(integer(config, "maxPlayers", 10, 1, 500)));
		serverProperties.setProperty("view-distance", Integer.toString(integer(config, "viewDistance", 32, 2, 32)));
		serverProperties.setProperty("simulation-distance", Integer.toString(integer(config, "simulationDistance", 12, 2, 32)));
		serverProperties.setProperty("difficulty", choice(config, "difficulty", "normal", "peaceful", "easy", "normal", "hard"));
		serverProperties.setProperty("gamemode", choice(config, "gamemode", "survival", "survival", "creative", "adventure", "spectator"));
		serverProperties.setProperty("force-gamemode", Boolean.toString(bool(config, "forceGamemode", false)));
		serverProperties.setProperty("pvp", Boolean.toString(bool(config, "pvp", true)));
		serverProperties.setProperty("allow-flight", Boolean.toString(bool(config, "allowFlight", true)));
		serverProperties.setProperty("enable-command-block", Boolean.toString(bool(config, "commandBlocks", false)));
		serverProperties.setProperty("spawn-protection", Integer.toString(integer(config, "spawnProtection", 0, 0, 64)));
		serverProperties.setProperty("hardcore", Boolean.toString(bool(config, "hardcore", false)));
		serverProperties.setProperty("allow-nether", Boolean.toString(bool(config, "allowNether", true)));
		serverProperties.setProperty("generate-structures", Boolean.toString(bool(config, "generateStructures", true)));
		serverProperties.setProperty("spawn-animals", Boolean.toString(bool(config, "spawnAnimals", true)));
		serverProperties.setProperty("spawn-monsters", Boolean.toString(bool(config, "spawnMonsters", true)));
		serverProperties.setProperty("spawn-npcs", Boolean.toString(bool(config, "spawnNpcs", true)));
		serverProperties.setProperty("enable-status", Boolean.toString(bool(config, "enableStatus", true)));
		serverProperties.setProperty("hide-online-players", Boolean.toString(bool(config, "hideOnlinePlayers", false)));
		serverProperties.setProperty("prevent-proxy-connections", Boolean.toString(bool(config, "preventProxyConnections", false)));
		serverProperties.setProperty("player-idle-timeout", Integer.toString(integer(config, "playerIdleTimeout", 0, 0, 1440)));
		serverProperties.setProperty("entity-broadcast-range-percentage", Integer.toString(integer(config, "entityBroadcastRangePercentage", 100, 10, 1000)));
		serverProperties.setProperty("rate-limit", Integer.toString(integer(config, "rateLimit", 0, 0, 10000)));
		serverProperties.setProperty("op-permission-level", Integer.toString(integer(config, "opPermissionLevel", 4, 1, 4)));
		serverProperties.setProperty("function-permission-level", Integer.toString(integer(config, "functionPermissionLevel", 2, 1, 4)));
		serverProperties.setProperty("online-mode", Boolean.toString(bool(config, "onlineMode", true)));
		serverProperties.setProperty("enforce-secure-profile", Boolean.toString(bool(config, "secureProfiles", true)));
		serverProperties.setProperty("white-list", Boolean.toString(bool(config, "whitelist", false)));
		serverProperties.setProperty("enforce-whitelist", Boolean.toString(bool(config, "enforceWhitelist", false)));
		serverProperties.setProperty("motd", config.getProperty("motd", "EverHost persistent world"));
		serverProperties.setProperty("enable-query", "false");
		serverProperties.setProperty("enable-rcon", "false");
		serverProperties.setProperty("broadcast-console-to-ops", Boolean.toString(bool(config, "broadcastConsoleToOps", true)));
		serverProperties.setProperty("network-compression-threshold", compression);
		serverProperties.setProperty("use-native-transport", "true");
		serverProperties.setProperty("pause-when-empty-seconds", "-1");
		serverProperties.setProperty("max-tick-time", "60000");
		storeProperties(runtime.resolve("server.properties"), serverProperties, "Managed by EverHost");
		writeLagGuardConfig(config, networkMode);
		Files.writeString(runtime.resolve("eula.txt"), "# Accepted through the EverHost confirmation screen\neula=true\n", StandardCharsets.UTF_8);

		if (bool(config, "syncOps", true)) {
			copyIfPresent(profile.path().resolve("ops.json"), runtime.resolve("ops.json"));
		}
		if (bool(config, "syncWhitelist", true)) {
			copyIfPresent(profile.path().resolve("whitelist.json"), runtime.resolve("whitelist.json"));
		}
		copyIfPresent(profile.path().resolve("banned-players.json"), runtime.resolve("banned-players.json"));
		copyIfPresent(profile.path().resolve("banned-ips.json"), runtime.resolve("banned-ips.json"));
	}

	private void writeLagGuardConfig(Properties config, String networkMode) throws IOException {
		Properties lag = new Properties();
		lag.setProperty("enabled", Boolean.toString(bool(config, "lagGuard", true)));
		lag.setProperty("notices", Boolean.toString(bool(config, "lagNotices", true)));
		lag.setProperty("joinProtection", Boolean.toString(bool(config, "lagJoinProtection", true)));
		lag.setProperty("networkMode", networkMode);
		lag.setProperty("normalView", Integer.toString(integer(config, "viewDistance", 32, 2, 32)));
		lag.setProperty("normalSimulation", Integer.toString(integer(config, "simulationDistance", 12, 2, 32)));
		lag.setProperty("minimumView", Integer.toString(integer(config, "lagMinViewDistance", 4, 2, 32)));
		lag.setProperty("minimumSimulation", Integer.toString(integer(config, "lagMinSimulationDistance", 4, 2, 32)));
		lag.setProperty("warningMspt", Integer.toString(integer(config, "lagWarnMspt", 65, 50, 250)));
		lag.setProperty("warningPingMs", Integer.toString(integer(config, "lagPingMs", 350, 100, 2000)));
		lag.setProperty("recoverySeconds", Integer.toString(integer(config, "lagRecoverySeconds", 45, 10, 180)));
		storeProperties(runtime.resolve("everhost-lagguard.properties"), lag, "Managed by EverHost");
	}

	private static void copyIfPresent(Path source, Path destination) throws IOException {
		if (Files.isRegularFile(source)) {
			Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private String serverClasspath() {
		List<String> paths = new ArrayList<>();
		paths.add(runtime.resolve("server.jar").toAbsolutePath().toString());
		for (Library library : LIBRARIES) {
			paths.add(runtime.resolve(".fabric").resolve("libraries").resolve(library.relativePath()).toAbsolutePath().toString());
		}
		return String.join(System.getProperty("path.separator"), paths);
	}

	private void startLogReader(Process process) {
		Thread reader = new Thread(() -> {
			Path serverLog = root.resolve("logs").resolve("server-console.log");
			Path currentConsole = root.resolve("console-current.log");
			try (BufferedReader lines = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
				 BufferedWriter logWriter = Files.newBufferedWriter(
					 serverLog,
					 StandardCharsets.UTF_8,
					 StandardOpenOption.CREATE,
					 StandardOpenOption.APPEND
				 );
				 BufferedWriter currentWriter = Files.newBufferedWriter(
					 currentConsole,
					 StandardCharsets.UTF_8,
					 StandardOpenOption.CREATE,
					 StandardOpenOption.TRUNCATE_EXISTING
				 )) {
				synchronized (this) {
					consoleOutput = currentWriter;
				}
				String line;
				while ((line = lines.readLine()) != null) {
					logWriter.write(line);
					logWriter.newLine();
					logWriter.flush();
					synchronized (this) {
						currentWriter.write(line);
						currentWriter.newLine();
						currentWriter.flush();
					}
					parseServerLine(line);
				}
			} catch (IOException exception) {
				log("Server log reader ended: " + exception.getMessage());
			} finally {
				synchronized (this) {
					consoleOutput = null;
				}
			}
		}, "EverHost server log reader");
		reader.setDaemon(true);
		reader.start();
	}

	private synchronized void executeConsoleCommand(String value) throws IOException {
		String command = value == null ? "" : value.strip();
		if (command.startsWith("/")) {
			command = command.substring(1).stripLeading();
		}
		if (command.isBlank() || command.length() > 2048
			|| command.chars().anyMatch(character -> character == '\r' || character == '\n' || character == 0)) {
			appendConsoleLine("[EverHost] Command rejected: enter one non-empty line up to 2048 characters.");
			return;
		}
		if (server == null || !server.isAlive() || serverInput == null) {
			appendConsoleLine("[EverHost] Server is offline. Start it before sending commands.");
			return;
		}
		appendConsoleLine("> " + command);
		serverInput.write(command);
		serverInput.newLine();
		serverInput.flush();
	}

	private void appendConsoleLine(String line) throws IOException {
		if (consoleOutput != null) {
			consoleOutput.write(line);
			consoleOutput.newLine();
			consoleOutput.flush();
		}
	}

	private synchronized void parseServerLine(String line) {
		Matcher domain = DOMAIN_PATTERN.matcher(line);
		if (domain.find()) {
			status.put("domain", domain.group(1));
			if (serverReady) setState("ONLINE", "Friends can join at " + domain.group(1));
		}
		if (line.contains("Done (") && line.contains("For help")) {
			serverReady = true;
			String publicAddress = publicAddress();
			setState("ONLINE", publicAddress.isBlank() ? "Waiting for a public address"
				: "Friends can join at " + publicAddress);
			crashAttempts = 0;
		}
		Matcher joined = JOIN_PATTERN.matcher(line);
		if (joined.find()) {
			players.add(joined.group(1));
			updatePlayers();
		}
		Matcher left = LEAVE_PATTERN.matcher(line);
		if (left.find()) {
			players.remove(left.group(1));
			updatePlayers();
		}
		Matcher enabled = PLUGIN_ENABLE_PATTERN.matcher(line);
		Matcher enabledBracket = PLUGIN_ENABLE_BRACKET_PATTERN.matcher(line);
		if (enabled.find()) {
			recordPluginLoaded(enabled.group(1));
		} else if (enabledBracket.find()) {
			recordPluginLoaded(enabledBracket.group(1));
		}
		Matcher failed = PLUGIN_FAILURE_PATTERN.matcher(line);
		String failedName = failed.find() ? expectedPluginName(failed.group(1)) : "";
		if (!failedName.isBlank()) {
			failedPlugins.add(failedName);
			loadedPlugins.remove(failedName);
			updatePluginRuntimeStatus();
		}
		Matcher taskFailure = PLUGIN_TASK_FAILURE_PATTERN.matcher(line);
		if (taskFailure.find()) {
			String plugin = expectedPluginName(taskFailure.group(1));
			if (!plugin.isBlank()) {
				failedPlugins.add(plugin);
				loadedPlugins.remove(plugin);
				updatePluginRuntimeStatus();
			}
		}
		Matcher runtimeFailure = PLUGIN_RUNTIME_FAILURE_PATTERN.matcher(line);
		if (runtimeFailure.find()) {
			String plugin = expectedPluginName(runtimeFailure.group(1));
			if (!plugin.isBlank()) {
				failedPlugins.add(plugin);
				loadedPlugins.remove(plugin);
				updatePluginRuntimeStatus();
			}
		}
		if (line.contains("Failed to start the minecraft server") || line.contains("Encountered an unexpected exception")) {
			status.put("lastError", stripLogPrefix(line));
		}
		try {
			writeStatus();
		} catch (IOException ignored) {
		}
	}

	private synchronized void refreshPublicAddressStatus() {
		if (!serverReady || server == null || !server.isAlive()) return;
		String publicAddress = publicAddress();
		if (publicAddress.isBlank()) return;
		String next = "Friends can join at " + publicAddress;
		if (!next.equals(status.get("message"))) setState("ONLINE", next);
	}

	private String publicAddress() {
		Properties config = loadProperties(configFile);
		return AddressClient.shareAddress(root, status.getOrDefault("domain", ""),
			config.getProperty("addressMode", "auto"));
	}

	private void updatePlayers() {
		status.put("playerCount", Integer.toString(players.size()));
		status.put("players", String.join(", ", players));
	}

	private void updatePluginRuntimeStatus() {
		status.put("pluginLoaded", Integer.toString(loadedPlugins.size()));
		status.put("pluginFailed", Integer.toString(failedPlugins.size()));
		status.put("loadedPlugins", String.join(", ", loadedPlugins));
		status.put("failedPlugins", String.join(", ", failedPlugins));
	}

	private void recordPluginLoaded(String reportedName) {
		String plugin = expectedPluginName(reportedName);
		if (plugin.isBlank()) return;
		loadedPlugins.add(plugin);
		failedPlugins.remove(plugin);
		updatePluginRuntimeStatus();
	}

	private String expectedPluginName(String reportedName) {
		if (reportedName == null || reportedName.isBlank()) return "";
		String cleaned = reportedName.trim();
		int separator = Math.max(cleaned.lastIndexOf('/'), cleaned.lastIndexOf('\\'));
		if (separator >= 0) cleaned = cleaned.substring(separator + 1);
		String fromFile = pluginNamesByFile.get(cleaned.toLowerCase(Locale.ROOT));
		if (fromFile != null) return fromFile;
		if (cleaned.toLowerCase(Locale.ROOT).endsWith(".jar")) cleaned = cleaned.substring(0, cleaned.length() - 4);
		for (String expected : expectedPlugins) {
			if (expected.equalsIgnoreCase(cleaned)) return expected;
		}
		return "";
	}

	private synchronized void watchServer() {
		Process current = server;
		if (current == null || current.isAlive()) {
			return;
		}
		int exitCode = current.exitValue();
		server = null;
		serverInput = null;
		status.put("serverPid", "");
		status.put("domain", "");
		players.clear();
		updatePlayers();
		if (stopRequested || exitCode == 0 && serverReady) {
			setState("OFFLINE", "Server stopped safely");
			stopRequested = false;
			return;
		}

		Properties config = loadProperties(configFile);
		boolean autoRestart = bool(config, "autoRestart", true);
		int maxAttempts = integer(config, "restartAttempts", 3, 0, 10);
		long now = System.currentTimeMillis();
		if (now - lastCrashAt > Duration.ofMinutes(10).toMillis()) {
			crashAttempts = 0;
		}
		lastCrashAt = now;
		crashAttempts++;
		if (autoRestart && crashAttempts <= maxAttempts) {
			setState("STARTING", "Server exited; restart " + crashAttempts + " of " + maxAttempts);
			try {
				Thread.sleep(2000L);
				startServer();
			} catch (Exception exception) {
				setError("Automatic restart failed: " + exception.getMessage());
			}
		} else {
			setError((serverReady ? "Server exited" : "Server failed before the world was ready")
				+ " (code " + exitCode + "). See the Console tab for details.");
		}
	}

	private void stopServer(boolean force, StopReason reason) throws Exception {
		// Keep the log reader free to drain output while Minecraft saves and exits.
		Process current = server;
		if (current == null || !current.isAlive()) {
			setState("OFFLINE", "Server is already stopped");
			return;
		}
		stopRequested = true;
		Properties config = loadProperties(configFile);
		if (!force && serverReady && serverInput != null) {
			int countdown = integer(config, "shutdownCountdownSeconds", 10, 5, 15);
			broadcastStopCountdown(reason, countdown);
		}
		setState("STOPPING", reason.finalStatus);
		writeStatus();
		if (!force && serverInput != null) {
			serverInput.write("stop");
			serverInput.newLine();
			serverInput.flush();
		}
		int timeout = integer(config, "stopTimeoutSeconds", 60, 10, 300);
		if (force || !current.waitFor(timeout, TimeUnit.SECONDS)) {
			log("Graceful stop timed out; terminating server process");
			current.destroy();
			if (!current.waitFor(10, TimeUnit.SECONDS)) {
				current.destroyForcibly();
				current.waitFor(10, TimeUnit.SECONDS);
			}
		}
		watchServer();
	}

	private void broadcastStopCountdown(StopReason reason, int seconds) throws Exception {
		for (int remaining = seconds; remaining > 0; remaining--) {
			if (ShutdownCountdown.shouldBroadcast(seconds, remaining))
				sendServerLine(ShutdownCountdown.countdownCommand(reason.announcement, remaining));
			setState("STOPPING", reason.announcement + " in " + remaining + (remaining == 1 ? " second" : " seconds"));
			status.put("heartbeat", Long.toString(System.currentTimeMillis()));
			writeStatusBestEffort();
			Thread.sleep(1000L);
		}
		sendServerLine(ShutdownCountdown.finalCommand(reason.nowMessage));
	}

	private void sendServerLine(String command) throws IOException {
		if (serverInput == null || server == null || !server.isAlive()) return;
		appendConsoleLine("> " + command);
		serverInput.write(command);
		serverInput.newLine();
		serverInput.flush();
	}

	private enum StopReason {
		STOP("Server shutting down", "Server is shutting down.", "Saving players and all dimensions"),
		RESTART("Server restarting", "Server is restarting.", "Saving before restart"),
		UPDATE("EverHost update starting", "EverHost is updating.", "Saving before EverHost update");

		private final String announcement;
		private final String nowMessage;
		private final String finalStatus;

		StopReason(String announcement, String nowMessage, String finalStatus) {
			this.announcement = announcement;
			this.nowMessage = nowMessage;
			this.finalStatus = finalStatus;
		}
	}

	private void backupNow() throws Exception {
		if (server != null && server.isAlive()) {
			throw new IllegalStateException("Stop the server before creating a manual backup");
		}
		Properties config = loadProperties(configFile);
		String worldId = config.getProperty("worldId", "").trim();
		if (worldId.isBlank()) {
			throw new IllegalStateException("Choose a world before creating a backup");
		}
		Profile profile = selectedProfile(config);
		Path world = profile.path().resolve("saves").resolve(worldId).normalize();
		ensureWorldAvailable(world);
		createBackup(world, config);
		setState("OFFLINE", "Backup completed");
	}

	private void createBackup(Path world, Properties config) throws IOException {
		setState("BACKING_UP", "Creating a safety backup");
		writeStatus();
		Path backupDir = root.resolve("backups");
		Files.createDirectories(backupDir);
		String safeName = world.getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_");
		Path target = backupDir.resolve(safeName + "_" + BACKUP_TIME.format(Instant.now()) + ".zip");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
			try (Stream<Path> files = Files.walk(world)) {
				for (Path file : files.filter(Files::isRegularFile).toList()) {
					Path relative = world.relativize(file);
					if (relative.toString().equals("session.lock")) {
						continue;
					}
					zip.putNextEntry(new ZipEntry(relative.toString().replace('\\', '/')));
					Files.copy(file, zip);
					zip.closeEntry();
				}
			}
		}
		trimBackups(integer(config, "backupRetention", 5, 1, 30));
		status.put("lastBackup", target.getFileName().toString());
		log("Backup created: " + target);
	}

	private void trimBackups(int retention) throws IOException {
		Path backupDir = root.resolve("backups");
		try (Stream<Path> backups = Files.list(backupDir)) {
			List<Path> sorted = backups.filter(path -> path.getFileName().toString().endsWith(".zip"))
				.sorted(Comparator.comparingLong(HostDaemon::lastModified).reversed())
				.toList();
			for (int index = retention; index < sorted.size(); index++) {
				Files.deleteIfExists(sorted.get(index));
			}
		}
	}

	private void download(URI uri, Path target, String expectedHash) throws Exception {
		if (Files.isRegularFile(target) && (expectedHash.isBlank() || hash(target, expectedHash).equalsIgnoreCase(expectedHash))) {
			return;
		}
		Files.createDirectories(target.getParent());
		Path temporary = target.resolveSibling(target.getFileName() + ".download");
		Files.deleteIfExists(temporary);
		setState("PROVISIONING", "Downloading " + target.getFileName());
		writeStatus();
		try {
			HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();
			HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5)).GET().build();
			HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(temporary));
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				throw new IOException("Download returned HTTP " + response.statusCode());
			}
		} catch (Exception exception) {
			log("Java download failed, using Windows downloader: " + exception.getMessage());
			downloadWithPowerShell(uri, temporary);
		}
		if (!expectedHash.isBlank() && !hash(temporary, expectedHash).equalsIgnoreCase(expectedHash)) {
			Files.deleteIfExists(temporary);
			throw new IOException("Checksum verification failed for " + target.getFileName());
		}
		moveAtomic(temporary, target);
	}

	private static void downloadWithPowerShell(URI uri, Path target) throws IOException, InterruptedException {
		String url = uri.toString().replace("'", "''");
		String output = target.toAbsolutePath().toString().replace("'", "''");
		Process process = new ProcessBuilder(
			"powershell.exe",
			"-NoProfile",
			"-NonInteractive",
			"-Command",
			"Invoke-WebRequest -UseBasicParsing -Uri '" + url + "' -OutFile '" + output + "'"
		).redirectErrorStream(true).start();
		if (!process.waitFor(5, TimeUnit.MINUTES) || process.exitValue() != 0) {
			throw new IOException("Windows downloader could not retrieve " + uri);
		}
	}

	private static String hash(Path path, String expectedHash) throws Exception {
		String algorithm = expectedHash.length() == 64 ? "SHA-256" : "SHA-1";
		MessageDigest digest = MessageDigest.getInstance(algorithm);
		try (var input = Files.newInputStream(path)) {
			byte[] buffer = new byte[1024 * 128];
			int read;
			while ((read = input.read(buffer)) >= 0) {
				digest.update(buffer, 0, read);
			}
		}
		return java.util.HexFormat.of().formatHex(digest.digest());
	}

	private synchronized void setState(String state, String message) {
		status.put("state", state);
		status.put("message", message);
		if (!"ERROR".equals(state)) {
			status.put("lastError", "");
		}
	}

	private synchronized void setError(String message) {
		status.put("state", "ERROR");
		status.put("message", message);
		status.put("lastError", message);
		try {
			writeStatus();
		} catch (IOException ignored) {
		}
	}

	private synchronized void writeStatus() throws IOException {
		Properties properties = new Properties();
		properties.putAll(status);
		storeProperties(statusFile, properties, "EverHost live status");
	}

	private void writeStatusBestEffort() {
		try {
			writeStatus();
		} catch (IOException exception) {
			log("Status update deferred: " + exception.getMessage());
		}
	}

	private void log(String message) {
		synchronized (logLock) {
			try {
				Files.writeString(
					daemonLog,
					Instant.now() + " " + message + System.lineSeparator(),
					StandardCharsets.UTF_8,
					StandardOpenOption.CREATE,
					StandardOpenOption.APPEND
				);
			} catch (IOException ignored) {
			}
		}
	}

	private static void storeProperties(Path target, Properties properties, String comment) throws IOException {
		Files.createDirectories(target.toAbsolutePath().getParent());
		String temporaryName = target.getFileName() + "." + ProcessHandle.current().pid()
			+ "." + Thread.currentThread().getId() + "." + System.nanoTime() + ".tmp";
		Path temporary = target.resolveSibling(temporaryName);
		try {
			try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
				properties.store(writer, comment);
			}
			moveAtomic(temporary, target);
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private static void writeStringAtomic(Path target, String contents) throws IOException {
		Files.createDirectories(target.toAbsolutePath().getParent());
		String temporaryName = target.getFileName() + "." + ProcessHandle.current().pid()
			+ "." + Thread.currentThread().getId() + "." + System.nanoTime() + ".tmp";
		Path temporary = target.resolveSibling(temporaryName);
		try {
			Files.writeString(temporary, contents, StandardCharsets.UTF_8);
			moveAtomic(temporary, target);
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private static void moveAtomic(Path source, Path target) throws IOException {
		IOException failure = null;
		for (int attempt = 0; attempt < 24; attempt++) {
			try {
				try {
					Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				} catch (AtomicMoveNotSupportedException exception) {
					Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
				}
				return;
			} catch (IOException exception) {
				failure = exception;
				try {
					Thread.sleep(Math.min(100L, 10L * (attempt + 1L)));
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					throw new IOException("Interrupted while replacing " + target.getFileName(), interrupted);
				}
			}
		}
		throw failure;
	}

	private static Properties loadProperties(Path path) {
		Properties properties = new Properties();
		if (!Files.isRegularFile(path)) {
			return properties;
		}
		try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			properties.load(reader);
		} catch (IOException ignored) {
		}
		return properties;
	}

	private static int integer(Properties properties, String key, int fallback, int minimum, int maximum) {
		try {
			return Math.max(minimum, Math.min(maximum, Integer.parseInt(properties.getProperty(key, Integer.toString(fallback)))));
		} catch (NumberFormatException exception) {
			return fallback;
		}
	}

	private static boolean bool(Properties properties, String key, boolean fallback) {
		return Boolean.parseBoolean(properties.getProperty(key, Boolean.toString(fallback)));
	}

	private static String choice(Properties properties, String key, String fallback, String... choices) {
		String value = properties.getProperty(key, fallback).toLowerCase(Locale.ROOT);
		for (String choice : choices) {
			if (choice.equals(value)) {
				return value;
			}
		}
		return fallback;
	}

	private static long lastModified(Path path) {
		try {
			return Files.getLastModifiedTime(path).toMillis();
		} catch (IOException exception) {
			return 0L;
		}
	}

	private static String stripLogPrefix(String line) {
		int marker = line.indexOf("]: ");
		return marker >= 0 ? line.substring(marker + 3) : line;
	}

	private static Map<String, String> parseArgs(String[] args) {
		Map<String, String> result = new LinkedHashMap<>();
		for (int index = 0; index + 1 < args.length; index += 2) {
			if (args[index].startsWith("--")) {
				result.put(args[index].substring(2), args[index + 1]);
			}
		}
		return result;
	}

	private static String required(Map<String, String> values, String key) {
		String value = values.get(key);
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("Missing --" + key);
		}
		return value;
	}

	private record Library(String group, String artifact, String version, String sha1) {
		String relativePath() {
			return group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-" + version + ".jar";
		}

		URI uri() {
			return URI.create("https://maven.fabricmc.net/" + relativePath());
		}
	}

	private record PluginDeployment(BridgePlan bridge, ScanResult scan) {
	}
}
