package kakha.kudava.onionp2p.contacts;

import kakha.kudava.onionp2p.drive.DownloadQueue;
import kakha.kudava.onionp2p.drive.SharedDriveStore;
import kakha.kudava.onionp2p.drive.UploadTracker;
import kakha.kudava.onionp2p.messaging.MessageStore;
import kakha.kudava.onionp2p.tor.OnionServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class QueuedSessionTest {
    @TempDir Path temp;

    @Test @Timeout(15)
    void queueOnFirstExchangeKeepsTransferStreamForPollingAndText() throws Exception {
        checkQueueKeepsConnection(false);
    }

    @Test @Timeout(15)
    void queueOnWorkingChatStreamDoesNotDisconnectText() throws Exception {
        checkQueueKeepsConnection(true);
    }

    private void checkQueueKeepsConnection(boolean chatEstablished) throws Exception {
        Path alice = temp.resolve("alice"), bob = temp.resolve("bob");
        String a = ContactLink.loadOrCreate(alice, "Alice"), b = ContactLink.loadOrCreate(bob, "Bob");
        try (var material = ContactRendezvous.derive(bob, a);
             var tor = new OnionServer(alice);
             var manager = new ContactManager(alice, tor, ignored -> {});
             var uploads = new UploadTracker();
             var listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             var sender = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
             var receiver = listener.accept()) {
            new ContactStore(alice).acknowledge(b, material.addresses().local(), "Bob");
            manager.restore();
            // No Tor is started. Reconnecting instead of retaining this socket
            // cannot pass, including after the first response on a new stream.
            var field = ContactManager.class.getDeclaredField(chatEstablished ? "messageSessions" : "transferSessions");
            field.setAccessible(true);
            var sessions = (Map<String, Object>) field.get(manager);
            var type = Class.forName(ContactManager.class.getName() + "$MessageSession");
            var constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
            Object session = constructor.newInstance();
            var socket = type.getDeclaredField("socket"); socket.setAccessible(true); socket.set(session, sender);
            var first = type.getDeclaredField("firstMessage"); first.setAccessible(true);
            if (chatEstablished) {
                new DataOutputStream(sender.getOutputStream()).writeInt(ContactMessaging.MAGIC);
                first.setBoolean(session, false);
                var used = type.getDeclaredField("lastUsed"); used.setAccessible(true);
                used.setLong(session, System.nanoTime());
            }
            sessions.put(b, session);

            var offers = new SharedDriveStore(bob);
            var messages = new MessageStore(bob);
            Path file = Files.writeString(bob.resolve("source.txt"), "shared file");
            String share = UUID.randomUUID().toString();
            offers.publish(share, a, "source.txt", file, Files.size(file), System.currentTimeMillis());
            for (int i = 0; i < UploadTracker.MAX_RECIPIENTS; i++)
                uploads.begin("other-" + i, "share-" + i, "", "busy.bin", 1000000, 0, new Object());

            var serving = CompletableFuture.runAsync(() -> {
                try {
                    assertEquals(ContactMessaging.MAGIC, new DataInputStream(receiver.getInputStream()).readInt());
                    ContactMessaging.receive(receiver, material, messages, offers, a, true, null, null, uploads);
                } catch (Exception error) { if (!receiver.isClosed()) throw new CompletionException(error); }
            });
            try {
                for (int poll = 0; poll < 2; poll++) {
                    var queued = assertThrows(DownloadQueue.QueuedException.class,
                            () -> manager.downloadChunk(b, share, "", 0, ""));
                    assertEquals(1, queued.position);
                    assertFalse(sender.isClosed(), "Capacity queue closed an authenticated connection");
                    assertSame(sender, socket.get(session));
                    assertFalse(first.getBoolean(session), "Next poll must not repeat the protocol preamble");
                }
                manager.send(b, "Text works while this recipient is queued");
                assertEquals(1, messages.all(a).size());
                offers.unpublish(share);
                assertThrows(DownloadQueue.OwnerStoppedException.class,
                        () -> manager.downloadChunk(b, share, "", 0, ""));
                assertFalse(sender.isClosed(), "Authenticated revocation closed the chat stream");
                manager.send(b, "Text still works after access was removed");
                assertEquals(2, messages.all(a).size());
            } finally { receiver.close(); serving.get(5, TimeUnit.SECONDS); }
        }
    }
}
