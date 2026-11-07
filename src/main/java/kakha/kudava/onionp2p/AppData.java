package kakha.kudava.onionp2p;

import java.nio.file.Path;

final class AppData {
    private AppData() {}

    static Path directory() {
        String local = System.getenv("LOCALAPPDATA");
        return Path.of(System.getProperty("onion.dataDir",
                (local == null ? System.getProperty("user.home") : local) + "/Onion-P2P"))
                .toAbsolutePath().normalize();
    }
}
