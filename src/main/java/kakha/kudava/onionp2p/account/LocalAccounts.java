package kakha.kudava.onionp2p.account;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Local account selection; legacy accounts keep their original data directory. */
public final class LocalAccounts {
    public record Unlocked(AccountStore.Profile profile, Path directory) {}
    private final Path root;
    private final AccountStore legacy;
    public LocalAccounts(Path root) throws Exception {
        this.root = root.toAbsolutePath().normalize(); legacy = new AccountStore(this.root);
    }
    private Path profileDirectory(String username) throws Exception {
        String clean = AccountStore.validateUsername(username).toLowerCase(Locale.ROOT);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(clean.getBytes(StandardCharsets.UTF_8)));
        return root.resolve("accounts").resolve(hash);
    }
    public synchronized boolean hasAccounts() throws Exception {
        if (legacy.profile() != null) return true;
        Path profiles = root.resolve("accounts"); if (!Files.isDirectory(profiles)) return false;
        try (var entries = Files.newDirectoryStream(profiles)) {
            for (Path entry : entries) if (Files.isRegularFile(entry.resolve("contacts.sqlite")) && new AccountStore(entry).profile() != null) return true;
        }
        return false;
    }
    public synchronized Unlocked register(String username, char[] password) throws Exception {
        try {
            String clean = AccountStore.validateUsername(username);
            var old = legacy.profile();
            if (old != null && old.username().equalsIgnoreCase(clean)) throw new IllegalArgumentException("That username already exists on this device.");
            if (old == null && !hasAccounts()) {
                legacy.create(clean, password);
                return new Unlocked(legacy.profile(), root);
            }
            Path directory = profileDirectory(clean);
            var store = new AccountStore(directory);
            if (store.profile() != null) throw new IllegalArgumentException("That username already exists on this device.");
            store.create(clean, password);
            return new Unlocked(store.profile(), directory);
        } finally { Arrays.fill(password, '\0'); }
    }
    public synchronized Unlocked login(String username, char[] password) throws Exception {
        try {
            String clean = username == null ? "" : username.strip();
            var old = legacy.profile();
            if (old != null && old.username().equalsIgnoreCase(clean)) return new Unlocked(legacy.authenticate(clean, password), root);
            Path directory;
            try { directory = profileDirectory(clean); }
            catch (IllegalArgumentException invalid) { throw new SecurityException("Username or password is incorrect."); }
            if (!Files.isRegularFile(directory.resolve("contacts.sqlite"))) throw new SecurityException("Username or password is incorrect.");
            var store = new AccountStore(directory);
            return new Unlocked(store.authenticate(clean, password), directory);
        } finally { Arrays.fill(password, '\0'); }
    }
}
