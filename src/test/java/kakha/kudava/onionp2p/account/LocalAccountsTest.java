package kakha.kudava.onionp2p.account;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class LocalAccountsTest {
    @TempDir Path root;
    private char[] password() { return "a private test password".toCharArray(); }
    @Test void registersAndLogsInToIsolatedAccountsWithoutChangingTheOriginal() throws Exception {
        var accounts = new LocalAccounts(root); assertFalse(accounts.hasAccounts());
        var alice = accounts.register("Alice", password()); assertEquals(root, alice.directory());
        Files.writeString(alice.directory().resolve("keep.txt"), "original account data");
        var bob = accounts.register("Bob", password());
        assertNotEquals(alice.directory(), bob.directory()); assertTrue(bob.directory().startsWith(root.resolve("accounts")));
        assertNotEquals(kakha.kudava.onionp2p.contacts.ContactLink.loadOrCreate(alice.directory(), "Alice"),
                kakha.kudava.onionp2p.contacts.ContactLink.loadOrCreate(bob.directory(), "Bob"));
        assertTrue(accounts.hasAccounts());
        var restarted = new LocalAccounts(root);
        assertEquals(alice.directory(), restarted.login("Alice", password()).directory());
        assertEquals(bob.directory(), restarted.login("Bob", password()).directory());
        assertEquals("original account data", Files.readString(root.resolve("keep.txt")));
        assertThrows(IllegalArgumentException.class, () -> accounts.register("alice", password()));
        assertThrows(IllegalArgumentException.class, () -> accounts.register("BOB", password()));
        assertThrows(SecurityException.class, () -> accounts.login("Bob", "incorrect long password".toCharArray()));
        assertThrows(SecurityException.class, () -> accounts.login("Unknown", password()));
    }
    @Test void existingAccountRemainsUsableAndPasswordsAreCleared() throws Exception {
        new AccountStore(root).create("Existing", password());
        var accounts = new LocalAccounts(root); char[] secret = password();
        assertEquals(root, accounts.login("Existing", secret).directory());
        for (char character : secret) assertEquals('\0', character);
        assertThrows(IllegalArgumentException.class, () -> accounts.register("NewUser", "short".toCharArray()));
        assertEquals("Existing", new AccountStore(root).profile().username());
    }
}
