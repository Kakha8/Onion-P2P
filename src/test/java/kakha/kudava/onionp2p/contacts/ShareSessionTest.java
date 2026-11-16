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
    void textRemoveAndReAddUseTheSameWorkingConnection() throws Exception {
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
                manager.send(b, "Still working");
                assertEquals(2, messages.all(a).size());
            } finally { receiver.close(); serving.get(5, TimeUnit.SECONDS); }
        }
    }
}
