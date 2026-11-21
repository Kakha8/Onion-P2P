package kakha.kudava.onionp2p.drive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.SocketException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class DownloadVerificationTest {
    @Test void longSizesAndResumeOffsetsAreAcceptedWithoutAllocatingTheWholeFile() throws Exception {
        Path output = Files.createDirectory(temp.resolve("large")), checkpoint = temp.resolve("large.properties");
        Path partial = output.resolve(".onion-resume-large.part");
        long received = 2L * 1024 * 1024 * 1024 + 11, total = 3L * 1024 * 1024 * 1024;
        try (var file = java.nio.channels.FileChannel.open(partial, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SPARSE)) {
            file.position(received - 1); file.write(java.nio.ByteBuffer.wrap(new byte[]{0}));
        }
        String token = version(new byte[]{1, 2, 3});
        var saved = new Properties(); saved.setProperty("partial", partial.toString()); saved.setProperty("name", "large.bin");
        saved.setProperty("version", token); saved.setProperty("received", Long.toString(received)); saved.setProperty("total", Long.toString(total));
        DownloadState.write(checkpoint, saved);
        var transfer = new ResumableFile(checkpoint, output);
        assertThrows(CancellationException.class, () -> transfer.download(output, (offset, version) -> {
            assertEquals(received, offset); assertEquals(token, version);
            return new ResumableFile.Chunk(token, "large.bin", total, offset, new byte[]{1, 2, 3});
        }, new DownloadQueue.Progress() {
            public void stage(String detail) { fail("The incomplete file must not be verified yet"); }
            public void update(long bytes, long size) { assertEquals(received + 3, bytes); assertEquals(total, size); throw new CancellationException(); }
        }));
        assertEquals(received + 3, new ResumableFile(checkpoint, output).received());
        assertFalse(Files.exists(output.resolve("large.bin")));
        transfer.discard();
    }
    @TempDir Path temp;
    private static final DownloadQueue.Progress PROGRESS = new DownloadQueue.Progress() {
        public void update(long bytes, long total) {} public void stage(String detail) {}
    };
    private static String version(byte[] bytes) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)) + ":version1";
    }
    private static ResumableFile.Source source(byte[] bytes, String version) {
        return (offset, ignored) -> new ResumableFile.Chunk(version, "file.bin", bytes.length, offset,
                Arrays.copyOfRange(bytes, (int) offset, (int) Math.min(bytes.length, offset + ResumableFile.CHUNK_SIZE)));
    }

    @Test void verifiedFileIsPromotedOnlyAfterReadingTheSavedBytes() throws Exception {
        byte[] bytes = new byte[300000]; new Random(42).nextBytes(bytes);
        var verifying = new AtomicBoolean();
        Path output = temp.resolve("out");
        var progress = new DownloadQueue.Progress() {
            public void update(long received, long total) {}
            public void stage(String detail) {
                assertTrue(detail.startsWith("Verifying")); assertFalse(Files.exists(output.resolve("file.bin"))); verifying.set(true);
            }
        };
        Path file = new ResumableFile().download(output, source(bytes, version(bytes)), progress);
        assertTrue(verifying.get()); assertArrayEquals(bytes, Files.readAllBytes(file));
    }

    @Test void mismatchedDigestFailsAndDiscardsTemporaryCopyAndCheckpoint() throws Exception {
        Path state = temp.resolve("state/file.properties"), output = temp.resolve("out");
        var transfer = new ResumableFile(state, output);
        var error = assertThrows(ResumableFile.VerificationException.class,
                () -> transfer.download(output, source(new byte[]{1, 3}, version(new byte[]{1, 2})), PROGRESS));
        assertTrue(error.getMessage().startsWith("Verification failed"));
        assertFalse(Files.exists(state)); assertNull(transfer.completedPath()); assertEquals(0, transfer.received());
        try (var files = Files.list(output)) { assertEquals(0, files.count()); }
        Path retry = transfer.download(output, source(new byte[]{1, 2}, version(new byte[]{1, 2})), PROGRESS);
        assertArrayEquals(new byte[]{1, 2}, Files.readAllBytes(retry));
    }

    @Test void corruptedBytesFromBeforeRestartAreDetectedAfterResume() throws Exception {
        byte[] bytes = new byte[400000]; new Random(7).nextBytes(bytes);
        String version = version(bytes); Path state = temp.resolve("state/file.properties"), output = temp.resolve("out");
        var first = new ResumableFile(state, output);
        assertThrows(SocketException.class, () -> first.download(output, (offset, token) -> {
            if (offset > 0) throw new SocketException("offline");
            return source(bytes, version).read(offset, token);
        }, PROGRESS));
        try (var files = Files.list(output); var channel = java.nio.channels.FileChannel.open(files.findFirst().orElseThrow(), StandardOpenOption.WRITE)) {
            channel.write(java.nio.ByteBuffer.wrap(new byte[]{(byte) (bytes[0] ^ 1)}));
        }
        var resumed = new ResumableFile(state, output);
        assertThrows(ResumableFile.VerificationException.class,
                () -> resumed.download(output, (offset, token) -> {
                    assertTrue(offset >= ResumableFile.CHUNK_SIZE); return source(bytes, version).read(offset, token);
                }, PROGRESS));
        assertFalse(Files.exists(output.resolve("file.bin")));
    }

    @Test void pausingVerificationKeepsThePartialAndResumeRechecksIt() throws Exception {
        byte[] bytes = new byte[]{1, 2, 3}; String token = version(bytes);
        Path state = temp.resolve("state/file.properties"), output = temp.resolve("out");
        var verifying = new AtomicBoolean();
        var progress = new DownloadQueue.Progress() {
            public void update(long received, long total) {}
            public void stage(String detail) { verifying.set(true); }
            public void checkpoint() { if (verifying.get()) throw new CancellationException(); }
        };
        assertThrows(CancellationException.class, () -> new ResumableFile(state, output).download(output, source(bytes, token), progress));
        assertTrue(Files.exists(state)); assertFalse(Files.exists(output.resolve("file.bin")));
        Path file = new ResumableFile(state, output).download(output, source(bytes, token), PROGRESS);
        assertArrayEquals(bytes, Files.readAllBytes(file));
    }

    @Test void fileVersionChangesCannotMixChunksAndOlderSendersCannotBypassVerification() throws Exception {
        byte[] bytes = new byte[300000]; String token = version(bytes); var transfer = new ResumableFile();
        assertThrows(java.io.IOException.class, () -> transfer.download(temp.resolve("changed"), (offset, ignored) ->
                source(bytes, offset == 0 ? token : token + "changed").read(offset, ignored), PROGRESS));
        assertNull(transfer.completedPath()); assertEquals(0, transfer.received());
        assertThrows(ResumableFile.VerificationException.class,
                () -> new ResumableFile().download(temp.resolve("old"), source(new byte[]{1}, "old-version"), PROGRESS));
    }

    @Test void emptyFilesVerifyAndRestartedCompletedCopiesAreNotTrustedBySizeAlone() throws Exception {
        Path state = temp.resolve("state/file.properties"), output = temp.resolve("out");
        byte[] bytes = new byte[]{1, 2}; String token = version(bytes);
        Path file = new ResumableFile(state, output).download(output, source(bytes, token), PROGRESS);
        Files.write(file, new byte[]{9, 9});
        var resumed = new ResumableFile(state, output);
        assertThrows(ResumableFile.VerificationException.class, () -> resumed.download(output, source(bytes, token), PROGRESS));
        assertTrue(Files.exists(file), "An existing published copy must not be deleted by verification");
        assertNull(resumed.completedPath());
        assertArrayEquals(bytes, Files.readAllBytes(resumed.download(output, source(bytes, token), PROGRESS)));
        Path empty = new ResumableFile().download(temp.resolve("empty"), source(new byte[0], version(new byte[0])), PROGRESS);
        assertEquals(0, Files.size(empty));
    }

    @Test @org.junit.jupiter.api.Timeout(15)
    void queueShowsVerifyingThenFailureAndAllowsAFreshRetry() throws Exception {
        byte[] original = new byte[]{1, 2}; String token = version(original);
        var correct = new AtomicBoolean(); var blockOnce = new AtomicBoolean(true);
        var verifying = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var failed = new java.util.concurrent.CountDownLatch(1);
        var done = new java.util.concurrent.CountDownLatch(1);
        Path output = temp.resolve("out"); var transfer = new ResumableFile();
        try (var queue = new DownloadQueue(owner -> true, 50)) {
            queue.setListener(items -> {
                if (items.isEmpty()) return;
                var item = items.getFirst();
                if (item.detail().startsWith("Verifying") && blockOnce.getAndSet(false)) {
                    verifying.countDown();
                    try { release.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                }
                if (item.state() == DownloadQueue.State.FAILED) failed.countDown();
                if (item.state() == DownloadQueue.State.COMPLETE) done.countDown();
            });
            String id = queue.add("file", "owner", "Owner", "file.bin", 2, new DownloadQueue.Work() {
                public Path run(DownloadQueue.Progress progress) throws Exception {
                    return transfer.download(output, source(correct.get() ? original : new byte[]{1, 3}, token), progress);
                }
            });
            try {
                assertTrue(verifying.await(3, java.util.concurrent.TimeUnit.SECONDS));
                assertNotEquals(DownloadQueue.State.COMPLETE, queue.items().getFirst().state());
                assertNull(queue.items().getFirst().destination()); assertFalse(Files.exists(output.resolve("file.bin")));
            } finally { release.countDown(); }
            assertTrue(failed.await(3, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(queue.items().getFirst().detail().startsWith("Verification failed"));
            correct.set(true); queue.retry(id);
            assertTrue(done.await(3, java.util.concurrent.TimeUnit.SECONDS));
            assertArrayEquals(original, Files.readAllBytes(queue.items().getFirst().destination()));
        } finally { release.countDown(); }
    }
}
