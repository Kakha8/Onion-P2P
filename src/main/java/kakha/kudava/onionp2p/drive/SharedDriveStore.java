package kakha.kudava.onionp2p.drive;

import kakha.kudava.onionp2p.account.AppData;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Metadata for files received through Drive sharing, separate from chat history. */
public final class SharedDriveStore {
    public record SharedFile(String id, String contactLink, String name, String path, long size, long receivedAt) {}
    private final String url;

    public SharedDriveStore(Path appDirectory) throws Exception {
        Files.createDirectories(appDirectory);
        Path file = appDirectory.resolve("contacts.sqlite");
        if (!Files.exists(file)) Files.createFile(file);
        AppData.restrictToOwner(file);
        Class.forName("org.sqlite.JDBC");
        url = "jdbc:sqlite:" + file.toAbsolutePath();
        try (var connection = connect(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS shared_drive_files ("
                    + "share_id TEXT PRIMARY KEY, contact_link TEXT NOT NULL, name TEXT NOT NULL, "
                    + "file_path TEXT NOT NULL, file_size INTEGER NOT NULL, received_at INTEGER NOT NULL)");
        }
    }

    private java.sql.Connection connect() throws SQLException {
        var connection = DriverManager.getConnection(url);
        try (var statement = connection.createStatement()) { statement.execute("PRAGMA busy_timeout=5000"); }
        return connection;
    }

    public synchronized void save(String id, String link, String name, Path path, long size, long receivedAt) throws SQLException {
        try (var connection = connect(); var statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO shared_drive_files(share_id,contact_link,name,file_path,file_size,received_at) VALUES(?,?,?,?,?,?)")) {
            statement.setString(1, id); statement.setString(2, link); statement.setString(3, name);
            statement.setString(4, path.toAbsolutePath().toString()); statement.setLong(5, size); statement.setLong(6, receivedAt);
            statement.executeUpdate();
        }
    }

    public synchronized List<SharedFile> all() throws SQLException {
        var files = new ArrayList<SharedFile>();
        try (var connection = connect(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT share_id,contact_link,name,file_path,file_size,received_at FROM shared_drive_files ORDER BY received_at DESC")) {
            while (rows.next()) files.add(new SharedFile(rows.getString(1), rows.getString(2), rows.getString(3),
                    rows.getString(4), rows.getLong(5), rows.getLong(6)));
        }
        return List.copyOf(files);
    }
}
