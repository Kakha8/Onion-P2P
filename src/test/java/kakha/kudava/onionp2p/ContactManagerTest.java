package kakha.kudava.onionp2p;

import kakha.kudava.onionp2p.contacts.ContactLink;
import kakha.kudava.onionp2p.contacts.ContactManager;
import kakha.kudava.onionp2p.contacts.ContactStore;
import kakha.kudava.onionp2p.tor.OnionServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ContactManagerTest {
    @TempDir Path directory;
    @Test void importingWhileOfflineDoesNotSaveAContact() throws Exception {
        String peer = ContactLink.loadOrCreate(directory.resolve("peer"));
        try (var tor = new OnionServer(directory); var manager = new ContactManager(directory, tor, e -> {})) {
            var addresses = manager.add(peer);
            assertEquals(addresses, manager.add(peer));
            assertTrue(manager.contacts().isEmpty());
        }
        assertTrue(new ContactStore(directory).all().isEmpty());
    }
}
