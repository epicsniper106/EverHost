package dev.everhost.client;

import dev.everhost.address.AddressFiles;
import dev.everhost.universal.Models.Profile;
import java.util.Locale;
import java.util.Properties;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class PlayitAddressScreen extends Screen {
	private final Screen parent;
	private final HostService service;
	private final EverHostConfig config;
	private final String profileKey;
	private final String profileName;
	private final String tunnelLabel;
	private String state = "OFF";
	private String message = "Permanent address is not set up";
	private String address = "";
	private String claimUrl = "";
	private String accountStatus = "";
	private boolean enabled;
	private boolean agentRunning;
	private boolean autoStarted;
	private int pollTicks;

	public PlayitAddressScreen(Screen parent, HostService service) {
		super(Component.literal("EverHost | Public Address"));
		this.parent = parent;
		this.service = service;
		this.config = EverHostConfig.load(service.configFile());
		Profile profile = service.selectedProfile(config).orElse(null);
		this.profileKey = profile == null ? config.profilePath : profile.path().toString();
		this.profileName = profile == null || profile.name().isBlank() ? "selected profile" : profile.name();
		this.tunnelLabel = profile == null ? profileName
			: profile.name() + " - " + profile.minecraftVersion() + " " + profile.loader().name().toLowerCase(Locale.ROOT);
		refresh();
	}

	@Override
	protected void init() {
		int x = left();
		int width = panelWidth();
		int gap = 6;
		int half = (width - gap) / 2;

		Button temporary = button("Temporary E4MC", x, 64, half,
			() -> choose(EverHostConfig.AddressMode.E4MC),
			"Uses the changing e4mc.link address created whenever the server starts. This needs no Playit account setup.");
		temporary.active = config.addressMode != EverHostConfig.AddressMode.E4MC;
		Button permanent = button("Permanent Playit", x + half + gap, 64, width - half - gap,
			() -> choose(EverHostConfig.AddressMode.PLAYIT),
			"Uses one saved Playit address for this CurseForge profile. Your Playit account needs a one-time browser approval.");
		permanent.active = config.addressMode != EverHostConfig.AddressMode.PLAYIT;

		if (config.addressMode == EverHostConfig.AddressMode.PLAYIT) {
			buildPlayitControls(x, width, half, gap);
			if (!autoStarted && !enabled && address.isBlank()) {
				autoStarted = true;
				startSetup();
			}
		} else if (config.addressMode == EverHostConfig.AddressMode.E4MC) {
			Button selected = button("E4MC is selected", x, 122, width, () -> {},
				"Start the EverHost server, then copy the e4mc.link address from Overview.");
			selected.active = false;
		} else {
			Button selected = button("Advanced custom address selected", x, 122, width,
				() -> minecraft.setScreen(new PermanentAddressScreen(this, service)),
				"Opens the existing self-hosted controller settings for this profile.");
			selected.active = true;
		}

		int third = (width - gap * 2) / 3;
		button("Advanced", x, height - 25, third, () -> minecraft.setScreen(new PermanentAddressScreen(this, service)),
			"For advanced owners who operate their own public controller, domain, and router endpoint.");
		button("Logs", x + third + gap, height - 25, third, service::openLogs,
			"Opens EverHost's server log for troubleshooting.");
		button("Done", x + (third + gap) * 2, height - 25, width - (third + gap) * 2, this::onClose,
			"Returns to the EverHost dashboard.");
	}

	private void buildPlayitControls(int x, int width, int half, int gap) {
		if (!address.isBlank()) {
			button("Copy permanent address", x, 122, width, () -> minecraft.keyboardHandler.setClipboard(address),
				"Copies the permanent Playit address friends should save in Multiplayer. The reservation remains while the server is off.");
			button(enabled ? "Turn permanent address off" : "Turn permanent address on", x, 148, width, () -> {
				service.setPlayitEnabled(!enabled, config.port);
				message = enabled ? "Turning the Playit connection off..." : "Starting the Playit connection...";
			}, "Stops or starts Playit without deleting this profile's address.");
			return;
		}
		if (!claimUrl.isBlank()) {
			button("1. Open approval page", x, 122, half,
				() -> FabricClientCompat.openLink(this, claimUrl),
				"Opens Playit's approval page. Sign in if asked, then approve this EverHost profile. EverHost never sees your password.");
			button("2. I approved it", x + half + gap, 122, width - half - gap, () -> {
				service.refreshPlayit();
				message = "Checking approval and finishing automatically...";
			}, "Checks the approval. EverHost then downloads the verified agent, creates the Minecraft tunnel, and sets its local port automatically.");
			return;
		}
		button(state.equals("RETRYING") || state.equals("ACTION_REQUIRED") ? "Try again" : "Check setup now",
			x, 122, half, () -> {
				service.refreshPlayit();
				message = "Checking setup...";
			}, "Checks setup immediately. EverHost also retries automatically in the background.");
		button("Playit account", x + half + gap, 122, width - half - gap,
			() -> FabricClientCompat.openLink(this, "https://playit.gg/account/tunnels?view=tunnel-type&sort=age"),
			"Opens your Playit tunnel list only for account limits or unusual errors. Normal setup does not require creating a tunnel there.");
	}

	private void choose(EverHostConfig.AddressMode mode) {
		config.addressMode = mode;
		EverHostClient.saveConfig(config);
		if (mode == EverHostConfig.AddressMode.PLAYIT) {
			startSetup();
		} else {
			service.setPlayitEnabled(false, config.port);
			message = "E4MC selected. Start the server to receive its temporary address.";
		}
		rebuildWidgets();
	}

	private void startSetup() {
		if (service.setupPlayit(tunnelLabel, profileKey, config.port)) {
			enabled = true;
			message = "Preparing the one-time approval page...";
		}
	}

	private Button button(String text, int x, int y, int width, Runnable action, String tooltip) {
		Button result = Button.builder(Component.literal(text), ignored -> action.run())
			.bounds(x, y, width, 20)
			.tooltip(Tooltip.create(Component.literal(tooltip)))
			.build();
		return addRenderableWidget(result);
	}

	@Override
	public void tick() {
		if (++pollTicks >= 10) {
			pollTicks = 0;
			String before = state + message + address + claimUrl + enabled + agentRunning + accountStatus;
			refresh();
			String after = state + message + address + claimUrl + enabled + agentRunning + accountStatus;
			if (!before.equals(after)) rebuildWidgets();
		}
	}

	private void refresh() {
		Properties playit = read("playit.properties");
		Properties status = read("playit-status.properties");
		String activeProfile = normalizeProfileKey(playit.getProperty("profileKey", ""));
		boolean sameProfile = activeProfile.isBlank() || activeProfile.equals(normalizeProfileKey(profileKey));
		state = status.getProperty("state", playit.getProperty("enabled", "false").equals("true") ? "STARTING" : "OFF");
		message = status.getProperty("message", message);
		address = sameProfile ? status.getProperty("address", playit.getProperty("address", "")) : "";
		claimUrl = sameProfile ? status.getProperty("claimUrl", "") : "";
		accountStatus = status.getProperty("accountStatus", playit.getProperty("accountStatus", ""));
		enabled = sameProfile && Boolean.parseBoolean(status.getProperty("enabled", playit.getProperty("enabled", "false")));
		agentRunning = Boolean.parseBoolean(status.getProperty("agentRunning", "false"));
	}

	private Properties read(String name) {
		try {
			return AddressFiles.read(service.root().resolve(name));
		} catch (Exception ignored) {
			return new Properties();
		}
	}

	private static String normalizeProfileKey(String value) {
		return value == null ? "" : value.strip().replace('\\', '/').toLowerCase(Locale.ROOT);
	}

	private int left() {
		return (width - panelWidth()) / 2;
	}

	private int panelWidth() {
		return Math.min(540, width - 24);
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(0, 0, width, height, 0xFF111419);
		int x = left();
		int panel = panelWidth();
		graphics.drawString(font, title, x, 12, 0xFFF1F4F7, false);
		graphics.drawString(font, font.plainSubstrByWidth("Profile: " + profileName, panel), x, 29, 0xFF57D6C6, false);
		String mode = switch (config.addressMode) {
			case PLAYIT -> "PERMANENT PLAYIT";
			case CUSTOM -> "ADVANCED CUSTOM";
			case E4MC -> "TEMPORARY E4MC";
		};
		graphics.drawString(font, mode, x, 47, 0xFFF1F4F7, false);
		if (config.addressMode == EverHostConfig.AddressMode.PLAYIT) {
			int color = state.equals("ONLINE") ? 0xFF55D68B : state.equals("RETRYING") || state.equals("ACTION_REQUIRED") ? 0xFFFF707C : 0xFFF0B45C;
			graphics.drawString(font, "SETUP: " + state, x, 92, color, false);
			graphics.drawString(font, font.plainSubstrByWidth(message, panel), x, 106, 0xFFB9C2CE, false);
			boolean routed = state.equals("ONLINE") && agentRunning;
			if (!address.isBlank()) {
				String addressLine = (routed ? "LIVE: " : "SAVED: ") + address;
				graphics.drawString(font, font.plainSubstrByWidth(addressLine, panel), x, 177,
					routed ? 0xFF55D68B : 0xFFF0B45C, false);
			}
			String owner = accountStatus.isBlank() ? "One browser approval is needed for this profile." : "Playit account approved.";
			graphics.drawString(font, owner, x, 195, 0xFF98A2AE, false);
			graphics.drawString(font, "EverHost creates the tunnel and port settings automatically.", x, 208, 0xFF98A2AE, false);
			graphics.drawString(font, agentRunning ? "Playit is running on this PC." : "Your PC must be on for friends to connect.", x, 221, 0xFF98A2AE, false);
		} else if (config.addressMode == EverHostConfig.AddressMode.E4MC) {
			graphics.drawString(font, "No account setup. The address changes after a new server session.", x, 96, 0xFFB9C2CE, false);
			graphics.drawString(font, "Start the server, then copy the e4mc.link address from Overview.", x, 109, 0xFF98A2AE, false);
		} else {
			graphics.drawString(font, "Your existing self-hosted controller address remains selected.", x, 96, 0xFFB9C2CE, false);
		}
		super.render(graphics, mouseX, mouseY, delta);
	}

	@Override
	public void onClose() {
		if (parent instanceof EverHostDashboardScreen dashboard) dashboard.reloadSavedConfig();
		minecraft.setScreen(parent);
	}
}
