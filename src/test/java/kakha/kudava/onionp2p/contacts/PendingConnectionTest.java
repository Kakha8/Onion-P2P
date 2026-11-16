package kakha.kudava.onionp2p.contacts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class PendingConnectionTest {
    @Test void shortWaitsKeepOneConnectionAttemptUntilReady() throws Exception {
        var connecting = new PendingConnection();
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        var attempts = new AtomicInteger();
        try (var socket = new Socket()) {
            Callable<Socket> connect = () -> {
                attempts.incrementAndGet(); started.countDown(); release.await(); return socket;
            };
            try {
                assertThrows(SocketTimeoutException.class, () -> connecting.await(connect, 20, TimeUnit.MILLISECONDS));
                assertTrue(started.await(2, TimeUnit.SECONDS));
                assertThrows(SocketTimeoutException.class, () -> connecting.await(connect, 20, TimeUnit.MILLISECONDS));
                assertEquals(1, attempts.get());
                release.countDown();
                assertSame(socket, connecting.await(connect, 2, TimeUnit.SECONDS));
                assertEquals(1, attempts.get());
            } finally { release.countDown(); }
        }
    }

    @Test void actualConnectionFailureAllowsANewAttempt() throws Exception {
        var connecting = new PendingConnection();
        assertThrows(IOException.class, () -> connecting.await(() -> { throw new IOException("offline"); }, 2, TimeUnit.SECONDS));
        try (var socket = new Socket()) {
            assertSame(socket, connecting.await(() -> socket, 2, TimeUnit.SECONDS));
        }
    }
}
