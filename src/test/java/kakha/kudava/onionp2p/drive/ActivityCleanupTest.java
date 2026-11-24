package kakha.kudava.onionp2p.drive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class ActivityCleanupTest {
    @TempDir Path temp;
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean());
    }

    @Test void dismissingImportsKeepsTheirFilesAndDoesNotStartRemovedQueuedJobs() throws Exception {
        var release = new CountDownLatch(1); var entered = new CountDownLatch(1);
        try (var queue = new ImportQueue(new DriveChecksums(temp.resolve("profile")))) {
            var field = ImportQueue.class.getDeclaredField("worker"); field.setAccessible(true);
            ((ExecutorService) field.get(queue)).execute(() -> {
                entered.countDown(); try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Path first = Files.writeString(temp.resolve("cancel.txt"), "original"), second = Files.writeString(temp.resolve("keep.txt"), "keep");
            queue.add(List.of(first, second), temp.resolve("drive"));
            String cancelled = queue.items().getFirst().id();
            queue.dismiss(cancelled); queue.clearFinished(); assertEquals(2, queue.items().size());
            queue.cancel(cancelled); queue.dismiss(cancelled); assertEquals(1, queue.items().size());
            release.countDown(); await(() -> queue.items().getFirst().state() == ImportQueue.State.COMPLETE);
            Path copy = queue.items().getFirst().destination(); queue.clearFinished(); assertTrue(queue.items().isEmpty());
            assertEquals("keep", Files.readString(copy)); assertEquals("original", Files.readString(first)); assertTrue(Files.exists(second));
            assertFalse(Files.exists(temp.resolve("drive/cancel.txt")));
        } finally { release.countDown(); }
    }

    @Test void clearingDownloadHistoryPersistsButKeepsSavedFilesPausedWaitingAndQueuedWork() throws Exception {
        Path journal = temp.resolve("journal"), output = temp.resolve("output");
        var transfer = new ResumableFile(temp.resolve("work/file.properties"), output);
        var discarded = new CountDownLatch(1);
        byte[] content = "saved contents".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String version = "sha256:" + HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content)) + ":test";
        DownloadQueue.Work verified = new DownloadQueue.Work() {
            public Path run(DownloadQueue.Progress progress) throws Exception {
                return transfer.download(output, (offset, token) -> new ResumableFile.Chunk(version, "file.txt", content.length, offset, content), progress);
            }
            public Properties descriptor() { return new Properties(); }
            public void discard() { transfer.discard(); discarded.countDown(); }
        };
        DownloadQueue.Work idle = new DownloadQueue.Work() {
            public Path run(DownloadQueue.Progress progress) { fail("Offline work must not run"); return null; }
            public Properties descriptor() { return new Properties(); }
        };
        Set<String> retained;
        try (var queue = new DownloadQueue(owner -> owner.equals("online"), journal, values -> idle)) {
            String complete = queue.add("complete", "online", "Alice", "file.txt", content.length, verified);
            String waiting = queue.add("waiting", "offline", "Bob", "waiting.txt", 5, idle);
            String paused = queue.add("paused", "offline", "Bob", "paused.txt", 5, idle); queue.pause(paused);
            String failed = queue.add("failed", "online", "Alice", "bad.txt", 5, new DownloadQueue.Work() {
                public Path run(DownloadQueue.Progress progress) throws Exception { throw new IOException("Invalid file"); }
                public Properties descriptor() { return new Properties(); }
            });
            String queued = queue.add("queued", "online", "Alice", "queued.txt", 5, new DownloadQueue.Work() {
                public Path run(DownloadQueue.Progress progress) throws Exception { throw new DownloadQueue.QueuedException(2); }
                public Properties descriptor() { return new Properties(); }
            });
            await(() -> queue.items().stream().anyMatch(item -> item.id().equals(complete) && item.state() == DownloadQueue.State.COMPLETE)
                    && queue.items().stream().anyMatch(item -> item.id().equals(failed) && item.state() == DownloadQueue.State.FAILED)
                    && queue.items().stream().anyMatch(item -> item.id().equals(queued) && item.state() == DownloadQueue.State.QUEUED));
            Path saved = queue.items().stream().filter(item -> item.id().equals(complete)).findFirst().orElseThrow().destination();
            queue.dismiss(paused); queue.clearFinished();
            retained = Set.of(waiting, paused, queued);
            assertEquals(retained, queue.items().stream().map(DownloadQueue.Item::id).collect(java.util.stream.Collectors.toSet()));
            assertTrue(discarded.await(5, TimeUnit.SECONDS)); assertArrayEquals(content, Files.readAllBytes(saved));
            assertFalse(Files.exists(journal.resolve(complete + ".properties"))); assertFalse(Files.exists(journal.resolve(failed + ".properties")));
        }
        try (var restored = new DownloadQueue(owner -> false, journal, values -> idle)) {
            assertEquals(retained, restored.items().stream().map(DownloadQueue.Item::id).collect(java.util.stream.Collectors.toSet()));
            assertTrue(restored.items().stream().allMatch(item -> item.state() == DownloadQueue.State.PAUSED));
            assertArrayEquals(content, Files.readAllBytes(output.resolve("file.txt")));
        }
    }

    @Test void dismissingARunningCancelledDownloadCannotResurrectItsJournalOrDiscardTwice() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var cleaned = new CountDownLatch(1);
        var discards = new AtomicInteger(); Path journal = temp.resolve("journal");
        DownloadQueue.Work work = new DownloadQueue.Work() {
            public Path run(DownloadQueue.Progress progress) throws Exception { entered.countDown(); release.await(); progress.update(1, 2); return temp.resolve("unused"); }
            public Properties descriptor() { return new Properties(); }
            public void discard() { discards.incrementAndGet(); cleaned.countDown(); }
        };
        try (var queue = new DownloadQueue(owner -> true, journal, values -> work)) {
            String id = queue.add("cancel", "alice", "Alice", "file", 2, work);
            assertTrue(entered.await(5, TimeUnit.SECONDS)); queue.cancel(id); queue.dismiss(id); release.countDown();
            assertTrue(cleaned.await(5, TimeUnit.SECONDS)); assertTrue(queue.items().isEmpty()); assertEquals(1, discards.get());
            assertFalse(Files.exists(journal.resolve(id + ".properties")));
        } finally { release.countDown(); }
        try (var restored = new DownloadQueue(owner -> false, journal, values -> work)) { assertTrue(restored.items().isEmpty()); }
    }

    @Test void clearingUploadsPreservesUnfinishedTransfersAndSenderCancellationGuards() throws Exception {
        try (var uploads = new UploadTracker()) {
            String completed = uploads.begin("one", "share", "", "complete", 10, 0, new Object()); uploads.acknowledged(completed, 10);
            String cancelled = uploads.begin("two", "share", "", "cancelled", 10, 0, new Object(), "request"); uploads.cancelBySender(cancelled);
            String failed = uploads.begin("three", "share", "", "failed", 10, 0, new Object()); uploads.failed(failed);
            String paused = uploads.begin("four", "share", "", "paused", 10, 0, new Object()); uploads.paused("four", "share", "");
            Object connection = new Object(); uploads.begin("five", "share", "", "disconnected", 10, 0, connection); uploads.disconnected(connection);
            for (int i = 0; i < 4; i++) uploads.begin("active-" + i, "share", "", "active", 10, 0, new Object());
            var retained = uploads.items().stream().filter(item -> !item.finished()).map(UploadTracker.Item::key).collect(java.util.stream.Collectors.toSet());
            uploads.dismiss(paused); uploads.clearFinished();
            assertEquals(retained, uploads.items().stream().map(UploadTracker.Item::key).collect(java.util.stream.Collectors.toSet()));
            assertTrue(uploads.items().stream().anyMatch(item -> item.state() == UploadTracker.State.QUEUED));
            assertTrue(uploads.stoppedBySender("two", "share", "", "request"));
            uploads.begin("two", "share", "", "cancelled", 10, 0, new Object(), "request");
            assertEquals(retained, uploads.items().stream().map(UploadTracker.Item::key).collect(java.util.stream.Collectors.toSet()));
        }
    }
}
