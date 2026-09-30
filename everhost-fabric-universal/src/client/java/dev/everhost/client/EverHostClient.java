package dev.everhost.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.everhost.EverHostMod;
import dev.everhost.install.ModInstallCoordinator;
import dev.everhost.universal.RequiredModsManifest.Requirement;
import dev.everhost.update.EverHostUpdater;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class EverHostClient implements ClientModInitializer {
	public static final String MOD_ID = "everhost";
	private static final int DIRECT_SOURCE_MAGIC = 0x45484D33;
	private static HostService service;
	private static EverHostConfig config;
	private static KeyMapping dashboardKey;
	private static ModInstallCoordinator.Result installerResult;
	private static EverHostUpdater.UpdateCandidate pendingUpdate;
	private static boolean automaticUpdatePromptShown;

	@Override
	public void onInitializeClient() {
		service = new HostService();
		config = EverHostConfig.load(service.configFile());
		dashboardKey = KeyBindingHelper.registerKeyBinding(FabricClientCompat.createDashboardKey());
		ClientTickEvents.END_CLIENT_TICK.register(EverHostClient::tick);
		ClientLoginNetworking.registerGlobalReceiver(EverHostMod.requirementsChannel(), (client, handler, request, callbacks) -> {
			var response = PacketByteBufs.create();
			var containers = FabricLoader.getInstance().getAllMods();
			List<Requirement> requirements = readRequirements(request);
			var mods = containers.stream()
				.map(container -> container.getMetadata().getId())
				.distinct()
				.sorted(Comparator.naturalOrder())
				.toList();
			response.writeVarInt(mods.size());
			for (String modId : mods) response.writeUtf(modId, 256);
			Set<String> relevantIds = new LinkedHashSet<>();
			for (Requirement requirement : requirements) relevantIds.addAll(requirement.modIds());
			Set<Path> loadedPaths = new LinkedHashSet<>();
			for (var container : containers) {
				if (relevantIds.contains(container.getMetadata().getId())) loadedPaths.addAll(container.getOrigin().getPaths());
			}
			Set<String> hashes = ModInstallCoordinator.hashes(loadedPaths);
			ModInstallCoordinator.assess(requirements, Set.copyOf(mods), hashes);
			response.writeVarInt(hashes.size());
			for (String hash : hashes) response.writeUtf(hash, 64);
			return CompletableFuture.completedFuture(response);
		});
		installerResult = ModInstallCoordinator.consumeResult(LoaderBridge.gameDirectory()).orElse(null);
		service.ensureDaemon();
	}

	private static void tick(Minecraft minecraft) {
		if (installerResult != null) {
			ModInstallCoordinator.Result result = installerResult;
			installerResult = null;
			HostService.showNotification(result.title(), result.message());
		}
		showPendingUpdatePrompt();
		while (dashboardKey.consumeClick()) {
			if (minecraft.level != null) minecraft.setScreen(createDashboard(minecraft.screen));
		}
		service.tick(config);
	}

	public static Screen createDashboard(Screen parent) {
		return new EverHostDashboardScreen(parent, service, config);
	}

	public static void openDashboard(Screen parent, String worldId, String worldName) {
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
		boolean startupChanged = config != null && config.startWithWindows != updated.startWithWindows;
		config = updated.copy();
		config.save(service.configFile());
		if (startupChanged) {
			service.configureWindowsStartup(config.startWithWindows);
		}
	}

	public static Component keyCategoryName() {
		return Component.translatable("key.category.everhost");
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

	private static List<Requirement> readRequirements(net.minecraft.network.FriendlyByteBuf input) {
		if (input.readableBytes() == 0) return List.of();
		int count = input.readVarInt();
		if (count < 0 || count > 8192) throw new IllegalArgumentException("invalid requirement count");
		List<Requirement> result = new ArrayList<>();
		for (int index = 0; index < count; index++) {
			String name = input.readUtf(512);
			int idCount = input.readVarInt();
			if (idCount < 1 || idCount > 128) throw new IllegalArgumentException("invalid mod id count");
			Set<String> ids = new LinkedHashSet<>();
			for (int idIndex = 0; idIndex < idCount; idIndex++) ids.add(input.readUtf(256));
			result.add(new Requirement(name, ids, input.readUtf(512), input.readUtf(512), input.readVarLong(),
				input.readVarLong(), input.readUtf(4096), input.readUtf(64), input.readUtf(40), input.readVarLong()));
		}
		if (input.readableBytes() >= 5 && input.readInt() == DIRECT_SOURCE_MAGIC) {
			int sourceCount = input.readVarInt();
			if (sourceCount != result.size()) throw new IllegalArgumentException("invalid direct source count");
			List<Requirement> withSources = new ArrayList<>();
			for (int index = 0; index < sourceCount; index++) {
				Requirement base = result.get(index);
				withSources.add(new Requirement(base.name(), base.modIds(), base.version(), base.fileName(),
					base.curseProjectId(), base.curseFileId(), base.downloadUrl(), base.sha256(), base.curseSha1(),
					base.fileSize(), input.readUtf(4096), input.readUtf(64)));
			}
			result = withSources;
		}
		return List.copyOf(result);
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
	}
}
