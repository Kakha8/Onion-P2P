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
    @Test @Timeout(30)
    void fourthRecipientReceivesAnEncryptedQueuePositionAndStartsAfterASlotIsFreed() throws Exception {
        var owner = new SharedDriveStore(temp.resolve("limited-owner"));
        Path file = Files.write(temp.resolve("limited.bin"), new byte[900000]);
        try (var uploads = new kakha.kudava.onionp2p.drive.UploadTracker()) {
            var sessions = new ArrayList<Session>();
            var shares = new ArrayList<String>();
            try {
                for (int i = 0; i < 4; i++) {
                    String share = UUID.randomUUID().toString(); shares.add(share);
                    owner.publish(share, "p" + i, "limited.bin", file, 900000, System.currentTimeMillis());
                    var session = new Session(owner, "p" + i, true, uploads); sessions.add(session);
                    if (i < 3) {
                        ContactMessaging.downloadChunk(session.client, material(false), share, "", 0, "", true);
                        ContactMessaging.ping(session.client, material(false), false);
                    }
                }
                var fourth = sessions.get(3);
                var queued = assertThrows(kakha.kudava.onionp2p.drive.DownloadQueue.QueuedException.class,
                        () -> ContactMessaging.downloadChunk(fourth.client, material(false), shares.get(3), "", 0, "", true));
                assertEquals(1, queued.position);
                assertEquals(3, uploads.items().stream().filter(item -> item.state() == kakha.kudava.onionp2p.drive.UploadTracker.State.UPLOADING).count());
                ContactMessaging.notifyDownloadPaused(sessions.getFirst().client, material(false), shares.getFirst(), "", false);
                var chunk = ContactMessaging.downloadChunk(fourth.client, material(false), shares.get(3), "", 0, "", false);
                assertEquals(262144, chunk.bytes().length);
            } finally { for (Session session : sessions) session.close(); }
        }
    }
    @Test @Timeout(30)
    void senderCancellationBlocksOnlyTheCurrentRequestAndRevocationHasADistinctReason() throws Exception {
        var owner = new SharedDriveStore(temp.resolve("sender-stop"));
        Path file = Files.write(temp.resolve("sender-stop.bin"), new byte[700000]);
        String share = UUID.randomUUID().toString(), task = UUID.randomUUID().toString();
        owner.publish(share, "recipient", "sender-stop.bin", file, 700000, System.currentTimeMillis());
        try (var uploads = new kakha.kudava.onionp2p.drive.UploadTracker(); var session = new Session(owner, "recipient", true, uploads)) {
            var first = ContactMessaging.downloadChunk(session.client, material(false), share, "", 0, "", true, task);
            ContactMessaging.ping(session.client, material(false), false);
            assertNotNull(uploads.cancelBySender(uploads.items().getFirst().key()));
            var cancelled = assertThrows(kakha.kudava.onionp2p.drive.DownloadQueue.OwnerStoppedException.class,
                    () -> ContactMessaging.downloadChunk(session.client, material(false), share, "", 262144, first.version(), false, task));
            assertEquals("Download cancelled by sender.", cancelled.getMessage());
            assertNotNull(owner.findPublished(share, "recipient"));
            // Starting a separate download is allowed because sharing was not revoked.
            var fresh = ContactMessaging.downloadChunk(session.client, material(false), share, "", 0, "", false, UUID.randomUUID().toString());
            assertEquals(262144, fresh.bytes().length);
            owner.unpublish(share);
            var revoked = assertThrows(kakha.kudava.onionp2p.drive.DownloadQueue.OwnerStoppedException.class,
                    () -> ContactMessaging.downloadChunk(session.client, material(false), share, "", 262144, fresh.version(), false, task));
            assertEquals("Sharing access removed by sender.", revoked.getMessage());
        }
    }

    @Test @Timeout(30)
    void senderNoticeAndRevocationInventoryStopPausedRecipientJobs() throws Exception {
        var owner = new SharedDriveStore(temp.resolve("notice-owner"));
        var recipient = new SharedDriveStore(temp.resolve("notice-recipient"));
        String share = UUID.randomUUID().toString(), task = UUID.randomUUID().toString();
        try (var queue = new kakha.kudava.onionp2p.drive.DownloadQueue(link -> false, 10000)) {
            var request = new Properties(); request.setProperty("request.share", share); request.setProperty("request.task", task);
            request.setProperty("request.relative", ""); request.setProperty("request.folder", "false");
            var work = new kakha.kudava.onionp2p.drive.DownloadQueue.Work() {
                public Path run(kakha.kudava.onionp2p.drive.DownloadQueue.Progress progress) { fail("Stopped transfer must not run"); return null; }
                public Properties descriptor() { var copy = new Properties(); copy.putAll(request); return copy; }
            };
            String id = queue.add("stop-1", "owner", "Owner", "file.bin", 100, work); queue.pause(id);
            try (var session = new Session(recipient, "owner", false, null,
                    stop -> queue.stoppedByOwner("owner", stop.share(), stop.relative(), stop.reason(), stop.request()))) {
                ContactMessaging.sendDownloadStop(session.client, material(true),
                        new ContactMessaging.DownloadStop(share, "", "Download cancelled by sender.", task), true);
                assertEquals(kakha.kudava.onionp2p.drive.DownloadQueue.State.CANCELLED, queue.items().getFirst().state());
                assertEquals("Download cancelled by sender.", queue.items().getFirst().detail());
                String second = queue.add("stop-2", "owner", "Owner", "file.bin", 100, work); queue.pause(second);
                recipient.recordOffer(share, "owner", "file.bin", 100, System.currentTimeMillis());
                ContactMessaging.syncDriveShares(session.client, material(true), owner, "recipient", false);
                assertEquals(kakha.kudava.onionp2p.drive.DownloadQueue.State.CANCELLED, queue.items().get(1).state());
                assertEquals("Sharing access removed by sender.", queue.items().get(1).detail());
            }
        }
    }
    @Test @Timeout(30)
    void senderSeesAuthenticatedDownloadsPauseDisconnectAndCompletion() throws Exception {
        var owner = new SharedDriveStore(temp.resolve("upload-db"));
        Path file = Files.write(temp.resolve("upload.bin"), new byte[700000]);
        String id = UUID.randomUUID().toString();
        owner.publish(id, "recipient", "upload.bin", file, 700000, System.currentTimeMillis());
        try (var uploads = new kakha.kudava.onionp2p.drive.UploadTracker()) {
            String version;
            try (var session = new Session(owner, "recipient", true, uploads)) {
                var chunk = ContactMessaging.downloadChunk(session.client, material(false), id, "", 0, "", true);
                assertTrue(chunk.version().startsWith("sha256:" + kakha.kudava.onionp2p.drive.DriveChecksums.calculate(file, () -> {}) + ":"));
                version = chunk.version();
                ContactMessaging.notifyDownloadPaused(session.client, material(false), id, "", false);
                assertEquals(kakha.kudava.onionp2p.drive.UploadTracker.State.PAUSED, uploads.items().getFirst().state());
                assertEquals("recipient", uploads.items().getFirst().contact());
                assertEquals(262144, uploads.items().getFirst().sent());
                ContactMessaging.notifyDownloadCancelled(session.client, material(false), id, "", false, false);
                assertEquals(kakha.kudava.onionp2p.drive.UploadTracker.State.CANCELLED, uploads.items().getFirst().state());
                ContactMessaging.downloadChunk(session.client, material(false), id, "", 262144, version, false);
            }
            assertEquals(kakha.kudava.onionp2p.drive.UploadTracker.State.DISCONNECTED, uploads.items().getFirst().state());
            assertEquals(524288, uploads.items().getFirst().sent());
            try (var session = new Session(owner, "recipient", true, uploads)) {
                ContactMessaging.downloadChunk(session.client, material(false), id, "", 524288, version, true);
                ContactMessaging.ping(session.client, material(false), false);
                assertEquals(kakha.kudava.onionp2p.drive.UploadTracker.State.COMPLETE, uploads.items().getFirst().state());
                assertEquals(700000, uploads.items().getFirst().sent());
            }
        }
    }
    @Test @Timeout(30)
    void resumesAuthenticatedChunksOnANewConnectionAndChecksRevocation() throws Exception {
        var owner = new SharedDriveStore(temp.resolve("owner-chunks"));
        byte[] original = new byte[700000]; new Random(43).nextBytes(original);
        Path source = Files.write(temp.resolve("large.bin"), original);
        String id = UUID.randomUUID().toString();
        owner.publish(id, "recipient", "large.bin", source, original.length, System.currentTimeMillis());
        var download = new kakha.kudava.onionp2p.drive.ResumableFile();
        Path saved = temp.resolve("saved");
        var offsets = new ArrayList<Long>();
        var progress = new kakha.kudava.onionp2p.drive.DownloadQueue.Progress() {
            public void update(long received, long total) {} public void stage(String detail) {}
        };
        try (var session = new Session(owner, "recipient", true)) {
            assertThrows(SocketException.class, () -> download.download(saved, (offset, version) -> {
                offsets.add(offset);
                if (offset > 0) throw new SocketException("Owner went offline");
                return ContactMessaging.downloadChunk(session.client, material(false), id, "", offset, version, true);
            }, progress));
        }
        assertEquals(List.of(0L, 262144L), offsets);
        try (var session = new Session(owner, "recipient", true)) {
            var first = new java.util.concurrent.atomic.AtomicBoolean(true);
            Path result = download.download(saved, (offset, version) -> {
                assertTrue(offset >= 262144);
                return ContactMessaging.downloadChunk(session.client, material(false), id, "", offset, version, first.getAndSet(false));
            }, progress);
            assertArrayEquals(original, Files.readAllBytes(result));
            owner.unpublish(id);
            assertThrows(IOException.class, () -> ContactMessaging.downloadChunk(session.client, material(false), id, "", 0, "", false));
        }
    }

    @Test @Timeout(30)
    void changedSourceIsNeverAppendedToAPartialDownload() throws Exception {
        var owner = new SharedDriveStore(temp.resolve("owner-changed"));
        Path source = Files.write(temp.resolve("change.bin"), new byte[300000]);
        String id = UUID.randomUUID().toString();
        owner.publish(id, "recipient", "change.bin", source, 300000, System.currentTimeMillis());
        var download = new kakha.kudava.onionp2p.drive.ResumableFile();
        try (var session = new Session(owner, "recipient", true)) {
            var first = new java.util.concurrent.atomic.AtomicBoolean(true);
            assertThrows(IOException.class, () -> download.download(temp.resolve("changed-output"), (offset, version) -> {
                if (offset > 0) Files.write(source, new byte[100]);
                return ContactMessaging.downloadChunk(session.client, material(false), id, "", offset, version, first.getAndSet(false));
            }, new kakha.kudava.onionp2p.drive.DownloadQueue.Progress() {
                public void update(long received, long total) {} public void stage(String detail) {}
            }));
        }
        try (var files = Files.list(temp.resolve("changed-output"))) { assertEquals(0, files.count()); }
    }
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
            this(receiver, contact, receiverIsOwner, null);
        }
        Session(SharedDriveStore receiver, String contact, boolean receiverIsOwner,
                kakha.kudava.onionp2p.drive.UploadTracker uploads) throws Exception {
            this(receiver, contact, receiverIsOwner, uploads, ignored -> {});
        }
        Session(SharedDriveStore receiver, String contact, boolean receiverIsOwner,
                kakha.kudava.onionp2p.drive.UploadTracker uploads, java.util.function.Consumer<ContactMessaging.DownloadStop> stopped) throws Exception {
            this(receiver, contact, receiverIsOwner, uploads, stopped, null);
        }
        Session(SharedDriveStore receiver, String contact, boolean receiverIsOwner,
                kakha.kudava.onionp2p.drive.UploadTracker uploads, java.util.function.Consumer<ContactMessaging.DownloadStop> stopped,
                Runnable online) throws Exception {
            listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            client = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort()); client.setSoTimeout(5000);
            server = listener.accept();
            receiving = CompletableFuture.runAsync(() -> {
                try {
                    assertEquals(ContactMessaging.MAGIC, new DataInputStream(server.getInputStream()).readInt());
                    ContactMessaging.receive(server, material(receiverIsOwner), null, receiver, contact, true, online, null, uploads, stopped);
                } catch (Exception error) {
                    if (!server.isClosed()) throw new CompletionException(error);
                }
            });
        }
        public void close() throws Exception {
            server.close(); client.close(); listener.close(); receiving.get(5, TimeUnit.SECONDS);
        }
    }

    @Test @Timeout(15)
    void presenceIsAcknowledgedBeforeSlowOnlineBookkeeping() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Runnable slowOnline = () -> {
            entered.countDown();
            try { release.await(); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        };
        var store = new SharedDriveStore(temp.resolve("slow-online"));
        try (var session = new Session(store, "owner", false, null, ignored -> {}, slowOnline)) {
            try {
                var ping = CompletableFuture.runAsync(() -> {
                    try { ContactMessaging.ping(session.client, material(true), true); }
                    catch (Exception error) { throw new CompletionException(error); }
                });
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                ping.get(1, TimeUnit.SECONDS);
            } finally { release.countDown(); }
            ContactMessaging.ping(session.client, material(true), false);
        }
    }

    @Test @Timeout(30)
    void sharesAfterPresenceAndReconciliationOnTheSameConnection() throws Exception {
        var owner = new SharedDriveStore(temp.resolve("owner"));
        var recipient = new SharedDriveStore(temp.resolve("recipient"));
        Path file = Files.writeString(temp.resolve("test.txt"), "shared after online");
        try (var session = new Session(recipient, "owner", false)) {
            ContactMessaging.ping(session.client, material(true), true);
            ContactMessaging.syncDriveShares(session.client, material(true), owner, "recipient", false);
            for (int i = 0; i < 3; i++) {
                String id = UUID.randomUUID().toString();
                ContactMessaging.sendDriveShare(session.client, material(true), owner, "recipient", id, file, false, ignored -> {}, () -> false);
                assertEquals(i + 1, recipient.all().size());
            }
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
    void reconnectRemovesRevokedOffersOnlyAndKeepsDownloadedCopies() throws Exception {
        var owner = new SharedDriveStore(temp.resolve("owner-db"));
        var recipient = new SharedDriveStore(temp.resolve("recipient-db"));
        Path folder = Files.createDirectory(temp.resolve("folder"));
        Path file = Files.writeString(temp.resolve("source.txt"), "source");
        Path downloaded = Files.writeString(temp.resolve("saved-copy.txt"), "keep my copy");
        String folderId = UUID.randomUUID().toString(), fileId = UUID.randomUUID().toString();
        String otherOwnerId = UUID.randomUUID().toString();
        owner.publish(folderId, "recipient", "folder", folder, -1, 0);
        owner.publish(fileId, "recipient", "source.txt", file, Files.size(file), 0);
        recipient.recordOffer(folderId, "owner", "folder", -1, 0);
        recipient.recordOffer(fileId, "owner", "source.txt", Files.size(file), 0);
        recipient.markDownloaded(fileId, downloaded);
        recipient.recordOffer(otherOwnerId, "someone-else", "unrelated", -1, 0);

        // Owner revokes while disconnected: recipient must retain its cached offers.
        owner.unpublish(fileId);
        assertEquals(3, recipient.all().size());
        try (var session = new Session(recipient, "owner", false)) {
            ContactMessaging.syncDriveShares(session.client, material(true), owner, "recipient", true);
            assertEquals(Set.of(folderId, otherOwnerId), recipient.all().stream().map(SharedDriveStore.SharedFile::id).collect(java.util.stream.Collectors.toSet()));
            assertEquals("keep my copy", Files.readString(downloaded));
            ContactMessaging.syncDriveShares(session.client, material(true), owner, "recipient", false);
            assertEquals(2, recipient.all().size());
        }
        owner.unpublish(folderId);
        try (var session = new Session(recipient, "owner", false)) {
            // An authenticated empty inventory must remove the last revoked folder.
            ContactMessaging.syncDriveShares(session.client, material(true), owner, "recipient", true);
            assertEquals(List.of(otherOwnerId), recipient.all().stream().map(SharedDriveStore.SharedFile::id).toList());
        }
        recipient.recordOffer(fileId, "owner", "source.txt", Files.size(file), 0);
        assertThrows(IllegalArgumentException.class, () -> recipient.reconcileReceived("owner", List.of("invalid")));
        assertEquals(2, recipient.all().size());
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

    @Test @Timeout(30)
    void reportsNetworkProgressBeforeMarkingDownloadComplete() throws Exception {
        var owner = new SharedDriveStore(temp.resolve("owner"));
        var receiver = new SharedDriveStore(temp.resolve("receiver"));
        Path source = temp.resolve("large.bin"); byte[] content = new byte[2 * 1024 * 1024];
        new Random(42).nextBytes(content); Files.write(source, content);
        String id = UUID.randomUUID().toString();
        try (var session = new Session(receiver, "owner", false)) {
            ContactMessaging.sendDriveShare(session.client, material(true), owner, "recipient", id, source, true, ignored -> {}, () -> false);
        }
        String previous = System.getProperty("onion.dataDir");
        System.setProperty("onion.dataDir", temp.resolve("downloads").toString());
        var callbacks = new java.util.concurrent.atomic.AtomicInteger();
        var intermediate = new java.util.concurrent.atomic.AtomicBoolean();
        var verifying = new java.util.concurrent.atomic.AtomicBoolean();
        try (var session = new Session(owner, "recipient", true)) {
            ContactMessaging.downloadDriveShare(session.client, material(false), receiver, id, "owner", true, null,
                    new kakha.kudava.onionp2p.drive.DownloadQueue.Progress() {
                        public void update(long received, long total) {
                            callbacks.incrementAndGet();
                            if (received > 0 && received < total) intermediate.set(true);
                        }
                        public void stage(String detail) { verifying.set(true); }
                    });
            assertTrue(callbacks.get() > 2); assertTrue(intermediate.get()); assertTrue(verifying.get());
            assertArrayEquals(content, Files.readAllBytes(Path.of(receiver.all().getFirst().path())));
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
