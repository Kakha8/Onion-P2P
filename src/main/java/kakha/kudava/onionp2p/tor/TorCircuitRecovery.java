package kakha.kudava.onionp2p.tor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Observes a dedicated event connection; recovery leaves active sockets and disk state intact. */
final class TorCircuitRecovery implements AutoCloseable {
    private final Path directory;
    private final CircuitRecoveryPolicy policy;
    private final Consumer<Boolean> recoveryListener;
    private boolean recovering;
    private volatile boolean closed;
    private volatile TorControl subscription;
    private final Thread worker;

    TorCircuitRecovery(Path directory) { this(directory, System::nanoTime, ignored -> {}); }

    TorCircuitRecovery(Path directory, Consumer<Boolean> recoveryListener) {
        this(directory, System::nanoTime, recoveryListener);
    }

    TorCircuitRecovery(Path directory, java.util.function.LongSupplier clock) {
        this(directory, clock, ignored -> {});
    }

    TorCircuitRecovery(Path directory, java.util.function.LongSupplier clock, Consumer<Boolean> recoveryListener) {
        this.directory = directory;
        policy = new CircuitRecoveryPolicy(clock);
        this.recoveryListener = recoveryListener;
        worker = Thread.ofPlatform().daemon().name("tor-circuit-recovery").unstarted(this::monitor);
    }

    void start() { worker.start(); }
    synchronized void exchangeSucceeded() {
        policy.exchangeSucceeded();
        recoveryState(false);
    }

    private synchronized boolean recoveryState(boolean active) {
        if (active && closed) return false;
        if (recovering != active) {
            recovering = active;
            try { recoveryListener.accept(active); }
            catch (RuntimeException error) { System.err.println("Tor routing status update failed."); }
        }
        return true;
    }

    private void monitor() {
        long lastReport = 0;
        boolean reported = false;
        while (!closed) {
            try (var control = new TorControl(directory)) {
                subscription = control;
                if (closed) return;
                control.monitorCircuitEvents(event -> {
                    if (!closed && policy.event(event)) requestFreshCircuits();
                });
            } catch (IOException error) {
                long now = System.nanoTime();
                if (!closed && (!reported || now - lastReport >= java.util.concurrent.TimeUnit.MINUTES.toNanos(1))) {
                    System.err.println("Tor circuit monitoring unavailable; reconnecting to the local control service.");
                    reported = true;
                    lastReport = now;
                }
            } finally { subscription = null; policy.monitorDisconnected(); }
            if (!closed) try { Thread.sleep(5000); }
            catch (InterruptedException stopped) { Thread.currentThread().interrupt(); return; }
        }
    }

    private void requestFreshCircuits() {
        if (!recoveryState(true)) return;
        try (var control = new TorControl(directory)) {
            if (closed) return;
            control.freshCircuits();
            System.out.println("Tor routing recovery: requested fresh circuits after sustained relay failures. "
                    + "Existing connections and saved guard state are retained.");
        } catch (IOException error) {
            if (!closed) System.err.println("Tor routing recovery request failed; normal contact retries will continue.");
        }
    }

    @Override public void close() {
        synchronized (this) { closed = true; recoveryState(false); }
        TorControl control = subscription;
        if (control != null) try { control.close(); } catch (IOException ignored) {}
        worker.interrupt();
    }
}
