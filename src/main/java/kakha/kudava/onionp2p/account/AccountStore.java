package kakha.kudava.onionp2p.account;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import java.nio.file.*;
import java.sql.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;

/** Local profile metadata and Argon2id verifier, kept in the same owner-only SQLite file. */
public final class AccountStore {
    public record Profile(String username) {}
    private static final int SALT_BYTES = 16, HASH_BYTES = 32;
    private static final int MEMORY_KIB = 19_456, ITERATIONS = 2, PARALLELISM = 1;
    private final String url;

    public AccountStore(Path directory) throws Exception {
        Files.createDirectories(directory);
        Path file = directory.resolve("contacts.sqlite");
        if (!Files.exists(file)) Files.createFile(file);
        AppData.restrictToOwner(file);
        Class.forName("org.sqlite.JDBC");
        url = "jdbc:sqlite:" + file.toAbsolutePath();
        try (var connection = connect(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS account (id INTEGER PRIMARY KEY CHECK(id=1), "
                    + "username TEXT NOT NULL, password_salt BLOB NOT NULL, password_hash BLOB NOT NULL, "
                    + "argon_memory_kib INTEGER NOT NULL, argon_iterations INTEGER NOT NULL, argon_parallelism INTEGER NOT NULL)");
        }
    }

    private Connection connect() throws SQLException {
        var connection = DriverManager.getConnection(url);
        try (var statement = connection.createStatement()) { statement.execute("PRAGMA busy_timeout=5000"); }
        return connection;
    }

    public synchronized Profile profile() throws SQLException {
        try (var connection = connect(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT username FROM account WHERE id=1")) {
            return rows.next() ? new Profile(rows.getString(1)) : null;
        }
    }

    public synchronized void create(String username, char[] password) throws Exception {
        byte[] salt = null, hash = null;
        try {
            String cleanName = validateUsername(username);
            validatePassword(password);
            salt = new byte[SALT_BYTES]; new SecureRandom().nextBytes(salt);
            hash = hash(password, salt, MEMORY_KIB, ITERATIONS, PARALLELISM);
            try (var connection = connect(); var statement = connection.prepareStatement(
                    "INSERT INTO account VALUES (1, ?, ?, ?, ?, ?, ?)")) {
                statement.setString(1, cleanName); statement.setBytes(2, salt); statement.setBytes(3, hash);
                statement.setInt(4, MEMORY_KIB); statement.setInt(5, ITERATIONS); statement.setInt(6, PARALLELISM);
                statement.executeUpdate();
            } catch (SQLException e) {
                if (profile() != null) throw new IllegalStateException("An account is already set up.");
                throw e;
            }
        } finally {
            Arrays.fill(password, '\0');
            if (hash != null) Arrays.fill(hash, (byte) 0);
            if (salt != null) Arrays.fill(salt, (byte) 0);
        }
    }

    public synchronized Profile authenticate(String username, char[] password) throws Exception {
        byte[] salt = null, expected = null;
        try {
        validatePassword(password);
        String cleanName = username == null ? "" : username.strip();
        int memory, iterations, parallelism;
        try (var connection = connect(); var statement = connection.prepareStatement(
                "SELECT username,password_salt,password_hash,argon_memory_kib,argon_iterations,argon_parallelism FROM account WHERE id=1")) {
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("No account is set up yet.");
                if (!rows.getString(1).equals(cleanName)) throw new SecurityException("Username or password is incorrect.");
                salt = rows.getBytes(2); expected = rows.getBytes(3);
                memory = rows.getInt(4); iterations = rows.getInt(5); parallelism = rows.getInt(6);
            }
        }
        if (salt.length != SALT_BYTES || expected.length != HASH_BYTES || memory < 8_192 || memory > 131_072
                || iterations < 1 || iterations > 10 || parallelism != 1) throw new SQLException("Stored account verifier is invalid.");
        byte[] actual = hash(password, salt, memory, iterations, parallelism);
        try {
            if (!MessageDigest.isEqual(expected, actual)) throw new SecurityException("Username or password is incorrect.");
            return new Profile(cleanName);
        } finally { Arrays.fill(actual, (byte) 0); }
        } finally {
            Arrays.fill(password, '\0');
            if (expected != null) Arrays.fill(expected, (byte) 0);
            if (salt != null) Arrays.fill(salt, (byte) 0);
        }
    }

    private static byte[] hash(char[] password, byte[] salt, int memory, int iterations, int parallelism) throws CharacterCodingException {
        var encoded = java.nio.charset.StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(password));
        byte[] input = new byte[encoded.remaining()]; encoded.get(input);
        try {
            var parameters = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                    .withVersion(Argon2Parameters.ARGON2_VERSION_13).withSalt(salt)
                    .withMemoryAsKB(memory).withIterations(iterations).withParallelism(parallelism).build();
            var generator = new Argon2BytesGenerator(); generator.init(parameters);
            byte[] output = new byte[HASH_BYTES]; generator.generateBytes(input, output); return output;
        } finally { Arrays.fill(input, (byte) 0); }
    }

    public static String validateUsername(String username) {
        String clean = username == null ? "" : username.strip();
        if (!clean.matches("[A-Za-z0-9_.-]{3,32}"))
            throw new IllegalArgumentException("Username must be 3–32 characters: letters, numbers, dot, dash or underscore.");
        return clean;
    }
    public static void validatePassword(char[] password) {
        if (password == null || password.length < 12 || password.length > 1024)
            throw new IllegalArgumentException("Password must be at least 12 characters.");
    }
}
