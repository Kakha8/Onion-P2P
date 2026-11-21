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
            for (int attempt = 0; ; attempt++) {
                try {
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    break;
                } catch (AccessDeniedException locked) {
                    // Windows can briefly deny replacement while a reader or scanner has the checkpoint open.
                    if (attempt >= 5) throw locked;
                    try { Thread.sleep(10L << attempt); }
                    catch (InterruptedException stopped) {
                        Thread.currentThread().interrupt();
                        var interrupted = new InterruptedIOException("Checkpoint write interrupted.");
                        interrupted.initCause(stopped); throw interrupted;
                    }
                }
            }
        } finally { Files.deleteIfExists(temporary); }
    }
}
