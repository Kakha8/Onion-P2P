package kakha.kudava.onionp2p.contacts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ContactHandshakeTest {
    @TempDir Path directory;

    @Test void mutualAcknowledgmentSavesBothAndSurvivesRestartWithoutDuplicates() throws Exception {
        Path a = directory.resolve("a"), b = directory.resolve("b");
        String aLink = ContactLink.loadOrCreate(a), bLink = ContactLink.loadOrCreate(b);
        var aStore = new ContactStore(a); var bStore = new ContactStore(b);
        assertTrue(aStore.all().isEmpty()); assertTrue(bStore.all().isEmpty());
        try (var am = ContactRendezvous.derive(a, bLink); var bm = ContactRendezvous.derive(b, aLink)) {
            for (int i = 0; i < 2; i++) {
                exchange(am, bm, () -> aStore.acknowledge(bLink, am.addresses().remote(), "bob"),
                        () -> bStore.acknowledge(aLink, bm.addresses().remote(), "alice"), null);
            }
            assertEquals(1, new ContactStore(a).all().size());
            assertEquals(bLink, new ContactStore(a).all().getFirst().link());
            assertEquals(1, new ContactStore(b).all().size());
        }
    }

    @Test void invalidAuthenticationAndReplayCannotSaveContacts() throws Exception {
        Path a = directory.resolve("a"), b = directory.resolve("b");
        String aLink = ContactLink.loadOrCreate(a), bLink = ContactLink.loadOrCreate(b);
        var saves = new AtomicInteger();
        try (var am = ContactRendezvous.derive(a, bLink); var bm = ContactRendezvous.derive(b, aLink)) {
            am.sharedSecret()[0] ^= 1;
            assertThrows(Exception.class, () -> exchange(am, bm, saves::incrementAndGet, saves::incrementAndGet, null));
            assertEquals(0, saves.get());
            am.sharedSecret()[0] ^= 1;
            var capture = new ByteArrayOutputStream();
            exchange(am, bm, () -> {}, () -> {}, capture);
            try (var listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                 var client = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
                 var server = listener.accept()) {
                client.getOutputStream().write(capture.toByteArray()); client.getOutputStream().flush();
                assertThrows(Exception.class, () -> ContactHandshake.server(server, bm, saves::incrementAndGet));
            }
            assertEquals(0, saves.get());
        }
    }

    @Test void failedServerCommitNeverReportsSuccessToClient() throws Exception {
        Path a = directory.resolve("a"), b = directory.resolve("b");
        String aLink = ContactLink.loadOrCreate(a), bLink = ContactLink.loadOrCreate(b);
        var saves = new AtomicInteger();
        try (var am = ContactRendezvous.derive(a, bLink); var bm = ContactRendezvous.derive(b, aLink)) {
            assertThrows(Exception.class, () -> exchange(am, bm, saves::incrementAndGet,
                    () -> { throw new IOException("Disk full"); }, null));
            assertEquals(0, saves.get());
        }
    }

    private static void exchange(ContactRendezvous.Material a, ContactRendezvous.Material b,
                                 ContactHandshake.Save saveA, ContactHandshake.Save saveB,
                                 ByteArrayOutputStream capture) throws Exception {
        try (var listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             var client = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
             var server = listener.accept(); var executor = Executors.newSingleThreadExecutor()) {
            var task = executor.submit(() -> {
                try (server) { ContactHandshake.server(server, b, saveB); return null; }
            });
            Socket transport = capture == null ? client : new Socket() {
                @Override public void setSoTimeout(int timeout) throws SocketException { client.setSoTimeout(timeout); }
                @Override public InputStream getInputStream() throws IOException { return client.getInputStream(); }
                @Override public OutputStream getOutputStream() throws IOException {
                    var actual = client.getOutputStream();
                    return new OutputStream() {
                        @Override public void write(int value) throws IOException { actual.write(value); capture.write(value); }
                        @Override public void flush() throws IOException { actual.flush(); }
                    };
                }
            };
            ContactHandshake.client(transport, a, saveA);
            task.get(5, TimeUnit.SECONDS);
        }
    }
}
