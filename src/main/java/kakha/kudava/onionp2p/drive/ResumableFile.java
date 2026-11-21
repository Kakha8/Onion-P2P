package kakha.kudava.onionp2p.drive;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.*;

/** Keeps only complete authenticated chunks across pauses and network failures. */
public final class ResumableFile {
    public static final int CHUNK_SIZE = 256 * 1024;
    public static final class VerificationException extends IOException {
        public VerificationException(String message) { super(message); }
    }
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
            // Old checkpoints contain no authenticated expected digest. Keep any
            // published copy, but start unfinished work afresh with a checked sender.
            if (!version.isEmpty() && !hasChecksum(version)) { destination = null; discard(); }
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
        if (destination != null) { verify(destination, progress); return destination; }
        Files.createDirectories(directory);
        while (true) {
            progress.checkpoint();
            Chunk chunk = source.read(received, version);
            FolderAccess.validateName(chunk.name());
            if (!hasChecksum(chunk.version())) {
                discard();
                throw new VerificationException("The sender does not provide file checksums. Update both apps and retry.");
            }
            if (!version.isEmpty() && (!version.equals(chunk.version()) || total != chunk.total() || !name.equals(chunk.name()))) {
                discard(); throw new IOException("The shared file changed. Retry to download the new version.");
            }
            if (chunk.offset() != received || chunk.total() < 0 || received > chunk.total()
                    || chunk.bytes().length > CHUNK_SIZE || chunk.bytes().length > chunk.total() - received
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
                verify(partial, progress);
                Path target = directory.resolve(name);
                for (int i = 1; Files.exists(target); i++) target = directory.resolve(i + "-" + name);
                save(target);
                progress.checkpoint();
                Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
                partial = null; destination = target;
                save(null);
                return target;
            }
        }
    }

    private static boolean hasChecksum(String version) {
        return version != null && version.length() > 72 && version.startsWith("sha256:")
                && version.charAt(71) == ':' && version.substring(7, 71).matches("[0-9a-f]{64}");
    }

    private void verify(Path file, DownloadQueue.Progress progress) throws IOException {
        progress.stage("Verifying " + name + "…");
        try {
            if (!hasChecksum(version) || Files.size(file) != total)
                throw new IOException("Size or checksum metadata does not match.");
            String actual = DriveChecksums.calculate(file, progress::checkpoint);
            if (!java.security.MessageDigest.isEqual(actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    version.substring(7, 71).getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
                throw new IOException("Checksum does not match.");
        } catch (IOException mismatch) {
            discard(); // Published copies are retained; only our temporary file is deleted.
            throw new VerificationException("Verification failed. Retry to download a fresh copy.");
        }
        progress.checkpoint();
    }

    public void discard() {
        if (partial != null) try { Files.deleteIfExists(partial); } catch (IOException ignored) {}
        partial = null; destination = null; version = ""; name = null; received = 0; total = -1;
        if (checkpoint != null) try { Files.deleteIfExists(checkpoint); } catch (IOException ignored) {}
    }
}
