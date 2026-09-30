package dev.everhost.client;

import dev.everhost.install.ModInstallCoordinator;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = EverHostClient.MOD_ID, value = Dist.CLIENT)
public final class ForgeMenuEvents {
	private static TitleScreen titleScreen;
	private static Button serverButton;
	private static boolean testDashboardOpened;
	private static final KeyMapping OPEN_DASHBOARD = new KeyMapping(
		"key.everhost.dashboard",
		InputConstants.Type.KEYSYM,
		GLFW.GLFW_KEY_H,
		"key.categories.everhost.controls"
	);

	private ForgeMenuEvents() {}

	@SubscribeEvent(priority = EventPriority.LOWEST)
	public static void onScreenInit(ScreenEvent.Init.Post event) {
		Screen screen = event.getScreen();
		if (screen instanceof TitleScreen title) {
			titleScreen = title;
			serverButton = null;
			addTitleButton(event);
		} else if (screen instanceof SelectWorldScreen || screen instanceof PauseScreen) {
			event.addListener(Button.builder(Component.literal("EverHost"), ignored ->
				EverHostClient.openDashboard(screen, null, null)
			).bounds(screen.width - 116, 6, 108, 20).build());
		} else if (screen instanceof DisconnectedScreen && ModInstallCoordinator.hasPendingOffer()) {
			String label = ModInstallCoordinator.pendingCount() == 1
				? "Install Required Mod" : "Install Required Mods";
			event.addListener(Button.builder(Component.literal(label), ignored ->
				EverHostClient.installRequiredMods()
			).bounds(screen.width / 2 - 100, screen.height - 52, 200, 20).build());
		}
	}

	@SubscribeEvent
	public static void onClientTick(TickEvent.ClientTickEvent event) {
		if (event.phase != TickEvent.Phase.END) return;
		Minecraft minecraft = Minecraft.getInstance();
		EverHostClient.showPendingInstallerResult();
		EverHostClient.showPendingUpdatePrompt();
		if (!testDashboardOpened && (minecraft.screen instanceof TitleScreen || minecraft.screen instanceof DisconnectedScreen)
			&& !System.getProperty("everhost.test.page", "").isBlank()) {
			testDashboardOpened = true;
			minecraft.setScreen(EverHostClient.createDashboard(minecraft.screen));
		}
		while (OPEN_DASHBOARD.consumeClick()) {
			if (minecraft.level != null) minecraft.setScreen(EverHostClient.createDashboard(minecraft.screen));
		}
	}

	private static void addTitleButton(ScreenEvent.Init.Post event) {
		String multiplayerText = Component.translatable("menu.multiplayer").getString();
		AbstractWidget multiplayer = null;
		for (GuiEventListener child : event.getListenersList()) {
			if (child instanceof AbstractWidget widget) {
				if ("Server".equals(widget.getMessage().getString())) return;
				if (multiplayerText.equals(widget.getMessage().getString())) multiplayer = widget;
			}
		}
		if (multiplayer == null) return;

		Screen screen = event.getScreen();
		int serverY = multiplayer.getY() + 24;
		int menuLeft = multiplayer.getX();
		int menuRight = multiplayer.getX() + multiplayer.getWidth();
		for (GuiEventListener child : event.getListenersList()) {
			if (!(child instanceof AbstractWidget widget) || widget == multiplayer) continue;
			boolean centralRow = widget.getX() < menuRight && widget.getX() + widget.getWidth() > menuLeft;
			boolean aboveFooter = widget.getY() < screen.height - 32;
			if (centralRow && aboveFooter && widget.getY() >= serverY) widget.setY(widget.getY() + 24);
		}

		serverButton = Button.builder(Component.literal("Server"), ignored ->
			EverHostClient.openDashboard(screen, null, null)
		).bounds(multiplayer.getX(), serverY, multiplayer.getWidth(), multiplayer.getHeight()).build();
		event.addListener(serverButton);
	}

	@SubscribeEvent(priority = EventPriority.LOWEST)
	public static void onScreenRender(ScreenEvent.Render.Pre event) {
		if (event.getScreen() != titleScreen || serverButton == null) return;
		serverButton.setMessage(Component.literal(EverHostClient.hasPendingUpdate()
			? "UPDATE: EverHost " + EverHostClient.pendingUpdateVersion()
			: "Server"));
		// Some menu mods reposition Multiplayer after initialization.
		String multiplayerText = Component.translatable("menu.multiplayer").getString();
		for (GuiEventListener child : titleScreen.children()) {
			if (child instanceof AbstractWidget widget && multiplayerText.equals(widget.getMessage().getString())) {
				serverButton.setX(widget.getX());
				serverButton.setY(widget.getY() + 24);
				return;
			}
		}
	}

	@Mod.EventBusSubscriber(modid = EverHostClient.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
	public static final class ModBusEvents {
		private ModBusEvents() {}

		@SubscribeEvent
		public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
			event.register(OPEN_DASHBOARD);
		}
	}
}
