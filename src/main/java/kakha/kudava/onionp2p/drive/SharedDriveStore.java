package kakha.kudava.onionp2p.drive;

import kakha.kudava.onionp2p.account.AppData;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Local metadata for Drive shares. Incoming rows are offers until the user downloads them. */
public final class SharedDriveStore {
    public record SharedFile(String id, String contactLink, String name, String path, long size, long receivedAt) {
        public boolean folder() { return size == -1; }
        public boolean downloaded() { return path != null && !path.isBlank() && Files.isRegularFile(Path.of(path)); }
    }
    public record ShareCount(int delivered, int queued) {
        public int total() { return delivered + queued; }
        public String label() {
            if (queued == 0) return delivered + " shared";
            if (delivered == 0) return queued + (queued == 1 ? " waiting" : " waiting");
            return delivered + " shared · " + queued + " waiting";
        }
    }
    public record PendingShare(String id, String contactLink, String path) {}
    private final String url;

    /** Keep grants and queued offers attached to their IDs when an item is renamed. */
    public synchronized void moved(Path before, Path after) throws SQLException {
        String newPath = after.toAbsolutePath().normalize().toString();
        try (var c = connect()) {
            c.setAutoCommit(false);
            try {
                for (String table : List.of("drive_share_offers", "pending_drive_shares")) {
                    var changes = new ArrayList<String[]>();
                    try (var s = c.createStatement(); var rows = s.executeQuery("SELECT file_path FROM " + table)) {
                        while (rows.next()) {
                            Path path = Path.of(rows.getString(1));
                            if (path.startsWith(before.toAbsolutePath().normalize()))
                                changes.add(new String[]{path.toString(), after.toAbsolutePath().normalize().resolve(before.toAbsolutePath().normalize().relativize(path)).toString()});
                        }
                    }
                    try (var s = c.prepareStatement("UPDATE " + table + " SET file_path=? WHERE file_path=?")) {
                        for (var change : changes) { s.setString(1, change[1]); s.setString(2, change[0]); s.executeUpdate(); }
                    }
                }
                try (var s = c.prepareStatement("UPDATE drive_share_offers SET name=? WHERE file_path=?")) {
                    s.setString(1, after.getFileName().toString()); s.setString(2, newPath); s.executeUpdate();
                }
                c.commit();
            } catch (SQLException e) { c.rollback(); throw e; }
        }
    }

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
            statement.execute("CREATE TABLE IF NOT EXISTS pending_drive_shares (contact_link TEXT NOT NULL, file_path TEXT NOT NULL, queued_at INTEGER NOT NULL, share_id TEXT NOT NULL, PRIMARY KEY(contact_link,file_path))");
            boolean hasShareId = false;
            try (var columns = statement.executeQuery("PRAGMA table_info(pending_drive_shares)")) {
                while (columns.next()) if ("share_id".equalsIgnoreCase(columns.getString("name"))) hasShareId = true;
            }
            if (!hasShareId) statement.execute("ALTER TABLE pending_drive_shares ADD COLUMN share_id TEXT");
            var missingIds = new ArrayList<String[]>();
            try (var rows = statement.executeQuery("SELECT contact_link,file_path FROM pending_drive_shares WHERE share_id IS NULL OR share_id=''")) {
                while (rows.next()) missingIds.add(new String[]{rows.getString(1), rows.getString(2)});
            }
            try (var update = connection.prepareStatement("UPDATE pending_drive_shares SET share_id=? WHERE contact_link=? AND file_path=?")) {
                for (String[] row : missingIds) {
                    update.setString(1, UUID.randomUUID().toString()); update.setString(2, row[0]); update.setString(3, row[1]); update.addBatch();
                }
                update.executeBatch();
            }
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

    public synchronized int unpublishForPath(Path file) throws SQLException {
        try (var c = connect(); var s = c.prepareStatement("DELETE FROM drive_share_offers WHERE file_path=?")) {
            s.setString(1, file.toAbsolutePath().normalize().toString());
            return s.executeUpdate();
        }
    }

    public synchronized int unpublishForPathAndContact(Path file, String contactLink) throws SQLException {
        try (var c = connect(); var s = c.prepareStatement("DELETE FROM drive_share_offers WHERE file_path=? AND contact_link=?")) {
            s.setString(1, file.toAbsolutePath().normalize().toString());
            s.setString(2, contactLink);
            return s.executeUpdate();
        }
    }

    public synchronized boolean isPublishedForPathAndContact(Path file, String contactLink) throws SQLException {
        try (var c = connect(); var s = c.prepareStatement("SELECT 1 FROM drive_share_offers WHERE file_path=? AND contact_link=? LIMIT 1")) {
            s.setString(1, file.toAbsolutePath().normalize().toString());
            s.setString(2, contactLink);
            try (var rows = s.executeQuery()) { return rows.next(); }
        }
    }

    /** Persist a complete selection before attempting any network delivery. */
    public synchronized int queueShares(String contactLink, List<Path> files) throws SQLException {
        int inserted = 0;
        try (var c = connect()) {
            c.setAutoCommit(false);
            try (var s = c.prepareStatement("INSERT OR IGNORE INTO pending_drive_shares(contact_link,file_path,queued_at,share_id) VALUES(?,?,?,?)")) {
                for (Path file : files) {
                    s.setString(1, contactLink);
                    s.setString(2, file.toAbsolutePath().normalize().toString());
                    s.setLong(3, System.currentTimeMillis());
                    s.setString(4, UUID.randomUUID().toString());
                    inserted += s.executeUpdate();
                }
                c.commit();
            } catch (SQLException error) {
                c.rollback();
                throw error;
            } finally { c.setAutoCommit(true); }
        }
        return inserted;
    }

