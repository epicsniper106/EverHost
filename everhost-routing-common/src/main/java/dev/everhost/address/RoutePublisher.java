package dev.everhost.address;

import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.*;

/** Runs in the host daemon, so publishing survives closing the Minecraft client. */
public final class RoutePublisher implements AutoCloseable {
    private final Path root;
    private final AddressApi api = new AddressApi();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "EverHost-Address-Publisher"); thread.setDaemon(true); return thread;
    });
    private String lastRoute = "";
    private long nextAttempt;
    private int failures;
    public RoutePublisher(Path root) { this.root = root; }
    public void start() { worker.scheduleWithFixedDelay(this::publishSafely, 0, 2, TimeUnit.SECONDS); }
    private void publishSafely() { try { publishNow(false); } catch (Exception ignored) { } }
    public synchronized void publishNow(boolean shuttingDown) throws Exception {
        long now = System.currentTimeMillis();
        if (!shuttingDown && failures > 0 && now < nextAttempt) return;
        AddressSettings settings = AddressSettings.load(root);
        AddressIdentity identity = AddressIdentity.load(root);
        if (identity.serverId().isEmpty()) return;
        try {
            identity.checkController(settings);
            Properties host = AddressFiles.read(root.resolve("status.properties"));
            String upstream = host.getProperty("domain", "").strip().toLowerCase(java.util.Locale.ROOT);
            boolean live = !shuttingDown && settings.enabled() && "ONLINE".equals(host.getProperty("state"))
                && alive(host.getProperty("serverPid", "")) && !upstream.isEmpty();
            if (live && !upstream.matches("[a-z0-9.-]+\\.e4mc\\.(link|test)")) live = false;
            String route = identity.hostname() + ":" + (live ? upstream : "offline");
            if (!shuttingDown && route.equals(lastRoute) && now < nextAttempt) return;
            Map<String, Object> body = live ? Map.of("serverId", identity.serverId(), "upstream", upstream) : Map.of("serverId", identity.serverId());
            var result = api.call(settings, live ? "/api/servers/update" : "/api/servers/offline", body, identity.ownerToken());
            lastRoute = route; failures = 0; nextAttempt = now + (live ? 15000 : 30000);
            writeState(live ? "Online" : settings.enabled() ? "Offline" : "Disabled", "", upstream, RouteJson.text(result, "lastUpdated"));
        } catch (Exception error) {
            failures = Math.min(4, failures + 1); nextAttempt = now + Math.min(30000, 2000L << failures);
            writeState("Reconnecting", AddressClient.safeMessage(error), "", "");
            throw error;
        }
    }
    private static boolean alive(String pid) {
        try { return ProcessHandle.of(Long.parseLong(pid)).map(ProcessHandle::isAlive).orElse(false); }
        catch (NumberFormatException ex) { return false; }
    }
    private void writeState(String state, String message, String upstream, String updated) throws Exception {
        Properties p = new Properties(); p.setProperty("state", state); p.setProperty("message", message);
        p.setProperty("upstream", upstream); p.setProperty("lastUpdated", updated);
        p.setProperty("checkedAt", Long.toString(System.currentTimeMillis()));
        AddressFiles.write(root.resolve("permanent-status.properties"), p, false);
    }
    @Override public void close() {
        worker.shutdownNow();
        try { worker.awaitTermination(8, TimeUnit.SECONDS); publishNow(true); }
        catch (Exception ignored) { }
    }
}
