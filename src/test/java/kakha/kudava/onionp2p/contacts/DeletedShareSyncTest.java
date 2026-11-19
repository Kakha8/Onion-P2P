package kakha.kudava.onionp2p.contacts;

import kakha.kudava.onionp2p.drive.SharedDriveStore;
import kakha.kudava.onionp2p.messaging.MessageStore;
import kakha.kudava.onionp2p.tor.OnionServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.io.DataInputStream;
import java.net.*;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DeletedShareSyncTest {
    @TempDir Path temp;

    @Test @Timeout(20)
    void ownerDeleteImmediatelyRemovesTheOfferAndNotifiesTheRecipient() throws Exception {
        Path owner = temp.resolve("owner"), recipient = temp.resolve("recipient");
        String ownerLink = ContactLink.loadOrCreate(owner, "Alice"), recipientLink = ContactLink.loadOrCreate(recipient, "Bob");
        var offers = new SharedDriveStore(recipient);
        try (var session = new OwnerSession(owner, recipient, ownerLink, recipientLink, offers)) {
            Path source = Files.writeString(owner.resolve("source.txt"), "shared");
            Path other = Files.writeString(owner.resolve("source-other.txt"), "keep");
            assertEquals(2, session.manager.shareDriveFiles(recipientLink, List.of(source, other)).sharedNow());
            var offer = offers.all().stream().filter(file -> file.name().equals("source.txt")).findFirst().orElseThrow();
            Path copy = Files.writeString(recipient.resolve("saved-copy.txt"), "already downloaded");
            offers.markDownloaded(offer.id(), copy);
            int updatesBefore = session.updates.get();
            assertEquals(1, session.manager.driveDeletionShareCount(source));
            Files.delete(source);
            session.manager.driveEntryDeleted(source);
            await(() -> offers.all().size() == 1 && session.updates.get() > updatesBefore);
            assertEquals("source-other.txt", offers.all().getFirst().name());
            assertEquals("already downloaded", Files.readString(copy));
            session.manager.send(recipientLink, "Text remains connected after deletion");
        }
    }

    @Test @Timeout(20)
    void deletionWhileOfflineIsReconciledWhenTheOwnerReconnects() throws Exception {
        Path owner = temp.resolve("owner"), recipient = temp.resolve("recipient");
        String ownerLink = ContactLink.loadOrCreate(owner, "Alice"), recipientLink = ContactLink.loadOrCreate(recipient, "Bob");
        var offers = new SharedDriveStore(recipient);
        Path folder = Files.createDirectory(owner.resolve("folder"));
        Path child = Files.writeString(folder.resolve("child.txt"), "shared");
        try (var session = new OwnerSession(owner, recipient, ownerLink, recipientLink, offers)) {
            assertEquals(2, session.manager.shareDriveFiles(recipientLink, List.of(folder, child)).sharedNow());
            assertEquals(2, offers.all().size());
        }
        // A disconnected owner can revoke locally without waiting for Tor.
        var sourceStore = new SharedDriveStore(owner);
        sourceStore.queueShares(recipientLink, List.of(child));
        Files.delete(child); Files.delete(folder);
        try (var tor = new OnionServer(owner); var offline = new ContactManager(owner, tor, ignored -> {})) {
            offline.driveEntryDeleted(folder);
            assertTrue(sourceStore.publishedIds(recipientLink).isEmpty());
            assertTrue(sourceStore.pendingShares(recipientLink).isEmpty());
        }
        assertEquals(2, offers.all().size(), "Offline clients retain their cached offers until contact is possible");
        try (var session = new OwnerSession(owner, recipient, ownerLink, recipientLink, offers)) {
            session.tor.socksReady().complete(1); // Reuse the injected socket; there is no SOCKS server.
            assertTrue(session.manager.isOnline(recipientLink));
            await(() -> offers.all().isEmpty());
        }
    }

    @Test @Timeout(25)
    void externallyDeletedSourcesAreRemovedWithoutRefreshingTheRecipient() throws Exception {
        Path owner = temp.resolve("owner"), recipient = temp.resolve("recipient");
        String ownerLink = ContactLink.loadOrCreate(owner, "Alice"), recipientLink = ContactLink.loadOrCreate(recipient, "Bob");
        var offers = new SharedDriveStore(recipient);
        try (var session = new OwnerSession(owner, recipient, ownerLink, recipientLink, offers)) {
            Path source = Files.writeString(owner.resolve("external.txt"), "shared");
            assertEquals(1, session.manager.shareDriveFiles(recipientLink, List.of(source)).sharedNow());
            Files.delete(source);
            await(() -> offers.all().isEmpty(), 14);
        }
    }

    @FunctionalInterface private interface Condition { boolean met() throws Exception; }
    private static void await(Condition condition) throws Exception {
        await(condition, 4);
    }
    private static void await(Condition condition, long seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.met() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.met());
    }

    private static final class OwnerSession implements AutoCloseable {
        final OnionServer tor;
        final ContactManager manager;
        final ServerSocket listener;
        final Socket sender, receiver;
        final ContactRendezvous.Material material;
        final CompletableFuture<Void> serving;
        final AtomicInteger updates = new AtomicInteger();
        OwnerSession(Path owner, Path recipient, String ownerLink, String recipientLink, SharedDriveStore offers) throws Exception {
            material = ContactRendezvous.derive(recipient, ownerLink);
            new ContactStore(owner).acknowledge(recipientLink, material.addresses().local(), "Bob");
            tor = new OnionServer(owner);
            manager = new ContactManager(owner, tor, ignored -> {});
            manager.restore();
            listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            sender = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
            receiver = listener.accept();
            var field = ContactManager.class.getDeclaredField("messageSessions"); field.setAccessible(true);
            var type = Class.forName(ContactManager.class.getName() + "$MessageSession");
            var ctor = type.getDeclaredConstructor(); ctor.setAccessible(true);
            Object session = ctor.newInstance();
            var socket = type.getDeclaredField("socket"); socket.setAccessible(true); socket.set(session, sender);
            ((Map<String, Object>) field.get(manager)).put(recipientLink, session);
            serving = CompletableFuture.runAsync(() -> {
                try {
                    assertEquals(ContactMessaging.MAGIC, new DataInputStream(receiver.getInputStream()).readInt());
                    ContactMessaging.receive(receiver, material, new MessageStore(recipient), offers, ownerLink, true, null, updates::incrementAndGet);
                } catch (Exception error) { if (!receiver.isClosed() && !sender.isClosed()) throw new CompletionException(error); }
            });
        }
        public void close() throws Exception {
            receiver.close(); sender.close(); listener.close(); manager.close(); tor.close();
            serving.get(3, TimeUnit.SECONDS); material.close();
        }
    }
}
