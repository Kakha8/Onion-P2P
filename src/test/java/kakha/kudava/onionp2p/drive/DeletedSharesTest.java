package kakha.kudava.onionp2p.drive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class DeletedSharesTest {
    @TempDir Path temp;
    private String publish(SharedDriveStore store, String contact, Path path) throws Exception {
        String id = UUID.randomUUID().toString();
        store.publish(id, contact, path.getFileName().toString(), path, Files.isDirectory(path) ? -1 : Files.size(path), 0);
        return id;
    }

    @Test void deletionCountsParentsAndDescendantsButRevokesOnlyTheDeletedTree() throws Exception {
        var store = new SharedDriveStore(temp.resolve("data"));
        Path drive = Files.createDirectory(temp.resolve("drive"));
        Path folder = Files.createDirectory(drive.resolve("folder"));
        Path nested = Files.writeString(folder.resolve("nested.txt"), "shared");
        Path sibling = Files.createDirectory(drive.resolve("folder-other"));
        String parent = publish(store, "parent-contact", drive);
        publish(store, "same-contact", folder);
        publish(store, "same-contact", nested);
        String other = publish(store, "other-contact", sibling);
        store.queueShares("offline-contact", List.of(nested));
        store.queueShares("same-contact", List.of(folder));
        assertEquals(3, store.deletionShareCount(folder));
        assertEquals(Set.of("same-contact", "offline-contact"), store.removeDeletedShares(folder));
        assertEquals(List.of(parent), store.publishedIds("parent-contact"));
        assertEquals(List.of(other), store.publishedIds("other-contact"));
        assertTrue(store.publishedIds("same-contact").isEmpty());
        assertTrue(store.pendingShares("offline-contact").isEmpty());
        assertTrue(store.pendingShares("same-contact").isEmpty());
        assertEquals(1, store.deletionShareCount(nested), "The surrounding shared folder still grants access");
    }

    @Test void externallyDeletedSourcesAreDetectedAndCannotBeRepublishedByAnInflightOffer() throws Exception {
        var store = new SharedDriveStore(temp.resolve("data"));
        Path source = Files.writeString(temp.resolve("source.txt"), "shared");
        String id = publish(store, "recipient", source);
        store.queueShares("offline", List.of(source));
        assertTrue(store.missingSharedPaths().isEmpty());
        Files.delete(source);
        assertEquals(Set.of(source.toAbsolutePath().normalize()), store.missingSharedPaths());
        assertThrows(SQLException.class, () -> store.publish(UUID.randomUUID().toString(), "recipient", "source.txt", source, 6, 0));
        assertThrows(SQLException.class, () -> store.queueShares("another", List.of(source)));
        assertEquals(Set.of("recipient", "offline"), store.removeDeletedShares(source));
        assertNull(store.findPublished(id, "recipient"));
        assertTrue(store.pendingShareContacts().isEmpty());
        assertTrue(store.missingSharedPaths().isEmpty());
    }

    @Test void removingAReceivedOfferLeavesAnAlreadyDownloadedCopyOnDisk() throws Exception {
        var store = new SharedDriveStore(temp.resolve("recipient"));
        String id = UUID.randomUUID().toString();
        Path copy = Files.writeString(temp.resolve("downloaded.txt"), "my downloaded copy");
        store.recordOffer(id, "owner", "source.txt", 18, 0);
        store.markDownloaded(id, copy);
        assertEquals(1, store.reconcileReceived("owner", List.of()));
        assertTrue(store.all().isEmpty());
        assertEquals("my downloaded copy", Files.readString(copy));
    }
}
