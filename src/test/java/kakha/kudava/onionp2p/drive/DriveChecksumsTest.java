package kakha.kudava.onionp2p.drive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

class DriveChecksumsTest {
    @TempDir Path temp;
    private static final String ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    private static final String EMPTY = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Test void importedBytesHaveAKnownPersistentSha256AndOriginalIsKept() throws Exception {
        Path profile = temp.resolve("profile"), source = Files.writeString(temp.resolve("abc.txt"), "abc");
        var checksums = new DriveChecksums(profile);
        Path copy = DriveImports.add(source, profile.resolve("drive"), checksums);
        assertEquals("abc", Files.readString(source)); assertEquals("abc", Files.readString(copy));
        assertEquals(ABC, checksums.sha256(copy));
        assertEquals(ABC, new DriveChecksums(profile).sha256(copy));
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + profile.resolve("contacts.sqlite"));
             var rows = db.createStatement().executeQuery("SELECT sha256,file_size FROM drive_checksums")) {
            assertTrue(rows.next()); assertEquals(ABC, rows.getString(1)); assertEquals(3, rows.getLong(2));
        }
    }

    @Test void folderImportsHashEveryFileAndRetainEmptyFoldersAndDistinctNames() throws Exception {
        Path profile = temp.resolve("profile"), source = Files.createDirectory(temp.resolve("folder"));
        Files.writeString(source.resolve("a.txt"), "abc");
        Files.createFile(source.resolve("empty.txt")); Files.createDirectory(source.resolve("Empty folder"));
        var checksums = new DriveChecksums(profile);
        Path first = DriveImports.add(source, profile.resolve("drive"), checksums);
        Path second = DriveImports.add(source, profile.resolve("drive"), checksums);
        assertNotEquals(first, second);
        assertEquals(ABC, checksums.sha256(first.resolve("a.txt")));
        assertEquals(EMPTY, checksums.sha256(first.resolve("empty.txt")));
        assertEquals(ABC, checksums.sha256(second.resolve("a.txt")));
        assertTrue(Files.isDirectory(second.resolve("Empty folder")));
        assertEquals(4, count(profile));
    }

    @Test void changesInvalidateOldChecksumEvenWhenSizeStaysTheSame() throws Exception {
        Path profile = temp.resolve("profile"); var checksums = new DriveChecksums(profile);
        Path file = Files.writeString(temp.resolve("mutable.txt"), "abc");
        assertEquals(ABC, checksums.sha256(file));
        Files.writeString(file, "xyz"); Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 10000));
        String changed = checksums.sha256(file);
        assertNotEquals(ABC, changed);
        assertEquals("3608bca1e44ea6c4d268eb6db02260269892c0b42b86bbf1e77a6fa16c3c9282", changed);
    }

    @Test void renameAndDeleteKeepMetadataScopedToTheCorrectSubtree() throws Exception {
        Path profile = temp.resolve("profile"), from = Files.createDirectory(temp.resolve("data"));
        Path file = Files.writeString(from.resolve("a.txt"), "abc");
        Path sibling = Files.writeString(temp.resolve("data-other.txt"), "abc");
        var checksums = new DriveChecksums(profile); checksums.sha256(file); checksums.sha256(sibling);
        Path to = temp.resolve("renamed"); Files.move(from, to); checksums.moved(from, to);
        assertEquals(ABC, checksums.sha256(to.resolve("a.txt"))); assertEquals(2, count(profile));
        checksums.deleted(to); assertEquals(1, count(profile)); assertEquals(ABC, checksums.sha256(sibling));
    }

    @Test void copyNeverOverwritesAnExistingDestinationAndRejectsRecursiveImports() throws Exception {
        Path profile = temp.resolve("profile"); var checksums = new DriveChecksums(profile);
        Path source = Files.writeString(temp.resolve("source.txt"), "abc");
        Path target = Files.writeString(temp.resolve("existing.txt"), "keep");
        assertThrows(FileAlreadyExistsException.class, () -> checksums.copy(source, target));
        assertEquals("keep", Files.readString(target)); assertEquals(0, count(profile));
        assertThrows(java.io.IOException.class, () -> DriveImports.add(profile, profile.resolve("drive"), checksums));
    }

    private static int count(Path profile) throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + profile.resolve("contacts.sqlite"));
             var rows = c.createStatement().executeQuery("SELECT COUNT(*) FROM drive_checksums")) {
            rows.next(); return rows.getInt(1);
        }
    }
}
