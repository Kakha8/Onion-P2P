package kakha.kudava.onionp2p.drive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.SocketException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class DownloadFailuresTest {
    @TempDir Path temp;
    private static final DownloadQueue.Progress PROGRESS = new DownloadQueue.Progress() {
        public void update(long received, long total) {} public void stage(String detail) {}
    };
    private static ResumableFile.Source source(byte[] bytes) throws Exception {
        String token = "sha256:" + HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)) + ":v1";
        return (offset, version) -> new ResumableFile.Chunk(token, "file.bin", bytes.length, offset,
                Arrays.copyOfRange(bytes, (int) offset, (int) Math.min(bytes.length, offset + ResumableFile.CHUNK_SIZE)));
    }

    @Test void verificationPermissionFailureKeepsTheActualPartialAndRetryDoesNotRedownloadIt() throws Exception {
        byte[] bytes = new byte[300000]; new Random(4).nextBytes(bytes);
        var blocked = new AtomicBoolean(true);
        Path directory = temp.resolve("downloads"), state = temp.resolve("state.properties");
        var transfer = new ResumableFile(state, directory, (file, checkpoint) -> {
            if (blocked.get()) throw new AccessDeniedException(file.toString());
            return DriveChecksums.calculate(file, checkpoint);
        });
        var failed = new CountDownLatch(1); var complete = new CountDownLatch(1); var offsets = new CopyOnWriteArrayList<Long>();
        var original = source(bytes);
        try (var queue = new DownloadQueue(owner -> true, 30)) {
            queue.setListener(items -> items.forEach(item -> {
                if (item.state() == DownloadQueue.State.FAILED) failed.countDown();
                if (item.state() == DownloadQueue.State.COMPLETE) complete.countDown();
            }));
            String id = queue.add("file", "owner", "Owner", "file.bin", bytes.length, progress ->
                    transfer.download(directory, (offset, token) -> { offsets.add(offset); return original.read(offset, token); }, progress));
            assertTrue(failed.await(5, TimeUnit.SECONDS));
            assertTrue(queue.items().getFirst().detail().contains("Check permissions"));
            assertFalse(queue.items().getFirst().detail().contains("Verification failed"));
            assertEquals(bytes.length, transfer.received()); assertTrue(Files.exists(state));
            assertEquals(bytes.length, new ResumableFile(state, directory).received(), "A permission failure must preserve resumable data across restart too");
            assertFalse(Files.exists(directory.resolve("file.bin")));
            offsets.clear(); blocked.set(false);
            // Listener callbacks happen before the worker releases its running flag.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (complete.getCount() > 0 && System.nanoTime() < deadline) { queue.retry(id); complete.await(10, TimeUnit.MILLISECONDS); }
            assertEquals(0, complete.getCount()); assertEquals(List.of((long) bytes.length), offsets);
            assertArrayEquals(bytes, Files.readAllBytes(queue.items().getFirst().destination()));
        }
    }

    @Test void missingPartialRestartsAtZeroInsteadOfAppendingAtTheSavedOffset() throws Exception {
        byte[] bytes = new byte[400000]; var original = source(bytes); var transfer = new ResumableFile();
        Path directory = temp.resolve("downloads");
        assertThrows(SocketException.class, () -> transfer.download(directory, (offset, token) -> {
            if (offset > 0) throw new SocketException("connection lost"); return original.read(offset, token);
        }, PROGRESS));
        assertEquals(ResumableFile.CHUNK_SIZE, transfer.received());
        try (var files = Files.list(directory)) { Files.delete(files.findFirst().orElseThrow()); }
        var offsets = new ArrayList<Long>();
        Path completed = transfer.download(directory, (offset, token) -> { offsets.add(offset); return original.read(offset, token); }, PROGRESS);
        assertEquals(0L, offsets.getFirst()); assertArrayEquals(bytes, Files.readAllBytes(completed));
    }

    @Test void interruptedFolderShowsTheMemberAndRetainsAutomaticRecovery() throws Exception {
        var attempts = new AtomicInteger(); var waiting = new CountDownLatch(1); var complete = new CountDownLatch(1);
        var detail = new AtomicReference<String>(); var received = new AtomicLong();
        try (var queue = new DownloadQueue(owner -> true, 30)) {
            queue.setListener(items -> items.forEach(item -> {
                if (item.state() == DownloadQueue.State.WAITING && item.detail().contains("interrupted")) {
                    detail.set(item.detail()); received.set(item.received()); waiting.countDown();
                }
                if (item.state() == DownloadQueue.State.COMPLETE) complete.countDown();
            }));
            queue.add("folder", "alice", "Alice", "Reports", 100, progress -> {
                if (attempts.incrementAndGet() == 1) {
                    progress.update(40, 100);
                    throw new FileFailures.FolderItemException("Reports/year.pdf", new IOException("Tor could not reach the contact"));
                }
                progress.update(100, 100); return temp.resolve("Reports");
            });
            assertTrue(waiting.await(5, TimeUnit.SECONDS)); assertEquals(40, received.get());
            assertTrue(detail.get().contains("Reports/year.pdf")); assertTrue(detail.get().contains("Saved progress is kept"));
            assertTrue(complete.await(5, TimeUnit.SECONDS)); assertEquals(2, attempts.get());
        }
    }

    @Test void wrappedFolderControlResponsesRetainQueueAndSenderCancellationSemantics() throws Exception {
        var queued = new CountDownLatch(1); var cancelled = new CountDownLatch(1);
        var stop = new AtomicBoolean();
        try (var queue = new DownloadQueue(owner -> true, 30)) {
            queue.setListener(items -> items.forEach(item -> {
                if (item.state() == DownloadQueue.State.QUEUED && item.detail().contains("position 2")) queued.countDown();
                if (item.state() == DownloadQueue.State.CANCELLED && item.detail().equals("Sharing access removed by sender.")) cancelled.countDown();
            }));
            queue.add("folder", "alice", "Alice", "Reports", 100, progress -> {
                throw new FileFailures.FolderItemException("Reports/year.pdf", stop.get()
                        ? new DownloadQueue.OwnerStoppedException("Sharing access removed by sender.") : new DownloadQueue.QueuedException(2));
            });
            assertTrue(queued.await(5, TimeUnit.SECONDS)); stop.set(true); queue.wake("alice");
            assertTrue(cancelled.await(5, TimeUnit.SECONDS));
        }
    }

    @Test void diskFullFailsWithRetryAdviceRatherThanWaitingForTheOwner() throws Exception {
        var failed = new CountDownLatch(1);
        try (var queue = new DownloadQueue(owner -> true, 30)) {
            queue.setListener(items -> items.forEach(item -> { if (item.state() == DownloadQueue.State.FAILED) failed.countDown(); }));
            queue.add("file", "alice", "Alice", "file.bin", 100, progress -> {
                progress.update(40, 100); throw new FileSystemException(temp.toString(), null, "No space left on device");
            });
            assertTrue(failed.await(5, TimeUnit.SECONDS));
            assertTrue(queue.items().getFirst().detail().contains("Free up space")); assertEquals(40, queue.items().getFirst().received());
        }
    }

    @Test void journalWriteFailureStillPublishesTheActionableFailureAndInitialFailureLeavesNoPhantomDownload() throws Exception {
        Path journal = temp.resolve("journal"), backup = temp.resolve("journal-backup");
        var started = new CountDownLatch(1); var release = new CountDownLatch(1); var failed = new CountDownLatch(1);
        DownloadQueue.Work work = new DownloadQueue.Work() {
            public Path run(DownloadQueue.Progress progress) throws Exception {
                started.countDown(); release.await(5, TimeUnit.SECONDS); progress.update(40, 100); return temp.resolve("unused");
            }
            public Properties descriptor() { return new Properties(); }
        };
        try (var queue = new DownloadQueue(owner -> true, journal, values -> work)) {
            queue.setListener(items -> items.forEach(item -> {
                if (item.state() == DownloadQueue.State.FAILED && item.detail().contains("app storage")) failed.countDown();
            }));
            queue.add("first", "alice", "Alice", "file.bin", 100, work);
            assertTrue(started.await(5, TimeUnit.SECONDS));
            Files.move(journal, backup); Files.writeString(journal, "simulated unavailable storage");
            try {
                release.countDown(); assertTrue(failed.await(5, TimeUnit.SECONDS), "A failed journal must not suppress UI notification");
                assertThrows(UncheckedIOException.class, () -> queue.add("second", "alice", "Alice", "second.bin", 100, work));
                assertEquals(1, queue.items().size());
            } finally { Files.delete(journal); Files.move(backup, journal); }
        } finally { release.countDown(); }
    }
}
