package kakha.kudava.onionp2p.contacts;

import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.*;

/** Short caller waits retain the actual Tor connection attempt until it finishes. */
final class PendingConnection {
    private CompletableFuture<Socket> pending;

    synchronized Socket await(Callable<Socket> connect, long timeout, TimeUnit unit) throws Exception {
        if (pending == null) {
            pending = new CompletableFuture<>();
            var result = pending;
            Thread.ofPlatform().daemon().name("contact-circuit").start(() -> {
                try { result.complete(connect.call()); }
                catch (Exception error) { result.completeExceptionally(error); }
            });
        }
        try {
            Socket socket = pending.get(timeout, unit);
            pending = null;
            return socket;
        } catch (TimeoutException stillConnecting) {
            throw new SocketTimeoutException("Tor is still establishing the contact circuit; the same connection attempt will continue.");
        } catch (ExecutionException failed) {
            pending = null;
            if (failed.getCause() instanceof Exception error) throw error;
            throw failed;
        }
    }
}
