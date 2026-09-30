package dev.everhost.client;

import dev.everhost.address.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

public final class PermanentAddressScreen extends Screen {
    private final Screen parent;
    private final HostService service;
    private final AddressClient client;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "EverHost-Address-Menu"); thread.setDaemon(true); return thread;
    });
    private String url = "", domain = "", name = "", code = "", message = "";
    private boolean development, enabled, advanced, busy;
    private String hostname = "No name reserved";
    private Button statusButton;
    private final List<Button> actions = new ArrayList<>();
    private int ticks;

    public PermanentAddressScreen(Screen parent, HostService service) {
        super(Component.literal("EverHost | Permanent Address"));
        this.parent = parent; this.service = service; this.client = new AddressClient(service.root());
        try {
            AddressSettings settings = AddressSettings.load(service.root());
            url = settings.apiUrl(); domain = settings.baseDomain(); name = settings.name(); code = settings.registrationKey();
            enabled = settings.enabled(); development = settings.development(); refreshIdentity();
        } catch (Exception ex) { message = AddressClient.safeMessage(ex); }
    }
    private void refreshIdentity() throws Exception {
        var identity = AddressIdentity.load(service.root());
        hostname = identity.hostname().isEmpty() ? "No name reserved" : identity.hostname();
        enabled = AddressSettings.load(service.root()).enabled();
    }
    private int left() { return (width - panelWidth()) / 2; }
    private int panelWidth() { return Math.min(540, width - 24); }
    private AddressSettings draft() { return new AddressSettings(true, url.strip(), domain.strip(), name.strip(), code.strip(), development); }
    @Override protected void init() {
        actions.clear();
        int x = left(), w = panelWidth(), half = (w - 6) / 2;
        if (advanced) {
            Properties state = new Properties();
            try { state = AddressFiles.read(service.root().resolve("permanent-status.properties")); } catch (Exception ignored) { }
            info("Tunnel: " + service.status().domain(), 48, "Actual e4mc destination. This may change after a restart; friends should use the reserved address.");
            info("Last update: " + state.getProperty("lastUpdated", "Never"), 76, "Time when the controller last accepted a route update, in UTC.");
            info("Controller: " + url, 104, "The HTTPS service that owns the reservation database and shares it with the Minecraft router.");
            info("Route: " + state.getProperty("state", "Not published"), 132, state.getProperty("message", ""));
            button("Copy temporary tunnel", x, 160, w, () -> minecraft.keyboardHandler.setClipboard(service.status().domain()),
                "Copies the changing e4mc hostname for direct connection troubleshooting. Does not change your permanent address.");
        } else {
            field("Controller URL", url, 44, 240, value -> url = value,
                "HTTPS URL of your deployed EverHost controller, for example https://control.your-domain.com. EverHost does not supply or own a public domain.", false);
            field("Base domain", domain, 70, 220, value -> domain = value,
                "Domain whose wildcard DNS points at your public Minecraft router. It must match BASE_DOMAIN on the controller.", false);
            field("Server name", name, 96, 32, value -> name = value,
                "Your permanent prefix: 3-32 letters, digits or hyphens. Example: spixy. Renaming reserves the old name to your account but stops routing that old name.", false);
            field("Registration code", code, 122, 200, value -> code = value,
                "Private registration code supplied by the controller administrator. Stored locally with restricted file permissions and never shown in logs.", true);
            button("Local testing: " + (development ? "On" : "Off"), x, 148, half, () -> {
                development = !development; rebuildWidgets();
            }, "Allows HTTP only to localhost for local development. Real public deployments still require HTTPS.");
            button("Permanent: " + (enabled ? "On" : "Off"), x + half + 6, 148, w - half - 6,
                () -> perform(() -> { client.enabled(!enabled); return !enabled ? "Permanent address enabled." : "Permanent address disabled; e4mc remains available."; }),
                "Turns routing on or off without deleting your reservation. Reserve a name before enabling. Disabling publishes an offline route.");
            int third = (w - 12) / 3;
            button("Check name", x, 174, third, () -> {
                AddressSettings settings = draft();
                perform(() -> client.available(settings) ? "That name is available." : "That name is reserved; owners may save it again.");
            }, "Checks the controller for availability without reserving or changing anything.");
            button("Save + Enable", x + third + 6, 174, third, () -> {
                AddressSettings settings = draft();
                perform(() -> "Reserved " + client.registerOrRename(settings));
            }, "Reserves or renames this server and enables automatic updates. Requires a deployed controller and wildcard DNS for public use.");
            button("Copy address", x + 2 * (third + 6), 174, w - 2 * (third + 6), () -> {
                try {
                    String saved = AddressIdentity.load(service.root()).hostname();
                    if (!saved.isEmpty()) { minecraft.keyboardHandler.setClipboard(saved); message = "Permanent address copied."; }
                    else message = "Reserve a name first.";
                } catch (Exception ex) { message = AddressClient.safeMessage(ex); }
            }, "Copies the reserved hostname, even while the server is offline. Public DNS must be configured by the controller administrator.");
        }
        statusButton = info(message.isEmpty() ? "Not configured" : message, 198, message);
        int third = (w - 12) / 3;
        button(advanced ? "Settings" : "Details", x, height - 25, third, () -> { advanced = !advanced; rebuildWidgets(); }, "Switches between self-hosted address settings and connection details.");
        button("Easy Playit", x + third + 6, height - 25, third, () -> minecraft.setScreen(new PlayitAddressScreen(parent, service)), "Returns to the automatic third-party setup recommended for home-hosted EverHost servers.");
        button("Done", x + 2 * (third + 6), height - 25, w - 2 * (third + 6), this::onClose, "Returns to EverHost. Unsaved field edits are not applied.");
        for (Button action : actions) action.active = !busy;
    }
    private void field(String label, String value, int y, int max, Consumer<String> setter, String tooltip, boolean secret) {
        EditBox box = new EditBox(font, left() + 100, y, panelWidth() - 100, 20, Component.literal(label));
        box.setMaxLength(max); box.setValue(value); box.setResponder(setter);
        box.setTooltip(Tooltip.create(Component.literal(tooltip)));
        if (secret) box.setFormatter((text, offset) -> FormattedCharSequence.forward("*".repeat(text.length()), net.minecraft.network.chat.Style.EMPTY));
        addRenderableWidget(box);
    }
    private Button info(String text, int y, String tip) {
        Button result = Button.builder(Component.literal(text), ignored -> {}).bounds(left(), y, panelWidth(), 12)
            .tooltip(Tooltip.create(Component.literal(tip))).build();
        result.active = false; return addRenderableWidget(result);
    }
    private void button(String text, int x, int y, int w, Runnable action, String tip) {
        Button result = Button.builder(Component.literal(text), ignored -> action.run()).bounds(x, y, w, 20)
            .tooltip(Tooltip.create(Component.literal(tip))).build();
        actions.add(result); addRenderableWidget(result);
    }
    @FunctionalInterface private interface Operation { String run() throws Exception; }
    private void perform(Operation operation) {
        if (busy) return;
        busy = true; message = "Contacting controller...";
        for (Button action : actions) action.active = false;
        worker.execute(() -> {
            String result;
            try { result = operation.run(); } catch (Exception error) { result = AddressClient.safeMessage(error); }
            String finalResult = result;
            minecraft.execute(() -> {
                message = finalResult; busy = false; ticks = 0;
                try { refreshIdentity(); } catch (Exception ignored) { }
                if (minecraft.screen == this) rebuildWidgets();
            });
        });
    }
    @Override public void tick() {
        if (!busy && ticks > 160) message = "";
        if (++ticks % 20 == 0 && message.isEmpty()) {
            try {
                Properties state = AddressFiles.read(service.root().resolve("permanent-status.properties"));
                long checked = Long.parseLong(state.getProperty("checkedAt", "0"));
                String text = !enabled ? "Permanent routing disabled" : System.currentTimeMillis() - checked > 75000
                    ? "Waiting for host / controller" : state.getProperty("state", "Not published");
                statusButton.setMessage(Component.literal(text));
                statusButton.setTooltip(Tooltip.create(Component.literal(state.getProperty("message", text))));
            } catch (Exception ignored) { }
        } else if (!message.isEmpty()) statusButton.setMessage(Component.literal(message));
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0xFF111419);
        graphics.drawString(font, title, left(), 12, 0xFFF1F4F7, false);
        graphics.drawString(font, font.plainSubstrByWidth(hostname, panelWidth()), left(), 28, 0xFF57D6C6, false);
        if (!advanced) {
            String[] labels = {"Controller URL", "Base domain", "Server name", "Join code"};
            for (int i = 0; i < labels.length; i++) graphics.drawString(font, labels[i], left(), 50 + i * 26, 0xFFB9C2CE, false);
        }
        super.render(graphics, mouseX, mouseY, delta);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public void removed() { worker.shutdown(); }
}
