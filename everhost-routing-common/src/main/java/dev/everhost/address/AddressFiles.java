package dev.everhost.address;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

public final class AddressFiles {
    private AddressFiles() {}
    public static Properties read(Path file) throws IOException {
        Properties values = new Properties();
        if (Files.exists(file)) try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { values.load(reader); }
        return values;
    }
    public static void write(Path file, Properties values, boolean secret) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temp = Files.createTempFile(file.toAbsolutePath().getParent(), ".address-", ".tmp");
        try {
            if (secret) {
                var posix = Files.getFileAttributeView(temp, PosixFileAttributeView.class);
                if (posix != null) Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
                else {
                    var acl = Files.getFileAttributeView(temp, AclFileAttributeView.class);
                    if (acl != null) acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                        .setPrincipal(Files.getOwner(temp)).setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
                }
            }
            try (var writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) { values.store(writer, "EverHost permanent address"); }
            try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
}
