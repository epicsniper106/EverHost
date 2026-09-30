package dev.everhost.client.mixin;

import dev.everhost.client.EverHostClient;
import dev.everhost.install.ModInstallCoordinator;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DisconnectedScreen.class)
public abstract class DisconnectedScreenMixin {
	@Inject(method = "init", at = @At("TAIL"))
	private void everhost$addAutomaticInstaller(CallbackInfo callbackInfo) {
		if (!ModInstallCoordinator.hasPendingOffer()) return;
		Screen screen = (Screen)(Object)this;
		String label = ModInstallCoordinator.pendingCount() == 1
			? "Install Required Mod" : "Install Required Mods";
		Button button = Button.builder(Component.literal(label), ignored -> EverHostClient.installRequiredMods())
			.bounds(screen.width / 2 - 100, screen.height - 52, 200, 20).build();
		((ScreenInvoker)(Object)screen).everhost$addRenderableWidget(button);
	}
}
