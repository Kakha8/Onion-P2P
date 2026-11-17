package kakha.kudava.onionp2p.drive;

import kakha.kudava.onionp2p.account.AppData;
import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/** Atomic local checkpoints. Never replaces the previous checkpoint with a partial write. */
final class DownloadState {
    static Properties read(Path file) throws IOException {
        var values = new Properties();
        if (Files.exists(file)) try (var input = Files.newInputStream(file)) { values.load(input); }
        return values;
    }
    static void write(Path file, Properties values) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".checkpoint-", ".tmp");
        try {
            AppData.restrictToOwner(temporary);
            try (var output = Files.newOutputStream(temporary)) { values.store(output, "Onion-P2P download state"); }
            try (var channel = java.nio.channels.FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
