package kakha.kudava.onionp2p;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/** Local, plaintext conversation history. Transport payloads are encrypted separately. */
final class MessageStore {
    record Message(String id, String contactLink, boolean outgoing, String body,
                   String attachmentName, String attachmentPath, long attachmentSize, long timestamp) {}

    private final String url;

    MessageStore(Path directory) throws Exception {
        Files.createDirectories(directory);
        Path file = directory.resolve("contacts.sqlite");
        if (!Files.exists(file)) Files.createFile(file);
        ContactLink.restrictToOwner(file);
        Class.forName("org.sqlite.JDBC");
        url = "jdbc:sqlite:" + file.toAbsolutePath();
        try (var connection = connect(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS messages ("
                    + "contact_link TEXT NOT NULL, message_id TEXT NOT NULL, direction INTEGER NOT NULL, "
                    + "body TEXT NOT NULL, sent_at INTEGER NOT NULL, attachment_name TEXT, attachment_path TEXT, "
                    + "attachment_size INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(contact_link,message_id))");
            addColumn(statement, "attachment_name TEXT");
            addColumn(statement, "attachment_path TEXT");
            addColumn(statement, "attachment_size INTEGER NOT NULL DEFAULT 0");
            statement.execute("CREATE INDEX IF NOT EXISTS messages_conversation_time ON messages(contact_link,sent_at)");
        }
    }

    private Connection connect() throws SQLException {
        var connection = DriverManager.getConnection(url);
        try (var statement = connection.createStatement()) { statement.execute("PRAGMA busy_timeout=5000"); }
        return connection;
    }

    synchronized void save(String contactLink, String id, boolean outgoing, String body, long timestamp) throws SQLException {
        save(contactLink, id, outgoing, body, null, null, 0, timestamp);
    }

    synchronized void saveFile(String contactLink, String id, boolean outgoing, String name,
                               String path, long size, long timestamp) throws SQLException {
        saveFile(contactLink, id, outgoing, "", name, path, size, timestamp);
    }

    synchronized void saveFile(String contactLink, String id, boolean outgoing, String caption, String name,
                               String path, long size, long timestamp) throws SQLException {
        save(contactLink, id, outgoing, caption, name, path, size, timestamp);
    }

    private void save(String contactLink, String id, boolean outgoing, String body, String fileName,
                      String filePath, long fileSize, long timestamp) throws SQLException {
        try (var connection = connect(); var statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO messages(contact_link,message_id,direction,body,sent_at,attachment_name,attachment_path,attachment_size) VALUES(?,?,?,?,?,?,?,?)")) {
            statement.setString(1, contactLink);
            statement.setString(2, id);
            statement.setInt(3, outgoing ? 1 : 0);
            statement.setString(4, body);
            statement.setLong(5, timestamp);
            statement.setString(6, fileName);
            statement.setString(7, filePath);
            statement.setLong(8, fileSize);
            statement.executeUpdate();
        }
    }

    synchronized List<Message> all(String contactLink) throws SQLException {
        var messages = new ArrayList<Message>();
        try (var connection = connect(); var statement = connection.prepareStatement(
                "SELECT message_id,direction,body,attachment_name,attachment_path,attachment_size,sent_at FROM messages WHERE contact_link=? ORDER BY sent_at,message_id LIMIT 5000")) {
            statement.setString(1, contactLink);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) messages.add(new Message(rows.getString(1), contactLink,
                        rows.getInt(2) == 1, rows.getString(3), rows.getString(4), rows.getString(5),
                        rows.getLong(6), rows.getLong(7)));
            }
        }
        return List.copyOf(messages);
    }

    private static void addColumn(Statement statement, String definition) throws SQLException {
        try { statement.execute("ALTER TABLE messages ADD COLUMN " + definition); }
        catch (SQLException e) {
            if (e.getMessage() == null || !e.getMessage().toLowerCase(java.util.Locale.ROOT).contains("duplicate column name")) throw e;
        }
    }
}
