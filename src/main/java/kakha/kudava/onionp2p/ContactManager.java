package kakha.kudava.onionp2p;

import java.net.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Pending imports remain in memory; only cryptographically acknowledged contacts enter SQLite. */
final class ContactManager implements AutoCloseable {
    record Event(String link, String message) {}
    private final Path directory;
    private final OnionServer tor;
    private final ContactStore store;
    private final MessageStore messageStore;
    private final String localUsername;
    private final Consumer<Event> events;
    private final ConcurrentMap<String, Pending> pending = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, MessageSession> messageSessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService retries = Executors.newScheduledThreadPool(2, r -> {
        Thread thread = new Thread(r, "contact-retry"); thread.setDaemon(true); return thread;
    });
    private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
    private final Semaphore incoming = new Semaphore(256);
    private volatile boolean closed;

    ContactManager(Path directory, OnionServer tor, Consumer<Event> events) throws Exception {
        this.directory = directory; this.tor = tor; this.events = events;
        store = new ContactStore(directory);
        messageStore = new MessageStore(directory);
        var profile = new AccountStore(directory).profile();
        localUsername = profile == null ? "local" : profile.username();
    }

    List<ContactStore.Contact> contacts() throws Exception { return store.all(); }
    boolean isOnline(String link) throws Exception {
        String canonical = canonicalLink(link);
        Pending entry = pending.get(canonical);
        if (entry == null || !entry.saved || entry.stopped || entry.listener == null) return false;
        int socksPort = tor.socksReady().get(30, TimeUnit.SECONDS);
        try (Socket socket = TorControl.connect(socksPort, entry.material.addresses().remote(), 12000)) {
            ContactMessaging.ping(socket, entry.material, true);
            return true;
        }
    }
    List<MessageStore.Message> messages(String link) throws Exception {
        return messageStore.all(canonicalLink(link));
    }

    MessageStore.Message send(String link, String body) throws Exception {
        return send(link, body, ignored -> {});
    }

    MessageStore.Message send(String link, String body, java.util.function.Consumer<String> status) throws Exception {
        return withMessageSession(link, status, () -> false, (entry, session, canonical) ->
                ContactMessaging.send(session.socket, entry.material, messageStore, canonical, body, session.firstMessage));
    }

    MessageStore.Message sendFile(String link, Path file, java.util.function.LongConsumer progress) throws Exception {
        return sendFile(link, file, progress, ignored -> {});
    }

    MessageStore.Message sendFile(String link, Path file, java.util.function.LongConsumer progress,
                                  java.util.function.Consumer<String> status) throws Exception {
        return sendFile(link, file, new java.util.concurrent.atomic.AtomicBoolean(), progress, status);
    }

    MessageStore.Message sendFile(String link, Path file, java.util.concurrent.atomic.AtomicBoolean cancelled,
                                  java.util.function.LongConsumer progress,
                                  java.util.function.Consumer<String> status) throws Exception {
        return sendFile(link, file, "", cancelled, progress, status);
    }

    MessageStore.Message sendFile(String link, Path file, String caption,
                                  java.util.concurrent.atomic.AtomicBoolean cancelled,
                                  java.util.function.LongConsumer progress,
                                  java.util.function.Consumer<String> status) throws Exception {
        if (java.nio.file.Files.size(file) > ContactMessaging.MAX_FILE_BYTES)
            throw new IllegalArgumentException("Files must be 1 GiB or smaller.");
        return withMessageSession(link, status, cancelled::get, (entry, session, canonical) ->
                ContactMessaging.sendFile(session.socket, entry.material, messageStore, canonical, file, caption,
                        session.firstMessage, progress, cancelled::get));
    }

    private MessageStore.Message withMessageSession(String link, java.util.function.Consumer<String> status,
                                                    java.util.function.BooleanSupplier cancelled,
                                                    SessionSend action) throws Exception {
        String canonical = canonicalLink(link);
        Pending entry = pending.get(canonical);
        if (entry == null || !entry.saved) throw new IllegalStateException("This contact is not ready. Keep both apps open until pairing is acknowledged.");
        MessageSession session = messageSessions.computeIfAbsent(canonical, ignored -> new MessageSession());
        synchronized (session) {
            if (closed) throw new IllegalStateException("Contact service is stopped.");
            checkCancelled(cancelled);
            if (session.socket != null && !session.socket.isClosed() && !session.firstMessage) {
                try { ContactMessaging.ping(session.socket, entry.material); }
                catch (Exception stale) {
                    connections.remove(session.socket);
                    try { session.socket.close(); } catch (Exception ignored) {}
                    session.socket = null;
                    session.firstMessage = true;
                    status.accept("The previous Tor connection closed; reconnecting…");
                }
            }
            checkCancelled(cancelled);
            if (session.socket == null || session.socket.isClosed()) {
                int socksPort = tor.socksReady().get(30, TimeUnit.SECONDS);
                session.socket = connectWithRetry(socksPort, entry.material.addresses().remote(), status, cancelled);
                session.firstMessage = true;
                connections.add(session.socket);
            }
            try {
                checkCancelled(cancelled);
                var message = action.send(entry, session, canonical);
                session.firstMessage = false;
                return message;
            } catch (Exception e) {
                connections.remove(session.socket);
                try { session.socket.close(); } catch (Exception ignored) {}
                session.socket = null;
                session.firstMessage = true;
                throw e;
            }
        }
    }

