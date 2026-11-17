package kakha.kudava.onionp2p.drive;

import org.junit.jupiter.api.Test;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class UploadTrackerTest {
    @Test void tracksProgressSpeedPauseAndReconnectWithoutMixingRecipients() {
        var now = new AtomicLong();
        try (var tracker = new UploadTracker(now::get)) {
            Object first = new Object(), second = new Object();
            String alice = tracker.begin("alice", "share", "file", "file.bin", 1000, 0, first);
            tracker.begin("bob", "share", "file", "file.bin", 1000, 0, second);
            now.addAndGet(TimeUnit.SECONDS.toNanos(1)); tracker.acknowledged(alice, 400);
            assertEquals(400, tracker.items().getFirst().sent());
            assertEquals(400, tracker.items().getFirst().speed(), .01);
            tracker.paused("alice", "share", "file");
            tracker.disconnected(first);
            assertEquals(UploadTracker.State.PAUSED, tracker.items().getFirst().state());
            Object reconnected = new Object();
            tracker.begin("alice", "share", "file", "file.bin", 1000, 400, reconnected);
            tracker.disconnected(first);
            assertEquals(UploadTracker.State.UPLOADING, tracker.items().getFirst().state());
            tracker.acknowledged(alice, 1000);
            assertEquals(UploadTracker.State.COMPLETE, tracker.items().getFirst().state());
            tracker.disconnected(second);
            assertEquals(UploadTracker.State.DISCONNECTED, tracker.items().get(1).state());
        }
    }
    @Test void inactivityShowsWaitingWithoutPretendingThePeerPaused() {
        var now = new AtomicLong();
        try (var tracker = new UploadTracker(now::get)) {
            tracker.begin("alice", "share", "", "file", 100, 0, new Object());
            now.addAndGet(TimeUnit.SECONDS.toNanos(21)); tracker.refresh();
            assertEquals(UploadTracker.State.WAITING, tracker.items().getFirst().state());
            assertEquals(0, tracker.items().getFirst().speed());
        }
    }
}
