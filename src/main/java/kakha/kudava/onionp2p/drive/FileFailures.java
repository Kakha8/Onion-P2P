package kakha.kudava.onionp2p.drive;

import java.io.*;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;

/** Actionable file-operation messages; technical filesystem paths stay out of activity rows. */
public final class FileFailures {
    private FileFailures() {}
    enum Kind { FULL, DENIED, READ_ONLY, MISSING, CHANGED, BUSY, DATABASE, OTHER }
    public static final class ChangedException extends IOException {
        public ChangedException(String message) { super(message); }
    }
    static final class FolderItemException extends IOException {
        final String member;
        FolderItemException(String member, Exception cause) { super("Folder item failed", cause); this.member = member; }
    }
    static <T extends Throwable> T find(Throwable error, Class<T> type) {
        for (var cause : causes(error)) if (type.isInstance(cause)) return type.cast(cause);
        return null;
    }
    private static List<Throwable> causes(Throwable error) {
        var result = new ArrayList<Throwable>(); var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = error; cause != null && seen.add(cause); cause = cause.getCause()) result.add(cause);
        return result;
    }
    static Kind kind(Throwable error) {
        var causes = causes(error);
        for (var cause : causes) {
            String reason = cause instanceof FileSystemException fs ? fs.getReason() : cause.getMessage();
            String text = reason == null ? "" : reason.toLowerCase(Locale.ROOT);
            if (text.contains("no space left") || text.contains("not enough space") || text.contains("disk is full")
                    || text.contains("disk full") || text.contains("disk quota exceeded") || text.contains("sqlite_full")) return Kind.FULL;
            if (cause instanceof ReadOnlyFileSystemException || text.contains("read-only file system") || text.contains("sqlite_readonly")) return Kind.READ_ONLY;
            if (text.contains("being used by another process") || text.contains("sharing violation") || text.contains("resource busy") || text.contains("file is locked")) return Kind.BUSY;
            if (cause instanceof AccessDeniedException || cause instanceof SecurityException || text.contains("access is denied")
                    || text.contains("permission denied") || text.contains("operation not permitted")) return Kind.DENIED;
            if (cause instanceof NoSuchFileException || cause instanceof FileNotFoundException) return Kind.MISSING;
            if (cause instanceof ChangedException) return Kind.CHANGED;
        }
        return find(error, SQLException.class) != null ? Kind.DATABASE : Kind.OTHER;
    }
    private static boolean sourceFile(Throwable error, Path source) {
        Path root = source.toAbsolutePath().normalize();
        for (var cause : causes(error)) if (cause instanceof FileSystemException fs && fs.getFile() != null) {
            try { if (Path.of(fs.getFile()).toAbsolutePath().normalize().startsWith(root)) return true; }
            catch (InvalidPathException ignored) { }
        }
        return false;
    }
    static String adding(Throwable error, Path source) {
        boolean original = sourceFile(error, source);
        String message = switch (kind(error)) {
            case FULL -> "Not enough disk space to add this item. Free up space in app storage, then add it again.";
            case DENIED -> original ? "Cannot read the original file or folder. Check its permissions, then add it again."
                    : "Cannot write to app storage. Check its permissions, then add this item again.";
            case READ_ONLY -> "App storage is read-only. Make it writable, then add this item again.";
            case MISSING -> original ? "An original file or folder was moved or deleted. Check the source, then add it again."
                    : "The destination or temporary folder is no longer available. Restore it, then add this item again.";
            case CHANGED -> "The original changed while it was being added. Wait until it stops changing, then add it again.";
            case BUSY -> "A file is being used by another program. Close that program, then add this item again.";
            case DATABASE -> "Could not save import information. Check app storage is writable, then add this item again.";
            case OTHER -> domainMessage(error, "Could not add this item. Check the original and app storage, then add it again.");
        };
        if (original) {
            Path root = source.toAbsolutePath().normalize();
            for (var cause : causes(error)) if (cause instanceof FileSystemException fs && fs.getFile() != null) {
                try {
                    Path path = Path.of(fs.getFile()).toAbsolutePath().normalize();
                    if (!path.equals(root) && path.startsWith(root)) {
                        message = "“" + shortName(root.relativize(path).toString()) + "”: " + message;
                        break;
                    }
                } catch (InvalidPathException ignored) { }
            }
        }
        return cleanupNote(error, message);
    }
    public static String downloading(Throwable error) {
        boolean saving = causes(error).stream().anyMatch(cause -> "Could not save download progress".equals(cause.getMessage())
                || "Could not save download request".equals(cause.getMessage()));
        if (saving) return kind(error) == Kind.FULL
                ? "Not enough disk space to save download progress. Free up space in app storage, then choose Retry."
                : "Could not save download progress. Check app storage is available and writable, then choose Retry.";
        var verification = find(error, ResumableFile.VerificationException.class);
        String message = verification != null ? verification.getMessage() : switch (kind(error)) {
            case FULL -> "Not enough disk space. Free up space in the download folder, then choose Retry.";
            case DENIED -> "Cannot access the download folder or saved file. Check permissions, then Retry, or choose Download to… for another folder.";
            case READ_ONLY -> "The download location is read-only. Choose Download to… and select a writable folder.";
            case MISSING -> "The download folder or saved data was moved or deleted. Restore the folder and Retry, or choose Download to….";
            case CHANGED -> "The shared file changed. Choose Retry to download a fresh copy.";
            case BUSY -> "The downloaded file is being used by another program. Close it, then choose Retry.";
            case DATABASE -> "Could not save download information. Check app storage is writable, then choose Retry.";
            case OTHER -> domainMessage(error, "Could not download this item. Choose Retry. If it keeps failing, ask the owner to check the file.");
        };
        var member = find(error, FolderItemException.class);
        return member == null ? message : "“" + shortName(member.member) + "”: " + message;
    }
    public static String remote(Throwable error) {
        return switch (kind(error)) {
            case DENIED, READ_ONLY -> "The owner's app cannot read this file. Ask them to check its permissions, then Retry.";
            case MISSING -> "The owner can no longer find this file. Ask them to check it or share it again.";
            case CHANGED -> "The shared file changed. Choose Retry to download the new version.";
            default -> "The file is unavailable. Ask the owner to check it, then Retry.";
        };
    }
    static String interruptedFolder(Throwable error) {
        var member = find(error, FolderItemException.class);
        return member == null ? "" : "Folder download interrupted at “" + shortName(member.member) + "”. ";
    }
    private static String shortName(String name) { return name.length() > 100 ? "…" + name.substring(name.length() - 99) : name; }
    private static String cleanupNote(Throwable error, String message) {
        return error.getSuppressed().length > 0 ? message + " Temporary cleanup will retry when you sign in again." : message;
    }
    private static String domainMessage(Throwable error, String fallback) {
        for (var cause : causes(error)) {
            if (cause instanceof FolderItemException) continue;
            String message = cause.getMessage();
            if (message != null && (message.startsWith("The shared file changed") || message.startsWith("The sender does not provide")
                    || message.startsWith("The owner") || message.startsWith("This folder") || message.startsWith("Folder download exceeds")
                    || message.startsWith("Folder nesting exceeds") || message.startsWith("Choose ") || message.startsWith("Links cannot")
                    || message.startsWith("The import and My files") || message.startsWith("The file is unavailable"))) return message;
            if ("Folder access was removed.".equals(message)) return "Sharing access was removed. Ask the owner to share the folder again.";
            if ("The shared folder is no longer available.".equals(message) || "Folder is no longer available.".equals(message))
                return "The shared folder is no longer available. Ask the owner to check it, then Retry.";
        }
        return fallback;
    }
}
