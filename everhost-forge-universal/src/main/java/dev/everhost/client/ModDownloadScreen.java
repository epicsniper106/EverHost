package dev.everhost.client;

import dev.everhost.install.ModInstallCoordinator;
import dev.everhost.install.ModDownloadPreferences;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Required installation and optional consent use the same exact-file, restart-aware installer. */
public final class ModDownloadScreen extends Screen {
    private final Screen parent;
    private final ModInstallCoordinator.Offer offer;
    private Checkbox suppress;
    private boolean busy;
    private boolean suppressChoice = true;
    private int page;
    private String status = "";
    private int rows;
    private int left;
    private int panelWidth;

    public ModDownloadScreen(Screen parent, ModInstallCoordinator.Offer offer) {
        super(Component.literal(offer.optional() ? "Optional server mods" : "Install required server mods"));
        this.parent = parent;
        this.offer = offer;
    }

    @Override protected void init() {
        panelWidth = Math.max(240, Math.min(560, width - 32));
        left = (width - panelWidth) / 2;
        rows = Math.max(1, (height - 203) / 22);
        int pages = Math.max(1, (offer.requirements().size() + rows - 1) / rows);
        page = Math.min(page, pages - 1);
        for (int index = page * rows; index < Math.min(offer.requirements().size(), (page + 1) * rows); index++) {
            var mod = offer.requirements().get(index);
            String label = font.plainSubstrByWidth(mod.name(), panelWidth - 16);
            Button row = addRenderableWidget(Button.builder(Component.literal(label), ignored -> {})
                .bounds(left, 98 + (index - page * rows) * 22, panelWidth, 20).build());
            row.setTooltip(Tooltip.create(Component.literal(mod.fileName() + "\n" + mod.version() + "\n" + mod.sourceDescription())));
        }
        int navY = height - 100;
        Button previous = addRenderableWidget(Button.builder(Component.literal("<"), ignored -> { rememberCheckbox(); page--; rebuildWidgets(); })
            .bounds(left, navY, 30, 18).build());
        previous.active = !busy && page > 0;
        Button next = addRenderableWidget(Button.builder(Component.literal(">"), ignored -> { rememberCheckbox(); page++; rebuildWidgets(); })
            .bounds(left + panelWidth - 30, navY, 30, 18).build());
        next.active = !busy && page + 1 < pages;
        if (offer.optional()) {
            suppress = addRenderableWidget(new Checkbox(left, height - 75, 20, 20,
                Component.literal("Don't ask again until required mods change"), suppressChoice));
            suppress.active = !busy;
        }
        int buttonWidth = (panelWidth - 8) / 2;
        Button yes = addRenderableWidget(Button.builder(Component.literal(offer.optional() ? "Yes, install optional mods" : "Install required mods"), ignored -> install())
            .bounds(left, height - 43, buttonWidth, 22).build());
        Button no = addRenderableWidget(Button.builder(Component.literal(offer.optional() ? "No, join server" : "Back"), ignored -> decline())
            .bounds(left + buttonWidth + 8, height - 43, buttonWidth, 22).build());
        yes.active = no.active = !busy;
    }

    private void rememberCheckbox() { if (suppress != null) suppressChoice = suppress.selected(); }

    private void install() {
        if (busy) return;
        rememberCheckbox();
        busy = true;
        status = "Starting the installer...";
        rebuildWidgets();
        EverHostClient.installServerMods(offer, suppressChoice, message -> {
            status = message;
            if (message.startsWith("Failed:")) { busy = false; rebuildWidgets(); }
        });
    }

    private void decline() {
        if (busy) return;
        rememberCheckbox();
        try {
            if (offer.optional()) {
                ModInstallCoordinator.dismissOptional(LoaderBridge.gameDirectory(), offer, suppressChoice);
                RequiredModsClient.joinWithoutOptional(parent, offer);
            } else minecraft.setScreen(parent);
        } catch (Exception exception) {
            status = "Failed: " + exception.getMessage();
        }
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0xFF10141A);
        graphics.drawCenteredString(font, title, width / 2, 16, 0xFF57D6C6);
        graphics.drawWordWrap(font, Component.literal(offer.optional()
            ? "Your required mods are ready. Download optional mods from this host?"
            : "These exact mods are needed to join. Minecraft closes to install them."), left, 37, panelWidth, 0xFFF1F4F7);
        graphics.drawString(font, String.format(java.util.Locale.ROOT, "%d %s | %.1f MB | Host download",
            offer.requirements().size(), offer.requirements().size() == 1 ? "mod" : "mods",
            offer.totalBytes() / 1048576.0), left, 66, 0xFFB9C2CE);
        graphics.drawString(font, "CurseForge fallback: " + (ModDownloadPreferences.curseForgeFallback(LoaderBridge.gameDirectory()) ? "On" : "Off")
            + "  (Server > Mods)", left, 81, 0xFFB9C2CE);
        int pages = Math.max(1, (offer.requirements().size() + rows - 1) / rows);
        graphics.drawCenteredString(font, (page + 1) + " / " + pages, width / 2, height - 95, 0xFFB9C2CE);
        if (!offer.optional()) graphics.drawWordWrap(font, Component.literal("Original versions are backed up. CurseForge reopens after installation."),
            left, height - 74, panelWidth, 0xFFB9C2CE);
        if (!status.isBlank()) graphics.drawString(font, font.plainSubstrByWidth(status, panelWidth), left, height - 16,
            status.startsWith("Failed:") ? 0xFFFF7777 : 0xFF57D6C6);
        super.render(graphics, mouseX, mouseY, delta);
    }

    @Override public void onClose() { decline(); }
    @Override public boolean shouldCloseOnEsc() { return !busy; }
}
