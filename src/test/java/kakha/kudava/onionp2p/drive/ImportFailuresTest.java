package kakha.kudava.onionp2p.drive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class ImportFailuresTest {
    @TempDir Path temp;

    @Test void diskFullDuringRealCopyCleansStagingAndAllowsTheNextImport() throws Exception {
        Path source = Files.write(temp.resolve("large.bin"), new byte[400000]);
        var injected = new AtomicBoolean();
        checkFailure(source, "Free up space", (from, to, checksums, progress) -> DriveImports.add(from, to, checksums, new Forward(progress) {
            public void file(Path path, long copied, long hashed, long total) {
                super.file(path, copied, hashed, total);
                if (copied > 0 && injected.compareAndSet(false, true))
                    throw new UncheckedIOException(new FileSystemException(to.toString(), null, "There is not enough space on the disk"));
            }
        }));
        assertTrue(injected.get()); assertEquals(400000, Files.size(source));
    }

    @Test void sourceChangeAndInterruptedFolderCopyDoNotPublishIncompleteItems() throws Exception {
        Path changing = Files.write(temp.resolve("changing.bin"), new byte[400000]);
        var changed = new AtomicBoolean();
        checkFailure(changing, "stops changing", (from, to, checksums, progress) -> DriveImports.add(from, to, checksums, new Forward(progress) {
            public void file(Path path, long copied, long hashed, long total) {
                super.file(path, copied, hashed, total);
                if (copied > 0 && changed.compareAndSet(false, true)) {
                    try { Files.write(path, new byte[]{1}, StandardOpenOption.APPEND); }
                    catch (IOException error) { throw new UncheckedIOException(error); }
                }
            }
        }));
        Path folder = Files.createDirectory(temp.resolve("folder"));
        Files.writeString(folder.resolve("first.txt"), "first"); Files.writeString(folder.resolve("second.txt"), "second");
        var deleted = new AtomicBoolean();
        checkFailure(folder, "moved or deleted", (from, to, checksums, progress) -> DriveImports.add(from, to, checksums, new Forward(progress) {
            public void copied(Path path, long bytes) {
                super.copied(path, bytes);
                if (deleted.compareAndSet(false, true)) {
                    try (var files = Files.list(folder)) { Files.delete(files.filter(file -> !file.equals(path)).findFirst().orElseThrow()); }
                    catch (IOException error) { throw new UncheckedIOException(error); }
                }
            }
        }));
        assertTrue(changed.get()); assertTrue(deleted.get());
    }

    @Test void deniedSourceAndStorageGiveDifferentNextSteps() throws Exception {
        Path source = Files.writeString(temp.resolve("source.txt"), "keep");
        checkFailure(source, "Cannot read the original", (from, to, checksums, progress) -> { throw new AccessDeniedException(from.toString()); });
        checkFailure(source, "Cannot write to app storage", (from, to, checksums, progress) -> { throw new AccessDeniedException(to.toString()); });
    }

    private void checkFailure(Path source, String expected, ImportQueue.Importer failure) throws Exception {
        Path profile = temp.resolve(UUID.randomUUID().toString()), destination = profile.resolve("drive");
        var checksums = new DriveChecksums(profile);
        Path following = Files.writeString(temp.resolve("following.txt"), "following");
        try (var queue = new ImportQueue(checksums, (from, to, hashes, progress) ->
                from.equals(source) ? failure.add(from, to, hashes, progress) : DriveImports.add(from, to, hashes, progress))) {
            var finished = new CountDownLatch(1);
            queue.setListener(items -> { if (items.size() == 2 && items.stream().noneMatch(ImportQueue.Item::active)) finished.countDown(); });
            queue.add(List.of(source, following), destination);
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertEquals(ImportQueue.State.FAILED, queue.items().getFirst().state());
            assertTrue(queue.items().getFirst().detail().contains(expected), queue.items().getFirst().detail());
            assertFalse(Files.exists(destination.resolve(source.getFileName())));
            assertNull(queue.items().getFirst().destination()); assertTrue(Files.exists(source));
            assertEquals(ImportQueue.State.COMPLETE, queue.items().getLast().state());
            assertEquals("following", Files.readString(destination.resolve("following.txt")));
            try (var staged = Files.list(checksums.importStagingDirectory())) {
                assertTrue(staged.allMatch(path -> path.getFileName().toString().equals(".lock")));
            }
            try (var db = java.sql.DriverManager.getConnection("jdbc:sqlite:" + profile.resolve("contacts.sqlite"));
                 var query = db.createStatement(); var rows = query.executeQuery("SELECT file_path FROM drive_checksums")) {
                assertTrue(rows.next()); assertEquals(destination.resolve("following.txt").toString(), rows.getString(1)); assertFalse(rows.next());
            }
        }
    }

    private static class Forward implements DriveImports.Progress {
        private final DriveImports.Progress next;
        Forward(DriveImports.Progress next) { this.next = next; }
        public void planned(long bytes, int files) { next.planned(bytes, files); }
        public void file(Path path, long copied, long hashed, long total) { next.file(path, copied, hashed, total); }
        public void copied(Path path, long bytes) { next.copied(path, bytes); }
        public void hashing(Path path, long bytes, long total) { next.hashing(path, bytes, total); }
        public void finished(Path path, long bytes) { next.finished(path, bytes); }
        public void publishing() { next.publishing(); }
        public void checkpoint() { next.checkpoint(); }
    }
}
