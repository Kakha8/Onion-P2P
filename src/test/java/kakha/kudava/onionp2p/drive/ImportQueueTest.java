package kakha.kudava.onionp2p.drive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ImportQueueTest {
    @TempDir Path temp;

    @Test @Timeout(15)
    void multipleFilesAndFoldersFinishEvenWhenOneSelectedItemFails() throws Exception {
        var checksums = new DriveChecksums(temp.resolve("profile"));
        var folder = Files.createDirectory(temp.resolve("Folder"));
        Files.writeString(folder.resolve("nested.txt"), "abc");
        Files.createDirectory(folder.resolve("Empty"));
        var file = Files.writeString(temp.resolve("other.txt"), "other");
        Path destination = temp.resolve("drive/subfolder");
        try (var queue = new ImportQueue(checksums)) {
            var finished = new CountDownLatch(1);
            var snapshots = new CopyOnWriteArrayList<List<ImportQueue.Item>>();
            queue.setListener(items -> {
                snapshots.add(items);
                if (items.size() == 3 && items.stream().noneMatch(ImportQueue.Item::active)) finished.countDown();
            });
            queue.add(List.of(temp.resolve("missing"), folder, file), destination);
            assertTrue(finished.await(10, TimeUnit.SECONDS));
            var items = queue.items();
            assertEquals(List.of(ImportQueue.State.FAILED, ImportQueue.State.COMPLETE, ImportQueue.State.COMPLETE), items.stream().map(ImportQueue.Item::state).toList());
            assertEquals("abc", Files.readString(destination.resolve("Folder/nested.txt")));
            assertTrue(Files.isDirectory(destination.resolve("Folder/Empty")));
            assertEquals("other", Files.readString(destination.resolve("other.txt")));
            assertEquals(checksums.sha256(file), checksums.sha256(destination.resolve("other.txt")));
            for (var item : items.subList(1, 3)) {
                assertEquals(item.total(), item.copied()); assertEquals(item.total(), item.hashed());
                assertEquals(item.files(), item.filesDone());
            }
            assertTrue(snapshots.stream().flatMap(List::stream).anyMatch(item -> item.state() == ImportQueue.State.COPYING));
            assertTrue(snapshots.stream().flatMap(List::stream).anyMatch(item -> item.state() == ImportQueue.State.HASHING));
            assertThrows(UnsupportedOperationException.class, () -> items.clear());
        }
    }

    @Test
    void copyReportsRealByteProgressForBothStagesAndSavesTheMatchingDigest() throws Exception {
        var checksums = new DriveChecksums(temp.resolve("profile"));
        var data = new byte[400_000]; new Random(12).nextBytes(data);
        Path source = Files.write(temp.resolve("large.bin"), data), target = temp.resolve("copied.bin");
        var samples = new ArrayList<long[]>();
        checksums.copy(source, target, (copied, hashed, total) -> samples.add(new long[]{copied, hashed, total}));
        assertTrue(samples.size() > 5);
        assertTrue(samples.stream().anyMatch(sample -> sample[0] > sample[1]), "Copy progress precedes hashing of each buffer");
        long copied = 0, hashed = 0;
        for (var sample : samples) {
            assertTrue(sample[0] >= copied); assertTrue(sample[1] >= hashed);
            assertTrue(sample[1] <= sample[0]); assertEquals(data.length, sample[2]);
            if (sample[1] > 0) assertEquals(data.length, sample[0], "Hashing must only start after copying finishes");
            copied = sample[0]; hashed = sample[1];
        }
        assertEquals(data.length, copied); assertEquals(data.length, hashed);
        assertArrayEquals(data, Files.readAllBytes(target));
        assertEquals(DriveChecksums.calculate(source, () -> {}), checksums.sha256(target));
    }

    @Test void folderCopyFinishesBeforeAnyOfItsFilesAreHashed() throws Exception {
        var checksums = new DriveChecksums(temp.resolve("profile"));
        Path folder = Files.createDirectory(temp.resolve("folder"));
        Files.writeString(folder.resolve("first.txt"), "first"); Files.writeString(folder.resolve("second.txt"), "second");
        var copied = new java.util.HashSet<Path>(); var hashed = new java.util.HashSet<Path>();
        DriveImports.add(folder, temp.resolve("drive"), checksums, new DriveImports.Progress() {
            @Override public void file(Path source, long bytes, long hash, long total) { assertTrue(hashed.isEmpty()); }
            @Override public void copied(Path source, long bytes) { copied.add(source); }
            @Override public void hashing(Path source, long bytes, long total) {
                assertEquals(2, copied.size(), "Copy the entire folder before hashing"); hashed.add(source);
            }
        });
        assertEquals(copied, hashed);
    }
}
