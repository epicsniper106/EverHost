package dev.everhost.address;

import java.util.Locale;
import java.util.Set;

public final class Names {
    private static final Set<String> RESERVED = Set.of("www", "api", "admin", "router", "mail", "smtp",
        "status", "support", "account", "accounts", "login", "register", "localhost", "root", "autodiscover");
    private Names() {}
    public static String name(String value) {
        String name = value.toLowerCase(Locale.ROOT);
        if (!name.matches("[a-z0-9][a-z0-9-]{1,30}[a-z0-9]") || RESERVED.contains(name))
            throw new IllegalArgumentException("Use 3-32 letters, numbers or internal hyphens; this name may be reserved.");
        return name;
    }
    public static String domain(String value) {
        String domain = value.toLowerCase(Locale.ROOT);
        if (domain.endsWith(".")) domain = domain.substring(0, domain.length() - 1);
        if (domain.length() > 220 || !domain.contains(".")) throw new IllegalArgumentException("Enter a valid base domain.");
        for (String label : domain.split("\\.", -1)) {
            if (!label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))
                throw new IllegalArgumentException("Invalid domain name.");
        }
        return domain;
    }
    public static String hostname(String value, String base) {
        String normalized = domain(value);
        String suffix = "." + domain(base);
        if (!normalized.endsWith(suffix)) throw new IllegalArgumentException("Hostname belongs to another router.");
        return name(normalized.substring(0, normalized.length() - suffix.length())) + suffix;
    }
}
