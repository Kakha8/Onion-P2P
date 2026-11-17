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
    private final Path checkpoint;
    public ResumableFile() { checkpoint = null; }
    public ResumableFile(Path checkpoint, Path directory) throws IOException {
        this.checkpoint = checkpoint;
        var values = DownloadState.read(checkpoint);
        if (values.isEmpty()) return;
        try {
            version = values.getProperty("version", ""); name = values.getProperty("name");
            received = Long.parseLong(values.getProperty("received", "0")); total = Long.parseLong(values.getProperty("total", "-1"));
            if (name != null) FolderAccess.validateName(name);
            Path parent = directory.toAbsolutePath().normalize();
            if (values.containsKey("partial")) {
                partial = Path.of(values.getProperty("partial")).toAbsolutePath().normalize();
                if (!parent.equals(partial.getParent()) || !partial.getFileName().toString().startsWith(".onion-resume-")
                        || !partial.getFileName().toString().endsWith(".part") || Files.isSymbolicLink(partial)) throw new IOException("Invalid partial download path.");
            }
            if (values.containsKey("destination")) {
                Path target = Path.of(values.getProperty("destination")).toAbsolutePath().normalize();
                if (!parent.equals(target.getParent()) || Files.isSymbolicLink(target)) throw new IOException("Invalid download destination.");
                if ((partial == null || !Files.exists(partial)) && Files.isRegularFile(target) && Files.size(target) == total) destination = target;
            }
            if (destination == null && partial != null) {
                if (received < 0 || total < received || !Files.isRegularFile(partial) || Files.size(partial) < received) { discard(); return; }
                try (var channel = java.nio.channels.FileChannel.open(partial, StandardOpenOption.WRITE)) { channel.truncate(received); }
            }
            if (destination == null && partial == null) { version = ""; name = null; received = 0; total = -1; }
        } catch (IllegalArgumentException error) { throw new IOException("Invalid download checkpoint.", error); }
    }
    public Path completedPath() { return destination; }
    public long received() { return received; }
    private void save(Path finishing) throws IOException {
        if (checkpoint == null) return;
        var values = new java.util.Properties();
        values.setProperty("version", version); if (name != null) values.setProperty("name", name);
        values.setProperty("received", Long.toString(received)); values.setProperty("total", Long.toString(total));
        if (partial != null) values.setProperty("partial", partial.toAbsolutePath().normalize().toString());
        Path target = finishing != null ? finishing : destination;
        if (target != null) values.setProperty("destination", target.toAbsolutePath().normalize().toString());
        DownloadState.write(checkpoint, values);
    }

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
            if (checkpoint != null) {
                try (var channel = java.nio.channels.FileChannel.open(partial, StandardOpenOption.WRITE)) { channel.force(true); }
                save(null);
            }
            progress.update(received, total);
            progress.checkpoint();
            if (received == total) {
                Path target = directory.resolve(name);
                for (int i = 1; Files.exists(target); i++) target = directory.resolve(i + "-" + name);
                save(target);
                Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
                partial = null; destination = target;
                save(null);
                return target;
            }
        }
    }
    public void discard() {
        if (partial != null) try { Files.deleteIfExists(partial); } catch (IOException ignored) {}
        partial = null; version = ""; name = null; received = 0; total = -1;
        if (checkpoint != null) try { Files.deleteIfExists(checkpoint); } catch (IOException ignored) {}
    }
}
