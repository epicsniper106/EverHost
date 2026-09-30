package dev.everhost.client.mixin;

import dev.everhost.client.EverHostClient;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin {
	@Inject(method = "init", at = @At("TAIL"))
	private void everhost$addDashboardButton(CallbackInfo callbackInfo) {
		Screen screen = (Screen)(Object)this;
		Button button = Button.builder(Component.literal("EverHost"), ignored ->
			EverHostClient.openDashboard(screen, null, null)
		).bounds(screen.width - 116, 6, 108, 20).build();
		((ScreenInvoker)(Object)screen).everhost$addRenderableWidget(button);
	}
}
