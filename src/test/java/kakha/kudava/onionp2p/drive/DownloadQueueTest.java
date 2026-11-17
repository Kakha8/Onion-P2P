package kakha.kudava.onionp2p.drive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.io.EOFException;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class DownloadQueueTest {
    @Test void cancellingAPausedDownloadNotifiesItsOwner() throws Exception {
        var notice = new CountDownLatch(1);
        try (var queue = new DownloadQueue(owner -> false, 20)) {
            String id = queue.add("cancel-notice", "alice", "Alice", "file", 100, new DownloadQueue.Work() {
                public Path run(DownloadQueue.Progress progress) { fail("Paused offline transfer must not start"); return null; }
                public void cancelled() { notice.countDown(); }
            });
            queue.pause(id); queue.cancel(id);
            assertTrue(notice.await(3, TimeUnit.SECONDS));
            assertEquals(DownloadQueue.State.CANCELLED, queue.items().getFirst().state());
        }
    }
    @Test void resumingWhileOwnerIsOfflineExplainsWhyAndKeepsTheDownload() throws Exception {
        var offlineMessage = new CountDownLatch(1);
        try (var queue = new DownloadQueue(owner -> false, 20)) {
            queue.setListener(items -> {
                if (!items.isEmpty() && items.getFirst().detail().contains("Alice is offline or unreachable. Try again later."))
                    offlineMessage.countDown();
            });
            String id = queue.add("resume-offline", "alice", "Alice", "file", 100, progress -> Path.of("unused"));
            queue.pause(id); queue.resume(id);
            assertTrue(offlineMessage.await(3, TimeUnit.SECONDS));
            assertEquals(DownloadQueue.State.WAITING, queue.items().getFirst().state());
            assertEquals(id, queue.items().getFirst().id());
        }
    }
    @Test void pausingDuringTransferKeepsProgressAndCanResume() throws Exception {
        var reached = new CountDownLatch(1); var release = new CountDownLatch(1);
        var pausedWorker = new CountDownLatch(1); var done = new CountDownLatch(1);
        var offset = new AtomicInteger();
        try (var queue = new DownloadQueue(owner -> true, 20)) {
            queue.setListener(items -> { if (!items.isEmpty() && items.getFirst().state() == DownloadQueue.State.COMPLETE) done.countDown(); });
            String id = queue.add("mid-file", "alice", "Alice", "file", 100, progress -> {
                if (offset.compareAndSet(0, 40)) {
                    progress.update(40, 100); reached.countDown(); release.await(3, TimeUnit.SECONDS);
                    try { progress.checkpoint(); } finally { pausedWorker.countDown(); }
                }
                progress.checkpoint(); offset.set(100); progress.update(100, 100); return Path.of("saved");
            });
            try {
                assertTrue(reached.await(3, TimeUnit.SECONDS)); queue.pause(id); release.countDown();
                assertTrue(pausedWorker.await(3, TimeUnit.SECONDS));
                assertEquals(DownloadQueue.State.PAUSED, queue.items().getFirst().state());
                assertEquals(40, queue.items().getFirst().received());
                queue.resume(id); assertTrue(done.await(3, TimeUnit.SECONDS));
                assertEquals(100, offset.get());
            } finally { release.countDown(); }
        }
    }
    @Test void manualPauseSurvivesOwnerComingOnlineUntilResume() throws Exception {
        var online = new AtomicBoolean(); var work = new AtomicInteger(); var done = new CountDownLatch(1);
        try (var queue = new DownloadQueue(owner -> online.get(), 20)) {
            queue.setListener(items -> { if (!items.isEmpty() && items.getFirst().state() == DownloadQueue.State.COMPLETE) done.countDown(); });
            String id = queue.add("paused", "alice", "Alice", "file", 100, progress -> {
                work.incrementAndGet(); progress.update(100, 100); return Path.of("saved");
            });
            queue.pause(id); online.set(true); queue.wake("alice");
            assertEquals(DownloadQueue.State.PAUSED, queue.items().getFirst().state()); assertEquals(0, work.get());
            assertEquals(id, queue.add("paused", "alice", "Alice", "file", 100, progress -> Path.of("duplicate")));
            queue.resume(id); assertTrue(done.await(5, TimeUnit.SECONDS)); assertEquals(1, work.get());
        }
    }
    @Test void waitsForTorThenTriesTransferWithoutContactProbe() throws Exception {
        var torReady = new AtomicBoolean(); var checks = new AtomicInteger(); var transfers = new AtomicInteger();
        var waitingForTor = new CountDownLatch(1); var complete = new CountDownLatch(1);
        try (var queue = new DownloadQueue(owner -> {
            checks.incrementAndGet();
            if (!torReady.get()) throw new DownloadQueue.TorNotReadyException();
            return true;
        }, 20)) {
            queue.setListener(items -> items.forEach(item -> {
                if (item.detail().contains("Waiting for Tor")) waitingForTor.countDown();
                if (item.state() == DownloadQueue.State.COMPLETE) complete.countDown();
            }));
            queue.add("request", "alice", "Alice", "test.zip", 10, progress -> {
                transfers.incrementAndGet(); return Path.of("saved.zip");
            });
            assertTrue(waitingForTor.await(5, TimeUnit.SECONDS));
            assertEquals(0, transfers.get());
            torReady.set(true); queue.wake("alice");
            assertTrue(complete.await(5, TimeUnit.SECONDS));
            assertEquals(1, transfers.get());
            assertTrue(checks.get() >= 2);
        }
    }
    @Test void waitsOfflineThenAutomaticallyDownloadsOnReconnect() throws Exception {
        AtomicBoolean online = new AtomicBoolean(); AtomicInteger work = new AtomicInteger();
        var done = new CountDownLatch(1); var progressSeen = new AtomicBoolean();
        try (var queue = new DownloadQueue(owner -> online.get(), 20)) {
            queue.setListener(items -> items.forEach(item -> {
                if (item.received() == 40 && item.state() == DownloadQueue.State.DOWNLOADING) progressSeen.set(true);
                if (item.state() == DownloadQueue.State.COMPLETE) done.countDown();
            }));
            String id = queue.add("request", "alice", "Alice", "test.zip", 100, progress -> {
                work.incrementAndGet(); progress.update(40, 100); progress.update(100, 100); return Path.of("saved.zip");
            });
            assertEquals(DownloadQueue.State.WAITING, queue.items().getFirst().state());
            assertTrue(queue.items().getFirst().detail().contains("come online")); assertEquals(0, work.get());
            online.set(true); queue.wake("alice");
            assertTrue(done.await(5, TimeUnit.SECONDS)); assertEquals(1, work.get()); assertTrue(progressSeen.get());
            assertEquals(id, queue.items().getFirst().id()); assertEquals(100, queue.items().getFirst().received());
        }
    }
    @Test void interruptedDownloadRetriesWithoutManualRefresh() throws Exception {
        var attempts = new AtomicInteger(); var done = new CountDownLatch(1); var waitingAfterDrop = new AtomicBoolean();
        try (var queue = new DownloadQueue(owner -> true, 20)) {
            queue.setListener(items -> items.forEach(item -> {
                if (attempts.get() == 1 && item.state() == DownloadQueue.State.WAITING) waitingAfterDrop.set(true);
                if (item.state() == DownloadQueue.State.COMPLETE) done.countDown();
            }));
            queue.add("request", "alice", "Alice", "test", 100, progress -> {
                if (attempts.incrementAndGet() == 1) { progress.update(30, 100); throw new EOFException("Disconnected"); }
                progress.update(100, 100); return Path.of("saved");
            });
            assertTrue(done.await(5, TimeUnit.SECONDS)); assertEquals(2, attempts.get()); assertTrue(waitingAfterDrop.get());
        }
    }
    @Test void cancellationWhileWaitingNeverRunsTransferAndDuplicatesAreDeduplicated() throws Exception {
        var work = new AtomicInteger();
        try (var queue = new DownloadQueue(owner -> false, 20)) {
            DownloadQueue.Work action = progress -> { work.incrementAndGet(); return Path.of("saved"); };
            String first = queue.add("same", "alice", "Alice", "test", 100, action);
            assertEquals(first, queue.add("same", "alice", "Alice", "test", 100, action));
            assertEquals(1, queue.items().size()); queue.cancel(first); queue.wake("alice");
            assertEquals(DownloadQueue.State.CANCELLED, queue.items().getFirst().state()); assertEquals(0, work.get());
        }
    }
    @Test void revokedSharesFailRatherThanWaitForeverAndOtherDownloadsContinue() throws Exception {
        var done = new CountDownLatch(1); var failure = new CountDownLatch(1);
        try (var queue = new DownloadQueue(owner -> true, 20)) {
            queue.setListener(items -> items.forEach(item -> {
                if (item.state() == DownloadQueue.State.FAILED) failure.countDown();
                if (item.state() == DownloadQueue.State.COMPLETE) done.countDown();
            }));
            queue.add("revoked", "alice", "Alice", "revoked", 100, progress -> { throw new java.io.IOException("Folder access was removed."); });
            queue.add("valid", "bob", "Bob", "valid", 100, progress -> Path.of("saved"));
            assertTrue(failure.await(5, TimeUnit.SECONDS)); assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(DownloadQueue.State.FAILED, queue.items().getFirst().state());
        }
    }
}