    private Socket connectWithRetry(int socksPort, String onion, java.util.function.Consumer<String> status,
                                   java.util.function.BooleanSupplier cancelled) throws Exception {
        IOException last = null;
        for (int attempt = 1; attempt <= 5; attempt++) {
            if (closed) throw new IllegalStateException("Contact service is stopped.");
            checkCancelled(cancelled);
            try {
                Socket socket = TorControl.connect(socksPort, onion, 12000);
                if (cancelled.getAsBoolean()) { socket.close(); checkCancelled(cancelled); }
                return socket;
            }
            catch (IOException e) {
                last = e;
                if (attempt == 5) break;
                status.accept("Tor is still locating the contact service; retry " + (attempt + 1) + " of 5…");
                try { Thread.sleep(2000); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw interrupted; }
            }
        }
        throw new IOException("Tor could not reach the contact after five attempts: "
                + (last == null ? "connection unavailable" : safeMessage(last)), last);
    }

    private static void checkCancelled(java.util.function.BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException("File transfer cancelled.");
    }

    void cancelTransfer(String link) {
        MessageSession session = messageSessions.get(canonicalLink(link));
        if (session != null) {
            Socket socket = session.socket;
            if (socket != null) try { socket.close(); } catch (Exception ignored) {}
        }
    }

    @FunctionalInterface private interface SessionSend {
        MessageStore.Message send(Pending entry, MessageSession session, String canonical) throws Exception;
    }

    synchronized ContactRendezvous.Addresses add(String input) throws Exception {
        return add(input, false);
    }

    private synchronized ContactRendezvous.Addresses add(String input, boolean previouslyAcknowledged) throws Exception {
        if (closed) throw new IllegalStateException("Contact service is stopped.");
        String link = canonicalLink(input);
        Pending existing = pending.get(link);
        if (existing != null) {
            if (previouslyAcknowledged) existing.saved = true;
            event(link, existing.saved ? "Contact already acknowledged and saved." : "Waiting for contact—both users must import each other's links.");
            return existing.material.addresses();
        }
        if (pending.size() >= 100) throw new IllegalArgumentException("Contact limit reached (100).");
        var material = ContactRendezvous.derive(directory, link, localUsername);
        Pending entry = new Pending(link, material);
        entry.saved = previouslyAcknowledged;
        pending.put(link, entry);
        event(link, "Waiting for Tor, then for your contact to import your link…");
        entry.task = retries.scheduleWithFixedDelay(() -> attempt(entry), 0, 10, TimeUnit.SECONDS);
        return material.addresses();
    }

