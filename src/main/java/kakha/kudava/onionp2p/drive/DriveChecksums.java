package kakha.kudava.onionp2p.drive;

import kakha.kudava.onionp2p.account.AppData;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/** SHA-256 of managed file contents, cached against the last observed file version. */
public final class DriveChecksums {
    private final String url;
    private record Version(long size, String modified, String created, String key) {}

    public DriveChecksums(Path directory) throws Exception {
        Files.createDirectories(directory);
        Path database = directory.resolve("contacts.sqlite");
        if (!Files.exists(database)) Files.createFile(database);
        AppData.restrictToOwner(database);
        Class.forName("org.sqlite.JDBC");
        url = "jdbc:sqlite:" + database.toAbsolutePath();
        try (var c = connect(); var s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS drive_checksums (file_path TEXT PRIMARY KEY, sha256 TEXT NOT NULL, file_size INTEGER NOT NULL, modified TEXT NOT NULL, created TEXT NOT NULL, file_key TEXT NOT NULL)");
        }
    }

    private Connection connect() throws SQLException {
        var c = DriverManager.getConnection(url);
        try (var s = c.createStatement()) { s.execute("PRAGMA busy_timeout=5000"); }
        return c;
    }

    private static Version version(Path file) throws IOException {
        var a = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!a.isRegularFile()) throw new IOException("Checksums require a regular file.");
        return new Version(a.size(), a.lastModifiedTime().toString(), a.creationTime().toString(), String.valueOf(a.fileKey()));
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }

    public String sha256(Path file) throws IOException, SQLException {
        Path path = file.toAbsolutePath().normalize();
        Version before = version(path);
        String saved = cached(path, before);
        if (saved != null) return saved;
        String value = calculate(path, () -> {});
        if (!before.equals(version(path))) throw new IOException("The file changed while calculating its checksum.");
        save(path, value, before);
        return value;
    }

    /** Always reads the saved bytes; receiver verification must never use the metadata cache. */
    public static String calculate(Path path, Runnable checkpoint) throws IOException {
        Version before = version(path);
        var hash = digest();
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[128 * 1024];
            for (int read; (read = input.read(buffer)) != -1;) { checkpoint.run(); hash.update(buffer, 0, read); }
        }
        checkpoint.run();
        if (!before.equals(version(path))) throw new IOException("The file changed while calculating its checksum.");
        return HexFormat.of().formatHex(hash.digest());
    }

    private String cached(Path file, Version version) throws SQLException {
        try (var c = connect(); var s = c.prepareStatement("SELECT sha256,file_size,modified,created,file_key FROM drive_checksums WHERE file_path=?")) {
            s.setString(1, file.toString());
            try (var rows = s.executeQuery()) {
                if (rows.next() && version.equals(new Version(rows.getLong(2), rows.getString(3), rows.getString(4), rows.getString(5))))
                    return rows.getString(1);
            }
        }
        return null;
    }

    private void save(Path file, String value, Version version) throws SQLException {
        try (var c = connect(); var s = c.prepareStatement("INSERT OR REPLACE INTO drive_checksums VALUES(?,?,?,?,?,?)")) {
            s.setString(1, file.toString()); s.setString(2, value); s.setLong(3, version.size());
            s.setString(4, version.modified()); s.setString(5, version.created()); s.setString(6, version.key()); s.executeUpdate();
        }
    }

    /** Stream the exact bytes written to the new managed copy into the digest. */
    public void copy(Path source, Path target) throws IOException, SQLException {
        copy(source, target, (copied, hashed, total) -> {});
    }

    @FunctionalInterface public interface CopyProgress { void update(long copied, long hashed, long total); }

    public void copy(Path source, Path target, CopyProgress progress) throws IOException, SQLException {
        Version original = version(source);
        var hash = digest();
        long copied = 0;
        boolean created = false;
        try {
            try (var input = Files.newInputStream(source);
                 var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                created = true;
                progress.update(0, 0, original.size());
                byte[] buffer = new byte[128 * 1024];
                for (int read; (read = input.read(buffer)) != -1;) {
                    if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Adding stopped.");
                    output.write(buffer, 0, read);
                    progress.update(copied + read, copied, original.size());
                    hash.update(buffer, 0, read); copied += read;
                    progress.update(copied, copied, original.size());
                }
            }
            if (!original.equals(version(source))) throw new IOException("The selected file changed while being added. Try again.");
            save(target.toAbsolutePath().normalize(), HexFormat.of().formatHex(hash.digest()), version(target));
        } catch (IOException | SQLException failure) {
            if (created) try { Files.deleteIfExists(target); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    public void moved(Path before, Path after) throws SQLException {
        Path from = before.toAbsolutePath().normalize(), to = after.toAbsolutePath().normalize();
        try (var c = connect()) {
            c.setAutoCommit(false);
            try {
                var paths = new ArrayList<Path>();
                try (var s = c.createStatement(); var rows = s.executeQuery("SELECT file_path FROM drive_checksums")) {
                    while (rows.next()) { Path path = Path.of(rows.getString(1)); if (path.startsWith(from)) paths.add(path); }
                }
                try (var s = c.prepareStatement("UPDATE drive_checksums SET file_path=? WHERE file_path=?")) {
                    for (Path path : paths) {
                        s.setString(1, to.resolve(from.relativize(path)).toString()); s.setString(2, path.toString()); s.addBatch();
                    }
                    s.executeBatch();
                }
                c.commit();
            } catch (SQLException error) { c.rollback(); throw error; }
        }
    }

    public void deleted(Path file) throws SQLException {
        Path root = file.toAbsolutePath().normalize();
        try (var c = connect(); var s = c.prepareStatement("DELETE FROM drive_checksums WHERE file_path=?")) {
            var paths = new ArrayList<String>();
            try (var query = c.createStatement(); var rows = query.executeQuery("SELECT file_path FROM drive_checksums")) {
                while (rows.next()) if (Path.of(rows.getString(1)).startsWith(root)) paths.add(rows.getString(1));
            }
            for (String path : paths) { s.setString(1, path); s.addBatch(); }
            s.executeBatch();
        }
    }
}
