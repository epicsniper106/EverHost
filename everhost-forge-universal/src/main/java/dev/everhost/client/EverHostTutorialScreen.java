package dev.everhost.client;

import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** A short, plain-language first-run guide that can also be reopened from the dashboard. */
final class EverHostTutorialScreen extends Screen {
	private static final int BACKGROUND = 0xF2111419;
	private static final int PANEL = 0xFF1B2027;
	private static final int BORDER = 0xFF303741;
	private static final int TEXT = 0xFFF1F4F7;
	private static final int MUTED = 0xFF98A2AE;
	private static final int ACCENT = 0xFF57D6C6;
	private static final List<Step> STEPS = List.of(
		new Step("Welcome to EverHost",
			"EverHost runs a dedicated Minecraft server from the CurseForge profile and world you choose. The server can keep running after you close the Minecraft window, as long as this PC stays powered on."),
		new Step("1. Choose a profile and world",
			"Open Profiles and select the exact CurseForge profile your group uses. Return to Overview, select the world, read and accept Mojang's EULA, then choose Start. EverHost keeps the original profile and save as the source."),
		new Step("2. Invite players",
			"A local address works only on this PC. A public address lets friends connect over the internet. Temporary E4MC addresses may change. Open Permanent address once, approve the Playit agent, and share the stable address shown by EverHost."),
		new Step("3. Mods and plugins",
			"The Mods page controls which profile mods run on the server and which are required on joining clients. The Plugins page shows only plugin jars actually placed in this profile's plugins folder. Plugin compatibility depends on the modpack and server bridge."),
		new Step("4. World controls",
			"World contains normal server settings, every Java 1.20.1 game rule, player mode and item actions, time and weather controls, saves, and whitelist shortcuts. Live action buttons work while the server is online."),
		new Step("5. Voice chat (optional)",
			"Install the same Simple Voice Chat version on the host and players. Voice uses a separate UDP port, normally 24454. With Playit, create an MC: Simple Voice Chat tunnel to 127.0.0.1:24454, then set voice_host to its public host and port. A normal Minecraft tunnel alone does not carry voice."),
		new Step("6. Console, safety, and restarts",
			"Console shows readable live output, supports filtering, scrolling, command history, and a clearly marked command box. Safety controls backups and crash recovery. Manual stops, restarts, and updates announce a countdown to connected players before saving."),
		new Step("Ready to host",
			"Start on Overview. If something fails, read the status message, filter Console for ERROR, and open the full logs from Integrations. The Search button can find any setting, action, or game rule by name.")
	);

	private final Screen returnScreen;
	private final HostService service;
	private final EverHostConfig config;
	private final boolean firstRun;
	private int step;

	EverHostTutorialScreen(Screen returnScreen, HostService service, EverHostConfig config, boolean firstRun) {
		super(Component.literal("EverHost hosting guide"));
		this.returnScreen = returnScreen;
		this.service = service;
		this.config = config;
		this.firstRun = firstRun;
	}

	@Override
	protected void init() {
		int panelWidth = Math.min(620, Math.max(300, this.width - 60));
		int left = (this.width - panelWidth) / 2;
		int bottom = Math.min(this.height - 30, this.height / 2 + 145);
		Button back = addRenderableWidget(Button.builder(Component.literal("Back"), ignored -> {
			if (step > 0) {
				step--;
				rebuildWidgets();
			}
		}).bounds(left + 14, bottom - 28, 82, 20).build());
		back.active = step > 0;

		if (step == 2) {
			addRenderableWidget(Button.builder(Component.literal("Open permanent address setup"), ignored ->
				minecraft.setScreen(new PlayitAddressScreen(this, service)))
				.bounds(left + 106, bottom - 28, Math.min(190, panelWidth - 310), 20).build());
		}

		String nextText = step + 1 == STEPS.size() ? "Finish" : "Next";
		addRenderableWidget(Button.builder(Component.literal(nextText), ignored -> {
			if (step + 1 < STEPS.size()) {
				step++;
				rebuildWidgets();
			} else finish();
		}).bounds(left + panelWidth - 96, bottom - 28, 82, 20).build());
		addRenderableWidget(Button.builder(Component.literal(firstRun ? "Skip guide" : "Close guide"), ignored -> finish())
			.bounds(left + panelWidth - 190, bottom - 28, 88, 20).build());
	}

	private void finish() {
		if (firstRun && !config.tutorialCompleted) {
			config.tutorialCompleted = true;
			EverHostClient.saveConfig(config);
		}
		minecraft.setScreen(returnScreen);
	}

	@Override
	public void onClose() {
		finish();
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(0, 0, width, height, BACKGROUND);
		int panelWidth = Math.min(620, Math.max(300, this.width - 60));
		int panelHeight = Math.min(300, Math.max(220, this.height - 80));
		int left = (this.width - panelWidth) / 2;
		int top = (this.height - panelHeight) / 2;
		graphics.fill(left, top, left + panelWidth, top + panelHeight, PANEL);
		graphics.fill(left, top, left + panelWidth, top + 1, BORDER);
		graphics.fill(left, top + panelHeight - 1, left + panelWidth, top + panelHeight, BORDER);
		graphics.fill(left, top, left + 1, top + panelHeight, BORDER);
		graphics.fill(left + panelWidth - 1, top, left + panelWidth, top + panelHeight, BORDER);
		graphics.fill(left, top, left + 5, top + panelHeight, ACCENT);

		Step current = STEPS.get(step);
		graphics.drawString(font, Component.literal("EVERHOST HOSTING GUIDE"), left + 20, top + 18, ACCENT, false);
		graphics.drawString(font, Component.literal(current.title), left + 20, top + 42, TEXT, false);
		int y = top + 66;
		for (var line : font.split(Component.literal(current.body), panelWidth - 44)) {
			graphics.drawString(font, line, left + 20, y, MUTED, false);
			y += 13;
		}
		String voiceState = step == 5
			? (LoaderBridge.isModLoaded("voicechat") ? "Simple Voice Chat detected in this profile." : "Simple Voice Chat is optional and is not detected in this profile.")
			: "";
		if (!voiceState.isBlank()) graphics.drawString(font, Component.literal(voiceState), left + 20, y + 8, ACCENT, false);
		String progress = "Step " + (step + 1) + " of " + STEPS.size();
		graphics.drawString(font, Component.literal(progress), left + 20, top + panelHeight - 45, MUTED, false);
		super.render(graphics, mouseX, mouseY, delta);
	}

	private record Step(String title, String body) {}
}
