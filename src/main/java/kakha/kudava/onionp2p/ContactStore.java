package kakha.kudava.onionp2p;

import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Stores only acknowledged contacts. Pending requests and private/session keys are not stored here. */
final class ContactStore {
    record Contact(String link, String onion, long acknowledgedAt) {}
    private final String url;

    ContactStore(Path directory) throws Exception {
        Files.createDirectories(directory);
        Path file = directory.resolve("contacts.sqlite");
        if (!Files.exists(file)) Files.createFile(file);
        ContactLink.restrictToOwner(file);
        Class.forName("org.sqlite.JDBC");
        url = "jdbc:sqlite:" + file.toAbsolutePath();
        try (var connection = connect(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS contacts (public_key_link TEXT PRIMARY KEY, "
                    + "onion_address TEXT NOT NULL, acknowledged_at INTEGER NOT NULL)");
        }
    }

    private Connection connect() throws SQLException {
        var connection = DriverManager.getConnection(url);
        try (var statement = connection.createStatement()) { statement.execute("PRAGMA busy_timeout=5000"); }
        return connection;
    }

    synchronized void acknowledge(String link, String onion) throws SQLException {
        try (var connection = connect(); var statement = connection.prepareStatement(
                "INSERT INTO contacts VALUES (?, ?, ?) ON CONFLICT(public_key_link) DO UPDATE SET "
                        + "onion_address=excluded.onion_address")) {
            statement.setString(1, link);
            statement.setString(2, onion);
            statement.setLong(3, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    synchronized List<Contact> all() throws SQLException {
        var contacts = new ArrayList<Contact>();
        try (var connection = connect(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT * FROM contacts ORDER BY acknowledged_at")) {
            while (rows.next()) contacts.add(new Contact(rows.getString(1), rows.getString(2), rows.getLong(3)));
        }
        return List.copyOf(contacts);
    }
}
