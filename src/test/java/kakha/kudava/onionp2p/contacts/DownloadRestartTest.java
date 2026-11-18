package kakha.kudava.onionp2p.contacts;

import kakha.kudava.onionp2p.drive.*;
import kakha.kudava.onionp2p.tor.OnionServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class DownloadRestartTest {
    @TempDir Path temp;
    private void attach(ContactManager manager, String owner, Socket socket) throws Exception {
        var field = ContactManager.class.getDeclaredField("transferSessions"); field.setAccessible(true);
        var sessions = (Map<String, Object>) field.get(manager);
        var type = Class.forName(ContactManager.class.getName() + "$MessageSession");
        var constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
        Object session = constructor.newInstance();
        var socketField = type.getDeclaredField("socket"); socketField.setAccessible(true); socketField.set(session, socket);
        sessions.put(owner, session);
    }
    @Test @Timeout(40)
    void pausedFolderSurvivesManagerRestartAndRetainsCompletedAndPartialFiles() throws Exception {
        Path ownerDir = temp.resolve("owner"), receiverDir = temp.resolve("receiver"), output = temp.resolve("output");
        String ownerLink = ContactLink.loadOrCreate(ownerDir, "Owner"), receiverLink = ContactLink.loadOrCreate(receiverDir, "Receiver");
        var store = new SharedDriveStore(ownerDir);
        Path folder = Files.createDirectory(ownerDir.resolve("Documents")); Files.createDirectory(folder.resolve("Empty"));
        Files.writeString(folder.resolve("a.txt"), "complete first");
        byte[] contents = new byte[900000]; new Random(17).nextBytes(contents); Files.write(folder.resolve("b.bin"), contents);
        String id = UUID.randomUUID().toString(); store.publish(id, receiverLink, "Documents", folder, -1, System.currentTimeMillis());
        new ContactStore(receiverDir).acknowledge(ownerLink, "unused", "Owner");
        var current = new AtomicReference<ContactManager>(); var restarted = new AtomicBoolean(); var resumedOffset = new AtomicLong(-1);
        try (var material = ContactRendezvous.derive(ownerDir, receiverLink); var uploads = new UploadTracker()) {
            uploads.setListener(items -> items.stream().filter(item -> item.name().equals("b.bin")).findFirst().ifPresent(item -> {
                if (!restarted.get() && item.sent() >= 262144) {
                    var queue = current.get().downloads().queue(); queue.pause(queue.items().getFirst().id());
                } else if (restarted.get() && item.state() == UploadTracker.State.UPLOADING) resumedOffset.compareAndSet(-1, item.sent());
            }));
            String queueId = null;
            for (int run = 0; run < 2; run++) {
                try (var tor = new OnionServer(receiverDir); var manager = new ContactManager(receiverDir, tor, ignored -> {});
                     var listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                     var client = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort()); var server = listener.accept()) {
                    current.set(manager); tor.socksReady().complete(0); manager.restore(); attach(manager, ownerLink, client);
                    var serving = CompletableFuture.runAsync(() -> {
                        try {
                            assertEquals(ContactMessaging.MAGIC, new DataInputStream(server.getInputStream()).readInt());
                            ContactMessaging.receive(server, material, null, store, receiverLink, true, null, null, uploads);
                        } catch (IOException closed) { /* End of either simulated app session. */ }
                        catch (Exception error) { throw new CompletionException(error); }
                    });
                    try {
                        if (run == 0) {
                            manager.downloads().folderItem(new SharedDriveStore.SharedFile(id, ownerLink, "Documents", null, -1, 0),
                                    "Owner", "", "Documents", true, -1, output);
                            var queue = manager.downloads().queue(); queueId = queue.items().getFirst().id();
                            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                            while (System.nanoTime() < deadline) {
                                boolean saved = false;
                                try (var paths = Files.walk(receiverDir.resolve("download-state/work"))) {
                                    for (Path path : paths.filter(path -> path.toString().endsWith(".properties")).toList()) {
                                        var values = new Properties(); try (var in = Files.newInputStream(path)) { values.load(in); }
                                        if (Long.parseLong(values.getProperty("received", "0")) >= 262144 && values.containsKey("partial")) saved = true;
                                    }
                                } catch (NoSuchFileException notStarted) { }
                                if (saved && queue.items().getFirst().state() == DownloadQueue.State.PAUSED) break;
                                Thread.sleep(20);
                            }
                            assertEquals(DownloadQueue.State.PAUSED, queue.items().getFirst().state(), queue.items().getFirst().detail());
                        } else {
                            var queue = manager.downloads().queue(); assertEquals(1, queue.items().size());
                            assertEquals(queueId, queue.items().getFirst().id());
                            assertEquals(DownloadQueue.State.PAUSED, queue.items().getFirst().state());
                            assertTrue(queue.items().getFirst().received() >= 262144);
                            var done = new CountDownLatch(1);
                            queue.setListener(items -> { if (items.getFirst().state() == DownloadQueue.State.COMPLETE) done.countDown(); });
                            queue.resume(queueId); assertTrue(done.await(8, TimeUnit.SECONDS));
                            assertTrue(resumedOffset.get() >= 262144);
                            assertArrayEquals(contents, Files.readAllBytes(output.resolve("Documents/b.bin")));
                            assertEquals("complete first", Files.readString(output.resolve("Documents/a.txt")));
                            assertTrue(Files.isDirectory(output.resolve("Documents/Empty")));
                            assertFalse(Files.exists(output.resolve("Documents (1)")));
                        }
                    } finally { manager.close(); server.close(); serving.get(5, TimeUnit.SECONDS); }
                }
                restarted.set(true);
            }
        }
    }
}
