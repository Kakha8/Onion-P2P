package kakha.kudava.onionp2p.drive;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Rolling throughput including idle time; resumed bytes are a baseline, not new traffic. */
public final class TransferRate {
    private record Sample(long time, long bytes) {}
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private final LongSupplier clock;
    public TransferRate() { this(System::nanoTime); }
    public TransferRate(LongSupplier clock) { this.clock = clock; }
    public void reset(long bytes) { samples.clear(); samples.add(new Sample(clock.getAsLong(), bytes)); }
    public double update(long bytes) {
        long now = clock.getAsLong();
        if (samples.isEmpty() || bytes < samples.getLast().bytes()) reset(bytes);
        if (samples.getLast().bytes() != bytes || now > samples.getLast().time()) samples.add(new Sample(now, bytes));
        while (samples.size() > 2) {
            var iterator = samples.iterator(); iterator.next();
            if (now - iterator.next().time() < TimeUnit.SECONDS.toNanos(5)) break;
            samples.removeFirst();
        }
        Sample first = samples.getFirst();
        if (now - samples.getLast().time() > TimeUnit.SECONDS.toNanos(5)) return 0;
        double elapsed = (now - first.time()) / 1_000_000_000.0;
        return elapsed < .25 ? 0 : Math.max(0, bytes - first.bytes()) / elapsed;
    }
}
