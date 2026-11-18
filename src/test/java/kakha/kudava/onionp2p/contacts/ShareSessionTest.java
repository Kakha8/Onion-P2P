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
import static org.junit.jupiter.api.Assertions.*;

class ShareSessionTest {
    @TempDir Path temp;

    @Test @Timeout(20)
    void textRemoveAndReAddUseWorkingConnectionsWithoutRetryWorkerStarvation() throws Exception {
        Path alice = temp.resolve("alice"), bob = temp.resolve("bob");
        String a = ContactLink.loadOrCreate(alice, "Alice"), b = ContactLink.loadOrCreate(bob, "Bob");
        try (var material = ContactRendezvous.derive(bob, a);
             var tor = new OnionServer(alice);
             var manager = new ContactManager(alice, tor, ignored -> {});
             var listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             var sender = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
             var receiver = listener.accept()) {
            new ContactStore(alice).acknowledge(b, material.addresses().local(), "Bob");
            manager.restore();
            // Supply the already established contact socket; any attempted new Tor
            // connection would fail because this test deliberately starts no Tor.
            var field = ContactManager.class.getDeclaredField("messageSessions"); field.setAccessible(true);
            var sessions = (Map<String, Object>) field.get(manager);
            var type = Class.forName(ContactManager.class.getName() + "$MessageSession");
            var constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
            Object session = constructor.newInstance();
            var socket = type.getDeclaredField("socket"); socket.setAccessible(true); socket.set(session, sender);
            sessions.put(b, session);
            var offers = new SharedDriveStore(bob);
            var messages = new MessageStore(bob);
            var serving = CompletableFuture.runAsync(() -> {
                try {
                    assertEquals(ContactMessaging.MAGIC, new DataInputStream(receiver.getInputStream()).readInt());
                    ContactMessaging.receive(receiver, material, messages, offers, a, true, null, null);
                } catch (Exception error) { if (!receiver.isClosed()) throw new CompletionException(error); }
            });
            try {
                manager.send(b, "Connection is working");
                Path file = Files.writeString(alice.resolve("test.txt"), "shared");
                for (int i = 0; i < 3; i++) {
                    var result = manager.shareDriveFiles(b, List.of(file));
                    assertEquals(1, result.sharedNow()); assertEquals(0, result.queued());
                    assertEquals(1, offers.all().size());
                    manager.stopSharingDriveFile(file, b);
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                    while (!offers.all().isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
                    assertTrue(offers.all().isEmpty());
                }
                // The transfer connection does not exist and Tor is deliberately not
                // started. A working chat connection must still initiate downloads.
                String remoteId = java.util.UUID.randomUUID().toString();
                Path remoteFile = Files.writeString(bob.resolve("remote.txt"), "Download through the working chat connection");
                offers.publish(remoteId, a, "remote.txt", remoteFile, Files.size(remoteFile), System.currentTimeMillis());
                var chunk = manager.downloadChunk(b, remoteId, "", 0, "");
                assertEquals("Download through the working chat connection", new String(chunk.bytes(), java.nio.charset.StandardCharsets.UTF_8));
                manager.notifyDownloadPaused(b, remoteId, "");
                manager.notifyDownloadCancelled(b, remoteId, "", false);
                manager.send(b, "Still working");
                assertEquals(2, messages.all(a).size());

                // Four slow contacts must not hold up a fifth reachable contact.
                // Its only established stream is a transfer stream: don't start
                // a new Tor connection just to announce a small share offer.
                var workersField = ContactManager.class.getDeclaredField("driveShareRetries");
                workersField.setAccessible(true);
                var workers = (ExecutorService) workersField.get(manager);
                var blocked = new CountDownLatch(4);
                var release = new CountDownLatch(1);
                try {
                    for (int i = 0; i < 4; i++) workers.submit(() -> {
                        blocked.countDown();
                        try { release.await(); }
                        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                    });
                    assertTrue(blocked.await(2, TimeUnit.SECONDS));
                    var transfersField = ContactManager.class.getDeclaredField("transferSessions");
                    transfersField.setAccessible(true);
                    ((Map<String, Object>) transfersField.get(manager)).put(b, sessions.remove(b));
                    Path next = Files.writeString(alice.resolve("pending.txt"), "Deliver without waiting for other contacts");
                    var outgoing = new SharedDriveStore(alice);
                    outgoing.queueShares(b, List.of(next));
                    var sync = ContactManager.class.getDeclaredMethod("requestDriveSync", String.class, boolean.class);
                    sync.setAccessible(true);
                    sync.invoke(manager, b, true);
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                    while (!outgoing.pendingShares(b).isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
                    assertTrue(outgoing.pendingShares(b).isEmpty(), "Reachable contact was blocked by other contacts");
                    assertEquals("pending.txt", offers.all().getFirst().name());
                    manager.send(b, "Text also uses the working transfer stream");
                    // Enable the readiness gate; no SOCKS service is listening
                    // here, so presence must reuse the supplied socket too.
                    tor.socksReady().complete(1);
                    assertTrue(manager.isOnline(b));
                    assertEquals(3, messages.all(a).size());
                } finally { release.countDown(); }
            } finally { receiver.close(); serving.get(5, TimeUnit.SECONDS); }
        }
    }
}
