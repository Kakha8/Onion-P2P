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
        clock.addAndGet(TimeUnit.SECONDS.toNanos(6));
        assertEquals(0, rate.update(2001000), .01);
        rate.reset(2001000);
        clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
        assertEquals(500, rate.update(2001500), .01);
        rate.reset(0);
        assertEquals(0, rate.update(0));
    }
}
