package kakha.kudava.onionp2p.drive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class SafeImportTest {
    @TempDir Path temp;
    private Path profile() { return temp.resolve("profile"); }
    private Path drive() { return profile().resolve("drive"); }
    private int checksumCount() throws Exception {
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + profile().resolve("contacts.sqlite"));
             var query = db.createStatement(); var rows = query.executeQuery("SELECT COUNT(*) FROM drive_checksums")) {
            rows.next(); return rows.getInt(1);
        }
    }
    private void assertClean(DriveChecksums checksums) throws IOException {
        try (var files = Files.list(checksums.importStagingDirectory())) {
            assertTrue(files.allMatch(file -> file.getFileName().toString().equals(".lock")), "No abandoned job or temporary copy should remain");
        }
    }
    private void assertEmpty(Path directory) {
        try (var files = Files.list(directory)) { assertEquals(0, files.count(), "Unfinished files must never enter My files"); }
        catch (IOException error) { throw new UncheckedIOException(error); }
    }

    @Test void filesAndWholeFoldersAppearOnlyAfterTheirLastChecksumIsSaved() throws Exception {
        var checksums = new DriveChecksums(profile());
        Path file = Files.write(temp.resolve("file.bin"), new byte[400_000]);
        Path folder = Files.createDirectory(temp.resolve("folder"));
        Files.writeString(folder.resolve("a.txt"), "abc"); Files.writeString(folder.resolve("b.txt"), "def");
        Files.createDirectory(folder.resolve("Empty"));
        for (Path source : List.of(file, folder)) {
            Path destination = drive().resolve(source.getFileName());
            var copySeen = new AtomicBoolean(); var hashSeen = new AtomicBoolean();
            Path result = DriveImports.add(source, drive(), checksums, new DriveImports.Progress() {
                @Override public void file(Path source, long bytes, long hash, long total) {
                    copySeen.set(true); assertFalse(Files.exists(destination));
                }
                @Override public void hashing(Path source, long bytes, long total) {
                    hashSeen.set(true); assertFalse(Files.exists(destination));
                }
                @Override public void finished(Path source, long bytes) { assertFalse(Files.exists(destination)); }
                @Override public void publishing() { assertFalse(Files.exists(destination)); }
            });
            assertTrue(copySeen.get()); assertTrue(hashSeen.get()); assertEquals(destination, result);
            assertTrue(Files.exists(source)); assertClean(checksums);
        }
        assertTrue(Files.isDirectory(drive().resolve("folder/Empty")));
        assertEquals(3, checksumCount());
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + profile().resolve("contacts.sqlite"));
             var query = db.createStatement(); var rows = query.executeQuery("SELECT file_path FROM drive_checksums")) {
            while (rows.next()) assertTrue(Path.of(rows.getString(1)).startsWith(drive()));
        }
    }

    @Test void aSourceChangingDuringCopyLeavesNoPublishedOrTemporaryCopy() throws Exception {
        var checksums = new DriveChecksums(profile());
        Path source = Files.write(temp.resolve("changing.bin"), new byte[400_000]);
        var changed = new AtomicBoolean();
        assertThrows(IOException.class, () -> DriveImports.add(source, drive(), checksums, new DriveImports.Progress() {
            @Override public void file(Path path, long bytes, long hash, long total) {
                if (bytes > 0 && changed.compareAndSet(false, true)) {
                    try { Files.write(path, new byte[]{1}, StandardOpenOption.APPEND); }
                    catch (IOException error) { throw new UncheckedIOException(error); }
                }
            }
        }));
        assertTrue(changed.get()); assertTrue(Files.exists(source));
        assertEmpty(drive()); assertClean(checksums); assertEquals(0, checksumCount());
    }

    @Test void hashingFailureRemovesTheEntireFolderAndAlreadySavedTemporaryChecksums() throws Exception {
        var checksums = new DriveChecksums(profile());
        Path source = Files.createDirectory(temp.resolve("folder"));
        Files.writeString(source.resolve("a.txt"), "first"); Files.writeString(source.resolve("b.txt"), "second");
        int[] finished = {0};
        assertThrows(IllegalStateException.class, () -> DriveImports.add(source, drive(), checksums, new DriveImports.Progress() {
            @Override public void hashing(Path path, long bytes, long total) {
                if (finished[0] > 0) throw new IllegalStateException("Simulated hashing failure");
            }
            @Override public void finished(Path path, long bytes) { finished[0]++; }
        }));
        assertEquals(1, finished[0]); assertEmpty(drive()); assertClean(checksums); assertEquals(0, checksumCount());
        assertEquals("first", Files.readString(source.resolve("a.txt")));
    }

    @Test void failingToSaveFinalChecksumPathsDoesNotPublishTheFile() throws Exception {
        var checksums = new DriveChecksums(profile());
        Path source = Files.writeString(temp.resolve("blocked.txt"), "abc");
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + profile().resolve("contacts.sqlite")); var query = db.createStatement()) {
            query.execute("CREATE TRIGGER fail_publication BEFORE UPDATE OF file_path ON drive_checksums BEGIN SELECT RAISE(FAIL, 'simulated database failure'); END");
        }
        assertThrows(IOException.class, () -> DriveImports.add(source, drive(), checksums));
        assertEmpty(drive()); assertClean(checksums); assertEquals(0, checksumCount());
        assertEquals("abc", Files.readString(source));
    }

    @Test void anItemCreatedWhileHashingIsKeptAndTheImportGetsADifferentName() throws Exception {
        var checksums = new DriveChecksums(profile());
        Path source = Files.writeString(temp.resolve("same.txt"), "imported");
        Path result = DriveImports.add(source, drive(), checksums, new DriveImports.Progress() {
            @Override public void publishing() {
                try { Files.writeString(drive().resolve("same.txt"), "already here", StandardOpenOption.CREATE_NEW); }
                catch (IOException error) { throw new UncheckedIOException(error); }
            }
        });
        assertEquals("already here", Files.readString(drive().resolve("same.txt")));
        assertEquals("same (2).txt", result.getFileName().toString()); assertEquals("imported", Files.readString(result));
        assertClean(checksums); assertEquals(1, checksumCount());
    }

    @Test void aFailedFinalRenameRemovesItsTemporaryAndUnpublishedFinalChecksumRows() throws Exception {
        var checksums = new DriveChecksums(profile()); Files.createDirectories(drive());
        try (var staging = ImportStaging.create(checksums)) {
            Files.writeString(staging.payload(), "abc"); checksums.hashImported(staging.payload(), ignored -> {});
            assertThrows(IOException.class, () -> staging.publish(drive(), "missing-parent/file.txt"));
        }
        assertEmpty(drive()); assertClean(checksums); assertEquals(0, checksumCount());
    }

    @Test void emptyFoldersArePublishedWithoutLeavingStagingFiles() throws Exception {
        var checksums = new DriveChecksums(profile()); Path source = Files.createDirectory(temp.resolve("Empty"));
        Path target = DriveImports.add(source, drive(), checksums);
        assertTrue(Files.isDirectory(target)); assertEmpty(target); assertTrue(Files.exists(source));
        assertClean(checksums); assertEquals(0, checksumCount());
    }

    @Test void interruptedImportsCleanTheirTemporaryDataAndPreserveTheInterruptFlag() throws Exception {
        var checksums = new DriveChecksums(profile()); Path source = Files.writeString(temp.resolve("interrupt.txt"), "abc");
        try {
            assertThrows(Exception.class, () -> DriveImports.add(source, drive(), checksums, new DriveImports.Progress() {
                @Override public void hashing(Path path, long bytes, long total) { Thread.currentThread().interrupt(); }
            }));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        assertEmpty(drive()); assertClean(checksums); assertEquals(0, checksumCount()); assertTrue(Files.exists(source));
    }

    @Test void startupCleansAbandonedImportsButSkipsLiveJobsAndPreservesPublishedItems() throws Exception {
        var checksums = new DriveChecksums(profile());
        try (var live = ImportStaging.create(checksums)) {
            Files.writeString(live.payload(), "active");
            Path abandoned = Files.createDirectory(checksums.importStagingDirectory().resolve("import-" + UUID.randomUUID()));
            Path partial = Files.writeString(abandoned.resolve("payload"), "unfinished"); checksums.sha256(partial);
            Path completed = Files.createDirectories(drive()).resolve("finished.txt"); Files.writeString(completed, "finished");
            String hash = checksums.sha256(completed);
            Path afterRename = Files.createDirectory(checksums.importStagingDirectory().resolve("import-" + UUID.randomUUID()));
            var publication = new Properties(); publication.setProperty("destination", completed.toString());
            DownloadState.write(afterRename.resolve("publication.properties"), publication);
            try (var queue = new ImportQueue(checksums)) {
                var done = new CountDownLatch(1);
                queue.setListener(items -> { if (!items.isEmpty() && items.stream().noneMatch(ImportQueue.Item::active)) done.countDown(); });
                queue.add(List.of(Files.writeString(temp.resolve("next.txt"), "next")), drive());
                assertTrue(done.await(5, TimeUnit.SECONDS));
                assertEquals(ImportQueue.State.COMPLETE, queue.items().getFirst().state());
            }
            assertFalse(Files.exists(abandoned)); assertFalse(Files.exists(afterRename));
            assertEquals("active", Files.readString(live.payload()));
            assertEquals("finished", Files.readString(completed)); assertEquals(hash, checksums.sha256(completed));
            assertEquals(2, checksumCount());
        }
        assertClean(checksums);
    }
}
