package kakha.kudava.onionp2p;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Per-device destination for received attachments. */
final class DownloadLocation {
    private static final String FILE_NAME = "download-directory.txt";

    private DownloadLocation() {}

    static Path get() throws IOException {
        Path setting = AppData.directory().resolve(FILE_NAME);
        if (!Files.exists(setting)) return AppData.directory().resolve("downloads");
        String value = Files.readString(setting, StandardCharsets.UTF_8).strip();
        try {
            Path folder = Path.of(value).toAbsolutePath().normalize();
            if (!Files.isDirectory(folder)) throw new IOException("The selected download folder is unavailable: " + folder);
            return folder;
        } catch (java.nio.file.InvalidPathException e) {
            throw new IOException("The saved download folder path is invalid.", e);
        }
    }

    static void set(Path folder) throws IOException {
        Path selected = folder.toAbsolutePath().normalize();
        if (!Files.isDirectory(selected)) throw new IOException("Choose an existing folder.");
        Path setting = AppData.directory().resolve(FILE_NAME);
        Path temporary = Files.createTempFile(AppData.directory(), "download-directory-", ".tmp");
        try {
            ContactLink.restrictToOwner(temporary);
            Files.writeString(temporary, selected.toString(), StandardCharsets.UTF_8);
            try { Files.move(temporary, setting, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, setting, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
    }
}
