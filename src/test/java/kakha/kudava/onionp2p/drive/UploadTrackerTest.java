package kakha.kudava.onionp2p.drive;

import org.junit.jupiter.api.Test;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class UploadTrackerTest {
    @Test void simultaneousRequestsCannotExceedThreeRecipients() throws Exception {
        try (var tracker = new UploadTracker(); var workers = java.util.concurrent.Executors.newFixedThreadPool(12)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 30; i++) {
                String recipient = "recipient-" + i;
                futures.add(workers.submit(() -> {
                    try { start.await(); tracker.begin(recipient, "share", "file", "file", 100, 0, new Object()); }
                    catch (InterruptedException error) { throw new RuntimeException(error); }
                }));
            }
            start.countDown(); for (var future : futures) future.get(3, TimeUnit.SECONDS);
            assertEquals(3, tracker.items().stream().filter(item -> item.state() == UploadTracker.State.UPLOADING).count());
            assertEquals(27, tracker.items().stream().filter(item -> item.state() == UploadTracker.State.QUEUED).count());
            assertEquals(27, tracker.items().stream().filter(item -> item.queuePosition() > 0).map(UploadTracker.Item::queuePosition).distinct().count());
        }
    }
    @Test void limitsDistinctRecipientsAndPromotesInOrderWithoutLettingNewcomersJumpTheQueue() {
        try (var tracker = new UploadTracker()) {
            Object a = new Object(), b = new Object(), c = new Object();
            String a1 = tracker.begin("a", "share", "one", "one", 100, 0, a);
            tracker.begin("a", "share", "two", "two", 100, 0, a);
            tracker.begin("b", "share", "one", "one", 100, 0, b);
            tracker.begin("c", "share", "one", "one", 100, 0, c);
            String d = tracker.begin("d", "share", "one", "one", 100, 0, new Object());
            String e = tracker.begin("e", "share", "one", "one", 100, 0, new Object());
            assertEquals(1, tracker.queuePosition(d)); assertEquals(2, tracker.queuePosition(e));
            assertEquals(3, tracker.items().stream().filter(item -> item.state() == UploadTracker.State.UPLOADING).map(UploadTracker.Item::contact).distinct().count());
            tracker.acknowledged(a1, 100);
            assertEquals(1, tracker.queuePosition(d)); // a's second file still occupies the same slot.
            tracker.cancelled("a", "share", "two", false);
            assertEquals(0, tracker.queuePosition(d));
            String f = tracker.begin("f", "share", "one", "one", 100, 0, new Object());
            assertEquals(2, tracker.queuePosition(f)); assertEquals(1, tracker.queuePosition(e));
            tracker.begin("d", "share", "one", "one", 100, 0, new Object());
            tracker.paused("b", "share", "one"); assertEquals(0, tracker.queuePosition(e));
            tracker.disconnected(c); assertEquals(0, tracker.queuePosition(f));
        }
    }
    @Test void queuedCancellationAndAnIdleRecipientReleaseSlots() {
        var now = new AtomicLong();
        try (var tracker = new UploadTracker(now::get)) {
            String a = tracker.begin("a", "share", "", "file", 100, 0, new Object());
            tracker.begin("b", "share", "", "file", 100, 0, new Object());
            tracker.begin("c", "share", "", "file", 100, 0, new Object());
            String d = tracker.begin("d", "share", "", "file", 100, 0, new Object());
            String e = tracker.begin("e", "share", "", "file", 100, 0, new Object());
            tracker.cancelBySender(d); assertEquals(1, tracker.queuePosition(e));
            tracker.acknowledged(a, 40);
            now.addAndGet(TimeUnit.SECONDS.toNanos(21)); tracker.refresh();
            assertEquals(0, tracker.queuePosition(e));
            assertEquals(UploadTracker.State.WAITING, tracker.items().getFirst().state());
            assertEquals(UploadTracker.State.UPLOADING, tracker.items().get(1).state()); // An in-flight chunk is not expired.
        }
    }
    @Test void cancellingAFolderOnlyMarksItsUnfinishedTransfersForThatRecipient() {
        try (var tracker = new UploadTracker()) {
            Object connection = new Object();
            String active = tracker.begin("alice", "share", "Docs/file.bin", "file.bin", 100, 20, connection);
            String done = tracker.begin("alice", "share", "Docs/done.txt", "done.txt", 10, 0, connection);
            tracker.acknowledged(done, 10);
            tracker.begin("bob", "share", "Docs/file.bin", "file.bin", 100, 0, connection);
            tracker.begin("alice", "share", "Docs-other/file.bin", "file.bin", 100, 0, connection);
            tracker.cancelled("alice", "share", "Docs", true);
            tracker.disconnected(new Object()); tracker.paused("alice", "share", "Docs/file.bin");
            tracker.acknowledged(active, 40);
            assertEquals(UploadTracker.State.CANCELLED, tracker.items().getFirst().state());
            assertEquals(20, tracker.items().getFirst().sent());
            assertEquals(UploadTracker.State.COMPLETE, tracker.items().get(1).state());
            assertEquals(UploadTracker.State.UPLOADING, tracker.items().get(2).state());
            assertEquals(UploadTracker.State.UPLOADING, tracker.items().get(3).state());
        }
    }
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
            String key = tracker.begin("alice", "share", "", "file", 100, 0, new Object());
            tracker.acknowledged(key, 10);
            now.addAndGet(TimeUnit.SECONDS.toNanos(21)); tracker.refresh();
            assertEquals(UploadTracker.State.WAITING, tracker.items().getFirst().state());
            assertEquals(0, tracker.items().getFirst().speed());
        }
    }
}
