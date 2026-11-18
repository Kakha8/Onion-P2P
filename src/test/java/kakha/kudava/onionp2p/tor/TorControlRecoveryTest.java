package kakha.kudava.onionp2p.tor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class TorControlRecoveryTest {
    @TempDir Path temp;

    @Test void monitorAndRecoveryUseSeparateAuthenticatedConnectionsAndCloseUnblocksRead() throws Exception {
        try (var server = new FakeTor(temp); var monitor = new TorControl(temp)) {
            var events = new LinkedBlockingQueue<String>();
            var listening = CompletableFuture.runAsync(() -> {
                try { monitor.monitorCircuitEvents(events::add); }
                catch (IOException error) { throw new CompletionException(error); }
            });
            assertTrue(server.subscribed.await(2, TimeUnit.SECONDS));
            // Tor may emit an event before acknowledging SETEVENTS.
            assertEquals("650 CIRC 1 LAUNCHED PURPOSE=GENERAL", events.poll(2, TimeUnit.SECONDS));
            try (var command = new TorControl(temp)) { command.freshCircuits(); }
            server.emit("650 STATUS_CLIENT NOTICE IGNORED",
                    "650 CIRC 2 FAILED REASON=DESTROYED REMOTE_REASON=RESOURCELIMIT",
                    "650 STREAM 3 SUCCEEDED 4 example.onion:80");
            assertEquals("650 CIRC 2 FAILED REASON=DESTROYED REMOTE_REASON=RESOURCELIMIT", events.poll(2, TimeUnit.SECONDS));
            assertEquals("650 STREAM 3 SUCCEEDED 4 example.onion:80", events.poll(2, TimeUnit.SECONDS));
            assertEquals(List.of("SETEVENTS CIRC STREAM", "SIGNAL NEWNYM"), List.copyOf(server.commands));
            assertEquals(2, server.authenticated);
            monitor.close();
            var error = assertThrows(ExecutionException.class, () -> listening.get(2, TimeUnit.SECONDS));
            assertInstanceOf(IOException.class, error.getCause());
            assertTrue(server.errors.isEmpty(), server.errors::toString);
        }
    }

    @Test void rejectedRecoveryCommandDoesNotExposeAuthenticationCookie() throws Exception {
        try (var server = new FakeTor(temp); var command = new TorControl(temp)) {
            server.rejectSignal = true;
            var error = assertThrows(IOException.class, command::freshCircuits);
            assertFalse(error.toString().contains(server.cookieHex));
            assertTrue(server.errors.isEmpty(), server.errors::toString);
        }
    }

    @Test void rejectedEventSubscriptionFailsWithoutWaitingForever() throws Exception {
        try (var server = new FakeTor(temp); var monitor = new TorControl(temp)) {
            server.rejectSubscription = true;
            assertThrows(IOException.class, () -> monitor.monitorCircuitEvents(ignored -> {}));
            assertTrue(server.errors.isEmpty(), server.errors::toString);
        }
    }

    @Test void sustainedRelayFailuresAutomaticallyRequestCleanCircuitsWithoutResettingSavedState() throws Exception {
        Path state = Files.createDirectory(temp.resolve("tor-data")).resolve("state");
        Files.writeString(state, "existing saved guard state");
        var ticks = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.LongSupplier clock = () -> {
            int tick = ticks.getAndIncrement();
            return tick == 0 ? 0 : TimeUnit.SECONDS.toNanos(60 + (tick - 1) * 9L);
        };
        var routingStates = new CopyOnWriteArrayList<Boolean>();
        try (var server = new FakeTor(temp); var recovery = new TorCircuitRecovery(temp, clock, routingStates::add)) {
            recovery.start();
            assertTrue(server.subscribed.await(2, TimeUnit.SECONDS));
            server.emit("650 STREAM 3 NEW 0 " + "a".repeat(56) + ".onion:80");
            for (int i = 0; i < 6; i++)
                server.emit("650 CIRC " + (100 + i) + " FAILED REASON=DESTROYED REMOTE_REASON=RESOURCELIMIT");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!server.commands.contains("SIGNAL NEWNYM") && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(List.of("SETEVENTS CIRC STREAM", "SIGNAL NEWNYM"), List.copyOf(server.commands));
            assertEquals(2, server.authenticated);
            assertEquals("existing saved guard state", Files.readString(state));
            assertEquals(List.of(true), routingStates, "Recovery should stay visible until a contact exchange succeeds");
            recovery.exchangeSucceeded();
            recovery.exchangeSucceeded();
            assertEquals(List.of(true, false), routingStates, "Repeated success must not flicker the UI status");
            assertTrue(server.errors.isEmpty(), server.errors::toString);
        }
    }

    @Test void recoveryRemainsVisibleWhenCircuitRequestFailsAndClearsOnClose() throws Exception {
        var states = new CopyOnWriteArrayList<Boolean>();
        var ticks = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.LongSupplier clock = () -> {
            int tick = ticks.getAndIncrement();
            return tick == 0 ? 0 : TimeUnit.SECONDS.toNanos(60 + (tick - 1) * 9L);
        };
        try (var server = new FakeTor(temp); var recovery = new TorCircuitRecovery(temp, clock, states::add)) {
            server.rejectSignal = true;
            recovery.start();
            assertTrue(server.subscribed.await(2, TimeUnit.SECONDS));
            server.emit("650 STREAM 3 NEW 0 " + "a".repeat(56) + ".onion:80");
            for (int i = 0; i < 6; i++)
                server.emit("650 CIRC " + (100 + i) + " FAILED REASON=DESTROYED REMOTE_REASON=RESOURCELIMIT");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!server.commands.contains("SIGNAL NEWNYM") && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(server.commands.contains("SIGNAL NEWNYM"));
            assertEquals(List.of(true), states);
            recovery.close();
            recovery.exchangeSucceeded();
            assertEquals(List.of(true, false), states);
        }
    }

    private static final class FakeTor implements AutoCloseable {
        final ServerSocket listener;
        final ExecutorService workers = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
        final List<Socket> sockets = new CopyOnWriteArrayList<>();
        final Queue<String> commands = new ConcurrentLinkedQueue<>();
        final Queue<Throwable> errors = new ConcurrentLinkedQueue<>();
        final CountDownLatch subscribed = new CountDownLatch(1);
        final String cookieHex;
        volatile int authenticated;
        volatile BufferedWriter eventOutput;
        volatile boolean rejectSignal, rejectSubscription, closed;

        FakeTor(Path directory) throws Exception {
            byte[] cookie = new byte[32]; new Random(123).nextBytes(cookie);
            cookieHex = HexFormat.of().formatHex(cookie);
            Files.write(directory.resolve("control-cookie"), cookie);
            listener = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
            Files.writeString(directory.resolve("control-port"), "PORT=127.0.0.1:" + listener.getLocalPort());
            workers.submit(() -> {
                try {
                    while (!closed) {
                        Socket client = listener.accept(); sockets.add(client);
                        workers.submit(() -> serve(client));
                    }
                } catch (IOException error) { if (!closed) errors.add(error); }
            });
        }

        private void serve(Socket socket) {
            try (socket) {
                var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), US_ASCII));
                var output = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), US_ASCII));
                assertEquals("AUTHENTICATE " + cookieHex, input.readLine());
                synchronized (this) { authenticated++; }
                send(output, "250 OK");
                String command;
                while ((command = input.readLine()) != null) {
                    commands.add(command);
                    if (command.equals("SETEVENTS CIRC STREAM")) {
                        if (rejectSubscription) { send(output, "552 Rejected"); continue; }
                        eventOutput = output;
                        send(output, "650 CIRC 1 LAUNCHED PURPOSE=GENERAL", "250 OK");
                        subscribed.countDown();
                    } else if (command.equals("SIGNAL NEWNYM")) {
                        send(output, rejectSignal ? "552 Rejected" : "250 OK");
                    } else throw new AssertionError("Unexpected control command");
                }
            } catch (Throwable error) { if (!closed) errors.add(error); }
        }

        void emit(String... lines) throws IOException { send(eventOutput, lines); }

        private static void send(BufferedWriter output, String... lines) throws IOException {
            synchronized (output) {
                for (String line : lines) { output.write(line); output.write("\r\n"); }
                output.flush();
            }
        }

        public void close() throws IOException {
            closed = true;
            listener.close();
            for (Socket socket : sockets) socket.close();
            workers.shutdownNow();
        }
    }
}
