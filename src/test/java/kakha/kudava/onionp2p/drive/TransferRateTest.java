package kakha.kudava.onionp2p.drive;

import org.junit.jupiter.api.Test;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class TransferRateTest {
    @Test void rateDecaysWhenStalledAndDoesNotCountSavedBytesAfterResume() {
        var clock = new AtomicLong(); var rate = new TransferRate(clock::get);
        rate.reset(2000000);
        clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
        assertEquals(1000, rate.update(2001000), .01);
        clock.addAndGet(TimeUnit.SECONDS.toNanos(12));
        assertEquals(0, rate.update(2001000), .01);
        rate.reset(2001000);
        clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
        assertEquals(500, rate.update(2001500), .01);
        rate.reset(0);
        assertEquals(0, rate.update(0));
    }

    @Test void timerRefreshesDoNotEraseRateBetweenSpacedChunks() {
        var clock = new AtomicLong(); var rate = new TransferRate(clock::get);
        rate.reset(0);
        long bytes = 0;
        for (int second = 1; second <= 36; second++) {
            clock.set(TimeUnit.SECONDS.toNanos(second));
            if (second == 1 || second % 6 == 0) bytes += 262144;
            assertTrue(rate.update(bytes) > 0, "Speed flashed zero at second " + second);
            assertTrue(rate.update(bytes) > 0, "Repeated refresh changed speed");
        }
        double previous = rate.update(bytes);
        for (int idle = 1; idle < 12; idle++) {
            clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
            double current = rate.update(bytes);
            assertTrue(current > 0 && current <= previous);
            previous = current;
        }
        clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
        assertEquals(0, rate.update(bytes));
        clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
        assertTrue(rate.update(bytes + 262144) > 0, "Speed recovers when traffic resumes");
    }
}
