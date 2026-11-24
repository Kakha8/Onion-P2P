package kakha.kudava.onionp2p.drive;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.SQLException;
import java.util.*;

/** Imports local copies, then hashes the saved bytes in a separate phase. */
public final class DriveImports {
    private DriveImports() {}
    public interface Progress {
        default void planned(long bytes, int files) {}
        default void file(Path source, long copied, long hashed, long total) {}
        default void copied(Path source, long bytes) {}
        default void hashing(Path source, long hashed, long total) {}
        default void finished(Path source, long bytes) {}
        default void publishing() {}
    }
    private record File(Path source, Path target, long size) {}
    public static Path add(Path source, Path directory, DriveChecksums checksums) throws IOException {
        return add(source, directory, checksums, new Progress() {});
    }
    public static Path add(Path source, Path directory, DriveChecksums checksums, Progress progress) throws IOException {
        Path from = source.toAbsolutePath().normalize(), root = directory.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(from)) throw new IOException("Links cannot be added to My files.");
        if (!Files.exists(from)) throw new IOException("The selected item no longer exists.");
        if (from.startsWith(checksums.importStagingDirectory())
                || Files.isDirectory(from) && checksums.importStagingDirectory().startsWith(from))
            throw new IOException("Choose files outside the app's temporary import folder.");
        if (Files.isDirectory(from) && root.startsWith(from))
            throw new IOException("Choose a folder that does not contain the app's Drive folder.");
        Files.createDirectories(root);
        ImportStaging staging = ImportStaging.create(checksums);
        Throwable failure = null;
        try {
            Path destination = staging.payload();
            var files = new ArrayList<File>();
            var directories = new ArrayList<Path>();
            if (Files.isDirectory(from)) {
                Files.walkFileTree(from, new SimpleFileVisitor<>() {
                    @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) throws IOException {
                        checkpoint(); directories.add(destination.resolve(from.relativize(path))); return FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                        checkpoint();
                        if (attributes.isRegularFile()) files.add(new File(path, destination.resolve(from.relativize(path)), attributes.size()));
                        return FileVisitResult.CONTINUE;
                    }
                });
            } else files.add(new File(from, destination, Files.size(from)));
            progress.planned(files.stream().mapToLong(File::size).sum(), files.size());
            for (Path folder : directories) { checkpoint(); Files.createDirectories(folder); }
            // Copy the entire item before starting its checksum phase.
            for (File file : files) {
                checkpoint();
                DriveChecksums.copyBytes(file.source(), file.target(), bytes -> progress.file(file.source(), bytes, 0, file.size()));
                progress.copied(file.source(), Files.size(file.target()));
            }
            for (File file : files) {
                checkpoint();
                try { checksums.hashImported(file.target(), bytes -> progress.hashing(file.source(), bytes, file.size())); }
                catch (SQLException error) { throw new IOException("Could not save the file checksum.", error); }
                progress.finished(file.source(), Files.size(file.target()));
            }
            checkpoint(); progress.publishing(); checkpoint();
            return staging.publish(root, from.getFileName().toString());
        } catch (IOException | RuntimeException | Error error) {
            failure = error; throw error;
        } finally {
            try { staging.close(); }
            catch (IOException cleanup) {
                if (failure != null) failure.addSuppressed(cleanup);
                else if (staging.isPublished()) System.err.println("Import completed; temporary cleanup will retry at the next startup.");
                else throw cleanup;
            }
        }
    }
    private static void checkpoint() throws java.io.InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Adding stopped.");
    }
    static Path unique(Path directory, String name) {
        Path path = directory.resolve(name);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name, extension = dot > 0 ? name.substring(dot) : "";
        for (int n = 2; Files.exists(path, LinkOption.NOFOLLOW_LINKS); n++) path = directory.resolve(base + " (" + n + ")" + extension);
        return path;
    }
}
