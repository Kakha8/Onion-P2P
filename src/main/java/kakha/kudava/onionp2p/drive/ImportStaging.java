package kakha.kudava.onionp2p.drive;

import kakha.kudava.onionp2p.account.AppData;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.SQLException;
import java.util.*;

/** Private staging outside Drive. OS locks distinguish abandoned jobs from live imports. */
final class ImportStaging implements AutoCloseable {
    private static final String PUBLICATION = "publication.properties";
    private final DriveChecksums checksums;
    private final Path directory;
    private final FileChannel channel;
    private final FileLock lock;
    private boolean published;

    private ImportStaging(DriveChecksums checksums, Path directory, FileChannel channel, FileLock lock) {
        this.checksums = checksums; this.directory = directory; this.channel = channel; this.lock = lock;
    }
    Path payload() { return directory.resolve("payload"); }
    void publication(Path destination) throws IOException {
        var values = new Properties(); values.setProperty("destination", destination.toString());
        DownloadState.write(directory.resolve(PUBLICATION), values);
    }
    Path publish(Path root, String name) throws IOException {
        return locked(checksums, () -> {
            if (!Files.getFileStore(payload()).equals(Files.getFileStore(root)))
                throw new IOException("The import and My files must be on the same drive to finish safely.");
            Path destination = DriveImports.unique(root, name);
            publication(destination);
            try { checksums.moved(payload(), destination); }
            catch (SQLException error) { throw new IOException("Could not save the file checksum.", error); }
            // Same-volume rename avoids a cross-volume copy and refuses existing names.
            Files.move(payload(), destination);
            published = true;
            return destination;
        });
    }
    boolean isPublished() { return published; }

    @FunctionalInterface private interface Operation<T> { T run() throws IOException; }
    // The process lock also covers the interval between making a job directory and locking it.
    private static synchronized <T> T locked(DriveChecksums checksums, Operation<T> operation) throws IOException {
        Path root = checksums.importStagingDirectory();
        if (Files.isSymbolicLink(root)) throw new IOException("Import staging cannot be a link.");
        Files.createDirectories(root); AppData.restrictToOwner(root);
        Path lockPath = root.resolve(".lock");
        if (Files.isSymbolicLink(lockPath)) throw new IOException("Invalid import staging lock.");
        try (var channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var ignored = channel.lock()) { return operation.run(); }
    }

    static ImportStaging create(DriveChecksums checksums) throws IOException {
        return locked(checksums, () -> {
            Path job = Files.createDirectory(checksums.importStagingDirectory().resolve("import-" + UUID.randomUUID()));
            FileChannel channel = null;
            try {
                AppData.restrictToOwner(job);
                channel = FileChannel.open(job.resolve(".lock"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                return new ImportStaging(checksums, job, channel, channel.lock());
            } catch (IOException | RuntimeException error) {
                if (channel != null) channel.close();
                Files.deleteIfExists(job.resolve(".lock")); Files.deleteIfExists(job); throw error;
            }
        });
    }

    static void cleanAbandoned(DriveChecksums checksums) throws IOException {
        locked(checksums, () -> {
            IOException failure = null;
            try (var jobs = Files.newDirectoryStream(checksums.importStagingDirectory(), "import-*")) {
                for (Path job : jobs) {
                    if (!job.getFileName().toString().matches("import-[0-9a-f-]{36}")
                            || !Files.isDirectory(job, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(job.resolve(".lock"))) continue;
                    try {
                        boolean cleaned = false;
                        try (var channel = FileChannel.open(job.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                            FileLock lock;
                            try { lock = channel.tryLock(); }
                            catch (OverlappingFileLockException activeInThisProcess) { continue; }
                            if (lock == null) continue;
                            try (lock) { cleanPayload(checksums, job, false); cleaned = true; }
                        }
                        if (cleaned) removeJob(job);
                    } catch (IOException error) {
                        if (failure == null) failure = error; else failure.addSuppressed(error);
                    }
                }
            }
            if (failure != null) throw failure;
            return null;
        });
    }

    private static void cleanPayload(DriveChecksums checksums, Path job, boolean published) throws IOException {
        IOException failure = null;
        try {
            checksums.deleted(job);
            if (!published) {
                var values = DownloadState.read(job.resolve(PUBLICATION));
                String destination = values.getProperty("destination");
                // A crash after the rename must preserve the completed item and its cached checksum.
                if (destination != null && Files.notExists(Path.of(destination), LinkOption.NOFOLLOW_LINKS))
                    checksums.deleted(Path.of(destination));
            }
        } catch (SQLException error) { failure = new IOException("Could not clean temporary checksums.", error); }
        try { deleteTree(job.resolve("payload")); }
        catch (IOException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        if (failure != null) throw failure;
    }
    private static void removeJob(Path job) throws IOException {
        // Files used by the checkpoint writer can remain if the process died mid-write.
        try (var files = Files.newDirectoryStream(job)) {
            for (Path file : files) Files.delete(file);
        }
        Files.delete(job);
    }
    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        // Do not follow links or reparse the contents outside this job's payload.
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
                Files.delete(path); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path path, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(path); return FileVisitResult.CONTINUE;
            }
        });
    }
    @Override public void close() throws IOException {
        // Cancellation must not interrupt the cleanup itself; restore the flag when finished.
        boolean interrupted = Thread.interrupted();
        try {
            locked(checksums, () -> {
                boolean cleaned = false;
                try { cleanPayload(checksums, directory, published); cleaned = true; }
                finally { try { lock.release(); } finally { channel.close(); } }
                if (cleaned) removeJob(directory);
                return null;
            });
        } finally {
            if (channel.isOpen()) { try { lock.release(); } finally { channel.close(); } }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
