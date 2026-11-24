package kakha.kudava.onionp2p.drive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.sql.SQLException;
import static org.junit.jupiter.api.Assertions.*;

class FileFailuresTest {
    @TempDir Path temp;

    @Test void nativeDiskAndDatabaseFailuresProvideAnActionWithoutLeakingPaths() {
        for (String reason : new String[]{"No space left on device", "There is not enough space on the disk", "Disk quota exceeded"}) {
            var error = new UncheckedIOException(new FileSystemException(temp.resolve("private.bin").toString(), null, reason));
            assertEquals(FileFailures.Kind.FULL, FileFailures.kind(error));
            assertTrue(FileFailures.adding(error, temp.resolve("source")).contains("Free up space"));
            assertTrue(FileFailures.downloading(error).contains("Retry"));
            assertFalse(FileFailures.downloading(error).contains(temp.toString()));
        }
        assertEquals(FileFailures.Kind.FULL, FileFailures.kind(new IOException(new SQLException("[SQLITE_FULL] disk full"))));
        assertTrue(FileFailures.downloading(new SQLException("database unavailable")).contains("app storage"));
        assertTrue(FileFailures.downloading(new ReadOnlyFileSystemException()).contains("writable folder"));
        assertTrue(FileFailures.downloading(new FileSystemException("private", null, "being used by another process")).contains("Close it"));
        assertEquals(FileFailures.Kind.OTHER, FileFailures.kind(new FileSystemException("disk full.txt")), "Do not classify filenames as native error reasons");
    }

    @Test void sourceFailuresIdentifyTheFolderMemberAndDifferentiateStoragePermissions() {
        Path source = temp.resolve("photos");
        String missing = FileFailures.adding(new NoSuchFileException(source.resolve("trip/photo.jpg").toString()), source);
        assertTrue(missing.contains("photo.jpg")); assertTrue(missing.contains("moved or deleted"));
        assertFalse(missing.contains(temp.toString()));
        assertTrue(FileFailures.adding(new AccessDeniedException(source.toString()), source).contains("Cannot read the original"));
        assertTrue(FileFailures.adding(new AccessDeniedException(temp.resolve("storage/payload").toString()), source).contains("Cannot write to app storage"));
        assertTrue(FileFailures.adding(new FileFailures.ChangedException("technical message"), source).contains("stops changing"));
    }

    @Test void remoteAndFolderMessagesPreserveContextAndHideOwnerPaths() {
        var error = new AccessDeniedException(temp.resolve("private/source.bin").toString());
        String remote = FileFailures.remote(error);
        assertTrue(remote.contains("owner's app")); assertTrue(remote.contains("permissions")); assertFalse(remote.contains(temp.toString()));
        assertTrue(FileFailures.remote(new NoSuchFileException("secret")).contains("share it again"));
        assertTrue(FileFailures.remote(new FileFailures.ChangedException("secret")).contains("shared file changed"));
        var member = new FileFailures.FolderItemException("Reports/report.pdf", error);
        assertTrue(FileFailures.downloading(member).startsWith("“Reports/report.pdf”: Cannot access"));
        assertTrue(FileFailures.interruptedFolder(member).contains("Reports/report.pdf"));
        assertTrue(FileFailures.downloading(new IOException("unexpected " + temp)).contains("Choose Retry"));
        assertFalse(FileFailures.downloading(new IOException("unexpected " + temp)).contains(temp.toString()));
        assertTrue(FileFailures.downloading(new IOException("Folder access was removed.")).contains("share the folder again"));
    }
}
