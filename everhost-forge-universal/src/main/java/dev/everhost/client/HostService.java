package dev.everhost.client;

import com.mojang.logging.LogUtils;
import dev.everhost.plugins.PluginSupport;
import dev.everhost.plugins.PluginCatalog;
import dev.everhost.plugins.PluginCatalog.CatalogEntry;
import dev.everhost.plugins.PluginSupport.BridgePlan;
import dev.everhost.plugins.PluginSupport.ScanResult;
import dev.everhost.universal.Models.ModInfo;
import dev.everhost.universal.Models.Profile;
import dev.everhost.universal.ProfileScanner;
import dev.everhost.update.EverHostUpdater;
import java.io.BufferedWriter;
import java.io.IOException;
import java.awt.Desktop;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

public final class HostService {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final AtomicLong COMMAND_SEQUENCE = new AtomicLong();

	private final Path instance;
	private final Path root;
	private final Path configFile;
	private final Path statusFile;
	private Process daemonProcess;
	private long daemonLaunchTime;
	private String daemonFailure;
	private Path pendingStart;
	private final Path javaExecutable;
	private HostStatus status;
	private HostStatus.State previousState = HostStatus.State.OFFLINE;
	private List<Profile> profiles = List.of();
	private volatile boolean pluginTaskActive;
	private volatile String pluginTaskMessage = "";

	public HostService() {
		this.instance = LoaderBridge.gameDirectory();
		this.root = instance.resolve("everhost");
		this.configFile = root.resolve("config.properties");
		this.statusFile = root.resolve("status.properties");
		this.javaExecutable = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
		this.status = HostStatus.read(statusFile);
		refreshProfiles();
		startAutomaticUpdater();
	}

	private void startAutomaticUpdater() {
		try {
			Path installed = LoaderBridge.installedModPath();
			if (!Files.isRegularFile(installed)) return;
			EverHostUpdater.consumeResult(instance, installed).ifPresent(result ->
				Minecraft.getInstance().execute(() -> showNotification(result.title(), result.message())));
			EverHostUpdater.check(instance, installed, notice -> {
				if (notice.type() == EverHostUpdater.NoticeType.AVAILABLE) {
					Minecraft.getInstance().execute(() -> EverHostClient.offerUpdate(notice.candidate()));
				} else LOGGER.warn("EverHost automatic update check failed: {}", notice.message());
			});
		} catch (IOException exception) {
			LOGGER.debug("Automatic updates are unavailable in this launch", exception);
		}
	}

	public void installUpdate(EverHostUpdater.UpdateCandidate candidate, Consumer<EverHostUpdater.Notice> notices) {
		try {
			Path installed = LoaderBridge.installedModPath();
			EverHostUpdater.install(instance, javaExecutable, installed, candidate, notices);
		} catch (IOException exception) {
			notices.accept(new EverHostUpdater.Notice(EverHostUpdater.NoticeType.FAILED,
				exception.getMessage() == null ? exception.toString() : exception.getMessage(), candidate));
		}
	}

	public void shutdownForUpdate() {
		enqueue(Action.SHUTDOWN_DAEMON, new Properties());
	}

	public Path root() {
		return root;
	}

	public Path instance() {
		return instance;
	}

	public Path configFile() {
		return configFile;
	}

	public List<Profile> profiles() {
		return profiles;
	}

	public void refreshProfiles() {
		profiles = ProfileScanner.scan(ProfileScanner.defaultInstancesRoot());
	}

	public Optional<Profile> selectedProfile(EverHostConfig config) {
		Path selected = path(config.profilePath);
		if (selected != null) {
			for (Profile profile : profiles) {
				if (profile.path().equals(selected)) return Optional.of(profile);
			}
		}
		for (Profile profile : profiles) {
			if (profile.path().equals(instance)) return Optional.of(profile);
		}
		if (!profiles.isEmpty()) return Optional.of(profiles.get(0));
		return Optional.empty();
	}

	public Optional<Profile> currentProfile() {
		return profiles.stream().filter(profile -> profile.path().equals(instance)).findFirst();
	}

	public List<ModInfo> selectedMods(EverHostConfig config) {
		Optional<Profile> selected = selectedProfile(config);
		if (selected.isEmpty()) return List.of();
		for (ModInfo mod : selected.get().mods()) {
			EverHostConfig.ModMode mode = config.modModes.get(mod.path.getFileName().toString());
			if (mode != null) {
				mod.selected = mode != EverHostConfig.ModMode.OFF;
				mod.clientRequired = mode == EverHostConfig.ModMode.REQUIRED;
			}
		}
		return selected.get().mods();
	}

