package dev.everhost.client;

import dev.everhost.install.ModInstallCoordinator;
import dev.everhost.update.EverHostUpdater;
import java.nio.file.Path;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;

public final class EverHostClient {
	public static final String MOD_ID = "everhost";
	private static HostService service;
	private static EverHostConfig config;
	private static ModInstallCoordinator.Result installerResult;
	private static EverHostUpdater.UpdateCandidate pendingUpdate;
	private static boolean automaticUpdatePromptShown;

	private EverHostClient() {}

	public static synchronized void bootstrap() {
		if (service != null) return;
		service = new HostService();
		config = EverHostConfig.load(service.configFile());
		installerResult = ModInstallCoordinator.consumeResult(LoaderBridge.gameDirectory()).orElse(null);
		service.ensureDaemon();
	}

	public static Screen createDashboard(Screen parent) {
		bootstrap();
		Screen dashboard = new EverHostDashboardScreen(parent, service, config);
		return config.tutorialCompleted || !System.getProperty("everhost.test.page", "").isBlank()
			? dashboard : new EverHostTutorialScreen(dashboard, service, config.copy(), true);
	}

	public static void openDashboard(Screen parent, String worldId, String worldName) {
		bootstrap();
		if (worldId != null && !worldId.isBlank()) {
			service.currentProfile().ifPresent(profile -> {
				config.profilePath = profile.path().toString();
				config.profileName = profile.name();
				config.minecraftVersion = profile.minecraftVersion();
				config.loader = profile.loader().name().toLowerCase(java.util.Locale.ROOT);
				config.loaderVersion = profile.loaderVersion();
				config.modModes.clear();
			});
			config.worldId = worldId;
			config.worldName = worldName == null || worldName.isBlank() ? worldId : worldName;
			saveConfig(config);
		}
		Minecraft.getInstance().setScreen(createDashboard(parent));
	}

	public static void saveConfig(EverHostConfig updated) {
		bootstrap();
		boolean startupChanged = config.startWithWindows != updated.startWithWindows;
		config = updated.copy();
		config.save(service.configFile());
		if (startupChanged) service.configureWindowsStartup(config.startWithWindows);
	}

	public static void showPendingInstallerResult() {
		if (installerResult == null) return;
		ModInstallCoordinator.Result result = installerResult;
		installerResult = null;
		HostService.showNotification(result.title(), result.message());
	}

	public static void installRequiredMods() {
		try {
			Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
			ModInstallCoordinator.launch(LoaderBridge.gameDirectory(), javaExecutable, LoaderBridge.installedModPath());
			Minecraft.getInstance().stop();
		} catch (Exception exception) {
			HostService.showNotification("Automatic mod installation failed",
				exception.getMessage() == null ? exception.toString() : exception.getMessage());
		}
	}

	public static void offerUpdate(EverHostUpdater.UpdateCandidate candidate) {
		if (candidate == null) return;
		bootstrap();
		if (pendingUpdate == null || !pendingUpdate.version().equals(candidate.version())) {
			automaticUpdatePromptShown = false;
		}
		pendingUpdate = candidate;
		HostService.showNotification("EverHost update available",
			"EverHost " + candidate.version() + " is ready. Restart Minecraft through the update screen to install it.");
	}

	public static void showPendingUpdatePrompt() {
		Minecraft minecraft = Minecraft.getInstance();
		if (pendingUpdate == null || automaticUpdatePromptShown || minecraft.screen instanceof EverHostUpdateScreen) return;
		if (minecraft.screen instanceof TitleScreen) {
			automaticUpdatePromptShown = true;
			minecraft.setScreen(new EverHostUpdateScreen(minecraft.screen, pendingUpdate));
		}
	}

	public static boolean hasPendingUpdate() {
		return pendingUpdate != null;
	}

	public static String pendingUpdateVersion() {
		return pendingUpdate == null ? "" : pendingUpdate.version();
	}

	public static void openPendingUpdate(Screen parent) {
		if (pendingUpdate != null) Minecraft.getInstance().setScreen(new EverHostUpdateScreen(parent, pendingUpdate));
	}

	public static void dismissUpdatePrompt(EverHostUpdater.UpdateCandidate candidate) {
		if (candidate != null && candidate.equals(pendingUpdate)) automaticUpdatePromptShown = true;
	}

	public static void installUpdate(EverHostUpdater.UpdateCandidate candidate, Consumer<String> status) {
		bootstrap();
		service.installUpdate(candidate, notice -> Minecraft.getInstance().execute(() -> {
			if (notice.type() == EverHostUpdater.NoticeType.READY) {
				status.accept("Verified. Closing Minecraft and installing EverHost " + candidate.version() + "...");
				service.shutdownForUpdate();
				HostService.showNotification("Installing EverHost update",
					"Minecraft will close. CurseForge will reopen after the verified update is installed.");
				Minecraft.getInstance().stop();
			} else {
				String message = "Update failed: " + notice.message();
				status.accept(message);
				HostService.showNotification("EverHost update failed", notice.message());
			}
		}));
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
	}
}
