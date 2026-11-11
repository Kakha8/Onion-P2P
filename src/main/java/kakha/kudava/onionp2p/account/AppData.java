package kakha.kudava.onionp2p.account;

import java.nio.file.Path;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.List;

public final class AppData {
    private AppData() {}

    public static Path directory() {
        String local = System.getenv("LOCALAPPDATA");
        return Path.of(System.getProperty("onion.dataDir",
                (local == null ? System.getProperty("user.home") : local) + "/Onion-P2P"))
                .toAbsolutePath().normalize();
    }

    public static void restrictToOwner(Path file) throws IOException {
        var acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
        if (acl != null) {
            acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                    .setPrincipal(Files.getOwner(file))
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
        } else if (Files.getFileAttributeView(file, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        }
    }
}
