package dev.everhost.address;

import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Properties;

public record AddressIdentity(String serverId, String hostname, String ownerToken, String controller, String baseDomain) {
    public static AddressIdentity load(Path root) throws IOException {
        Properties p = AddressFiles.read(root.resolve("permanent-identity.properties"));
        return new AddressIdentity(p.getProperty("serverId", ""), p.getProperty("hostname", ""),
            p.getProperty("ownerToken", ""), p.getProperty("controller", ""), p.getProperty("baseDomain", ""));
    }
    public static AddressIdentity create(AddressSettings settings) {
        byte[] random = new byte[32]; new SecureRandom().nextBytes(random);
        return new AddressIdentity("", "", Base64.getUrlEncoder().withoutPadding().encodeToString(random),
            settings.endpoint().toString(), Names.domain(settings.baseDomain()));
    }
    public void checkController(AddressSettings settings) {
        if (!controller.equals(settings.endpoint().toString()) || !baseDomain.equals(Names.domain(settings.baseDomain())))
            throw new IllegalArgumentException("This reservation belongs to a different controller. Restore its original URL and base domain.");
    }
    public void save(Path root) throws IOException {
        Properties p = new Properties(); p.setProperty("serverId", serverId); p.setProperty("hostname", hostname);
        p.setProperty("ownerToken", ownerToken); p.setProperty("controller", controller); p.setProperty("baseDomain", baseDomain);
        AddressFiles.write(root.resolve("permanent-identity.properties"), p, true);
    }
    @Override public String toString() { return "AddressIdentity[" + hostname + "]"; }
}
