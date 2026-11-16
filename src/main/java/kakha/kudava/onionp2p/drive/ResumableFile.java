package kakha.kudava.onionp2p.drive;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.*;

/** Keeps only complete authenticated chunks across pauses and network failures. */
public final class ResumableFile {
    public static final int CHUNK_SIZE = 256 * 1024;
    public record Chunk(String version, String name, long total, long offset, byte[] bytes) {}
    @FunctionalInterface public interface Source { Chunk read(long offset, String version) throws Exception; }
    private Path partial, destination;
    private String version = "", name;
    private long received, total = -1;

    public Path download(Path directory, Source source, DownloadQueue.Progress progress) throws Exception {
        if (destination != null) return destination;
        Files.createDirectories(directory);
        while (true) {
            progress.checkpoint();
            Chunk chunk = source.read(received, version);
            FolderAccess.validateName(chunk.name());
            if (!version.isEmpty() && (!version.equals(chunk.version()) || total != chunk.total() || !name.equals(chunk.name()))) {
                discard(); throw new IOException("The shared file changed. Retry to download the new version.");
            }
            if (chunk.offset() != received || chunk.total() < 0 || chunk.total() > 1024L * 1024 * 1024
                    || chunk.bytes().length > CHUNK_SIZE || received + chunk.bytes().length > chunk.total()
                    || chunk.bytes().length == 0 && received != chunk.total()) throw new IOException("Invalid download chunk.");
            version = chunk.version(); total = chunk.total(); name = chunk.name();
            if (partial == null) partial = Files.createTempFile(directory, ".onion-resume-", ".part");
            long before = received;
            try (OutputStream out = Files.newOutputStream(partial, StandardOpenOption.APPEND)) { out.write(chunk.bytes()); }
            catch (IOException failure) {
                try (var channel = java.nio.channels.FileChannel.open(partial, StandardOpenOption.WRITE)) { channel.truncate(before); }
                throw failure;
            }
            received += chunk.bytes().length;
            progress.update(received, total);
            progress.checkpoint();
            if (received == total) {
                Path target = directory.resolve(name);
                for (int i = 1; Files.exists(target); i++) target = directory.resolve(i + "-" + name);
                Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
                partial = null; destination = target;
                return target;
            }
        }
    }
    public void discard() {
        if (partial != null) try { Files.deleteIfExists(partial); } catch (IOException ignored) {}
        partial = null; version = ""; name = null; received = 0; total = -1;
    }
}