    private static String canonicalLink(String input) {
        var parsed = ContactLink.parseLink(input);
        var key = Base64.getUrlEncoder().withoutPadding().encodeToString(parsed.publicKey());
        if (parsed.username().equals("Unknown")) return ContactLink.PREFIX_V1 + key;
        return ContactLink.PREFIX + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(parsed.username().getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "/" + key;
    }

    void restore() throws Exception {
        for (var contact : store.all()) add(contact.link(), true);
    }

    private void attempt(Pending entry) {
        if (closed || entry.stopped) return;
        if (!entry.saved && System.nanoTime() - entry.created > TimeUnit.MINUTES.toNanos(10)) {
            event(entry.link, "Contact did not respond. Import the link again to retry.");
            remove(entry); return;
        }
        if (!tor.socksReady().isDone()) return;
        try {
            int socksPort = tor.socksReady().get();
            synchronized (entry) {
                if (closed || entry.stopped) return;
                if (entry.listener == null) {
                    event(entry.link, "Publishing your contact address on Tor…");
                    entry.listener = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
                    try (var control = new TorControl(directory)) {
                        control.publish(entry.material.localSeed(), entry.listener.getLocalPort(), entry.material.addresses().local());
                    } catch (Exception e) { entry.listener.close(); entry.listener = null; throw e; }
                    Thread.ofPlatform().daemon().name("contact-listen").start(() -> accept(entry));
                    event(entry.link, "Waiting for contact—both users must import each other's links.");
                }
            }
            // A previously acknowledged contact only needs its onion listener restored.
            if (entry.saved) return;
            try (Socket socket = TorControl.connect(socksPort, entry.material.addresses().remote(), 20000)) {
                connections.add(socket);
                try { ContactHandshake.client(socket, entry.material, () -> save(entry)); }
                finally { connections.remove(socket); }
            }
        } catch (Exception e) {
            if (!closed && !entry.saved && !entry.stopped) {
                String detail = safeMessage(e);
                if (detail.contains("not reachable"))
                    event(entry.link, "Waiting for your contact to come online… retrying automatically.");
                else event(entry.link, "Connecting securely; retrying automatically. " + detail);
            }
        }
    }

    private void accept(Pending entry) {
        while (!closed && !entry.stopped) {
            ServerSocket listener = entry.listener;
            if (listener == null) return;
            try {
                Socket socket = listener.accept();
                if (!incoming.tryAcquire()) { socket.close(); continue; }
                connections.add(socket);
                Thread.ofPlatform().daemon().name("contact-handshake").start(() -> {
                    try (socket) {
                        socket.setSoTimeout(20000);
                        int protocol = new java.io.DataInputStream(socket.getInputStream()).readInt();
                        if (protocol == ContactHandshake.MAGIC) ContactHandshake.server(socket, entry.material, protocol, () -> save(entry));
                        else if (protocol == ContactMessaging.MAGIC || protocol == ContactMessaging.LEGACY_MAGIC)
                            ContactMessaging.receive(socket, entry.material, messageStore, entry.link, protocol == ContactMessaging.MAGIC);
                        else throw new java.io.IOException("Unsupported contact protocol.");
                    }
                    catch (Exception e) {
                        if (!closed && !entry.saved) event(entry.link, "Contact verification incomplete; waiting for a valid acknowledgment.");
                        else if (!closed && !(e instanceof java.io.EOFException)) System.err.println("Incoming message failed: " + safeMessage(e));
                    } finally { connections.remove(socket); incoming.release(); }
                });
            } catch (Exception e) {
                if (!closed && !entry.stopped) {
                    synchronized (entry) {
                        if (entry.listener == listener) {
                            entry.listener = null;
                            try { listener.close(); } catch (Exception ignored) {}
                            try (var control = new TorControl(directory)) { control.unpublish(entry.material.addresses().local()); }
                            catch (Exception unpublishError) { System.err.println("Could not reset contact listener: " + safeMessage(unpublishError)); }
                        }
                    }
                    System.err.println("Contact listener stopped; it will be republished: " + safeMessage(e));
                }
                return;
            }
        }
    }

    private synchronized void save(Pending entry) throws Exception {
        if (closed || entry.stopped) throw new IllegalStateException("Contact service is stopped.");
        store.acknowledge(entry.link, entry.material.addresses().remote(), entry.material.remoteUsername());
        entry.saved = true;
        event(entry.link, "Acknowledged—contact saved locally.");
        System.out.println("Contact acknowledged and saved: " + entry.material.addresses().remote());
    }

    private void event(String link, String message) {
        if (!closed) events.accept(new Event(link, message));
    }
    private static String safeMessage(Exception e) {
        Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
        return cause.getMessage() == null ? "Connection unavailable." : cause.getMessage();
    }
    private void remove(Pending entry) {
        pending.remove(entry.link, entry);
        synchronized (entry) {
            entry.stopped = true;
            if (entry.task != null) entry.task.cancel(false);
            if (entry.listener != null) {
                try { entry.listener.close(); } catch (Exception ignored) {}
                try (var control = new TorControl(directory)) { control.unpublish(entry.material.addresses().local()); }
                catch (Exception ignored) {}
            }
        }
        // No future connection can be opened; in-flight handshakes are bounded by socket timeout.
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        retries.shutdownNow();
        for (var socket : connections) try { socket.close(); } catch (Exception ignored) {}
        messageSessions.clear();
        for (var entry : pending.values()) remove(entry);
    }

    private static final class Pending {
        final String link;
        final ContactRendezvous.Material material;
        final long created = System.nanoTime();
        volatile boolean saved, stopped;
        volatile ServerSocket listener;
        ScheduledFuture<?> task;
        Pending(String link, ContactRendezvous.Material material) { this.link = link; this.material = material; }
    }

    private static final class MessageSession {
        volatile Socket socket;
        boolean firstMessage = true;
    }
}