	public List<Path> selectedPlugins(EverHostConfig config) {
		Optional<Profile> selected = selectedProfile(config);
		if (selected.isEmpty()) return List.of();
		Path plugins = selected.get().path().resolve("plugins");
		if (!Files.isDirectory(plugins)) return List.of();
		try (var paths = Files.list(plugins)) {
			return paths.filter(Files::isRegularFile)
				.filter(file -> file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
				.sorted()
				.toList();
		} catch (IOException exception) {
			return List.of();
		}
	}

	public ScanResult selectedPluginScan(EverHostConfig config) {
		Optional<Profile> selected = selectedProfile(config);
		if (selected.isEmpty()) return new ScanResult(List.of());
		Set<String> disabled = config.pluginStates.entrySet().stream()
			.filter(entry -> entry.getValue() == EverHostConfig.PluginState.DISABLED)
			.map(java.util.Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
		List<Path> modJars = selectedMods(config).stream().filter(mod -> mod.selected).map(mod -> mod.path).toList();
		int maximumJava = "1.21.11".equals(selected.get().minecraftVersion())
			? 25 : ProfileScanner.requiredJava(selected.get().minecraftVersion());
		return PluginSupport.scan(selected.get().path().resolve("plugins"), selected.get().minecraftVersion(),
			disabled, maximumJava, modJars);
	}

	public Optional<BridgePlan> selectedPluginBridge(EverHostConfig config) {
		return selectedProfile(config).flatMap(PluginSupport::bridgeFor);
	}

	public void openPluginsFolder(EverHostConfig config) {
		Optional<Profile> selected = selectedProfile(config);
		if (selected.isEmpty()) {
			showToast("Plugins folder unavailable", "Choose a CurseForge profile first.");
			return;
		}
		Path directory = selected.get().path().resolve("plugins");
		try {
			Files.createDirectories(directory);
			if (!Desktop.isDesktopSupported()) throw new IOException("Desktop integration is unavailable.");
			Desktop.getDesktop().open(directory.toFile());
		} catch (IOException exception) {
			LOGGER.warn("Could not open plugins folder {}", directory, exception);
			showToast("Could not open plugins folder", directory.toString());
		}
	}

	public void openPluginDataFolder() {
		openDirectory(root.resolve("runtime").resolve("plugins"), "Could not open plugin data");
	}

	private static void openDirectory(Path directory, String errorTitle) {
		try {
			Files.createDirectories(directory);
			if (!Desktop.isDesktopSupported()) throw new IOException("Desktop integration is unavailable.");
			Desktop.getDesktop().open(directory.toFile());
		} catch (IOException exception) {
			LOGGER.warn("Could not open directory {}", directory, exception);
			showToast(errorTitle, directory.toString());
		}
	}

	public List<CatalogEntry> pluginCatalog(EverHostConfig config) {
		return selectedProfile(config).map(profile -> PluginCatalog.entries(profile.minecraftVersion())).orElse(List.of());
	}

	public boolean pluginTaskActive() {
		return pluginTaskActive;
	}

	public String pluginTaskMessage() {
		return pluginTaskMessage;
	}

	public void installPlugins(EverHostConfig config, List<CatalogEntry> entries, Runnable completed) {
		Optional<Profile> selected = selectedProfile(config);
		if (selected.isEmpty() || entries.isEmpty() || pluginTaskActive) return;
		Profile profile = selected.get();
		pluginTaskActive = true;
		pluginTaskMessage = "Preparing " + (entries.size() == 1 ? entries.get(0).displayName() : entries.size() + " plugins") + "...";
		CompletableFuture.runAsync(() -> {
			List<String> installed = new ArrayList<>();
			try {
				for (CatalogEntry entry : entries) {
					pluginTaskMessage = "Installing " + entry.displayName() + "...";
					PluginCatalog.install(profile.path().resolve("plugins"),
						profile.path().resolve("everhost").resolve("plugin-archive"), profile.minecraftVersion(), entry);
					installed.add(entry.displayName());
				}
				Minecraft.getInstance().execute(() -> showToast("Plugins installed",
					String.join(", ", installed) + ". Restart the server to load them."));
			} catch (Exception exception) {
				LOGGER.error("Could not install EverHost plugin", exception);
				Minecraft.getInstance().execute(() -> showToast("Plugin install failed",
					exception.getMessage() == null ? exception.toString() : exception.getMessage()));
			} finally {
				pluginTaskActive = false;
				pluginTaskMessage = "";
				Minecraft.getInstance().execute(completed);
			}
		});
	}

	public HostStatus status() {
		status = HostStatus.read(statusFile);
		boolean responsive = daemonResponsive();
		if (responsive) {
			daemonFailure = null;
		} else if (daemonProcess != null) {
			if (!daemonProcess.isAlive()) {
				failDaemon("Background host exited (code " + daemonProcess.exitValue() + "). See the Console tab for details.");
				daemonProcess = null;
			} else if (System.currentTimeMillis() - daemonLaunchTime > 15000L) {
				failDaemon("Background host did not become ready. See the Console tab for details.");
			}
		}
		if (daemonFailure != null) return status.withState(HostStatus.State.ERROR, daemonFailure);
		if (!responsive && daemonProcess != null) {
			return status.withState(HostStatus.State.STARTING, "Starting the background host...");
		}
		if (pendingStart != null) {
			if (!Files.exists(pendingStart)) pendingStart = null;
			else if (!status.state().isBusyOrOnline()) {
				return status.withState(HostStatus.State.STARTING, "Start requested; preparing the server...");
			}
		}
		return status;
	}

	private boolean daemonResponsive() {
		Properties values = new Properties();
		try (var reader = Files.newBufferedReader(statusFile, StandardCharsets.UTF_8)) {
			values.load(reader);
			return HostStatus.daemonAlive(values);
		} catch (IOException exception) {
			return false;
		}
	}

	private void failDaemon(String message) {
		if (!message.equals(daemonFailure)) showToast("EverHost could not start", message);
		daemonFailure = message;
		if (pendingStart != null) {
			try { Files.deleteIfExists(pendingStart); }
			catch (IOException exception) { LOGGER.warn("Could not withdraw failed start request", exception); }
			pendingStart = null;
		}
	}

	public void tick(EverHostConfig config) {
		HostStatus current = status();
		if (current.state() != previousState) {
			if (current.state() == HostStatus.State.ONLINE && previousState != HostStatus.State.ONLINE) {
				String address = shareAddress(config);
				notify(config, "EverHost is online", address.isBlank() ? current.message() : address);
			} else if (current.state() == HostStatus.State.ERROR) {
				notify(config, "EverHost needs attention", current.message());
			} else if (current.state() == HostStatus.State.OFFLINE && previousState == HostStatus.State.STOPPING) {
				notify(config, "EverHost stopped", "The world was saved safely.");
			}
			previousState = current.state();
		}
	}

	public boolean ensureDaemon() {
		if (daemonResponsive()) return true;
		if (daemonProcess != null && daemonProcess.isAlive()) return daemonFailure == null;
		daemonFailure = null;
		try {
			String daemonClasspath = findDaemonClasspath();
			Files.createDirectories(root.resolve("commands"));
			Files.createDirectories(root.resolve("logs"));
			Path launcher = isWindows()
				? javaExecutable.resolveSibling("javaw.exe")
				: javaExecutable;
			List<String> command = List.of(
				launcher.toString(),
				"-Djavax.net.ssl.trustStoreType=Windows-ROOT",
				"-Djavax.net.ssl.trustStore=NONE",
				"-cp",
				daemonClasspath,
				"dev.everhost.daemon.HostDaemon",
				"--root",
				root.toString(),
				"--instance",
				instance.toString(),
				"--java",
				javaExecutable.toString(),
				"--boot",
				"client"
			);
			LOGGER.info("Launching EverHost background host from {}", daemonClasspath);
			daemonLaunchTime = System.currentTimeMillis();
			daemonProcess = new ProcessBuilder(command)
				.directory(instance.toFile())
				.redirectErrorStream(true)
				.redirectOutput(ProcessBuilder.Redirect.appendTo(root.resolve("logs").resolve("daemon-bootstrap.log").toFile()))
				.start();
			return true;
		} catch (IOException | RuntimeException exception) {
			LOGGER.error("Could not start the EverHost daemon", exception);
			failDaemon(exception.getMessage() == null ? exception.toString() : exception.getMessage());
			return false;
		}
	}

	public void send(Action action) {
		enqueue(action, new Properties());
	}

	public boolean setupPlayit(String serverLabel, int port) {
		EverHostConfig config = EverHostConfig.load(configFile);
		return setupPlayit(serverLabel, config.profilePath, port);
	}

	public boolean setupPlayit(String serverLabel, String profileKey, int port) {
		Properties values = new Properties();
		values.setProperty("serverLabel", serverLabel == null ? "" : serverLabel);
		values.setProperty("profileKey", profileKey == null ? "" : profileKey);
		values.setProperty("port", Integer.toString(port));
		return enqueue(Action.PLAYIT_SETUP, values);
	}

	public boolean setPlayitEnabled(boolean enabled, int port) {
		Properties values = new Properties();
		values.setProperty("enabled", Boolean.toString(enabled));
		values.setProperty("port", Integer.toString(port));
		return enqueue(enabled ? Action.PLAYIT_START : Action.PLAYIT_STOP, values);
	}

	public boolean refreshPlayit() {
		return enqueue(Action.PLAYIT_REFRESH, new Properties());
	}

	public boolean sendConsoleCommand(String value) {
		String command = value == null ? "" : value.strip();
		if (command.startsWith("/")) {
			command = command.substring(1).stripLeading();
		}
		if (command.isBlank()) {
			showToast("Command not sent", "Enter a server command first.");
			return false;
		}
		if (command.length() > 2048 || command.chars().anyMatch(character -> character == '\r' || character == '\n' || character == 0)) {
			showToast("Command not sent", "Commands must be one line and no longer than 2048 characters.");
			return false;
		}
		if (status().state() != HostStatus.State.ONLINE) {
			showToast("Server console is offline", "Start the EverHost server before sending commands.");
			return false;
		}
		Properties values = new Properties();
		values.setProperty("command", command);
		return enqueue(Action.CONSOLE, values);
	}

	private boolean enqueue(Action action, Properties values) {
		status();
		if (action == Action.START && pendingStart != null && Files.exists(pendingStart)) return false;
		if (!ensureDaemon()) return false;
		Path temporary = null;
		try {
			Files.createDirectories(root.resolve("commands"));
			String name = String.format(
				Locale.ROOT,
				"%013d-%06d.properties",
				System.currentTimeMillis(),
				COMMAND_SEQUENCE.incrementAndGet() % 1_000_000L
			);
			Path target = root.resolve("commands").resolve(name);
			temporary = target.resolveSibling(target.getFileName() + ".tmp");
			Properties command = new Properties();
			command.putAll(values);
			command.setProperty("action", action.name());
			command.setProperty("createdAt", Instant.now().toString());
			try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
				command.store(writer, "EverHost command");
			}
			moveAtomic(temporary, target);
			if (action == Action.START || action == Action.RESTART) pendingStart = target;
			return true;
		} catch (IOException exception) {
			LOGGER.error("Could not send EverHost command {}", action, exception);
			showToast("EverHost command failed", exception.getMessage());
			return false;
		} finally {
			if (temporary != null) {
				try {
					Files.deleteIfExists(temporary);
				} catch (IOException ignored) {
				}
			}
		}
	}

	public void joinLocal(Screen parent) {
		HostStatus current = status();
		String address = "127.0.0.1:" + current.port();
		Minecraft.getInstance().keyboardHandler.setClipboard(address);
		showToast("Local address copied", address);
	}

	public String shareAddress() {
		return shareAddress(EverHostConfig.load(configFile));
	}

	public String shareAddress(EverHostConfig config) {
		return dev.everhost.address.AddressClient.shareAddress(root, status().domain(), config.addressMode.name());
	}

	public void copyAddress() {
		String address = shareAddress();
		if (!address.isBlank()) {
			Minecraft.getInstance().keyboardHandler.setClipboard(address);
			showToast("Address copied", address);
		}
	}

	public void openLogs() {
		Path log = root.resolve("logs").resolve("server-console.log");
		try {
			Files.createDirectories(log.getParent());
			if (!Files.exists(log)) {
				Files.writeString(log, "EverHost has not started a server yet.\n", StandardCharsets.UTF_8);
			}
			if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(log.toFile());
		} catch (IOException exception) {
			showToast("Could not open logs", exception.getMessage());
		}
	}

	public List<WorldInfo> worlds() {
		Path saves = instance.resolve("saves");
		if (!Files.isDirectory(saves)) {
			return List.of();
		}
		try (var paths = Files.list(saves)) {
			return paths.filter(Files::isDirectory)
				.filter(path -> Files.isRegularFile(path.resolve("level.dat")))
				.map(path -> new WorldInfo(path.getFileName().toString(), path.getFileName().toString(), lastModified(path.resolve("level.dat"))))
				.sorted(Comparator.comparingLong(WorldInfo::lastPlayed).reversed())
				.toList();
		} catch (IOException exception) {
			return List.of();
		}
	}

	public String recentLog(int maximumLines) {
		return readTail(root.resolve("logs").resolve("server-console.log"), maximumLines, "No historical server output is available.");
	}

	public String currentConsole(int maximumLines) {
		if (daemonFailure != null) {
			return daemonFailure + "\n" + readTail(root.resolve("logs").resolve("daemon-bootstrap.log"),
				maximumLines, "No background host output was written.") + "\n"
				+ readTail(root.resolve("logs").resolve("daemon-fatal.log"), maximumLines, "");
		}
		return readTail(root.resolve("console-current.log"), maximumLines, "Server offline");
	}

	private static String readTail(Path path, int maximumLines, String fallback) {
		if (!Files.isRegularFile(path)) {
			return fallback;
		}
		try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
			long size = channel.size();
			if (size == 0L) {
				return fallback;
			}
			int byteCount = (int)Math.min(size, 256L * 1024L);
			ByteBuffer buffer = ByteBuffer.allocate(byteCount);
			channel.position(size - byteCount);
			while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
			}
			buffer.flip();
			String text = StandardCharsets.UTF_8.decode(buffer).toString();
			String[] lines = text.split("\\R");
			int first = Math.max(0, lines.length - Math.max(1, maximumLines));
			return String.join("\n", java.util.Arrays.copyOfRange(lines, first, lines.length));
		} catch (IOException exception) {
			return "Could not read the live server console: " + exception.getMessage();
		}
	}

	public void configureWindowsStartup(boolean enabled) {
		if (!isWindows()) {
			return;
		}
		try {
			Path script = root.resolve("start-everhost.cmd");
			String daemonClasspath = findDaemonClasspath();
			String javaw = javaExecutable.resolveSibling("javaw.exe").toString();
			String body = "@echo off\r\nstart \"\" /B \"" + javaw + "\" "
				+ "-Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE "
				+ "-cp \"" + daemonClasspath + "\" dev.everhost.daemon.HostDaemon "
				+ "--root \"" + root + "\" --instance \"" + instance + "\" --java \"" + javaExecutable + "\" --boot windows\r\n";
			Files.createDirectories(root);
			Files.writeString(script, body, StandardCharsets.US_ASCII);
			String key = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
			ProcessBuilder registry = enabled
				? new ProcessBuilder("reg.exe", "ADD", key, "/v", "EverHost", "/t", "REG_SZ", "/d", "\"" + script + "\"", "/f")
				: new ProcessBuilder("reg.exe", "DELETE", key, "/v", "EverHost", "/f");
			registry.redirectErrorStream(true).start();
		} catch (IOException exception) {
			LOGGER.warn("Could not update EverHost Windows startup", exception);
			showToast("Windows startup was not changed", exception.getMessage());
		}
	}

	private void notify(EverHostConfig config, String title, String message) {
		if (config.essentialNotifications && pushEssential(title, message)) {
			return;
		}
		showToast(title, message);
	}

	public static void showNotification(String title, String message) {
		if (!pushEssential(title, message)) showToast(title, message);
	}

	private static boolean pushEssential(String title, String message) {
		try {
			Class<?> api = Class.forName("gg.essential.api.EssentialAPI");
			Object notifications = api.getMethod("getNotifications").invoke(null);
			Method push = notifications.getClass().getMethod("push", String.class, String.class);
			push.invoke(notifications, title, message);
			return true;
		} catch (ReflectiveOperationException | LinkageError exception) {
			return false;
		}
	}

	public static void showToast(String title, String message) {
		LOGGER.info("{}: {}", title, message == null ? "" : message);
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.execute(() -> SystemToast.add(minecraft.getToasts(), SystemToast.SystemToastIds.WORLD_ACCESS_FAILURE,
			Component.literal(title), Component.literal(message == null ? "" : message)));
	}

	private static String findDaemonClasspath() throws IOException {
		return LoaderBridge.codeClasspath();
	}

	private static void moveAtomic(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException exception) {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static long lastModified(Path path) {
		try {
			return Files.getLastModifiedTime(path).toMillis();
		} catch (IOException exception) {
			return 0L;
		}
	}

	private static Path path(String value) {
		if (value == null || value.isBlank()) return null;
		try {
			return Path.of(value).toAbsolutePath().normalize();
		} catch (RuntimeException exception) {
			return null;
		}
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}

	public enum Action {
		START, STOP, RESTART, BACKUP, CONSOLE, PLAYIT_SETUP, PLAYIT_START, PLAYIT_STOP, PLAYIT_REFRESH, SHUTDOWN_DAEMON
	}
	public record WorldInfo(String id, String name, long lastPlayed) {}
}
