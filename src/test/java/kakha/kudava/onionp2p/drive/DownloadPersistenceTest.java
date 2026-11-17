package kakha.kudava.onionp2p.drive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.net.SocketException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class DownloadPersistenceTest {
    @TempDir Path temp;
    private static final DownloadQueue.Progress PROGRESS = new DownloadQueue.Progress() {
        public void update(long received, long total) {} public void stage(String detail) {}
    };

    @Test void reopenedFileUsesSavedOffsetAndProducesTheSameBytes() throws Exception {
        byte[] source = new byte[700000]; new Random(81).nextBytes(source);
        Path checkpoint = temp.resolve("state/file.properties"), output = temp.resolve("output");
        var first = new ResumableFile(checkpoint, output);
        assertThrows(SocketException.class, () -> first.download(output, (offset, version) -> {
            if (offset > 0) throw new SocketException("closed");
            return new ResumableFile.Chunk("v1", "file.bin", source.length, offset, Arrays.copyOfRange(source, 0, ResumableFile.CHUNK_SIZE));
        }, PROGRESS));
        // Simulate a crash after writing bytes but before advancing the checkpoint.
        try (var files = Files.list(output)) {
            Files.write(files.findFirst().orElseThrow(), new byte[]{9, 9, 9}, StandardOpenOption.APPEND);
        }
        var reopened = new ResumableFile(checkpoint, output);
        assertEquals(ResumableFile.CHUNK_SIZE, reopened.received());
        Path complete = reopened.download(output, (offset, version) -> {
            assertTrue(offset >= ResumableFile.CHUNK_SIZE); assertEquals("v1", version);
            return new ResumableFile.Chunk("v1", "file.bin", source.length, offset,
                    Arrays.copyOfRange(source, (int) offset, (int) Math.min(source.length, offset + ResumableFile.CHUNK_SIZE)));
        }, PROGRESS);
        assertArrayEquals(source, Files.readAllBytes(complete));
        assertEquals(complete, new ResumableFile(checkpoint, output).completedPath());
    }

    @Test void pausedQueueReappearsAfterShutdownAndDoesNotStartUntilResume() throws Exception {
        Path journal = temp.resolve("queue"); var runs = new AtomicInteger();
        DownloadQueue.Work work = new DownloadQueue.Work() {
            public Path run(DownloadQueue.Progress progress) { runs.incrementAndGet(); return temp.resolve("result"); }
            public Properties descriptor() { var values = new Properties(); values.setProperty("request.kind", "test"); return values; }
        };
        String id;
        try (var queue = new DownloadQueue(owner -> false, journal, values -> work)) {
            id = queue.add("key", "alice", "Alice", "file.bin", 700000, work); queue.pause(id);
        }
        try (var reopened = new DownloadQueue(owner -> true, journal, values -> work)) {
            assertEquals(1, reopened.items().size()); assertEquals(id, reopened.items().getFirst().id());
            assertEquals(DownloadQueue.State.PAUSED, reopened.items().getFirst().state());
            reopened.wake("alice"); assertEquals(0, runs.get());
            var done = new CountDownLatch(1);
            reopened.setListener(items -> { if (items.getFirst().state() == DownloadQueue.State.COMPLETE) done.countDown(); });
            reopened.resume(id); assertTrue(done.await(3, TimeUnit.SECONDS)); assertEquals(1, runs.get());
        }
    }

    @Test void cancellationRemovesJournalAndPartialFile() throws Exception {
        Path journal = temp.resolve("cancel-queue"), output = temp.resolve("cancel-output"), checkpoint = temp.resolve("cancel-state/file.properties");
        var transfer = new ResumableFile(checkpoint, output);
        assertThrows(SocketException.class, () -> transfer.download(output, (offset, version) -> {
            if (offset > 0) throw new SocketException("offline");
            return new ResumableFile.Chunk("v1", "test.bin", 2, 0, new byte[]{1});
        }, PROGRESS));
        var discarded = new CountDownLatch(1);
        DownloadQueue.Work work = new DownloadQueue.Work() {
            public Path run(DownloadQueue.Progress progress) { return output; }
            public void discard() { transfer.discard(); discarded.countDown(); }
            public Properties descriptor() { return new Properties(); }
        };
        try (var queue = new DownloadQueue(owner -> false, journal, values -> work)) {
            String id = queue.add("key", "alice", "Alice", "file", 2, work); queue.pause(id); queue.cancel(id);
        }
        assertTrue(discarded.await(3, TimeUnit.SECONDS));
        try (var files = Files.list(output)) { assertEquals(0, files.count()); }
        try (var reopened = new DownloadQueue(owner -> true, journal, values -> work)) { assertTrue(reopened.items().isEmpty()); }
    }
}
