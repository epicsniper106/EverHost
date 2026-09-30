package dev.everhost.client;

import dev.everhost.plugins.PluginCatalog.CatalogEntry;
import dev.everhost.plugins.PluginSupport.BridgePlan;
import dev.everhost.plugins.PluginSupport.Decision;
import dev.everhost.plugins.PluginSupport.PluginInfo;
import dev.everhost.plugins.PluginSupport.ScanResult;
import dev.everhost.universal.Models.ModInfo;
import dev.everhost.universal.Models.ModSide;
import dev.everhost.universal.Models.Profile;
import java.net.URI;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
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
	private int pollTicks;
	private int modPage;
	private int pluginPage;
	private int profilePage = -1;
	private final List<Label> labels = new ArrayList<>();
	private Button startButton;
	private Button consoleSendButton;
	private Button eulaCheckbox;
	private EditBox consoleInput;
	private String consoleText = "";
	private String consoleDraft = "";
	private final List<String> consoleHistory = new ArrayList<>();
	private int consoleHistoryIndex;

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
	}

	@Override
	protected void init() {
		labels.clear();
		startButton = null;
		consoleSendButton = null;
		eulaCheckbox = null;
		consoleInput = null;
		optionIndex = 0;
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
		buildLabelWidgets();
		buildFooter();
	}

	private void buildHeaderWidgets() {
		Button brand = addButton("EVERHOST  |  Persistent world control", 18, 10, Math.min(250, Math.max(150, this.width - 170)), () -> {}, "EverHost runs its entire dashboard inside Minecraft.");
		brand.active = false;
		if (EverHostClient.hasPendingUpdate()) {
			int updateWidth = Math.min(210, Math.max(150, this.width / 6));
			addButton("INSTALL UPDATE " + EverHostClient.pendingUpdateVersion(),
				Math.max(280, this.width - 118 - updateWidth), 10, updateWidth,
				() -> EverHostClient.openPendingUpdate(this),
				"Downloads the exact verified release, restarts Minecraft, installs EverHost, and reopens CurseForge.");
		}
		String stateText = stateLabel(status.state());
		Button stateButton = addButton(stateText, Math.max(10, this.width - 112), 10, 100, () -> {}, status.message());
		stateButton.active = false;
		String detail = page == Page.MODS
			? modSummary(service.selectedMods(draft))
			: status.state().isBusyOrOnline() ? "Changes apply after restart" : "Ready for the next start";
		Button pageTitle = addButton(page.title + "  |  " + detail, contentLeft(), HEADER_HEIGHT + 10, contentWidth(), () -> {}, pageTooltip(page));
		pageTitle.active = false;
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
		int columns = 2;
		int gap = 5;
		int margin = 8;
		int columnWidth = (SIDEBAR_WIDTH - margin * 2 - gap) / columns;
		int spacing = 23;
		Page[] pages = Page.values();
		for (int index = 0; index < pages.length; index++) {
			Page candidate = pages[index];
			int column = index % columns;
			int row = index / columns;
			int x = margin + column * (columnWidth + gap);
			int y = HEADER_HEIGHT + 10 + row * spacing;
			Button tab = Button.builder(Component.literal(candidate.title), ignored -> switchPage(candidate))
				.bounds(x, y, columnWidth, CONTROL_HEIGHT)
				.tooltip(Tooltip.create(Component.literal(pageTooltip(candidate))))
				.build();
			tab.active = page != candidate;
			this.addRenderableWidget(tab);
		}
		addButton("Address", margin + columnWidth + gap, HEADER_HEIGHT + 10 + 5 * spacing, columnWidth,
			() -> minecraft.setScreen(new PlayitAddressScreen(this, service)),
			"Sets up a persistent Playit address on the owner's PC. Advanced owners can use their own EverHost controller and domain.");
	}

	private void buildOverview() {
		int left = contentLeft();
		int contentWidth = contentWidth();
		int y = HEADER_HEIGHT + 38;
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
		addButton("Read EULA", left + contentWidth - readWidth, eulaY - 1, readWidth, () -> FabricClientCompat.openLink(this, "https://aka.ms/MinecraftEULA"),
			"Opens Mojang's Minecraft EULA in your browser after a confirmation prompt.");
		String world = status.worldName().isBlank() ? (draft.worldName.isBlank() ? "Not selected" : draft.worldName) : status.worldName();
		String uptime = status.startedAt() > 0L && status.state().isBusyOrOnline() ? formatUptime(System.currentTimeMillis() - status.startedAt()) : "Offline";
		int infoY = eulaY + 29;
		if (infoY + 22 < this.height - FOOTER_HEIGHT) {
			labels.add(new Label(world + " | " + status.playerCount() + "/" + draft.maxPlayers + " players | " + uptime, left, infoY, contentWidth, 11));
			labels.add(new Label(draft.addressMode == EverHostConfig.AddressMode.PLAYIT ? "Permanent Playit address" : "Temporary E4MC address", left, infoY + 12, contentWidth, 11));
		}
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
		addEnum("Plugin support", "Off runs the normal modded server. Automatic installs the verified hybrid bridge for this profile, validates plugin jars, and loads compatible Bukkit, Spigot, and Paper plugins on the next start.",
			draft.pluginMode, EverHostConfig.PluginMode.values(), value -> Component.literal(value == EverHostConfig.PluginMode.AUTO ? "Automatic" : "Off"),
			value -> draft.pluginMode = value);
		int[] folderBounds = nextOptionBounds();
		addButton("Open plugins folder", folderBounds[0], folderBounds[1], folderBounds[2], () -> service.openPluginsFolder(draft),
			"Opens this profile's permanent plugin jar folder. Changes take effect after a server restart.");
		int[] dataBounds = nextOptionBounds();
		addButton("Open plugin data", dataBounds[0], dataBounds[1], dataBounds[2], service::openPluginDataFolder,
			"Opens the live plugin settings and data folders created by the server.");
		ScanResult scan = service.selectedPluginScan(draft);
		List<CatalogEntry> catalog = service.pluginCatalog(draft);
		List<String> installedNames = scan.plugins().stream().map(plugin -> plugin.name().toLowerCase(Locale.ROOT)).toList();
		List<CatalogEntry> available = catalog.stream()
			.filter(entry -> !installedNames.contains(entry.pluginName().toLowerCase(Locale.ROOT))).toList();
		List<CatalogEntry> recommended = available.stream().filter(CatalogEntry::recommended).toList();
		int[] essentialsBounds = nextOptionBounds();
		Button essentials = addButton(service.pluginTaskActive() ? "Installing..." : "Install survival essentials",
			essentialsBounds[0], essentialsBounds[1], essentialsBounds[2],
			() -> service.installPlugins(draft, recommended, this::rebuildWidgets),
			"Installs compatible LuckPerms, CoreProtect, and GriefPrevention releases from Modrinth with SHA-512 verification. Existing versions are archived, not deleted.");
		essentials.active = !service.pluginTaskActive() && !recommended.isEmpty();
		BridgePlan bridge = service.selectedPluginBridge(draft).orElse(null);
		int left = contentLeft();
		int width = contentWidth();
		int y = HEADER_HEIGHT + 100;
		String bridgeLabel = bridge == null ? "No hybrid bridge is available for this profile"
			: (draft.pluginMode == EverHostConfig.PluginMode.AUTO ? "Next start: " : "Available: ") + bridge.displayName();
		labels.add(new Label(bridgeLabel, left + 4, y, width - 8, 14));
		String summary = scan.readyCount() + " ready, " + scan.warningCount() + " review, " + scan.blockedCount()
			+ " blocked, " + scan.disabledCount() + " disabled";
		if (status.state().isBusyOrOnline() && !"Disabled".equals(status.pluginBridge())) {
			summary += "  |  runtime: " + status.pluginLoaded() + " loaded, " + status.pluginFailed() + " failed";
		}
		if (service.pluginTaskActive()) summary = service.pluginTaskMessage();
		labels.add(new Label(this.font.plainSubstrByWidth(summary, width - 8), left + 4, y + 14, width - 8, 14));
		int rows = Math.max(2, (this.height - FOOTER_HEIGHT - y - 56) / 20);
		int itemCount = scan.plugins().size() + available.size();
		int pages = Math.max(1, (itemCount + rows - 1) / rows);
		pluginPage = Math.max(0, Math.min(pluginPage, pages - 1));
		int first = pluginPage * rows;
		for (int index = first; index < Math.min(itemCount, first + rows); index++) {
			int rowY = y + 34 + (index - first) * 20;
			if (index < scan.plugins().size()) {
				PluginInfo plugin = scan.plugins().get(index);
				String state = pluginRuntimeState(plugin);
				addButton(state, left, rowY, 82, () -> cyclePlugin(plugin),
					"Enables or disables this plugin without deleting its jar. A running server must be restarted before the change takes effect.");
				String details = plugin.detail() + " Java: " + (plugin.requiredJava() == 0 ? "unknown" : plugin.requiredJava())
					+ ". Required: " + (plugin.dependencies().isEmpty() ? "none" : String.join(", ", plugin.dependencies()))
					+ ". Optional: " + (plugin.optionalDependencies().isEmpty() ? "none" : String.join(", ", plugin.optionalDependencies()))
					+ ". File: " + plugin.path().getFileName();
				String full = plugin.name() + "  |  " + plugin.kind().displayName();
				addButton(this.font.plainSubstrByWidth(full, width - 100), left + 88, rowY, width - 88,
					() -> HostService.showToast(plugin.name(), details), details);
			} else {
				CatalogEntry entry = available.get(index - scan.plugins().size());
				Button install = addButton("Install", left, rowY, 82,
					() -> service.installPlugins(draft, List.of(entry), this::rebuildWidgets), entry.description());
				install.active = !service.pluginTaskActive();
				addButton(this.font.plainSubstrByWidth("Add " + entry.displayName() + "  |  verified catalog", width - 100),
					left + 88, rowY, width - 88,
					() -> HostService.showToast(entry.displayName(), entry.description()), entry.description());
			}
		}
		if (itemCount == 0) labels.add(new Label("No plugins or compatible catalog entries were found for this profile.", left + 8, y + 40, width - 16, 14));
		int navY = this.height - FOOTER_HEIGHT - 25;
		Button previous = addButton("Previous", left, navY, 82, () -> { pluginPage--; this.rebuildWidgets(); }, "Shows the previous plugin page.");
		previous.active = pluginPage > 0;
		Button next = addButton("Next", left + width - 82, navY, 82, () -> { pluginPage++; this.rebuildWidgets(); }, "Shows the next plugin page.");
		next.active = pluginPage + 1 < pages;
		labels.add(new Label("Page " + (pluginPage + 1) + " / " + pages, left + 92, navY + 6, Math.max(40, width - 184), 14));
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

	private void buildPerformance() {
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
		consoleText = service.currentConsole(visibleConsoleLines());
		int outputY = HEADER_HEIGHT + 50;
		int maximumLines = visibleConsoleLines();
		List<String> outputLines = consoleText.lines().toList();
		int firstLine = Math.max(0, outputLines.size() - maximumLines);
		for (int index = firstLine; index < outputLines.size(); index++) {
			labels.add(new Label(outputLines.get(index), left + 4, outputY + (index - firstLine) * 12, width - 8, 11));
		}
		consoleInput = new EditBox(
			this.font,
			left,
			inputY,
			width - sendWidth - gap,
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
		this.setInitialFocus(consoleInput);
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
		return new int[]{contentLeft() + column * (columnWidth + gap), HEADER_HEIGHT + 54 + row * ROW_GAP, columnWidth};
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

	private int visibleConsoleLines() {
		int outputTop = HEADER_HEIGHT + 48;
		return Math.max(3, (consoleInputY() - outputTop - 10) / 10);
	}

	private void switchPage(Page next) {
		if (page != next) {
			page = next;
			this.rebuildWidgets();
		}
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
				String updatedConsole = service.currentConsole(visibleConsoleLines());
				if (!updatedConsole.equals(consoleText)) {
					consoleText = updatedConsole;
					this.rebuildWidgets();
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
		super.render(graphics, mouseX, mouseY, delta);
		if (page == Page.OVERVIEW) {
			int x = contentLeft();
			int y = HEADER_HEIGHT + 29;
			graphics.drawString(this.font, this.font.plainSubstrByWidth(status.message(), contentWidth()),
				x, y, status.state() == HostStatus.State.ERROR ? ERROR : MUTED, false);
			if (mouseX >= x && mouseX < x + contentWidth() && mouseY >= y && mouseY < y + 9) {
				FabricClientCompat.renderTooltip(graphics, this.font,
					this.font.split(Component.literal(status.message()), 260), mouseX, mouseY);
			}
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
	}

	private void renderOverview(GuiGraphics graphics) {
		int left = contentLeft();
		int width = contentWidth();
		drawText(graphics, "SERVER OVERVIEW", left, HEADER_HEIGHT + 14, TEXT, false);
		drawText(graphics, status.message(), left, HEADER_HEIGHT + 27, status.state() == HostStatus.State.ERROR ? ERROR : MUTED, false);
		String profile = draft.profileName.isBlank() ? "No CurseForge profile selected" : draft.profileName + " | " + draft.minecraftVersion + " | " + draft.loader;
		String world = status.worldName().isBlank() ? (draft.worldName.isBlank() ? "Not selected" : draft.worldName) : status.worldName();
		String uptime = status.startedAt() > 0L && status.state().isBusyOrOnline() ? formatUptime(System.currentTimeMillis() - status.startedAt()) : "Offline";
		int infoY = Math.min(this.height - FOOTER_HEIGHT - 25, HEADER_HEIGHT + 133);
		drawText(graphics, this.font.plainSubstrByWidth(profile, width), left, infoY, MUTED, false);
		String metrics = world + "  |  " + status.playerCount() + "/" + draft.maxPlayers + " players  |  " + uptime;
		drawText(graphics, this.font.plainSubstrByWidth(metrics, width), left, infoY + 12, TEXT, false);
		String players = status.players().isBlank() ? "No players connected" : status.players();
		drawText(graphics, this.font.plainSubstrByWidth(players, width), left, infoY + 23, MUTED, false);
	}

	private void renderSettingsHeader(GuiGraphics graphics) {
		int left = contentLeft();
		drawText(graphics, page.title.toUpperCase(Locale.ROOT), left, HEADER_HEIGHT + 15, TEXT, false);
		String detail = page == Page.MODS
			? modSummary(service.selectedMods(draft))
			: status.state().isBusyOrOnline() ? "Server changes apply after restart" : "Ready for the next start";
		drawText(graphics, detail, left, HEADER_HEIGHT + 29, MUTED, false);
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
		int outputTop = top + 20;
		int outputBottom = consoleInputY() - 7;
		graphics.fill(left, outputTop, left + width, outputBottom, 0xFF0D1014);
		graphics.fill(left, outputTop, left + width, outputTop + 1, BORDER);
		List<String> lines = consoleText.lines().toList();
		int y = outputTop + 7;
		int maximumWidth = width - 14;
		for (String line : lines) {
			String fitted = this.font.plainSubstrByWidth(line, maximumWidth);
			int color = line.startsWith("> ") ? ACCENT
				: line.contains("ERROR") ? ERROR
				: line.contains("WARN") ? WARNING
				: 0xFFC4CBD3;
			drawText(graphics, fitted, left + 7, y, color, false);
			y += 10;
			if (y > outputBottom - 10) break;
		}
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

	private static String pageTooltip(Page page) {
		return switch (page) {
			case OVERVIEW -> "Live state, selected world, public address, players, and primary server controls.";
			case PROFILES -> "Choose and rescan any supported local CurseForge profile.";
			case MODS -> "Review server-compatible mods and choose what joining players must install.";
			case PLUGINS -> "Review plugin jars and whether a compatible plugin bridge is selected.";
			case HOSTING -> "Memory, port, capacity, startup, and server-list settings.";
			case WORLD -> "Game mode, difficulty, distances, flight, combat, command blocks, and spawn rules.";
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
