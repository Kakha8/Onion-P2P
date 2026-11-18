package kakha.kudava.onionp2p.tor;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.function.LongSupplier;

/** Requests clean circuits for sustained routing failures, never chooses or resets guards. */
final class CircuitRecoveryPolicy {
    private static final long STARTUP_GRACE = Duration.ofSeconds(60).toNanos();
    private static final long FAILURE_PERIOD = Duration.ofSeconds(45).toNanos();
    private static final long FAILURE_WINDOW = Duration.ofMinutes(2).toNanos();
    private static final long COOLDOWN = Duration.ofMinutes(5).toNanos();
    private static final long REQUEST_WINDOW = Duration.ofHours(1).toNanos();
    private final LongSupplier clock;
    private final long started;
    private final Set<String> pendingStreams = new HashSet<>();
    private final ArrayDeque<Long> requests = new ArrayDeque<>();
    private long firstFailure;
    private int failures;

    CircuitRecoveryPolicy(LongSupplier clock) { this.clock = clock; started = clock.getAsLong(); }

    synchronized void exchangeSucceeded() { failures = 0; }
    synchronized void monitorDisconnected() { pendingStreams.clear(); exchangeSucceeded(); }

    synchronized boolean event(String event) {
        String[] fields = event.split(" +");
        if (fields.length < 4 || !fields[0].equals("650")) return false;
        if (fields[1].equals("STREAM")) {
            if (fields.length < 6) return false;
            String id = fields[2], state = fields[3];
            if (state.equals("CLOSED") || state.equals("FAILED")) { pendingStreams.remove(id); return false; }
            boolean onion = fields[5].matches("[a-z2-7]{56}\\.onion:[0-9]+");
            if (state.equals("SUCCEEDED")) {
                if (pendingStreams.remove(id) || onion) exchangeSucceeded();
            }
            else if (onion && Set.of("NEW", "SENTCONNECT", "DETACHED").contains(state) && pendingStreams.size() < 256)
                pendingStreams.add(id);
            return false;
        }
        if (!fields[1].equals("CIRC") || !fields[3].equals("FAILED") || pendingStreams.isEmpty()) return false;
        String reason = field(fields, "REASON="), remote = field(fields, "REMOTE_REASON=");
        if (!(Set.of("RESOURCELIMIT", "CONNECTFAILED", "CHANNEL_CLOSED", "HIBERNATING").contains(remote)
                || Set.of("CONNECTFAILED", "CHANNEL_CLOSED").contains(reason))) return false;
        long now = clock.getAsLong();
        if (now - started < STARTUP_GRACE) return false;
        if (failures == 0 || now - firstFailure > FAILURE_WINDOW) { firstFailure = now; failures = 0; }
        failures++;
        if (failures < 6 || now - firstFailure < FAILURE_PERIOD) return false;
        while (!requests.isEmpty() && now - requests.getFirst() >= REQUEST_WINDOW) requests.removeFirst();
        if (requests.size() >= 3 || !requests.isEmpty() && now - requests.getLast() < COOLDOWN) return false;
        requests.addLast(now);
        exchangeSucceeded();
        return true;
    }

    private static String field(String[] fields, String prefix) {
        for (String field : fields) if (field.startsWith(prefix)) return field.substring(prefix.length());
        return "";
    }
}
