package kakha.kudava.onionp2p.contacts;

import kakha.kudava.onionp2p.drive.FolderAccess;
import kakha.kudava.onionp2p.drive.SharedDriveStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class FolderSharingTest {
    @TempDir Path temp;
    private ContactRendezvous.Material material(boolean owner) {
        byte[] a = new byte[32], b = new byte[32], secret = new byte[32];
        Arrays.fill(a, (byte) 1); Arrays.fill(b, (byte) 2); Arrays.fill(secret, (byte) 3);
        return new ContactRendezvous.Material(new ContactRendezvous.Addresses("local", "remote"), "Alice", "Bob",
                owner ? a : b, owner ? b : a, secret, new byte[32]);
    }

    private final class Session implements AutoCloseable {
        final ServerSocket listener;
        final Socket client, server;
        final CompletableFuture<Void> receiving;
        Session(SharedDriveStore receiver, String contact, boolean receiverIsOwner) throws Exception {
            listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            client = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort()); client.setSoTimeout(5000);
            server = listener.accept();
            receiving = CompletableFuture.runAsync(() -> {
                try {
                    assertEquals(ContactMessaging.MAGIC, new DataInputStream(server.getInputStream()).readInt());
                    ContactMessaging.receive(server, material(receiverIsOwner), null, receiver, contact, true, null, null);
                } catch (Exception error) {
                    if (!server.isClosed()) throw new CompletionException(error);
                }
            });
        }
        public void close() throws Exception {
            server.close(); client.close(); listener.close(); receiving.get(5, TimeUnit.SECONDS);
        }
    }

    @Test @Timeout(30)
    void encryptedOffersLiveBrowsingDownloadsRenameAndRevocation() throws Exception {
        var owner = new SharedDriveStore(temp.resolve("owner-db"));
        var recipient = new SharedDriveStore(temp.resolve("recipient-db"));
        Path folder = Files.createDirectory(temp.resolve("Documents"));
        Files.createDirectory(folder.resolve("Empty"));
        Path nested = Files.createDirectory(folder.resolve("Nested"));
        Files.writeString(nested.resolve("hello.txt"), "Hello, encrypted folder!");
        String id = UUID.randomUUID().toString();
        try (var session = new Session(recipient, "owner", false)) {
            ContactMessaging.sendDriveShare(session.client, material(true), owner, "recipient", id, folder, true, ignored -> {}, () -> false);
            assertEquals(1, recipient.all().size()); assertTrue(recipient.all().getFirst().folder());
            assertNull(recipient.all().getFirst().path());
        }
        try (var session = new Session(owner, "recipient", true)) {
            var entries = ContactMessaging.browseFolder(session.client, material(false), id, "", true, recipient, "owner");
            assertEquals(List.of("Empty", "Nested"), entries.stream().map(FolderAccess.Entry::name).toList());
            Files.writeString(folder.resolve("added.txt"), "Added after sharing");
            assertEquals(3, ContactMessaging.browseFolder(session.client, material(false), id, "", false, recipient, "owner").size());
            Path destination = temp.resolve("download");
            ContactMessaging.downloadFolderFile(session.client, material(false), id, "Nested/hello.txt", destination, "owner", false);
            assertEquals("Hello, encrypted folder!", Files.readString(destination.resolve("hello.txt")));
            Path renamed = folder.resolveSibling("Renamed"); Files.move(folder, renamed); owner.moved(folder, renamed);
            assertEquals(id, owner.findPublished(id, "recipient").id());
            assertEquals(3, ContactMessaging.browseFolder(session.client, material(false), id, "", false, recipient, "owner").size());
            assertEquals("Renamed", recipient.all().getFirst().name());
            owner.unpublishForPathAndContact(renamed, "recipient");
            assertThrows(IOException.class, () -> ContactMessaging.browseFolder(session.client, material(false), id, "", false, recipient, "owner"));
            assertThrows(IOException.class, () -> ContactMessaging.downloadFolderFile(session.client, material(false), id, "Nested/hello.txt", destination, "owner", false));
        }
    }

    @Test
    void confinesPathsAndChecksRecipient() throws Exception {
        var store = new SharedDriveStore(temp.resolve("db"));
        Path root = Files.createDirectory(temp.resolve("root"));
        Files.writeString(temp.resolve("private.txt"), "private");
        String id = UUID.randomUUID().toString(); store.publish(id, "allowed", "root", root, -1, 0);
        for (String bad : List.of("../private.txt", "C:/private.txt", "/private.txt", "..\\private.txt", "file:stream"))
            assertThrows(IOException.class, () -> FolderAccess.resolve(store, id, "allowed", bad), bad);
        assertThrows(IOException.class, () -> FolderAccess.resolve(store, id, "stranger", ""));
        assertEquals(root.toAbsolutePath(), FolderAccess.resolve(store, id, "allowed", ""));
    }

    @Test @Timeout(30)
    void existingSingleFileSharesStillDownload() throws Exception {
        var owner = new SharedDriveStore(temp.resolve("owner"));
        var receiver = new SharedDriveStore(temp.resolve("receiver"));
        Path source = Files.writeString(temp.resolve("file.txt"), "Existing file share");
        String id = UUID.randomUUID().toString();
        try (var session = new Session(receiver, "owner", false)) {
            ContactMessaging.sendDriveShare(session.client, material(true), owner, "recipient", id, source, true, ignored -> {}, () -> false);
            assertFalse(receiver.all().getFirst().folder());
        }
        String previous = System.getProperty("onion.dataDir");
        System.setProperty("onion.dataDir", temp.resolve("downloads").toString());
        try (var session = new Session(owner, "recipient", true)) {
            ContactMessaging.downloadDriveShare(session.client, material(false), receiver, id, "owner", true, null);
            assertTrue(receiver.all().getFirst().downloaded());
            assertEquals("Existing file share", Files.readString(Path.of(receiver.all().getFirst().path())));
        } finally {
            if (previous == null) System.clearProperty("onion.dataDir"); else System.setProperty("onion.dataDir", previous);
        }
    }

    @Test
    void offlineFolderSelectionIsDurableAndCanBeRemoved() throws Exception {
        Path db = temp.resolve("db"); var store = new SharedDriveStore(db);
        Path folder = Files.createDirectory(temp.resolve("empty"));
        assertEquals(1, store.queueShares("friend", List.of(folder)));
        String id = store.pendingShares("friend").getFirst().id();
        var reopened = new SharedDriveStore(db);
        assertEquals(id, reopened.pendingShares("friend").getFirst().id());
        assertEquals(1, reopened.shareCountsForFiles(List.of(folder)).get("friend").queued());
        reopened.removePendingShares(folder, "friend"); assertTrue(reopened.pendingShares("friend").isEmpty());
    }

    @Test
    void rejectsMaliciousListingNamesAndPreservesEmptyFolders() throws Exception {
        var empty = new FolderAccess.Entry("Empty", true, -1);
        assertEquals(List.of(empty), FolderAccess.decode(FolderAccess.encode(List.of(empty))));
        for (String name : List.of("../escape", "C:escape", "NUL.txt", "x\\y", "bad.", "bad "))
            assertThrows(IOException.class, () -> FolderAccess.decode(FolderAccess.encode(List.of(new FolderAccess.Entry(name, true, -1)))));
        assertThrows(IOException.class, () -> FolderAccess.decode(FolderAccess.encode(List.of(empty, empty))));
    }
}
