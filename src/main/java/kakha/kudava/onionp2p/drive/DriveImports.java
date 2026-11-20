package kakha.kudava.onionp2p.drive;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.SQLException;

/** Imports local copies and records a checksum for every copied file. */
public final class DriveImports {
    private DriveImports() {}
    public static Path add(Path source, Path directory, DriveChecksums checksums) throws IOException {
        Path from = source.toAbsolutePath().normalize(), root = directory.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(from)) throw new IOException("Links cannot be added to My files.");
        if (!Files.exists(from)) throw new IOException("The selected item no longer exists.");
        Files.createDirectories(root);
        Path destination = unique(root, from.getFileName().toString());
        if (Files.isDirectory(from)) {
            if (root.startsWith(from)) throw new IOException("Choose a folder that does not contain the app's Drive folder.");
            Files.walkFileTree(from, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) throws IOException {
                    Files.createDirectories(destination.resolve(from.relativize(path))); return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                    if (attributes.isRegularFile()) copy(path, destination.resolve(from.relativize(path)), checksums);
                    return FileVisitResult.CONTINUE;
                }
            });
        } else copy(from, destination, checksums);
        return destination;
    }
    private static void copy(Path from, Path to, DriveChecksums checksums) throws IOException {
        try { checksums.copy(from, to); }
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
