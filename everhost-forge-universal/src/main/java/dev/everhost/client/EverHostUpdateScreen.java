package dev.everhost.client;

import dev.everhost.update.EverHostUpdater;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Explicit player approval screen shown after the startup update check finds a newer matching build. */
public final class EverHostUpdateScreen extends Screen {
	private final Screen parent;
	private final EverHostUpdater.UpdateCandidate candidate;
	private Button installButton;
	private Button laterButton;
	private boolean busy;
	private String status = "The exact release will be downloaded and verified before Minecraft closes.";

	public EverHostUpdateScreen(Screen parent, EverHostUpdater.UpdateCandidate candidate) {
		super(Component.literal("EverHost Update Available"));
		this.parent = parent;
		this.candidate = candidate;
	}

	@Override
	protected void init() {
		int left = width / 2 - 140;
		installButton = addRenderableWidget(Button.builder(Component.literal("Restart Minecraft & Install Update"), ignored -> install())
			.bounds(left, height / 2 + 24, 280, 22).build());
		laterButton = addRenderableWidget(Button.builder(Component.literal("Remind Me Next Launch"), ignored -> onClose())
			.bounds(left, height / 2 + 52, 280, 20).build());
		installButton.active = !busy;
		laterButton.active = !busy;
	}

	private void install() {
		if (busy) return;
		busy = true;
		status = "Downloading and verifying EverHost " + candidate.version() + "...";
		installButton.active = false;
		laterButton.active = false;
		EverHostClient.installUpdate(candidate, message -> {
			status = message;
			if (message.startsWith("Update failed:")) {
				busy = false;
				installButton.active = true;
				laterButton.active = true;
			}
		});
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(0, 0, width, height, 0xEE10141A);
		graphics.drawCenteredString(font, Component.literal("EVERHOST UPDATE READY"), width / 2, height / 2 - 82, 0xFFFFC857);
		graphics.drawCenteredString(font, Component.literal("Version " + candidate.version()), width / 2,
			height / 2 - 58, 0xFF57D6C6);
		graphics.drawCenteredString(font, Component.literal("Restart Minecraft to install the verified update."), width / 2,
			height / 2 - 34, 0xFFF1F4F7);
		graphics.drawCenteredString(font, Component.literal(font.plainSubstrByWidth(status, Math.max(120, width - 36))),
			width / 2, height / 2 - 12, status.startsWith("Update failed:") ? 0xFFFF7777 : 0xFFB9C2CE);
		super.render(graphics, mouseX, mouseY, delta);
	}

	@Override
	public void onClose() {
		if (!busy) {
			EverHostClient.dismissUpdatePrompt(candidate);
			minecraft.setScreen(parent);
		}
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}
}
