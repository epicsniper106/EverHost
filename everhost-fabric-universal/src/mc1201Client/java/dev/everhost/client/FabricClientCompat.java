package dev.everhost.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

final class FabricClientCompat {
	private FabricClientCompat() {}

	static KeyMapping createDashboardKey() {
		return new KeyMapping("key.everhost.dashboard", InputConstants.Type.KEYSYM, InputConstants.KEY_H,
			"key.categories.everhost.controls");
	}

	static void showToast(Minecraft minecraft, Component title, Component message) {
		SystemToast.add(minecraft.getToasts(), SystemToast.SystemToastIds.WORLD_ACCESS_FAILURE, title, message);
	}

	static void openLink(Screen screen, String url) {
		ConfirmLinkScreen.confirmLinkNow(url, screen, true);
	}

	static void renderTooltip(GuiGraphics graphics, Font font, List<FormattedCharSequence> lines, int x, int y) {
		graphics.renderTooltip(font, lines, x, y);
	}

	static void hideText(EditBox box) {
		box.setFormatter((text, offset) -> FormattedCharSequence.forward("*".repeat(text.length()), Style.EMPTY));
	}
}
