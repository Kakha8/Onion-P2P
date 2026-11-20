package kakha.kudava.onionp2p.drive;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.SQLException;

/** Imports local copies and records a checksum for every copied file. */
public final class DriveImports {
    private DriveImports() {}
    public interface Progress {
        default void planned(long bytes, int files) {}
        default void file(Path source, long copied, long hashed, long total) {}
        default void finished(Path source, long bytes) {}
    }
    public static Path add(Path source, Path directory, DriveChecksums checksums) throws IOException {
        return add(source, directory, checksums, new Progress() {});
    }
    public static Path add(Path source, Path directory, DriveChecksums checksums, Progress progress) throws IOException {
        Path from = source.toAbsolutePath().normalize(), root = directory.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(from)) throw new IOException("Links cannot be added to My files.");
        if (!Files.exists(from)) throw new IOException("The selected item no longer exists.");
        Files.createDirectories(root);
        Path destination = unique(root, from.getFileName().toString());
        if (Files.isDirectory(from)) {
            if (root.startsWith(from)) throw new IOException("Choose a folder that does not contain the app's Drive folder.");
            long[] bytes = {0}; int[] files = {0};
            Files.walkFileTree(from, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                    if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Adding stopped.");
                    if (attributes.isRegularFile()) { bytes[0] += attributes.size(); files[0]++; }
                    return FileVisitResult.CONTINUE;
                }
            });
            progress.planned(bytes[0], files[0]);
            Files.walkFileTree(from, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) throws IOException {
                    if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Adding stopped.");
                    Files.createDirectories(destination.resolve(from.relativize(path))); return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                    if (attributes.isRegularFile()) copy(path, destination.resolve(from.relativize(path)), checksums, progress);
                    return FileVisitResult.CONTINUE;
                }
            });
        } else {
            progress.planned(Files.size(from), 1);
            copy(from, destination, checksums, progress);
        }
        return destination;
    }
    private static void copy(Path from, Path to, DriveChecksums checksums, Progress progress) throws IOException {
        try {
            checksums.copy(from, to, (copied, hashed, total) -> progress.file(from, copied, hashed, total));
            progress.finished(from, Files.size(to));
        }
        catch (SQLException error) { throw new IOException("Could not save the file checksum.", error); }
    }
    private static Path unique(Path directory, String name) {
        Path path = directory.resolve(name);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name, extension = dot > 0 ? name.substring(dot) : "";
        for (int n = 2; Files.exists(path, LinkOption.NOFOLLOW_LINKS); n++) path = directory.resolve(base + " (" + n + ")" + extension);
        return path;
    }
}
