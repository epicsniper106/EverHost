package dev.everhost.client.mixin;

import dev.everhost.client.EverHostClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SelectWorldScreen.class)
public abstract class SelectWorldScreenMixin {
	@Inject(method = "init", at = @At("TAIL"))
	private void everhost$addDashboardButton(CallbackInfo callbackInfo) {
		SelectWorldScreen screen = (SelectWorldScreen)(Object)this;
		int width = Minecraft.getInstance().getWindow().getGuiScaledWidth();
		Button button = Button.builder(Component.literal("EverHost"), ignored -> {
			EverHostClient.openDashboard(screen, null, null);
		}).bounds(width - 116, 6, 108, 20).build();
		((ScreenInvoker)(Object)screen).everhost$addRenderableWidget(button);
	}
}
