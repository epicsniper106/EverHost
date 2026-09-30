package dev.everhost.address;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Properties;

public record AddressSettings(boolean enabled, String apiUrl, String baseDomain, String name,
                              String registrationKey, boolean development) {
    public static AddressSettings load(Path root) throws IOException {
        Properties p = AddressFiles.read(root.resolve("permanent-address.properties"));
        return new AddressSettings(Boolean.parseBoolean(p.getProperty("enabled", "false")),
            p.getProperty("apiUrl", System.getenv().getOrDefault("EVERHOST_API_URL", "")),
            p.getProperty("baseDomain", System.getenv().getOrDefault("EVERHOST_BASE_DOMAIN", "")),
            p.getProperty("name", ""), p.getProperty("registrationKey", ""),
            Boolean.parseBoolean(p.getProperty("development", "false")));
    }
    public void save(Path root) throws IOException {
        Properties p = new Properties();
        p.setProperty("enabled", Boolean.toString(enabled)); p.setProperty("apiUrl", apiUrl);
        p.setProperty("baseDomain", baseDomain); p.setProperty("name", name);
        p.setProperty("registrationKey", registrationKey); p.setProperty("development", Boolean.toString(development));
        AddressFiles.write(root.resolve("permanent-address.properties"), p, true);
    }
    public AddressSettings withEnabled(boolean value) {
        return new AddressSettings(value, apiUrl, baseDomain, name, registrationKey, development);
    }
    public String hostname() { return Names.name(name) + "." + Names.domain(baseDomain); }
    @Override public String toString() { return "AddressSettings[enabled=" + enabled + ", name=" + name + "]"; }
    public URI endpoint() {
        URI uri = URI.create(apiUrl.strip());
        String host = uri.getHost();
        boolean local = "localhost".equals(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
        if (host == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
            || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))
            || !("https".equals(uri.getScheme()) || development && local && "http".equals(uri.getScheme())))
            throw new IllegalArgumentException("Use an HTTPS controller URL. Local development allows HTTP on loopback only.");
        return uri.resolve("/");
    }
}
