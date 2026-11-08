package kakha.kudava.onionp2p;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/** Local, plaintext conversation history. Transport payloads are encrypted separately. */
final class MessageStore {
    record Message(String id, String contactLink, boolean outgoing, String body, long timestamp) {}

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
                    + "body TEXT NOT NULL, sent_at INTEGER NOT NULL, PRIMARY KEY(contact_link,message_id))");
            statement.execute("CREATE INDEX IF NOT EXISTS messages_conversation_time ON messages(contact_link,sent_at)");
        }
    }

    private Connection connect() throws SQLException {
        var connection = DriverManager.getConnection(url);
        try (var statement = connection.createStatement()) { statement.execute("PRAGMA busy_timeout=5000"); }
        return connection;
    }

    synchronized void save(String contactLink, String id, boolean outgoing, String body, long timestamp) throws SQLException {
        try (var connection = connect(); var statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO messages(contact_link,message_id,direction,body,sent_at) VALUES(?,?,?,?,?)")) {
            statement.setString(1, contactLink);
            statement.setString(2, id);
            statement.setInt(3, outgoing ? 1 : 0);
            statement.setString(4, body);
            statement.setLong(5, timestamp);
            statement.executeUpdate();
        }
    }

    synchronized List<Message> all(String contactLink) throws SQLException {
        var messages = new ArrayList<Message>();
        try (var connection = connect(); var statement = connection.prepareStatement(
                "SELECT message_id,direction,body,sent_at FROM messages WHERE contact_link=? ORDER BY sent_at,message_id LIMIT 5000")) {
            statement.setString(1, contactLink);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) messages.add(new Message(rows.getString(1), contactLink,
                        rows.getInt(2) == 1, rows.getString(3), rows.getLong(4)));
            }
        }
        return List.copyOf(messages);
    }
}
