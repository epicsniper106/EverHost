package dev.everhost.address;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

/** Blocking operations run on the menu worker, never Minecraft's render thread. */
public final class AddressClient {
    private final Path root;
    private final AddressApi api = new AddressApi();
    public AddressClient(Path root) { this.root = root; }
    public boolean available(AddressSettings settings) throws Exception {
        verifyController(settings);
        return Boolean.TRUE.equals(api.call(settings, "/api/servers/" + settings.hostname(), null, "").get("available"));
    }
    private void verifyController(AddressSettings settings) throws Exception {
        settings.hostname(); settings.endpoint();
        var config = api.call(settings, "/api/config", null, "");
        if (!Names.domain(settings.baseDomain()).equals(RouteJson.text(config, "baseDomain")))
            throw new IOException("Base domain does not match this controller.");
    }
    public synchronized String registerOrRename(AddressSettings settings) throws Exception {
        verifyController(settings);
        AddressIdentity identity = AddressIdentity.load(root);
        if (identity.ownerToken().isEmpty()) {
            identity = AddressIdentity.create(settings);
            // Persist before registration so a lost HTTP reply cannot lose ownership.
            identity.save(root);
        }
        identity.checkController(settings);
        var response = identity.serverId().isEmpty()
            ? api.call(settings, "/api/servers/register", Map.of("name", Names.name(settings.name())), identity.ownerToken())
            : api.call(settings, "/api/servers/rename", Map.of("serverId", identity.serverId(), "name", Names.name(settings.name())), identity.ownerToken());
        String id = RouteJson.text(response, "serverId"), hostname = RouteJson.text(response, "hostname");
        if (!id.matches("[a-f0-9-]{36}") || !hostname.equals(settings.hostname())) throw new IOException("Controller returned an invalid reservation.");
        new AddressIdentity(id, hostname, identity.ownerToken(), identity.controller(), identity.baseDomain()).save(root);
        settings.save(root);
        return hostname;
    }
    public synchronized void enabled(boolean enabled) throws Exception {
        AddressSettings settings = AddressSettings.load(root);
        if (enabled) {
            AddressIdentity identity = AddressIdentity.load(root); identity.checkController(settings);
            if (identity.serverId().isEmpty()) throw new IOException("Reserve a name first.");
        }
        settings.withEnabled(enabled).save(root);
    }
    public static String shareAddress(Path root, String fallback) {
        return shareAddress(root, fallback, "auto");
    }
    public static String shareAddress(Path root, String fallback, String mode) {
        String selected = mode == null ? "auto" : mode.strip().toLowerCase(java.util.Locale.ROOT);
        if ("e4mc".equals(selected)) return fallback == null ? "" : fallback;
        if ("playit".equals(selected)) return playitAddress(root);
        if ("custom".equals(selected)) return customAddress(root);
        String playit = playitAddress(root);
        if (!playit.isEmpty()) return playit;
        String custom = customAddress(root);
        return custom.isEmpty() ? (fallback == null ? "" : fallback) : custom;
    }
    private static String customAddress(Path root) {
        try {
            AddressSettings settings = AddressSettings.load(root);
            if (!settings.enabled()) return "";
            AddressIdentity identity = AddressIdentity.load(root); identity.checkController(settings);
            return identity.serverId().isEmpty() ? "" : identity.hostname();
        } catch (Exception ex) { return ""; }
    }
    private static String playitAddress(Path root) {
        try {
            Properties config = AddressFiles.read(root.resolve("playit.properties"));
            if (!Boolean.parseBoolean(config.getProperty("enabled", "false"))) return "";
            Properties status = AddressFiles.read(root.resolve("playit-status.properties"));
            if (!"ONLINE".equalsIgnoreCase(status.getProperty("state", ""))
                || !Boolean.parseBoolean(status.getProperty("agentRunning", "false"))) return "";
            String address = status.getProperty("address", config.getProperty("address", "")).strip();
            if (address.length() > 253 || address.isEmpty() || address.chars().anyMatch(Character::isWhitespace)) return "";
            return address;
        } catch (Exception ignored) {
            return "";
        }
    }
    public static String safeMessage(Exception error) {
        if (error instanceof IOException || error instanceof IllegalArgumentException) {
            String message = error.getMessage();
            // Never display exception text containing a URL, credential, or a file path.
            if (message != null && message.length() < 190 && !message.contains(":") && !message.contains("\\")) return message;
        }
        return "Address request failed. Check the controller settings and connection.";
    }
}
