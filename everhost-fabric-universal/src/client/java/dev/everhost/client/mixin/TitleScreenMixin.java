package dev.everhost.client.mixin;

import dev.everhost.client.EverHostClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin {
	@Unique private Button everhost$serverButton;
	@Unique private boolean everhost$testDashboardOpened;

	@Inject(method = "init", at = @At("TAIL"))
	private void everhost$addServerButton(CallbackInfo callbackInfo) {
		Screen screen = (Screen)(Object)this;
		String multiplayerText = Component.translatable("menu.multiplayer").getString();
		String serverText = "Server";
		AbstractWidget multiplayer = null;
		for (GuiEventListener child : screen.children()) {
			if (child instanceof AbstractWidget widget) {
				if (serverText.equals(widget.getMessage().getString())) return;
				if (multiplayerText.equals(widget.getMessage().getString())) multiplayer = widget;
			}
		}
		if (multiplayer == null) return;

		int serverY = multiplayer.getY() + 24;
		int menuLeft = multiplayer.getX();
		int menuRight = multiplayer.getX() + multiplayer.getWidth();
		for (GuiEventListener child : screen.children()) {
			if (!(child instanceof AbstractWidget widget) || widget == multiplayer) continue;
			boolean centralRow = widget.getX() < menuRight && widget.getX() + widget.getWidth() > menuLeft;
			boolean aboveFooter = widget.getY() < screen.height - 32;
			if (centralRow && aboveFooter && widget.getY() >= serverY) widget.setY(widget.getY() + 24);
		}

		everhost$serverButton = Button.builder(Component.literal("Server"), ignored ->
			EverHostClient.openDashboard(screen, null, null)
		).bounds(multiplayer.getX(), serverY, multiplayer.getWidth(), multiplayer.getHeight()).build();
		((ScreenInvoker)(Object)screen).everhost$addRenderableWidget(everhost$serverButton);
	}

	@Inject(method = "render", at = @At("HEAD"))
	private void everhost$keepServerBelowMultiplayer(GuiGraphics graphics, int mouseX, int mouseY, float delta, CallbackInfo callbackInfo) {
		if (!everhost$testDashboardOpened && Boolean.getBoolean("everhost.test.openDashboard")) {
			everhost$testDashboardOpened = true;
			System.getLogger("EverHost").log(System.Logger.Level.INFO, "EverHost verification dashboard opened");
			Minecraft.getInstance().setScreen(EverHostClient.createDashboard((Screen)(Object)this));
			return;
		}
		if (everhost$serverButton == null) return;
		everhost$serverButton.setMessage(Component.literal(EverHostClient.hasPendingUpdate()
			? "UPDATE: EverHost " + EverHostClient.pendingUpdateVersion()
			: "Server"));
		Screen screen = (Screen)(Object)this;
		String multiplayerText = Component.translatable("menu.multiplayer").getString();
		for (GuiEventListener child : screen.children()) {
			if (child instanceof AbstractWidget widget && multiplayerText.equals(widget.getMessage().getString())) {
				everhost$serverButton.setX(widget.getX());
				everhost$serverButton.setY(widget.getY() + 24);
				return;
			}
		}
	}
}