    public synchronized List<PendingShare> pendingShares(String contactLink) throws SQLException {
        var result = new ArrayList<PendingShare>();
        try (var c = connect(); var s = c.prepareStatement(
                "SELECT share_id,contact_link,file_path FROM pending_drive_shares WHERE contact_link=? ORDER BY queued_at,file_path")) {
            s.setString(1, contactLink);
            try (var rows = s.executeQuery()) {
                while (rows.next()) result.add(new PendingShare(rows.getString(1), rows.getString(2), rows.getString(3)));
            }
        }
        return List.copyOf(result);
    }

    public synchronized List<String> pendingShareContacts() throws SQLException {
        var contacts = new ArrayList<String>();
        try (var c = connect(); var s = c.prepareStatement(
                "SELECT contact_link FROM pending_drive_shares GROUP BY contact_link ORDER BY MIN(queued_at)")) {
            try (var rows = s.executeQuery()) { while (rows.next()) contacts.add(rows.getString(1)); }
        }
        return List.copyOf(contacts);
    }

    public synchronized void completePendingShare(String contactLink, Path file) throws SQLException {
        try (var c = connect(); var s = c.prepareStatement("DELETE FROM pending_drive_shares WHERE contact_link=? AND file_path=?")) {
            s.setString(1, contactLink); s.setString(2, file.toAbsolutePath().normalize().toString()); s.executeUpdate();
        }
    }

    public synchronized int removePendingShares(Path file) throws SQLException {
        try (var c = connect(); var s = c.prepareStatement("DELETE FROM pending_drive_shares WHERE file_path=?")) {
            s.setString(1, file.toAbsolutePath().normalize().toString()); return s.executeUpdate();
        }
    }

    public synchronized int removePendingShares(Path file, String contactLink) throws SQLException {
        try (var c = connect(); var s = c.prepareStatement("DELETE FROM pending_drive_shares WHERE file_path=? AND contact_link=?")) {
            s.setString(1, file.toAbsolutePath().normalize().toString()); s.setString(2, contactLink); return s.executeUpdate();
        }
    }

    public synchronized Map<String, ShareCount> shareCountsForFiles(List<Path> files) throws SQLException {
        if (files == null || files.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(files.size(), "?"));
        Map<String, int[]> counts = new HashMap<>();
        try (var c = connect(); var s = c.prepareStatement(
                "SELECT contact_link,COUNT(DISTINCT file_path) FROM drive_share_offers WHERE file_path IN (" + placeholders + ") GROUP BY contact_link")) {
            for (int i = 0; i < files.size(); i++) s.setString(i + 1, files.get(i).toAbsolutePath().normalize().toString());
            try (var rows = s.executeQuery()) { while (rows.next()) counts.computeIfAbsent(rows.getString(1), ignored -> new int[2])[0] = rows.getInt(2); }
        }
        try (var c = connect(); var s = c.prepareStatement(
                "SELECT p.contact_link,COUNT(DISTINCT p.file_path) FROM pending_drive_shares p WHERE p.file_path IN (" + placeholders
                        + ") AND NOT EXISTS (SELECT 1 FROM drive_share_offers d WHERE d.contact_link=p.contact_link AND d.file_path=p.file_path) GROUP BY p.contact_link")) {
            for (int i = 0; i < files.size(); i++) s.setString(i + 1, files.get(i).toAbsolutePath().normalize().toString());
            try (var rows = s.executeQuery()) { while (rows.next()) counts.computeIfAbsent(rows.getString(1), ignored -> new int[2])[1] = rows.getInt(2); }
        }
        Map<String, ShareCount> result = new HashMap<>();
        counts.forEach((link, count) -> result.put(link, new ShareCount(count[0], count[1])));
        return Map.copyOf(result);
    }

    public synchronized void removeReceived(String id, String contactLink) throws SQLException {
        try (var c = connect()) {
            try (var s = c.prepareStatement("DELETE FROM shared_drive_offers WHERE share_id=? AND contact_link=?")) {
                s.setString(1, id); s.setString(2, contactLink); s.executeUpdate();
            }
            try (var s = c.prepareStatement("DELETE FROM shared_drive_files WHERE share_id=? AND contact_link=?")) {
                s.setString(1, id); s.setString(2, contactLink); s.executeUpdate();
            }
        }
    }

    public synchronized SharedFile findPublished(String id, String requesterLink) throws SQLException {
        try (var c = connect(); var s = c.prepareStatement("SELECT share_id,contact_link,name,file_path,file_size,shared_at FROM drive_share_offers WHERE share_id=? AND contact_link=?")) {
            s.setString(1, id); s.setString(2, requesterLink);
            try (var rows = s.executeQuery()) { return rows.next() ? new SharedFile(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getLong(5), rows.getLong(6)) : null; }
        }
    }

    public synchronized void recordOffer(String id, String senderLink, String name, long size, long receivedAt) throws SQLException {
        try (var c = connect(); var s = c.prepareStatement("INSERT INTO shared_drive_offers(share_id,contact_link,name,file_path,file_size,received_at) VALUES(?,?,?,NULL,?,?) ON CONFLICT(share_id) DO UPDATE SET name=excluded.name,file_size=excluded.file_size WHERE contact_link=excluded.contact_link")) {
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
