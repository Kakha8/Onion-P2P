package kakha.kudava.onionp2p.drive;

import kakha.kudava.onionp2p.account.AppData;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Local metadata for Drive shares. Incoming rows are offers until the user downloads them. */
public final class SharedDriveStore {
    public record SharedFile(String id, String contactLink, String name, String path, long size, long receivedAt) {
        public boolean downloaded() { return path != null && !path.isBlank() && Files.isRegularFile(Path.of(path)); }
    }
    private final String url;

    public SharedDriveStore(Path appDirectory) throws Exception {
        Files.createDirectories(appDirectory);
        Path file = appDirectory.resolve("contacts.sqlite");
        if (!Files.exists(file)) Files.createFile(file);
        AppData.restrictToOwner(file);
        Class.forName("org.sqlite.JDBC");
        url = "jdbc:sqlite:" + file.toAbsolutePath();
        try (var connection = connect(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS shared_drive_files (share_id TEXT PRIMARY KEY, contact_link TEXT NOT NULL, name TEXT NOT NULL, file_path TEXT NOT NULL, file_size INTEGER NOT NULL, received_at INTEGER NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS drive_share_offers (share_id TEXT PRIMARY KEY, contact_link TEXT NOT NULL, name TEXT NOT NULL, file_path TEXT NOT NULL, file_size INTEGER NOT NULL, shared_at INTEGER NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS shared_drive_offers (share_id TEXT PRIMARY KEY, contact_link TEXT NOT NULL, name TEXT NOT NULL, file_path TEXT, file_size INTEGER NOT NULL, received_at INTEGER NOT NULL)");
        }
    }

    private java.sql.Connection connect() throws SQLException {
        var connection = DriverManager.getConnection(url);
        try (var statement = connection.createStatement()) { statement.execute("PRAGMA busy_timeout=5000"); }
        return connection;
    }

    public synchronized void publish(String id, String recipientLink, String name, Path source, long size, long sharedAt) throws SQLException {
        try (var c = connect(); var s = c.prepareStatement("INSERT OR REPLACE INTO drive_share_offers VALUES(?,?,?,?,?,?)")) {
            s.setString(1, id); s.setString(2, recipientLink); s.setString(3, name);
            s.setString(4, source.toAbsolutePath().normalize().toString()); s.setLong(5, size); s.setLong(6, sharedAt); s.executeUpdate();
        }
    }

    public synchronized void unpublish(String id) throws SQLException {
        try (var c = connect(); var s = c.prepareStatement("DELETE FROM drive_share_offers WHERE share_id=?")) { s.setString(1, id); s.executeUpdate(); }
    }

    public synchronized SharedFile findPublished(String id, String requesterLink) throws SQLException {
        try (var c = connect(); var s = c.prepareStatement("SELECT share_id,contact_link,name,file_path,file_size,shared_at FROM drive_share_offers WHERE share_id=? AND contact_link=?")) {
            s.setString(1, id); s.setString(2, requesterLink);
            try (var rows = s.executeQuery()) { return rows.next() ? new SharedFile(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getLong(5), rows.getLong(6)) : null; }
        }
    }

    public synchronized void recordOffer(String id, String senderLink, String name, long size, long receivedAt) throws SQLException {
        try (var c = connect(); var s = c.prepareStatement("INSERT OR IGNORE INTO shared_drive_offers(share_id,contact_link,name,file_path,file_size,received_at) VALUES(?,?,?,NULL,?,?)")) {
            s.setString(1, id); s.setString(2, senderLink); s.setString(3, name); s.setLong(4, size); s.setLong(5, receivedAt); s.executeUpdate();
        }
    }

    public synchronized void markDownloaded(String id, Path destination) throws SQLException {
        try (var c = connect(); var s = c.prepareStatement("UPDATE shared_drive_offers SET file_path=? WHERE share_id=?")) {
            s.setString(1, destination.toAbsolutePath().normalize().toString()); s.setString(2, id);
            if (s.executeUpdate() != 1) throw new SQLException("The shared-file offer is no longer available locally.");
        }
    }

    public synchronized List<SharedFile> all() throws SQLException {
        var files = new ArrayList<SharedFile>();
        String query = "SELECT share_id,contact_link,name,file_path,file_size,received_at FROM shared_drive_files UNION ALL "
                + "SELECT share_id,contact_link,name,file_path,file_size,received_at FROM shared_drive_offers ORDER BY received_at DESC";
        try (var c = connect(); var s = c.createStatement(); var rows = s.executeQuery(query)) {
            while (rows.next()) files.add(new SharedFile(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getLong(5), rows.getLong(6)));
        }
        return List.copyOf(files);
    }
}
