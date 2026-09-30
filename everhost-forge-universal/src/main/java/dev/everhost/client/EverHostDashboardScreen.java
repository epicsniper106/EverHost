package dev.everhost.client;

import dev.everhost.plugins.PluginSupport.Decision;
import dev.everhost.plugins.PluginSupport.PluginInfo;
import dev.everhost.plugins.PluginSupport.ScanResult;
import dev.everhost.universal.Models.ModInfo;
import dev.everhost.universal.Models.ModSide;
import dev.everhost.universal.Models.Profile;
import java.net.URI;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Function;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public final class EverHostDashboardScreen extends Screen {
	private static final int HEADER_HEIGHT = 48;
	private static final int SIDEBAR_WIDTH = 178;
	private static final int FOOTER_HEIGHT = 34;
	private static final int CONTROL_HEIGHT = 18;
	private static final int ROW_GAP = 19;
	private static final int BACKGROUND = 0xF2111419;
	private static final int HEADER = 0xFF171B21;
	private static final int SIDEBAR = 0xFF12161B;
	private static final int PANEL = 0xFF1B2027;
	private static final int BORDER = 0xFF303741;
	private static final int TEXT = 0xFFF1F4F7;
	private static final int MUTED = 0xFF98A2AE;
	private static final int ACCENT = 0xFF57D6C6;
	private static final int ONLINE = 0xFF55D68B;
	private static final int WARNING = 0xFFF0B45C;
	private static final int ERROR = 0xFFFF707C;

	private final Screen parent;
	private final HostService service;
	private EverHostConfig draft;
	private Page page = Page.OVERVIEW;
	private HostStatus status;
	private HostStatus.State renderedState;
	private String renderedDomain = "";
	private int optionIndex;
	private int optionTop = HEADER_HEIGHT + 54;
	private int pollTicks;
	private int modPage;
	private int pluginPage;
	private int profilePage = -1;
	private final List<Label> labels = new ArrayList<>();
	private Button startButton;
	private Button consoleSendButton;
	private Button eulaCheckbox;
	private EditBox consoleInput;
	private EditBox consoleFilterInput;
	private String consoleText = "";
	private String consoleDraft = "";
	private String consoleFilter = "";
	private int consoleScrollOffset;
	private final List<String> consoleHistory = new ArrayList<>();
	private int consoleHistoryIndex;
	private WorldSection worldSection = WorldSection.SETTINGS;
	private int gameRulePage;
	private final Map<String, String> gameRuleDrafts = new LinkedHashMap<>();
	private String giveTarget = "@a";
	private String giveItem = "minecraft:bread";
	private String giveCount = "1";

	public EverHostDashboardScreen(Screen parent, HostService service, EverHostConfig config) {
		super(Component.literal("EverHost"));
		this.parent = parent;
		this.service = service;
		this.draft = config.copy();
		this.status = service.status();
		this.renderedState = status.state();
		this.renderedDomain = status.domain();
		ensureSelectedProfile();
		String testPage = System.getProperty("everhost.test.page", "");
		if (!testPage.isBlank()) {
			try {
				this.page = Page.valueOf(testPage.toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException ignored) {
			}
		}
		String testWorldSection = System.getProperty("everhost.test.worldSection", "");
		if (!testWorldSection.isBlank()) {
			try {
				this.worldSection = WorldSection.valueOf(testWorldSection.toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException ignored) {
			}
		}
	}

	@Override
	protected void init() {
		labels.clear();
		startButton = null;
		consoleSendButton = null;
		eulaCheckbox = null;
		consoleInput = null;
		consoleFilterInput = null;
		optionIndex = 0;
		optionTop = HEADER_HEIGHT + 54;
		buildHeaderWidgets();
		buildSidebar();
		switch (page) {
			case OVERVIEW -> buildOverview();
			case PROFILES -> buildProfiles();
			case MODS -> buildMods();
			case PLUGINS -> buildPlugins();
			case HOSTING -> buildHosting();
			case WORLD -> buildWorld();
			case PERFORMANCE -> buildPerformance();
			case ACCESS -> buildAccess();
			case SAFETY -> buildSafety();
			case INTEGRATIONS -> buildIntegrations();
			case CONSOLE -> buildConsole();
		}
		buildFooter();
	}

	private void buildHeaderWidgets() {
		int searchWidth = Math.min(92, Math.max(70, this.width / 10));
		if (EverHostClient.hasPendingUpdate()) {
			int updateWidth = Math.min(190, Math.max(148, this.width / 7));
			addButton("INSTALL UPDATE " + EverHostClient.pendingUpdateVersion(),
				Math.max(280, this.width - 226 - updateWidth), 10, updateWidth,
				() -> EverHostClient.openPendingUpdate(this),
				"Downloads the exact verified release, restarts Minecraft, installs EverHost, and reopens CurseForge.");
		}
		addButton("Search", Math.max(280, this.width - 220), 10, searchWidth,
			() -> minecraft.setScreen(new FeatureSearchScreen(this)),
			"Search every EverHost page, server setting, quick action, and Java 1.20.1 game rule.");
		String stateText = stateLabel(status.state());
		Button stateButton = addButton(stateText, Math.max(10, this.width - 112), 10, 100, () -> {}, status.message());
		stateButton.active = false;
	}

	private void buildLabelWidgets() {
		for (Label label : labels) {
			int available = Math.max(40, this.width - label.x() - 12);
			int width = label.width() > 0 ? Math.min(label.width(), available) : available;
			Button text = addButton(label.text(), label.x(), label.y() - 4, width, label.height(), () -> {}, label.text());
			text.active = false;
		}
	}

	private void buildSidebar() {
		int margin = 8;
		int buttonWidth = SIDEBAR_WIDTH - margin * 2;
		int spacing = 21;
		Page[] pages = Page.values();
		for (int index = 0; index < pages.length; index++) {
			Page candidate = pages[index];
			int x = margin;
			int y = HEADER_HEIGHT + 8 + index * spacing;
			Button tab = Button.builder(Component.literal(candidate.title), ignored -> switchPage(candidate))
				.bounds(x, y, buttonWidth, CONTROL_HEIGHT)
				.tooltip(Tooltip.create(Component.literal(pageTooltip(candidate))))
				.build();
			tab.active = page != candidate;
			this.addRenderableWidget(tab);
		}
		int actionY = HEADER_HEIGHT + 8 + pages.length * spacing + 4;
		addButton("Permanent address", margin, actionY, buttonWidth,
			() -> minecraft.setScreen(new PlayitAddressScreen(this, service)),
			"Sets up a persistent Playit address on the owner's PC. Advanced owners can use their own EverHost controller and domain.");
		addButton("Hosting guide", margin, actionY + spacing, buttonWidth,
			() -> minecraft.setScreen(new EverHostTutorialScreen(this, service, draft.copy(), false)),
			"Reopens the step-by-step hosting, permanent address, and optional voice chat guide.");
	}

	private void buildOverview() {
		int left = contentLeft();
		int contentWidth = contentWidth();
		int y = HEADER_HEIGHT + 54;
		String profileName = draft.profileName.isBlank() ? "Choose a CurseForge profile" : draft.profileName;
		addButton("Host profile: " + profileName, left, y, contentWidth, () -> switchPage(Page.PROFILES),
			"Opens the profile list. EverHost can host a world from any supported CurseForge profile on this PC.");
		y += 24;
		List<WorldChoice> worlds = worldChoices();
		WorldChoice selected = worlds.stream().filter(world -> world.id().equals(draft.worldId)).findFirst().orElse(worlds.get(0));
		Button selector = addButton("World: " + selected.name(), left, y, contentWidth, () -> cycleWorld(worldChoices()),
			"Selects the save EverHost will run. Minecraft's world lock prevents this save from being opened twice.");
		selector.setTooltip(Tooltip.create(Component.literal(
			"Selects the save EverHost will run. Minecraft's world lock prevents this save from being opened twice."
		)));
		selector.active = !status.state().isBusyOrOnline();

		int actionY = y + 24;
		int gap = 5;
		int actionWidth = Math.max(58, (contentWidth - gap * 3) / 4);
		startButton = addButton("Start", left, actionY, actionWidth, () -> {
			persistDraft();
			service.send(HostService.Action.START);
		}, "Starts the selected world using the loader and Minecraft version from the chosen CurseForge profile. Minecraft can then be closed while the server stays online.");
		Button stop = addButton("Stop", left + actionWidth + gap, actionY, actionWidth, () -> service.send(HostService.Action.STOP),
			"Sends Minecraft's normal stop command, saves players and every dimension, then closes the server.");
		Button restart = addButton("Restart", left + (actionWidth + gap) * 2, actionY, actionWidth, () -> {
			persistDraft();
			service.send(HostService.Action.RESTART);
		}, "Saves and stops the current server, then starts it again with the latest settings.");
		Button backup = addButton("Backup", left + (actionWidth + gap) * 3, actionY, actionWidth, () -> service.send(HostService.Action.BACKUP),
			"Creates a compressed copy of the selected world while the server is offline. Old backups follow the retention setting.");
		stop.active = status.state() == HostStatus.State.ONLINE || status.state() == HostStatus.State.STARTING || status.state() == HostStatus.State.PROVISIONING;
		restart.active = status.state() == HostStatus.State.ONLINE;
		backup.active = status.state() == HostStatus.State.OFFLINE || status.state() == HostStatus.State.ERROR;

		int addressY = actionY + 24;
		int joinWidth = Math.min(86, Math.max(68, contentWidth / 3));
		int addressWidth = contentWidth - joinWidth - gap;
		String shareAddress = service.shareAddress(draft);
		String pendingAddress = draft.addressMode == EverHostConfig.AddressMode.PLAYIT
			? "Set up permanent address"
			: "E4MC address appears after Start";
		Button address = addButton(
			shareAddress.isBlank() ? pendingAddress : shareAddress,
			left,
			addressY,
			addressWidth,
			service::copyAddress,
			"Copies the selected public address. Open Address to switch between temporary E4MC and permanent Playit hosting."
		);
		address.active = !shareAddress.isBlank();
		Button join = addButton("Copy Local", left + addressWidth + gap, addressY, joinWidth, () -> service.joinLocal(this),
			"Copies the local EverHost address. Paste it into Multiplayer to connect without traveling through the public relay.");
		join.active = status.state() == HostStatus.State.ONLINE;

		int eulaY = addressY + 24;
		int readWidth = Math.min(82, contentWidth / 3);
		eulaCheckbox = addButton("Accept EULA: " + onOff(draft.eulaAccepted), left, eulaY,
			Math.max(100, contentWidth - readWidth - gap), () -> {
				draft.eulaAccepted = !draft.eulaAccepted;
				persistDraft();
				this.rebuildWidgets();
			}, "Required by Mojang before a dedicated server may start. This records only your explicit choice in EverHost's local settings.");
		eulaCheckbox.setTooltip(Tooltip.create(Component.literal(
			"Required by Mojang before a dedicated server may start. This records only your explicit choice in EverHost's local settings."
		)));
		addButton("Read EULA", left + contentWidth - readWidth, eulaY - 1, readWidth, () -> ConfirmLinkScreen.confirmLinkNow("https://aka.ms/MinecraftEULA", this, true),
			"Opens Mojang's Minecraft EULA in your browser after a confirmation prompt.");
		updateStartAvailability();
	}

	private void buildProfiles() {
		int left = contentLeft();
		int width = contentWidth();
		List<Profile> profiles = service.profiles();
		if (profiles.isEmpty()) {
			labels.add(new Label("No supported CurseForge profiles were found.", left, HEADER_HEIGHT + 63));
			addButton("Scan again", left, HEADER_HEIGHT + 88, Math.min(140, width), () -> {
				service.refreshProfiles();
				ensureSelectedProfile();
				this.rebuildWidgets();
			}, "Scans the CurseForge Instances folder again without opening another program.");
			return;
		}
		Profile selected = service.selectedProfile(draft).orElse(profiles.get(0));
		int firstY = HEADER_HEIGHT + 52;
		int rowsPerPage = Math.max(2, (this.height - FOOTER_HEIGHT - firstY - 30) / 23);
		int pages = Math.max(1, (profiles.size() + rowsPerPage - 1) / rowsPerPage);
		int selectedIndex = Math.max(0, profiles.indexOf(selected));
		if (profilePage < 0 || profilePage >= pages) profilePage = selectedIndex / rowsPerPage;
		profilePage = Math.max(0, Math.min(profilePage, pages - 1));
		int first = profilePage * rowsPerPage;
		int last = Math.min(profiles.size(), first + rowsPerPage);
		for (int index = first; index < last; index++) {
			Profile profile = profiles.get(index);
			boolean isSelected = profile.path().equals(selected.path());
			String prefix = isSelected ? "Selected" : "Use";
			String text = prefix + "  |  " + profile.name() + "  |  " + profile.minecraftVersion() + " "
				+ profile.loader().name().toLowerCase(Locale.ROOT);
			String fitted = this.font.plainSubstrByWidth(text, Math.max(40, width - 12));
			Button row = addButton(fitted, left, firstY + (index - first) * 23, width, () -> selectProfile(profile),
				"Hosts this profile's worlds, server-compatible mods, configs, operators, whitelist, and plugins. Your choice is saved immediately.");
			row.active = !isSelected && !status.state().isBusyOrOnline();
		}
		int navY = this.height - FOOTER_HEIGHT - 24;
		int navWidth = Math.min(90, Math.max(60, (width - 12) / 3));
		Button previous = addButton("Previous", left, navY, navWidth, () -> { profilePage--; this.rebuildWidgets(); }, "Shows earlier CurseForge profiles.");
		previous.active = profilePage > 0;
		Button next = addButton("Next", left + navWidth + 6, navY, navWidth, () -> { profilePage++; this.rebuildWidgets(); }, "Shows more CurseForge profiles.");
		next.active = profilePage + 1 < pages;
		addButton("Scan again", left + width - navWidth, navY, navWidth, () -> {
			service.refreshProfiles();
			ensureSelectedProfile();
			this.rebuildWidgets();
		}, "Refreshes worlds, mods, and plugins from every local CurseForge profile.");
	}

	private void buildMods() {
		List<ModInfo> mods = service.selectedMods(draft);
		int left = contentLeft();
		int width = contentWidth();
		int firstY = HEADER_HEIGHT + 54;
		int navigationHeight = 28;
		int rowsPerPage = Math.max(2, (this.height - FOOTER_HEIGHT - firstY - navigationHeight) / 23);
		int pages = Math.max(1, (mods.size() + rowsPerPage - 1) / rowsPerPage);
		modPage = Math.max(0, Math.min(modPage, pages - 1));
		int first = modPage * rowsPerPage;
		int last = Math.min(mods.size(), first + rowsPerPage);
		for (int index = first; index < last; index++) {
			ModInfo mod = mods.get(index);
			EverHostConfig.ModMode mode = modeFor(mod);
			String prefix = switch (mode) {
				case OFF -> "Off";
				case SERVER -> "Server";
				case REQUIRED -> "Required";
			};
			String fullLabel = prefix + "  |  " + mod.name;
			String label = fullLabel;
			int y = firstY + (index - first) * 23;
			Button row = addButton(label, left, y, width, () -> cycleMod(mod),
				mod.reason + " Click to cycle: Off excludes it, Server installs it only on the host, and Required also lists it as mandatory for joining players. File: " + mod.path.getFileName());
			row.active = !status.state().isBusyOrOnline();
		}
		if (mods.isEmpty()) {
			labels.add(new Label("This profile has no mod jars to review.", left, firstY + 6));
		}
		int navY = this.height - FOOTER_HEIGHT - 25;
		Button previous = addButton("Previous", left, navY, 82, () -> {
			modPage--;
			this.rebuildWidgets();
		}, "Shows the previous page of detected mods.");
		previous.active = modPage > 0;
		Button next = addButton("Next", left + width - 82, navY, 82, () -> {
			modPage++;
			this.rebuildWidgets();
		}, "Shows the next page of detected mods.");
		next.active = modPage + 1 < pages;
		labels.add(new Label((modPage + 1) + " / " + pages, left + 92, navY + 5, Math.max(40, width - 184), 14));
	}

	private void buildPlugins() {
		int left = contentLeft();
		int width = contentWidth();
		int toolbarY = HEADER_HEIGHT + 54;
		int gap = 6;
		int toolbarWidth = (width - gap * 2) / 3;
		addButton("Open plugins folder", left, toolbarY, toolbarWidth, () -> service.openPluginsFolder(draft),
			"Creates this profile's plugins folder if it is missing, then opens it. Only jar files placed there appear on this page.");
		addButton("Refresh installed list", left + toolbarWidth + gap, toolbarY, toolbarWidth, () -> {
			service.refreshProfiles();
			this.rebuildWidgets();
		}, "Scans the selected profile's plugins folder again.");

		ScanResult scan = service.selectedPluginScan(draft);
		Button loading = addButton("Plugin loading: " + (draft.pluginMode == EverHostConfig.PluginMode.AUTO ? "On" : "Off"),
			left + (toolbarWidth + gap) * 2, toolbarY, toolbarWidth, () -> {
				draft.pluginMode = draft.pluginMode == EverHostConfig.PluginMode.AUTO
					? EverHostConfig.PluginMode.OFF : EverHostConfig.PluginMode.AUTO;
				this.rebuildWidgets();
			}, "Controls whether installed plugin jars are loaded on the next server start. Compatibility still depends on each plugin and the modpack.");
		loading.active = !scan.plugins().isEmpty() && !status.state().isBusyOrOnline();

		int summaryY = toolbarY + 28;
		String profile = draft.profileName.isBlank() ? "No profile selected" : draft.profileName;
		String summary = scan.plugins().isEmpty()
			? "No plugin jars are installed for " + profile + "."
			: scan.plugins().size() + " installed  |  " + scan.readyCount() + " ready  |  " + scan.warningCount()
				+ " review  |  " + scan.blockedCount() + " blocked  |  " + scan.disabledCount() + " disabled";
		if (status.state().isBusyOrOnline() && draft.pluginMode == EverHostConfig.PluginMode.AUTO) {
			summary += "  |  " + status.pluginLoaded() + " loaded, " + status.pluginFailed() + " failed";
		}
		labels.add(new Label(this.font.plainSubstrByWidth(summary, width - 16), left + 8, summaryY, width - 16, 14));

		int listY = summaryY + 22;
		int rows = Math.max(2, (this.height - FOOTER_HEIGHT - listY - 34) / 23);
		int pages = Math.max(1, (scan.plugins().size() + rows - 1) / rows);
		pluginPage = Math.max(0, Math.min(pluginPage, pages - 1));
		int first = pluginPage * rows;
		for (int index = first; index < Math.min(scan.plugins().size(), first + rows); index++) {
			int rowY = listY + (index - first) * 23;
			PluginInfo plugin = scan.plugins().get(index);
			String state = pluginRuntimeState(plugin);
			Button stateButton = addButton(state, left, rowY, 78, () -> cyclePlugin(plugin),
				"Enables or disables this installed plugin without deleting its jar. Restart the server to apply the change.");
			stateButton.active = !status.state().isBusyOrOnline() && plugin.decision() != Decision.BLOCKED;
			String details = plugin.detail() + " Java: " + (plugin.requiredJava() == 0 ? "unknown" : plugin.requiredJava())
				+ ". Required: " + (plugin.dependencies().isEmpty() ? "none" : String.join(", ", plugin.dependencies()))
				+ ". Optional: " + (plugin.optionalDependencies().isEmpty() ? "none" : String.join(", ", plugin.optionalDependencies()))
				+ ". File: " + plugin.path().getFileName();
			String full = plugin.name() + "  |  " + plugin.path().getFileName();
			addButton(this.font.plainSubstrByWidth(full, width - 96), left + 84, rowY, width - 84,
				() -> HostService.showToast(plugin.name(), details), details);
		}
		if (scan.plugins().isEmpty()) {
			labels.add(new Label("Place a compatible .jar in the profile's plugins folder, then choose Refresh installed list.",
				left + 8, listY + 8, width - 16, 14));
		}
		int navY = this.height - FOOTER_HEIGHT - 25;
		Button previous = addButton("Previous", left, navY, 82, () -> { pluginPage--; this.rebuildWidgets(); }, "Shows the previous plugin page.");
		previous.active = pluginPage > 0;
		Button next = addButton("Next", left + width - 82, navY, 82, () -> { pluginPage++; this.rebuildWidgets(); }, "Shows the next plugin page.");
		next.active = pluginPage + 1 < pages;
		labels.add(new Label("Installed plugins  |  Page " + (pluginPage + 1) + " / " + pages,
			left + 92, navY + 6, Math.max(40, width - 184), 14));
	}

	private void buildHosting() {
		addSlider("Min memory", "Reserved memory when the server starts. More is not always faster; 2 GB is a solid baseline.", draft.minMemoryMb, 1024, 8192, 512,
			value -> Component.literal(String.format(Locale.ROOT, "%.1f GB", value / 1024.0)), value -> {
				draft.minMemoryMb = (int)Math.round(value);
				if (draft.maxMemoryMb < draft.minMemoryMb) draft.maxMemoryMb = draft.minMemoryMb;
			});
		addSlider("Max memory", "The most RAM the background server may use. 8 GB suits a powerful PC and a modest friend group.", draft.maxMemoryMb, 2048, 24576, 1024,
			value -> Component.literal(String.format(Locale.ROOT, "%.0f GB", value / 1024.0)), value -> draft.maxMemoryMb = (int)Math.round(value));
		addNumberField("Server port", "Local TCP port used by Minecraft and tunneled by e4mc. Change it only if another server already uses this port.", draft.port, value -> draft.port = value, 5);
		addSlider("Player limit", "Caps simultaneous players. Values up to 500 are available, but actual capacity still depends on hardware, mods, and upload speed.", draft.maxPlayers, 1, 500, 1,
			value -> Component.literal(Integer.toString((int)Math.round(value))), value -> draft.maxPlayers = (int)Math.round(value));
		addTextField("Server message", "The message shown for the local server entry and status queries.", draft.motd, 120, value -> draft.motd = value);
		addBoolean("Start with Minecraft", "Starts the selected world automatically when Minecraft launches and the background host is offline.", draft.autoStartWithMinecraft,
			value -> draft.autoStartWithMinecraft = value);
		addBoolean("Start with Windows", "Launches EverHost at Windows sign-in and starts the selected world without opening Minecraft. Your PC must remain powered on.", draft.startWithWindows,
			value -> draft.startWithWindows = value);
	}

	private void buildWorld() {
		int left = contentLeft();
		int width = contentWidth();
		int gap = 5;
		int sectionWidth = (width - gap * (WorldSection.values().length - 1)) / WorldSection.values().length;
		int sectionY = HEADER_HEIGHT + 51;
		for (int index = 0; index < WorldSection.values().length; index++) {
			WorldSection section = WorldSection.values()[index];
			Button button = addButton(section.title, left + index * (sectionWidth + gap), sectionY, sectionWidth,
				() -> {
					worldSection = section;
					this.rebuildWidgets();
				}, section.tooltip);
			button.active = worldSection != section;
		}
		optionTop = sectionY + 25;
		switch (worldSection) {
			case SETTINGS -> buildWorldSettings();
			case GAME_RULES -> buildGameRules();
			case PLAYERS -> buildPlayerActions();
			case QUICK_ACTIONS -> buildWorldActions();
			case SERVER_OPTIONS -> buildServerOptions();
		}
	}

	private void buildWorldSettings() {
		addEnum("Game mode", "Sets the default mode for new players and for everyone when Force game mode is enabled.", draft.gameMode,
			EverHostConfig.GameMode.values(), EverHostDashboardScreen::enumText, value -> draft.gameMode = value);
		addEnum("Difficulty", "Controls hostile mob damage, hunger effects, and whether hostile mobs spawn.", draft.difficulty,
			EverHostConfig.Difficulty.values(), EverHostDashboardScreen::enumText, value -> draft.difficulty = value);
		addSlider("View distance", "How many chunks the server sends around each player. Higher values improve horizons and increase CPU, RAM, and network use.", draft.viewDistance, 2, 32, 1,
			value -> Component.literal((int)Math.round(value) + " chunks"), value -> draft.viewDistance = (int)Math.round(value));
		addSlider("Simulation", "How far entities, redstone, crops, and other world systems keep ticking around players.", draft.simulationDistance, 2, 32, 1,
			value -> Component.literal((int)Math.round(value) + " chunks"), value -> draft.simulationDistance = (int)Math.round(value));
		addBoolean("Force game mode", "Reapplies the default game mode when a player joins, overriding the mode saved on that player.", draft.forceGamemode,
			value -> draft.forceGamemode = value);
		addBoolean("Player combat", "Allows players to damage one another. Mob and environmental damage are unaffected.", draft.pvp, value -> draft.pvp = value);
		addBoolean("Allow flight", "Prevents the server from kicking players for sustained flight. This does not grant creative mode or a flight ability.", draft.allowFlight,
			value -> draft.allowFlight = value);
		addBoolean("Command blocks", "Enables command block execution. Leave this off unless the selected world uses command blocks you trust.", draft.commandBlocks,
			value -> draft.commandBlocks = value);
		addSlider("Spawn protect", "Radius around world spawn where only operators may build. Set to zero to disable spawn protection.", draft.spawnProtection, 0, 32, 1,
			value -> Component.literal((int)Math.round(value) + " blocks"), value -> draft.spawnProtection = (int)Math.round(value));
	}

	private void buildGameRules() {
		int left = contentLeft();
		int width = contentWidth();
		int firstY = optionTop + 4;
		int navHeight = 28;
		int rows = Math.max(3, (this.height - FOOTER_HEIGHT - firstY - navHeight) / 23);
		int pages = Math.max(1, (GAME_RULES.size() + rows - 1) / rows);
		gameRulePage = Math.max(0, Math.min(gameRulePage, pages - 1));
		int first = gameRulePage * rows;
		int last = Math.min(GAME_RULES.size(), first + rows);
		boolean online = status.state() == HostStatus.State.ONLINE;
		for (int index = first; index < last; index++) {
			GameRuleSpec rule = GAME_RULES.get(index);
			int y = firstY + (index - first) * 23;
			int labelWidth = Math.max(150, width - 190);
			labels.add(new Label(rule.name + "  |  " + rule.description, left + 6, y + 5, labelWidth - 8, 14));
			if (rule.numeric) {
				String value = gameRuleDrafts.computeIfAbsent(rule.name, ignored -> rule.defaultValue);
				EditBox box = new EditBox(this.font, left + width - 180, y, 90, CONTROL_HEIGHT, Component.literal(rule.name));
				box.setFilter(text -> text.isEmpty() || text.chars().allMatch(character -> character == '-' || Character.isDigit(character)));
				box.setMaxLength(10);
				box.setValue(value);
				box.setResponder(text -> gameRuleDrafts.put(rule.name, text));
				box.setTooltip(Tooltip.create(Component.literal("Enter a value. Vanilla default: " + rule.defaultValue)));
				this.addRenderableWidget(box);
				Button apply = addButton("Apply", left + width - 84, y, 84,
					() -> sendQuickCommand("gamerule " + rule.name + " " + gameRuleDrafts.getOrDefault(rule.name, rule.defaultValue),
						"Updated " + rule.name), rule.description);
				apply.active = online;
			} else {
				Button on = addButton("On", left + width - 180, y, 86,
					() -> sendQuickCommand("gamerule " + rule.name + " true", "Enabled " + rule.name), rule.description);
				Button off = addButton("Off", left + width - 88, y, 88,
					() -> sendQuickCommand("gamerule " + rule.name + " false", "Disabled " + rule.name), rule.description);
				on.active = online;
				off.active = online;
			}
		}
		int navY = this.height - FOOTER_HEIGHT - 25;
		Button previous = addButton("Previous", left, navY, 82, () -> { gameRulePage--; this.rebuildWidgets(); }, "Shows earlier game rules.");
		Button next = addButton("Next", left + width - 82, navY, 82, () -> { gameRulePage++; this.rebuildWidgets(); }, "Shows more game rules.");
		previous.active = gameRulePage > 0;
		next.active = gameRulePage + 1 < pages;
		labels.add(new Label("All " + GAME_RULES.size() + " Java 1.20.1 game rules  |  Page " + (gameRulePage + 1) + " / " + pages,
			left + 92, navY + 6, width - 184, 14));
	}

	private void buildPlayerActions() {
		int left = contentLeft();
		int width = contentWidth();
		int gap = 6;
		int y = optionTop + 4;
		labels.add(new Label("CHANGE EVERY ONLINE PLAYER", left + 5, y, width - 10, 14));
		y += 16;
		String[] modes = {"survival", "creative", "adventure", "spectator"};
		int actionWidth = (width - gap * 3) / 4;
		for (int index = 0; index < modes.length; index++) {
			String mode = modes[index];
			Button button = addButton(capitalize(mode), left + index * (actionWidth + gap), y, actionWidth,
				() -> sendQuickCommand("gamemode " + mode + " @a", "Set every player to " + mode),
				"Changes every currently connected player to " + mode + " mode.");
			button.active = status.state() == HostStatus.State.ONLINE;
		}
		y += 28;
		labels.add(new Label("PLAYER CARE", left + 5, y, width - 10, 14));
		y += 16;
		String[][] actions = {
			{"Heal everyone", "effect give @a minecraft:instant_health 1 10 true"},
			{"Feed everyone", "effect give @a minecraft:saturation 1 10 true"},
			{"Clear effects", "effect clear @a"},
			{"Set spawn here", "spawnpoint @a"}
		};
		for (int index = 0; index < actions.length; index++) {
			String label = actions[index][0];
			String command = actions[index][1];
			Button button = addButton(label, left + index * (actionWidth + gap), y, actionWidth,
				() -> sendQuickCommand(command, label), "Runs: /" + command);
			button.active = status.state() == HostStatus.State.ONLINE;
		}
		y += 35;
		labels.add(new Label("GIVE AN ITEM", left + 5, y, width - 10, 14));
		y += 16;
		int targetWidth = Math.max(70, width / 7);
		int countWidth = 55;
		int giveWidth = 74;
		EditBox target = new EditBox(this.font, left, y, targetWidth, CONTROL_HEIGHT, Component.literal("Player or selector"));
		target.setMaxLength(64);
		target.setValue(giveTarget);
		target.setHint(Component.literal("@a"));
		target.setResponder(value -> giveTarget = value);
		target.setTooltip(Tooltip.create(Component.literal("Player name or selector, such as @a, @p, or epicsniper107.")));
		this.addRenderableWidget(target);
		EditBox item = new EditBox(this.font, left + targetWidth + gap, y,
			width - targetWidth - countWidth - giveWidth - gap * 3, CONTROL_HEIGHT, Component.literal("Item ID"));
		item.setMaxLength(180);
		item.setValue(giveItem);
		item.setHint(Component.literal("minecraft:bread"));
		item.setResponder(value -> giveItem = value);
		item.setTooltip(Tooltip.create(Component.literal("Minecraft or mod item ID, including its namespace.")));
		this.addRenderableWidget(item);
		EditBox count = new EditBox(this.font, left + width - countWidth - giveWidth - gap, y, countWidth, CONTROL_HEIGHT, Component.literal("Count"));
		count.setFilter(value -> value.isEmpty() || value.chars().allMatch(Character::isDigit));
		count.setMaxLength(4);
		count.setValue(giveCount);
		count.setResponder(value -> giveCount = value);
		this.addRenderableWidget(count);
		Button give = addButton("Give", left + width - giveWidth, y, giveWidth, () -> {
			String targetValue = giveTarget.isBlank() ? "@a" : giveTarget.strip();
			String itemValue = giveItem.isBlank() ? "minecraft:bread" : giveItem.strip();
			String countValue = giveCount.isBlank() ? "1" : giveCount.strip();
			sendQuickCommand("give " + targetValue + " " + itemValue + " " + countValue, "Give command sent");
		}, "Gives the selected item without requiring you to type a console command.");
		give.active = status.state() == HostStatus.State.ONLINE;
	}

	private void buildWorldActions() {
		int left = contentLeft();
		int width = contentWidth();
		int gap = 6;
		int buttonWidth = (width - gap * 3) / 4;
		int y = optionTop + 5;
		labels.add(new Label("TIME", left + 5, y, width - 10, 14));
		y += 16;
		addActionRow(left, y, buttonWidth, gap, new QuickAction[]{
			new QuickAction("Sunrise", "time set 0"), new QuickAction("Day", "time set day"),
			new QuickAction("Sunset", "time set 12000"), new QuickAction("Night", "time set night")
		});
		y += 34;
		labels.add(new Label("WEATHER", left + 5, y, width - 10, 14));
		y += 16;
		addActionRow(left, y, buttonWidth, gap, new QuickAction[]{
			new QuickAction("Clear", "weather clear"), new QuickAction("Rain", "weather rain"),
			new QuickAction("Thunder", "weather thunder"), new QuickAction("Clear 1 hour", "weather clear 3600")
		});
		y += 34;
		labels.add(new Label("SERVER MAINTENANCE", left + 5, y, width - 10, 14));
		y += 16;
		addActionRow(left, y, buttonWidth, gap, new QuickAction[]{
			new QuickAction("Save world", "save-all flush"), new QuickAction("Whitelist on", "whitelist on"),
			new QuickAction("Whitelist off", "whitelist off"), new QuickAction("List players", "list")
		});
	}

	private void buildServerOptions() {
		addBoolean("Hardcore", "Locks the world to the hardest survival rules after death. Use carefully on an existing world.", draft.hardcore,
			value -> draft.hardcore = value);
		addBoolean("Nether", "Allows players to travel to the Nether dimension.", draft.allowNether, value -> draft.allowNether = value);
		addBoolean("Structures", "Allows villages, strongholds, monuments, and other structures during new chunk generation.", draft.generateStructures,
			value -> draft.generateStructures = value);
		addBoolean("Spawn animals", "Allows passive animals to spawn naturally.", draft.spawnAnimals, value -> draft.spawnAnimals = value);
		addBoolean("Spawn monsters", "Allows hostile monsters to spawn naturally.", draft.spawnMonsters, value -> draft.spawnMonsters = value);
		addBoolean("Spawn NPCs", "Allows villagers and similar non-player characters to spawn.", draft.spawnNpcs, value -> draft.spawnNpcs = value);
		addBoolean("Server list status", "Lets multiplayer server lists query the server name, player count, and icon.", draft.enableStatus,
			value -> draft.enableStatus = value);
		addBoolean("Hide player list", "Hides the sample of online player names from multiplayer server-list status.", draft.hideOnlinePlayers,
			value -> draft.hideOnlinePlayers = value);
		addBoolean("Prevent proxies", "Rejects players whose ISP and Mojang authentication network do not match. This can block legitimate VPN users.", draft.preventProxyConnections,
			value -> draft.preventProxyConnections = value);
		addBoolean("Console to operators", "Shows server console command output to online operators.", draft.broadcastConsoleToOps,
			value -> draft.broadcastConsoleToOps = value);
		addSlider("Idle kick", "Minutes before an inactive player is removed. Zero disables idle kicking.", draft.playerIdleTimeout, 0, 120, 5,
			value -> Component.literal((int)Math.round(value) == 0 ? "Off" : (int)Math.round(value) + " min"), value -> draft.playerIdleTimeout = (int)Math.round(value));
		addSlider("Entity range", "Percentage multiplier for how far entities are sent to clients. Higher values increase network use.", draft.entityBroadcastRangePercentage, 25, 300, 25,
			value -> Component.literal((int)Math.round(value) + "%"), value -> draft.entityBroadcastRangePercentage = (int)Math.round(value));
		addSlider("Packet rate limit", "Maximum packets allowed per second before a client is kicked. Zero disables this limit.", draft.rateLimit, 0, 1000, 50,
			value -> Component.literal((int)Math.round(value) == 0 ? "Off" : Integer.toString((int)Math.round(value))), value -> draft.rateLimit = (int)Math.round(value));
		addSlider("Operator level", "Default command permission level granted to operators, from one to four.", draft.opPermissionLevel, 1, 4, 1,
			value -> Component.literal(Integer.toString((int)Math.round(value))), value -> draft.opPermissionLevel = (int)Math.round(value));
		addSlider("Function level", "Permission level used by datapack functions, from one to four.", draft.functionPermissionLevel, 1, 4, 1,
			value -> Component.literal(Integer.toString((int)Math.round(value))), value -> draft.functionPermissionLevel = (int)Math.round(value));
	}

	private void addActionRow(int left, int y, int width, int gap, QuickAction[] actions) {
		for (int index = 0; index < actions.length; index++) {
			QuickAction action = actions[index];
			Button button = addButton(action.label, left + index * (width + gap), y, width,
				() -> sendQuickCommand(action.command, action.label), "Runs: /" + action.command);
			button.active = status.state() == HostStatus.State.ONLINE;
		}
	}

	private void sendQuickCommand(String command, String confirmation) {
		if (service.sendConsoleCommand(command)) {
			HostService.showToast(confirmation, "/" + command);
		}
	}

	private void buildPerformance() {
		int left = contentLeft();
		int width = contentWidth();
		addButton("Reset performance settings to EverHost defaults", left, HEADER_HEIGHT + 51, width, () -> {
			draft.resetPerformanceDefaults();
			HostService.showToast("Performance defaults restored", "Choose Save to keep these values.");
			this.rebuildWidgets();
		}, "Restores only the settings on this Performance page. Your profile, world, access, controls, address, and backups are untouched.");
		optionTop = HEADER_HEIGHT + 75;
		addBoolean("DH", "Lets compatible players request far-away LOD terrain directly from the server instead of rebuilding it only by exploring nearby chunks.", draft.distantHorizons,
			value -> draft.distantHorizons = value);
		addSlider("Live", "Radius where distant terrain changes are pushed to players in real time. Higher values keep more far terrain current but use more CPU and upload bandwidth.", draft.dhRealTimeRadius, 64, 512, 64,
			value -> Component.literal((int)Math.round(value) + "c"), value -> draft.dhRealTimeRadius = (int)Math.round(value));
		addSlider("Reach", "Farthest distance the server may generate and synchronize Distant Horizons terrain. 4096 chunks is the supported maximum and is much farther than vanilla view distance.", draft.dhLodDistance, 512, 4096, 256,
			value -> Component.literal((int)Math.round(value) + "c"), value -> draft.dhLodDistance = (int)Math.round(value));
		addSlider("Rate", "Maximum Distant Horizons upload speed for each player. Higher values fill distant terrain sooner but can compete with normal movement and chunk traffic.", draft.dhPlayerBandwidthKbps, 250, 8000, 250,
			value -> Component.literal(String.format(Locale.ROOT, "%.1fMB/s", value / 1000.0)), value -> draft.dhPlayerBandwidthKbps = (int)Math.round(value));
		addBoolean("Adaptive", "Lets Distant Horizons reduce its transfer speed when a player's connection or game cannot keep up, helping prevent timeouts and delayed normal chunks.", draft.dhAdaptiveTransfer,
			value -> draft.dhAdaptiveTransfer = value);
		addSlider("Threads", "CPU worker threads available for building and serving LOD terrain. Sixteen suits your i9 while leaving capacity for Minecraft's main server thread.", draft.dhThreads, 2, 24, 1,
			value -> Component.literal(Integer.toString((int)Math.round(value))), value -> draft.dhThreads = (int)Math.round(value));
		addEnum("Network", "Auto protects normal chunks and movement when upload or server ticks fall behind. Slow Wi-Fi is stricter, Balanced follows your limits, and Fast favors maximum throughput.", draft.networkMode,
			EverHostConfig.NetworkMode.values(), EverHostDashboardScreen::enumText, value -> draft.networkMode = value);
		addBoolean("Lag Guard", "Watches server tick time and player delay. Under sustained trouble it gradually lowers server view and simulation distance, then restores your saved settings after recovery.", draft.lagGuard,
			value -> draft.lagGuard = value);
		addSlider("Tick warning", "Lag Guard starts reacting when average server work exceeds this many milliseconds per tick. Minecraft needs 50 ms or less to maintain 20 ticks per second.", draft.lagWarnMspt, 50, 200, 5,
			value -> Component.literal((int)Math.round(value) + " ms"), value -> draft.lagWarnMspt = (int)Math.round(value));
		addSlider("Ping warning", "Lag Guard treats a player's connection as congested above this round-trip delay and reduces new chunk traffic to help movement packets get through.", draft.lagPingMs, 100, 1500, 50,
			value -> Component.literal((int)Math.round(value) + " ms"), value -> draft.lagPingMs = (int)Math.round(value));
		addSlider("Minimum view", "Lowest server view distance Lag Guard may use during severe trouble. It never changes the owner's saved normal distance.", draft.lagMinViewDistance, 2, 12, 1,
			value -> Component.literal((int)Math.round(value) + "c"), value -> draft.lagMinViewDistance = (int)Math.round(value));
		addSlider("Minimum sim", "Lowest entity and redstone simulation distance Lag Guard may use temporarily. Four chunks is a practical emergency floor.", draft.lagMinSimulationDistance, 2, 12, 1,
			value -> Component.literal((int)Math.round(value) + "c"), value -> draft.lagMinSimulationDistance = (int)Math.round(value));
		addSlider("Recovery", "Healthy time required before Lag Guard restores one performance level. A gradual recovery avoids a repeating lag-on, lag-off cycle.", draft.lagRecoverySeconds, 10, 120, 5,
			value -> Component.literal((int)Math.round(value) + " sec"), value -> draft.lagRecoverySeconds = (int)Math.round(value));
		addBoolean("Join protection", "Temporarily reduces chunk pressure when a player first joins, which is when the server usually sends its largest burst of world data.", draft.lagJoinProtection,
			value -> draft.lagJoinProtection = value);
		addBoolean("Delay notice", "Shows players with this EverHost build a small honest notice when updates are delayed or Lag Guard is actively reducing chunk work.", draft.lagNotices,
			value -> draft.lagNotices = value);
	}

	private void buildAccess() {
		addBoolean("Online auth", "Verifies every joining player with Mojang. Keep this on to prevent username impersonation.", draft.onlineMode,
			value -> draft.onlineMode = value);
		addBoolean("Secure profiles", "Requires Mojang-signed player profiles. Keep this on for normal authenticated play.", draft.secureProfiles,
			value -> draft.secureProfiles = value);
		addBoolean("Use whitelist", "Only names in whitelist.json may join. Operators are not automatically whitelisted.", draft.whitelist,
			value -> draft.whitelist = value);
		addBoolean("Enforce whitelist", "Immediately removes connected players who are no longer on the whitelist when it is reloaded.", draft.enforceWhitelist,
			value -> draft.enforceWhitelist = value);
		addBoolean("Sync operators", "Copies the modpack's existing ops.json into EverHost before every start, preserving your operator level and trusted admins.", draft.syncOps,
			value -> draft.syncOps = value);
		addBoolean("Sync whitelist", "Copies the modpack's existing whitelist.json into EverHost before every start.", draft.syncWhitelist,
			value -> draft.syncWhitelist = value);
	}

	private void buildSafety() {
		addSlider("Stop countdown", "Broadcasts a countdown before a manual stop, restart, or EverHost update so connected players have time to prepare.",
			draft.shutdownCountdownSeconds, 5, 15, 1,
			value -> Component.literal((int)Math.round(value) + " seconds"), value -> draft.shutdownCountdownSeconds = (int)Math.round(value));
		addBoolean("Restart after crash", "Automatically relaunches the server after an unexpected exit, up to the configured attempt limit.", draft.autoRestart,
			value -> draft.autoRestart = value);
		addSlider("Restart attempts", "Maximum automatic relaunch attempts within ten minutes before EverHost stays offline for inspection.", draft.restartAttempts, 0, 10, 1,
			value -> Component.literal(Integer.toString((int)Math.round(value))), value -> draft.restartAttempts = (int)Math.round(value));
		addSlider("Stop timeout", "How long EverHost waits for Minecraft to finish saving before terminating a stuck server process.", draft.stopTimeoutSeconds, 15, 180, 5,
			value -> Component.literal((int)Math.round(value) + " seconds"), value -> draft.stopTimeoutSeconds = (int)Math.round(value));
		addBoolean("Backup before start", "Creates a full ZIP backup before every start. Large worlds can take several minutes and use substantial disk space.", draft.backupBeforeStart,
			value -> draft.backupBeforeStart = value);
		addSlider("Backup retention", "Keeps this many newest EverHost ZIP backups and removes older EverHost backups after a successful backup.", draft.backupRetention, 1, 20, 1,
			value -> Component.literal(Integer.toString((int)Math.round(value))), value -> draft.backupRetention = (int)Math.round(value));
		int[] bounds = nextOptionBounds();
		Button backup = addButton("Create backup now", bounds[0], bounds[1], bounds[2], () -> {
			persistDraft();
			service.send(HostService.Action.BACKUP);
		}, "Creates a compressed backup now. The server must be offline so every file belongs to one consistent save state.");
		backup.active = status.state() == HostStatus.State.OFFLINE || status.state() == HostStatus.State.ERROR;
	}

	private void buildIntegrations() {
		addBoolean("Essential notifications", "Uses Essential's public notification API for online, stopped, and error notices. Falls back to a Minecraft toast when unavailable.",
			draft.essentialNotifications, value -> draft.essentialNotifications = value);
		int[] e4mc = nextOptionBounds();
		Button e4mcStatus = addButton(
			LoaderBridge.isModLoaded("e4mc") ? "e4mc: Installed" : "e4mc: Missing",
			e4mc[0], e4mc[1], e4mc[2], () -> {},
			"e4mc creates the encrypted public relay address. EverHost copies the installed e4mc jar into its dedicated server runtime."
		);
		e4mcStatus.active = false;
		int[] essential = nextOptionBounds();
		Button essentialStatus = addButton(
			LoaderBridge.isModLoaded("essential") ? "Essential: Installed" : "Essential: Not detected",
			essential[0], essential[1], essential[2], () -> {},
			"Essential is optional. EverHost can match its notification flow, but Essential's private invite sessions cannot own a separate dedicated server."
		);
		essentialStatus.active = false;
		int[] addressMode = nextOptionBounds();
		String modeLabel = draft.addressMode == EverHostConfig.AddressMode.PLAYIT ? "Permanent Playit" : "Temporary E4MC";
		addButton("Public address: " + modeLabel, addressMode[0], addressMode[1], addressMode[2],
			() -> minecraft.setScreen(new PlayitAddressScreen(this, service)),
			"Choose a temporary E4MC address or set up a permanent Playit address for the selected profile.");
		int[] address = nextOptionBounds();
		Button copy = addButton("Copy public address", address[0], address[1], address[2], service::copyAddress,
			"Copies the currently selected E4MC or Playit address when it is ready.");
		copy.active = !service.shareAddress().isBlank();
		int[] logs = nextOptionBounds();
		addButton("Open server logs", logs[0], logs[1], logs[2], service::openLogs,
			"Opens the full EverHost server console log for troubleshooting e4mc, Fabric, or world startup.");
	}

	private void buildConsole() {
		int left = contentLeft();
		int width = contentWidth();
		int inputY = consoleInputY();
		int gap = 6;
		int sendWidth = 92;
		consoleText = service.currentConsole(600);
		int filterY = consoleFilterY();
		consoleFilterInput = new EditBox(this.font, left + 54, filterY, width - 54 - 150, CONTROL_HEIGHT, Component.literal("Filter console"));
		consoleFilterInput.setMaxLength(120);
		consoleFilterInput.setValue(consoleFilter);
		consoleFilterInput.setHint(Component.literal("errors, player name, mod, message..."));
		consoleFilterInput.setResponder(value -> {
			consoleFilter = value;
			consoleScrollOffset = 0;
		});
		consoleFilterInput.setTooltip(Tooltip.create(Component.literal("Filters the visible console without deleting or changing the log.")));
		this.addRenderableWidget(consoleFilterInput);
		addButton("Clear filter", left + width - 144, filterY, 86, () -> {
			consoleFilter = "";
			consoleFilterInput.setValue("");
			consoleScrollOffset = 0;
		}, "Shows every recent console line again.");
		addButton("Latest", left + width - 52, filterY, 52, () -> consoleScrollOffset = 0,
			"Returns to the newest console output.");
		consoleInput = new EditBox(
			this.font,
			left + 64,
			inputY,
			width - 64 - sendWidth - gap,
			CONTROL_HEIGHT,
			Component.literal("Server command")
		);
		consoleInput.setMaxLength(2048);
		consoleInput.setValue(consoleDraft);
		consoleInput.setHint(Component.literal(
			status.state() == HostStatus.State.ONLINE ? "Enter a command, then press Enter" : "Start the server to use the console"
		));
		consoleInput.setEditable(status.state() == HostStatus.State.ONLINE);
		consoleInput.setResponder(value -> {
			consoleDraft = value;
			updateConsoleSendAvailability();
		});
		consoleInput.setTooltip(Tooltip.create(Component.literal(
			"Runs a command with full dedicated-server console permission. A leading slash is optional. Press Up or Down to browse commands sent during this dashboard session."
		)));
		this.addRenderableWidget(consoleInput);
		consoleSendButton = addButton(
			"Send",
			left + width - sendWidth,
			inputY,
			sendWidth,
			this::submitConsoleCommand,
			"Sends this command directly to the running EverHost server console."
		);
		updateConsoleSendAvailability();
	}

	private void buildFooter() {
		if (page == Page.OVERVIEW || page == Page.CONSOLE) {
			addButton("Close", this.width - 90, this.height - 27, 80, this::onClose, "Returns to the previous screen.");
			return;
		}
		int width = 92;
		int gap = 5;
		int right = this.width - 10;
		addButton("Discard", right - width * 2 - gap, this.height - 27, width, () -> {
			draft = EverHostConfig.load(service.configFile());
			switchPage(Page.OVERVIEW);
		}, "Discards changes made since this dashboard opened and returns to Overview.");
		addButton("Save", right - width, this.height - 27, width, () -> {
			persistDraft();
			switchPage(Page.OVERVIEW);
		}, "Saves every EverHost setting. Server settings take effect on the next start or restart.");
	}

	private void addBoolean(String label, String tooltip, boolean value, Consumer<Boolean> setter) {
		int[] bounds = nextOptionBounds();
		addButton(label + ": " + onOff(value), bounds[0], bounds[1], bounds[2], () -> {
			setter.accept(!value);
			this.rebuildWidgets();
		}, tooltip);
	}

	private <T> void addEnum(String label, String tooltip, T value, T[] values, Function<T, Component> formatter, Consumer<T> setter) {
		int[] bounds = nextOptionBounds();
		addButton(label + ": " + formatter.apply(value).getString(), bounds[0], bounds[1], bounds[2], () -> {
			int index = java.util.Arrays.asList(values).indexOf(value);
			setter.accept(values[(index + 1) % values.length]);
			this.rebuildWidgets();
		}, tooltip);
	}

	private void addSlider(
		String label,
		String tooltip,
		double current,
		double minimum,
		double maximum,
		double step,
		Function<Double, Component> formatter,
		DoubleConsumer setter
	) {
		int[] bounds = nextOptionBounds();
		RangeSlider slider = new RangeSlider(bounds[0], bounds[1], bounds[2], Component.literal(label), current, minimum, maximum, step, formatter, setter);
		slider.setTooltip(Tooltip.create(Component.literal(tooltip)));
		this.addRenderableWidget(slider);
	}

	private void addTextField(String label, String tooltip, String current, int maximumLength, Consumer<String> setter) {
		int[] bounds = nextOptionBounds();
		int fieldLeft = bounds[0] + bounds[2] / 2;
		labels.add(new Label(label, bounds[0] + 4, bounds[1] + 6, Math.max(40, fieldLeft - bounds[0] - 8), 14));
		EditBox box = new EditBox(this.font, fieldLeft, bounds[1], bounds[2] - (fieldLeft - bounds[0]), CONTROL_HEIGHT, Component.literal(label));
		box.setMaxLength(maximumLength);
		box.setValue(current);
		box.setResponder(setter);
		box.setTooltip(Tooltip.create(Component.literal(tooltip)));
		this.addRenderableWidget(box);
	}

	private void addNumberField(String label, String tooltip, int current, Consumer<Integer> setter, int maximumLength) {
		int[] bounds = nextOptionBounds();
		int fieldLeft = bounds[0] + bounds[2] / 2;
		labels.add(new Label(label, bounds[0] + 4, bounds[1] + 6, Math.max(40, fieldLeft - bounds[0] - 8), 14));
		EditBox box = new EditBox(this.font, fieldLeft, bounds[1], bounds[2] - (fieldLeft - bounds[0]), CONTROL_HEIGHT, Component.literal(label));
		box.setMaxLength(maximumLength);
		box.setFilter(value -> value.isEmpty() || value.chars().allMatch(Character::isDigit));
		box.setValue(Integer.toString(current));
		box.setResponder(value -> {
			try {
				if (!value.isBlank()) setter.accept(Integer.parseInt(value));
			} catch (NumberFormatException ignored) {
			}
		});
		box.setTooltip(Tooltip.create(Component.literal(tooltip)));
		this.addRenderableWidget(box);
	}

	private Button addButton(String label, int x, int y, int width, Runnable action, String tooltip) {
		return addButton(label, x, y, width, CONTROL_HEIGHT, action, tooltip);
	}

	private Button addButton(String label, int x, int y, int width, int height, Runnable action, String tooltip) {
		Button button = Button.builder(Component.literal(label), ignored -> action.run())
			.bounds(x, y, width, height)
			.tooltip(Tooltip.create(Component.literal(tooltip)))
			.build();
		this.addRenderableWidget(button);
		return button;
	}

	private int[] nextOptionBounds() {
		int contentWidth = contentWidth();
		int gap = 8;
		int columnWidth = (contentWidth - gap) / 2;
		int column = optionIndex % 2;
		int row = optionIndex / 2;
		optionIndex++;
		return new int[]{contentLeft() + column * (columnWidth + gap), optionTop + row * ROW_GAP, columnWidth};
	}

	private int contentLeft() {
		return SIDEBAR_WIDTH + 14;
	}

	private int contentWidth() {
		return Math.max(220, this.width - contentLeft() - 14);
	}

	private List<WorldChoice> worldChoices() {
		List<WorldChoice> choices = new ArrayList<>();
		Profile selectedProfile = service.selectedProfile(draft).orElse(null);
		if (selectedProfile != null) {
			for (dev.everhost.universal.Models.WorldInfo world : selectedProfile.worlds()) {
				String id = world.path().getFileName().toString();
				String name = id.equals(draft.worldId) && !draft.worldName.isBlank() ? draft.worldName : world.name();
				choices.add(new WorldChoice(id, name));
			}
		}
		if (!draft.worldId.isBlank() && choices.stream().noneMatch(world -> world.id().equals(draft.worldId))) {
			choices.add(0, new WorldChoice(draft.worldId, draft.worldName.isBlank() ? draft.worldId : draft.worldName));
		}
		if (choices.isEmpty()) {
			choices.add(new WorldChoice("", "No worlds found"));
		}
		return choices;
	}

	private void updateStartAvailability() {
		if (startButton != null) {
			startButton.active = draft.eulaAccepted
				&& !draft.profilePath.isBlank()
				&& !draft.worldId.isBlank()
				&& (status.state() == HostStatus.State.OFFLINE || status.state() == HostStatus.State.ERROR);
		}
	}

	private void persistDraft() {
		draft.normalize();
		EverHostClient.saveConfig(draft);
	}

	void reloadSavedConfig() {
		draft = EverHostConfig.load(service.configFile());
		ensureSelectedProfile();
		this.rebuildWidgets();
	}

	private void ensureSelectedProfile() {
		Profile profile = service.selectedProfile(draft).orElse(null);
		if (profile == null) return;
		if (draft.profilePath.isBlank() || !profile.path().toString().equals(draft.profilePath)) {
			applyProfile(profile);
		}
	}

	private void selectProfile(Profile profile) {
		applyProfile(profile);
		persistDraft();
		if (draft.addressMode == EverHostConfig.AddressMode.PLAYIT) {
			service.setupPlayit(profileLabel(profile), profile.path().toString(), draft.port);
		}
		this.rebuildWidgets();
	}

	private static String profileLabel(Profile profile) {
		return profile.name() + " - " + profile.minecraftVersion() + " " + profile.loader().name().toLowerCase(Locale.ROOT);
	}

	private void cycleWorld(List<WorldChoice> worlds) {
		if (worlds.isEmpty()) return;
		int index = 0;
		for (int candidate = 0; candidate < worlds.size(); candidate++) {
			if (worlds.get(candidate).id().equals(draft.worldId)) {
				index = candidate;
				break;
			}
		}
		WorldChoice world = worlds.get((index + 1) % worlds.size());
		draft.worldId = world.id();
		draft.worldName = world.name();
		this.rebuildWidgets();
	}

	private static String onOff(boolean value) {
		return value ? "On" : "Off";
	}

	private static String capitalize(String value) {
		if (value == null || value.isBlank()) return "";
		return Character.toUpperCase(value.charAt(0)) + value.substring(1);
	}

	private void applyProfile(Profile profile) {
		draft.profilePath = profile.path().toString();
		draft.profileName = profile.name();
		draft.minecraftVersion = profile.minecraftVersion();
		draft.loader = profile.loader().name().toLowerCase(Locale.ROOT);
		draft.loaderVersion = profile.loaderVersion();
		draft.modModes.clear();
		draft.pluginStates.clear();
		modPage = 0;
		pluginPage = 0;
		if (profile.worlds().isEmpty()) {
			draft.worldId = "";
			draft.worldName = "";
		} else {
			dev.everhost.universal.Models.WorldInfo world = profile.worlds().get(0);
			draft.worldId = world.path().getFileName().toString();
			draft.worldName = world.name();
		}
	}

	private EverHostConfig.PluginState pluginStateFor(PluginInfo plugin) {
		return draft.pluginStates.getOrDefault(plugin.path().getFileName().toString(), EverHostConfig.PluginState.ENABLED);
	}

	private void cyclePlugin(PluginInfo plugin) {
		EverHostConfig.PluginState next = pluginStateFor(plugin) == EverHostConfig.PluginState.ENABLED
			? EverHostConfig.PluginState.DISABLED : EverHostConfig.PluginState.ENABLED;
		draft.pluginStates.put(plugin.path().getFileName().toString(), next);
		persistDraft();
		this.rebuildWidgets();
	}

	private String pluginRuntimeState(PluginInfo plugin) {
		if (plugin.decision() == Decision.DISABLED) return "Disabled";
		if (plugin.decision() == Decision.BLOCKED) return "Blocked";
		if (containsName(status.failedPlugins(), plugin.name())) return "Failed";
		if (containsName(status.loadedPlugins(), plugin.name())) return "Loaded";
		return plugin.decision() == Decision.WARNING ? "Review" : "Enabled";
	}

	private static boolean containsName(String names, String expected) {
		if (names == null || names.isBlank()) return false;
		for (String name : names.split(",")) if (name.trim().equalsIgnoreCase(expected)) return true;
		return false;
	}

	private EverHostConfig.ModMode modeFor(ModInfo mod) {
		EverHostConfig.ModMode saved = draft.modModes.get(mod.path.getFileName().toString());
		if (saved != null) return saved;
		if (!mod.selected || mod.side == ModSide.CLIENT) return EverHostConfig.ModMode.OFF;
		return mod.clientRequired ? EverHostConfig.ModMode.REQUIRED : EverHostConfig.ModMode.SERVER;
	}

	private void cycleMod(ModInfo mod) {
		EverHostConfig.ModMode next = switch (modeFor(mod)) {
			case OFF -> EverHostConfig.ModMode.SERVER;
			case SERVER -> EverHostConfig.ModMode.REQUIRED;
			case REQUIRED -> EverHostConfig.ModMode.OFF;
		};
		draft.modModes.put(mod.path.getFileName().toString(), next);
		mod.selected = next != EverHostConfig.ModMode.OFF;
		mod.clientRequired = next == EverHostConfig.ModMode.REQUIRED;
		this.rebuildWidgets();
	}

	private String modSummary(List<ModInfo> mods) {
		long server = mods.stream().filter(mod -> modeFor(mod) != EverHostConfig.ModMode.OFF).count();
		long required = mods.stream().filter(mod -> modeFor(mod) == EverHostConfig.ModMode.REQUIRED).count();
		long review = mods.stream().filter(mod -> mod.side == ModSide.UNCERTAIN).count();
		return server + " server, " + required + " required, " + review + " review";
	}

	private void submitConsoleCommand() {
		if (consoleInput == null) {
			return;
		}
		String command = consoleInput.getValue().strip();
		if (!service.sendConsoleCommand(command)) {
			updateConsoleSendAvailability();
			return;
		}
		if (consoleHistory.isEmpty() || !consoleHistory.get(consoleHistory.size() - 1).equals(command)) {
			consoleHistory.add(command);
		}
		consoleHistoryIndex = consoleHistory.size();
		consoleDraft = "";
		consoleInput.setValue("");
		consoleInput.setFocused(true);
		updateConsoleSendAvailability();
	}

	private void updateConsoleSendAvailability() {
		if (consoleSendButton != null) {
			consoleSendButton.active = status.state() == HostStatus.State.ONLINE && !consoleDraft.isBlank();
		}
	}

	private void browseConsoleHistory(int direction) {
		if (consoleInput == null || consoleHistory.isEmpty()) {
			return;
		}
		consoleHistoryIndex = Math.max(0, Math.min(consoleHistory.size(), consoleHistoryIndex + direction));
		String value = consoleHistoryIndex == consoleHistory.size() ? "" : consoleHistory.get(consoleHistoryIndex);
		consoleDraft = value;
		consoleInput.setValue(value);
		consoleInput.setCursorPosition(value.length());
	}

	private int consoleInputY() {
		return this.height - FOOTER_HEIGHT - CONTROL_HEIGHT - 8;
	}

	private int consoleFilterY() {
		return HEADER_HEIGHT + 48;
	}

	private int consoleOutputTop() {
		return consoleFilterY() + CONTROL_HEIGHT + 8;
	}

	private int visibleConsoleLines() {
		return Math.max(3, (consoleInputY() - consoleOutputTop() - 18) / 11);
	}

	private void switchPage(Page next) {
		if (page != next) {
			page = next;
			this.rebuildWidgets();
		}
	}

	private List<String> filteredConsoleLines() {
		String filter = consoleFilter == null ? "" : consoleFilter.strip().toLowerCase(Locale.ROOT);
		if (filter.isBlank()) return consoleText.lines().toList();
		return consoleText.lines().filter(line -> line.toLowerCase(Locale.ROOT).contains(filter)).toList();
	}

	private static String cleanConsoleLine(String line) {
		if (line == null) return "";
		return line.replaceFirst("^\\[[^]]+\\] \\[[^]]+/(TRACE|DEBUG|INFO|WARN|ERROR)\\] \\[[^]]+\\]:\\s*", "[$1] ")
			.replaceFirst("^\\[[^]]+\\] \\[[^]]+/(TRACE|DEBUG|INFO|WARN|ERROR)\\]:\\s*", "[$1] ");
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (page == Page.CONSOLE && consoleInput != null && consoleInput.isFocused()) {
			if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
				submitConsoleCommand();
				return true;
			}
			if (keyCode == GLFW.GLFW_KEY_UP) {
				browseConsoleHistory(-1);
				return true;
			}
			if (keyCode == GLFW.GLFW_KEY_DOWN) {
				browseConsoleHistory(1);
				return true;
			}
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (page == Page.CONSOLE && mouseX >= contentLeft() && mouseX <= contentLeft() + contentWidth()
			&& mouseY >= consoleOutputTop() && mouseY <= consoleInputY()) {
			consoleScrollOffset = Math.max(0, consoleScrollOffset + (amount > 0 ? 3 : -3));
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, amount);
	}

	@Override
	public void tick() {
		super.tick();
		pollTicks++;
		if (pollTicks >= 10) {
			pollTicks = 0;
			HostStatus updated = service.status();
			boolean changed = updated.state() != renderedState || !updated.domain().equals(renderedDomain)
				|| !updated.message().equals(status.message());
		status = updated;
			if (changed) {
				renderedState = status.state();
				renderedDomain = status.domain();
				this.rebuildWidgets();
			} else if (page == Page.CONSOLE) {
				String updatedConsole = service.currentConsole(600);
				if (!updatedConsole.equals(consoleText)) {
					consoleText = updatedConsole;
				}
			}
		}
	}

	@Override
	public void onClose() {
		this.minecraft.setScreen(parent);
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(0, 0, this.width, this.height, BACKGROUND);
		renderChrome(graphics);
		if (page == Page.OVERVIEW) renderOverview(graphics);
		else if (page == Page.CONSOLE) renderConsole(graphics);
		else renderSettingsHeader(graphics);
		for (Label label : labels) {
			String text = this.font.plainSubstrByWidth(label.text(), Math.max(20, label.width()));
			drawText(graphics, text, label.x(), label.y(), MUTED, false);
		}
		super.render(graphics, mouseX, mouseY, delta);
		int x = contentLeft();
		int y = HEADER_HEIGHT + 29;
		if (page == Page.OVERVIEW && mouseX >= x && mouseX < x + contentWidth() && mouseY >= y && mouseY < y + 9) {
			graphics.renderTooltip(this.font, this.font.split(Component.literal(status.message()), 280), mouseX, mouseY);
		}
	}

	private void renderChrome(GuiGraphics graphics) {
		graphics.fill(0, 0, this.width, HEADER_HEIGHT, HEADER);
		graphics.fill(0, HEADER_HEIGHT, SIDEBAR_WIDTH, this.height - FOOTER_HEIGHT, SIDEBAR);
		graphics.fill(0, this.height - FOOTER_HEIGHT, this.width, this.height, HEADER);
		graphics.fill(0, HEADER_HEIGHT - 1, this.width, HEADER_HEIGHT, BORDER);
		graphics.fill(SIDEBAR_WIDTH - 1, HEADER_HEIGHT, SIDEBAR_WIDTH, this.height - FOOTER_HEIGHT, BORDER);
		graphics.fill(0, this.height - FOOTER_HEIGHT, this.width, this.height - FOOTER_HEIGHT + 1, BORDER);
		graphics.fill(12, 10, 16, 38, ACCENT);
		drawText(graphics, "EVERHOST", 24, 14, TEXT, false);
		drawText(graphics, "Persistent server control", 24, 27, MUTED, false);
		drawText(graphics, "SERVER", 10, HEADER_HEIGHT + 4, ACCENT, false);
	}

	private void renderOverview(GuiGraphics graphics) {
		int left = contentLeft();
		int width = contentWidth();
		drawText(graphics, "OVERVIEW", left, HEADER_HEIGHT + 14, TEXT, false);
		drawText(graphics, status.message(), left, HEADER_HEIGHT + 27, status.state() == HostStatus.State.ERROR ? ERROR : MUTED, false);
		drawPanel(graphics, left, HEADER_HEIGHT + 48, width, Math.max(120, this.height - FOOTER_HEIGHT - HEADER_HEIGHT - 58));
		String profile = draft.profileName.isBlank() ? "No CurseForge profile selected" : draft.profileName + " | " + draft.minecraftVersion + " | " + draft.loader;
		String world = status.worldName().isBlank() ? (draft.worldName.isBlank() ? "Not selected" : draft.worldName) : status.worldName();
		String uptime = status.startedAt() > 0L && status.state().isBusyOrOnline() ? formatUptime(System.currentTimeMillis() - status.startedAt()) : "Offline";
		int infoY = Math.min(this.height - FOOTER_HEIGHT - 52, HEADER_HEIGHT + 181);
		drawText(graphics, "PROFILE", left + 10, infoY, ACCENT, false);
		drawText(graphics, this.font.plainSubstrByWidth(profile, width - 20), left + 10, infoY + 12, TEXT, false);
		String metrics = world + "  |  " + status.playerCount() + "/" + draft.maxPlayers + " players  |  " + uptime;
		drawText(graphics, this.font.plainSubstrByWidth(metrics, width - 20), left + 10, infoY + 25, MUTED, false);
		String players = status.players().isBlank() ? "No players connected" : status.players();
		drawText(graphics, this.font.plainSubstrByWidth(players, width - 20), left + 10, infoY + 37, status.players().isBlank() ? MUTED : ONLINE, false);
	}

	private void renderSettingsHeader(GuiGraphics graphics) {
		int left = contentLeft();
		drawText(graphics, page.title.toUpperCase(Locale.ROOT), left, HEADER_HEIGHT + 15, TEXT, false);
		String detail = page == Page.MODS
			? modSummary(service.selectedMods(draft))
			: status.state().isBusyOrOnline() ? "Server changes apply after restart" : "Ready for the next start";
		drawText(graphics, detail, left, HEADER_HEIGHT + 29, MUTED, false);
		drawPanel(graphics, left, HEADER_HEIGHT + 47, contentWidth(), Math.max(80, this.height - FOOTER_HEIGHT - HEADER_HEIGHT - 57));
	}

	private void renderConsole(GuiGraphics graphics) {
		int left = contentLeft();
		int top = HEADER_HEIGHT + 14;
		int width = contentWidth();
		drawText(graphics, "SERVER CONSOLE", left, top, TEXT, false);
		String connection = status.state() == HostStatus.State.ONLINE
			? "CONNECTED TO " + status.worldName().toUpperCase(Locale.ROOT)
			: "OFFLINE";
		drawText(graphics, connection, left + width - this.font.width(connection), top, status.state() == HostStatus.State.ONLINE ? ONLINE : MUTED, false);
		drawText(graphics, "FILTER", left + 4, consoleFilterY() + 5, MUTED, false);
		int outputTop = consoleOutputTop();
		int outputBottom = consoleInputY() - 10;
		graphics.fill(left, outputTop, left + width, outputBottom, 0xFF090C10);
		graphics.fill(left, outputTop, left + width, outputTop + 1, BORDER);
		graphics.fill(left, outputBottom - 1, left + width, outputBottom, BORDER);
		List<String> lines = filteredConsoleLines();
		int visible = visibleConsoleLines();
		int maximumOffset = Math.max(0, lines.size() - visible);
		consoleScrollOffset = Math.max(0, Math.min(consoleScrollOffset, maximumOffset));
		int end = Math.max(0, lines.size() - consoleScrollOffset);
		int first = Math.max(0, end - visible);
		int y = outputTop + 6;
		int maximumWidth = width - 14;
		graphics.enableScissor(left, outputTop, left + width, outputBottom);
		for (int index = first; index < end; index++) {
			String line = cleanConsoleLine(lines.get(index));
			String fitted = this.font.plainSubstrByWidth(line, maximumWidth);
			int color = line.startsWith("> ") ? ACCENT
				: line.contains("ERROR") ? ERROR
				: line.contains("WARN") ? WARNING
				: 0xFFC4CBD3;
			drawText(graphics, fitted, left + 7, y, color, false);
			y += 10;
			if (y > outputBottom - 10) break;
		}
		graphics.disableScissor();
		drawText(graphics, "COMMAND", left + 4, consoleInputY() + 5, status.state() == HostStatus.State.ONLINE ? ACCENT : MUTED, false);
		String count = lines.size() + (lines.size() == 1 ? " line" : " lines")
			+ (consoleScrollOffset > 0 ? "  |  viewing older output" : "  |  following latest");
		int countRight = left + width - this.font.width(connection) - 18;
		drawText(graphics, count, Math.max(left + 180, countRight - this.font.width(count)), top, MUTED, false);
	}

	private void drawPanel(GuiGraphics graphics, int x, int y, int width, int height) {
		graphics.fill(x, y, x + width, y + height, PANEL);
		graphics.fill(x, y, x + width, y + 1, BORDER);
		graphics.fill(x, y + height - 1, x + width, y + height, BORDER);
		graphics.fill(x, y, x + 1, y + height, BORDER);
		graphics.fill(x + width - 1, y, x + width, y + height, BORDER);
	}

	private void drawMetric(GuiGraphics graphics, String label, String value, int x, int y) {
		drawText(graphics, label, x, y, MUTED, false);
		drawText(graphics, this.font.plainSubstrByWidth(value, Math.max(50, contentWidth() / 3 - 18)), x, y + 13, TEXT, false);
	}

	private void drawText(GuiGraphics graphics, String text, int x, int y, int color, boolean shadow) {
		graphics.drawString(this.font, Component.literal(text), x, y, color, shadow);
	}

	private void drawCenteredText(GuiGraphics graphics, String text, int centerX, int y, int color) {
		graphics.drawCenteredString(this.font, Component.literal(text), centerX, y, color);
	}

	private static Component enumText(Object value) {
		String lower = ((Enum<?>)value).name().toLowerCase(Locale.ROOT).replace('_', ' ');
		return Component.literal(Character.toUpperCase(lower.charAt(0)) + lower.substring(1));
	}

	private static String stateLabel(HostStatus.State state) {
		return switch (state) {
			case OFFLINE -> "OFFLINE";
			case PROVISIONING -> "PREPARING";
			case BACKING_UP -> "BACKING UP";
			case STARTING -> "STARTING";
			case ONLINE -> "ONLINE";
			case STOPPING -> "STOPPING";
			case ERROR -> "ERROR";
		};
	}

	private static int stateColor(HostStatus.State state) {
		return switch (state) {
			case ONLINE -> ONLINE;
			case PROVISIONING, BACKING_UP, STARTING, STOPPING -> WARNING;
			case ERROR -> ERROR;
			case OFFLINE -> MUTED;
		};
	}

	private static String formatUptime(long milliseconds) {
		long seconds = Math.max(0L, milliseconds / 1000L);
		long hours = seconds / 3600L;
		long minutes = seconds % 3600L / 60L;
		return hours > 0 ? hours + "h " + minutes + "m" : minutes + "m " + seconds % 60L + "s";
	}

	private void openSearchTarget(SearchTarget target) {
		page = target.page;
		if (target.worldSection != null) worldSection = target.worldSection;
		if (target.gameRuleName != null) {
			int index = 0;
			for (int candidate = 0; candidate < GAME_RULES.size(); candidate++) {
				if (GAME_RULES.get(candidate).name.equals(target.gameRuleName)) {
					index = candidate;
					break;
				}
			}
			int rows = Math.max(3, (this.height - FOOTER_HEIGHT - (HEADER_HEIGHT + 80) - 28) / 23);
			gameRulePage = index / rows;
		}
		this.rebuildWidgets();
	}

	private static List<SearchTarget> searchTargets() {
		List<SearchTarget> targets = new ArrayList<>(List.of(
			new SearchTarget("Overview and server controls", "Start, stop, restart, backup, world, players, address", Page.OVERVIEW, null, null),
			new SearchTarget("CurseForge profiles", "Select or rescan the profile being hosted", Page.PROFILES, null, null),
			new SearchTarget("Server mods", "Required and server-only mod selection", Page.MODS, null, null),
			new SearchTarget("Installed plugins", "Only jars in this profile's plugins folder", Page.PLUGINS, null, null),
			new SearchTarget("Memory and server port", "Minimum RAM, maximum RAM, port, player limit, MOTD", Page.HOSTING, null, null),
			new SearchTarget("Automatic startup", "Start with Minecraft or Windows", Page.HOSTING, null, null),
			new SearchTarget("World settings", "Game mode, difficulty, view distance, simulation, PvP, flight", Page.WORLD, WorldSection.SETTINGS, null),
			new SearchTarget("Player actions and give item", "Set everyone to a mode, heal, feed, clear effects, give", Page.WORLD, WorldSection.PLAYERS, null),
			new SearchTarget("Time, weather, saves, whitelist", "One-click live server commands", Page.WORLD, WorldSection.QUICK_ACTIONS, null),
			new SearchTarget("Advanced server options", "Hardcore, Nether, structures, spawning, status privacy, idle kick, entity range, rate limit, operator permissions", Page.WORLD, WorldSection.SERVER_OPTIONS, null),
			new SearchTarget("Performance defaults", "Reset performance settings only", Page.PERFORMANCE, null, null),
			new SearchTarget("Distant Horizons performance", "LOD distance, live radius, rate, adaptive transfer, threads", Page.PERFORMANCE, null, null),
			new SearchTarget("Lag Guard and network", "Tick warning, ping warning, recovery, join protection", Page.PERFORMANCE, null, null),
			new SearchTarget("Access and whitelist", "Online authentication, secure profiles, operators", Page.ACCESS, null, null),
			new SearchTarget("Backups and crash recovery", "Restart attempts, stop countdown, retention", Page.SAFETY, null, null),
			new SearchTarget("Permanent Playit address", "Stable address friends can save", Page.INTEGRATIONS, null, null),
			new SearchTarget("Simple Voice Chat", "Optional UDP 24454 tunnel and voice_host setup", Page.INTEGRATIONS, null, null),
			new SearchTarget("Console", "Filter logs, inspect errors, send server commands", Page.CONSOLE, null, null)
		));
		for (GameRuleSpec rule : GAME_RULES) {
			targets.add(new SearchTarget("Game rule: " + rule.name, rule.description,
				Page.WORLD, WorldSection.GAME_RULES, rule.name));
		}
		return targets;
	}

	private static String pageTooltip(Page page) {
		return switch (page) {
			case OVERVIEW -> "Live state, selected world, public address, players, and primary server controls.";
			case PROFILES -> "Choose and rescan any supported local CurseForge profile.";
			case MODS -> "Review server-compatible mods and choose what joining players must install.";
			case PLUGINS -> "Shows only plugin jars installed in the selected profile's plugins folder, with enable and runtime status controls.";
			case HOSTING -> "Memory, port, capacity, startup, and server-list settings.";
			case WORLD -> "World settings, every Java 1.20.1 game rule, player actions, quick commands, and advanced server options.";
			case PERFORMANCE -> "Distant Horizons, network limits, adaptive chunk protection, and server delay notices.";
			case ACCESS -> "Authentication, secure profiles, whitelist behavior, operators, and access syncing.";
			case SAFETY -> "Graceful stop, crash recovery, backups, and retention.";
			case INTEGRATIONS -> "Essential notifications, e4mc, public address, and diagnostic logs.";
			case CONSOLE -> "Live dedicated-server output and full-permission command input.";
		};
	}

	private enum Page {
		OVERVIEW("Overview", "screen.everhost.tab.overview", "tooltip.everhost.tab.overview"),
		PROFILES("Profiles", "screen.everhost.tab.profiles", "tooltip.everhost.tab.profiles"),
		MODS("Mods", "screen.everhost.tab.mods", "tooltip.everhost.tab.mods"),
		PLUGINS("Plugins", "screen.everhost.tab.plugins", "tooltip.everhost.tab.plugins"),
		HOSTING("Hosting", "screen.everhost.tab.hosting", "tooltip.everhost.tab.hosting"),
		WORLD("World", "screen.everhost.tab.world", "tooltip.everhost.tab.world"),
		PERFORMANCE("Performance", "screen.everhost.tab.performance", "tooltip.everhost.tab.performance"),
		ACCESS("Access", "screen.everhost.tab.access", "tooltip.everhost.tab.access"),
		SAFETY("Safety", "screen.everhost.tab.safety", "tooltip.everhost.tab.safety"),
		INTEGRATIONS("Integrations", "screen.everhost.tab.integrations", "tooltip.everhost.tab.integrations"),
		CONSOLE("Console", "screen.everhost.tab.console", "tooltip.everhost.tab.console");

		private final String title;
		private final String key;
		private final String tooltipKey;

		Page(String title, String key, String tooltipKey) {
			this.title = title;
			this.key = key;
			this.tooltipKey = tooltipKey;
		}
	}

	private enum WorldSection {
		SETTINGS("Settings", "Default game mode, difficulty, view and simulation distance, PvP, flight, command blocks, and spawn protection."),
		GAME_RULES("Game rules", "Every game rule included in Minecraft Java 1.20.1, with live controls."),
		PLAYERS("Players", "One-click player modes, healing, feeding, effects, spawn points, and item giving."),
		QUICK_ACTIONS("Quick actions", "One-click time, weather, saving, whitelist, and player-list commands."),
		SERVER_OPTIONS("Server options", "Hardcore, dimensions, spawning, status privacy, idle kick, entity range, and permission levels.");

		private final String title;
		private final String tooltip;

		WorldSection(String title, String tooltip) {
			this.title = title;
			this.tooltip = tooltip;
		}
	}

	private static final List<GameRuleSpec> GAME_RULES = List.of(
		new GameRuleSpec("announceAdvancements", "Announce player advancements", false, "true"),
		new GameRuleSpec("blockExplosionDropDecay", "Reduce drops from block explosions", false, "true"),
		new GameRuleSpec("commandBlockOutput", "Show command block output", false, "true"),
		new GameRuleSpec("disableElytraMovementCheck", "Relax Elytra movement checks", false, "false"),
		new GameRuleSpec("disableRaids", "Prevent new raids", false, "false"),
		new GameRuleSpec("doDaylightCycle", "Advance the day and night cycle", false, "true"),
		new GameRuleSpec("doEntityDrops", "Let non-mob entities drop items", false, "true"),
		new GameRuleSpec("doFireTick", "Allow fire to spread and extinguish", false, "true"),
		new GameRuleSpec("doImmediateRespawn", "Respawn without the death screen", false, "false"),
		new GameRuleSpec("doInsomnia", "Allow phantoms to spawn", false, "true"),
		new GameRuleSpec("doLimitedCrafting", "Require unlocked recipes to craft", false, "false"),
		new GameRuleSpec("doMobLoot", "Let mobs drop items and experience", false, "true"),
		new GameRuleSpec("doMobSpawning", "Allow natural mob spawning", false, "true"),
		new GameRuleSpec("doPatrolSpawning", "Allow pillager patrols", false, "true"),
		new GameRuleSpec("doTileDrops", "Let broken blocks drop items", false, "true"),
		new GameRuleSpec("doTraderSpawning", "Allow wandering traders", false, "true"),
		new GameRuleSpec("doVinesSpread", "Allow vines to spread", false, "true"),
		new GameRuleSpec("doWardenSpawning", "Allow wardens to spawn", false, "true"),
		new GameRuleSpec("doWeatherCycle", "Allow weather to change", false, "true"),
		new GameRuleSpec("drowningDamage", "Players take drowning damage", false, "true"),
		new GameRuleSpec("fallDamage", "Players take fall damage", false, "true"),
		new GameRuleSpec("fireDamage", "Players take fire damage", false, "true"),
		new GameRuleSpec("forgiveDeadPlayers", "Angry neutral mobs forgive dead players", false, "true"),
		new GameRuleSpec("freezeDamage", "Players take freezing damage", false, "true"),
		new GameRuleSpec("globalSoundEvents", "Broadcast global sound events", false, "true"),
		new GameRuleSpec("keepInventory", "Keep inventory and experience after death", false, "false"),
		new GameRuleSpec("lavaSourceConversion", "Create new lava source blocks", false, "false"),
		new GameRuleSpec("logAdminCommands", "Write admin commands to the server log", false, "true"),
		new GameRuleSpec("maxCommandChainLength", "Maximum command blocks executed in one chain", true, "65536"),
		new GameRuleSpec("maxEntityCramming", "Entities allowed in one space before damage", true, "24"),
		new GameRuleSpec("mobExplosionDropDecay", "Reduce drops from mob explosions", false, "true"),
		new GameRuleSpec("mobGriefing", "Allow mobs to change blocks and pick up items", false, "true"),
		new GameRuleSpec("naturalRegeneration", "Regenerate health when well fed", false, "true"),
		new GameRuleSpec("playersSleepingPercentage", "Percent of players needed to skip night", true, "100"),
		new GameRuleSpec("randomTickSpeed", "Random block ticks per chunk section", true, "3"),
		new GameRuleSpec("reducedDebugInfo", "Limit information on the debug screen", false, "false"),
		new GameRuleSpec("sendCommandFeedback", "Show command feedback to players", false, "true"),
		new GameRuleSpec("showDeathMessages", "Show player death messages", false, "true"),
		new GameRuleSpec("snowAccumulationHeight", "Maximum snow layers from weather", true, "1"),
		new GameRuleSpec("spawnRadius", "New-player spawn radius in blocks", true, "10"),
		new GameRuleSpec("spectatorsGenerateChunks", "Let spectators generate new chunks", false, "true"),
		new GameRuleSpec("tntExplosionDropDecay", "Reduce drops from TNT explosions", false, "false"),
		new GameRuleSpec("universalAnger", "Angry neutral mobs target nearby players", false, "false"),
		new GameRuleSpec("waterSourceConversion", "Create new water source blocks", false, "true")
	);

	private record GameRuleSpec(String name, String description, boolean numeric, String defaultValue) {}
	private record QuickAction(String label, String command) {}
	private record SearchTarget(String title, String description, Page page, WorldSection worldSection, String gameRuleName) {}

	private static final class FeatureSearchScreen extends Screen {
		private final EverHostDashboardScreen dashboard;
		private EditBox search;
		private String query = "";

		private FeatureSearchScreen(EverHostDashboardScreen dashboard) {
			super(Component.literal("Search EverHost"));
			this.dashboard = dashboard;
		}

		@Override
		protected void init() {
			int left = Math.max(24, this.width / 2 - 280);
			int width = Math.min(560, this.width - 48);
			search = new EditBox(this.font, left, 48, width - 86, 20, Component.literal("Search EverHost"));
			search.setMaxLength(120);
			search.setValue(query);
			search.setHint(Component.literal("Search settings, actions, plugins, voice chat, or game rules"));
			search.setResponder(value -> query = value);
			addRenderableWidget(search);
			addRenderableWidget(Button.builder(Component.literal("Search"), ignored -> rebuildWidgets())
				.bounds(left + width - 80, 48, 80, 20).build());
			setInitialFocus(search);

			String needle = query.strip().toLowerCase(Locale.ROOT);
			List<SearchTarget> matches = searchTargets().stream()
				.filter(target -> needle.isBlank() || (target.title + " " + target.description).toLowerCase(Locale.ROOT).contains(needle))
				.limit(Math.max(4, (this.height - 116) / 24)).toList();
			int y = 86;
			for (SearchTarget target : matches) {
				String label = target.page.title + "  >  " + target.title;
				addRenderableWidget(Button.builder(Component.literal(font.plainSubstrByWidth(label, width - 12)), ignored -> {
					minecraft.setScreen(dashboard);
					dashboard.openSearchTarget(target);
				}).bounds(left, y, width, 20).tooltip(Tooltip.create(Component.literal(target.description))).build());
				y += 24;
			}
			addRenderableWidget(Button.builder(Component.literal("Back"), ignored -> onClose())
				.bounds(this.width - 94, this.height - 28, 84, 20).build());
		}

		@Override
		public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
			if (search != null && search.isFocused() && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)) {
				rebuildWidgets();
				return true;
			}
			return super.keyPressed(keyCode, scanCode, modifiers);
		}

		@Override
		public void onClose() {
			minecraft.setScreen(dashboard);
		}

		@Override
		public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
			graphics.fill(0, 0, width, height, BACKGROUND);
			int left = Math.max(24, this.width / 2 - 280);
			int panelWidth = Math.min(560, this.width - 48);
			graphics.fill(left - 10, 34, left + panelWidth + 10, this.height - 38, PANEL);
			graphics.drawString(font, Component.literal("SEARCH ALL EVERHOST FEATURES"), left, 18, ACCENT, false);
			graphics.drawString(font, Component.literal(query.isBlank() ? "Popular destinations" : "Results for: " + query), left, 74, MUTED, false);
			super.render(graphics, mouseX, mouseY, delta);
		}
	}

	private record Label(String text, int x, int y, int width, int height) {
		private Label(String text, int x, int y) {
			this(text, x, y, 0, 14);
		}
	}
	private record WorldChoice(String id, String name) {}

	private static final class RangeSlider extends AbstractSliderButton {
		private final Component label;
		private final double minimum;
		private final double maximum;
		private final double step;
		private final Function<Double, Component> formatter;
		private final DoubleConsumer setter;

		private RangeSlider(
			int x,
			int y,
			int width,
			Component label,
			double current,
			double minimum,
			double maximum,
			double step,
			Function<Double, Component> formatter,
			DoubleConsumer setter
		) {
			super(x, y, width, CONTROL_HEIGHT, CommonComponents.EMPTY, (current - minimum) / (maximum - minimum));
			this.label = label;
			this.minimum = minimum;
			this.maximum = maximum;
			this.step = step;
			this.formatter = formatter;
			this.setter = setter;
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			setMessage(CommonComponents.optionNameValue(label, formatter.apply(actualValue())));
		}

		@Override
		protected void applyValue() {
			setter.accept(actualValue());
		}

		private double actualValue() {
			double raw = minimum + value * (maximum - minimum);
			double rounded = Math.round(raw / step) * step;
			return Math.max(minimum, Math.min(maximum, rounded));
		}
	}
}
