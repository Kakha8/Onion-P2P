package kakha.kudava.onionp2p.drive;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Bounded directory metadata and confined, read-only access to a published folder. */
public final class FolderAccess {
    public record Entry(String name, boolean folder, long size) {}
    public static final int MAX_LIST_BYTES = 2 * 1024 * 1024;
    private FolderAccess() {}

    public static void validateName(String name) throws IOException {
        String base = name == null ? "" : name.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
        if (name == null || name.isBlank() || name.equals(".") || name.equals("..")
                || name.matches(".*[\\\\/:<>\"|?*\\p{Cntrl}].*") || name.endsWith(".") || name.endsWith(" ")
                || base.matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) throw new IOException("Invalid shared filename.");
    }

    public static Path resolve(SharedDriveStore store, String id, String contact, String relative) throws Exception {
        var share = store.findPublished(id, contact);
        if (share == null || !share.folder()) throw new IOException("Folder access was removed.");
        Path root = Path.of(share.path()).toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root))
            throw new IOException("The shared folder is no longer available.");
        if (relative.length() > 4096 || relative.contains("\\") || relative.contains(":"))
            throw new IOException("Invalid folder path.");
        Path tail = Path.of(relative.isEmpty() ? "." : relative);
        if (tail.isAbsolute()) throw new IOException("Invalid folder path.");
        for (Path part : tail) if (part.toString().equals("..")) throw new IOException("Invalid folder path.");
        Path target = root.resolve(tail).normalize();
        if (!target.startsWith(root)) throw new IOException("Invalid folder path.");
        Path step = root;
        for (Path part : root.relativize(target)) {
            step = step.resolve(part);
            if (Files.isSymbolicLink(step)) throw new IOException("Links cannot be shared.");
        }
        if (!target.toRealPath().startsWith(root.toRealPath())) throw new IOException("Invalid folder path.");
        return target;
    }

    public static List<Entry> list(Path directory) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Folder is no longer available.");
        var result = new ArrayList<Entry>();
        try (var paths = Files.newDirectoryStream(directory)) {
            for (Path path : paths) {
                if (Files.isSymbolicLink(path)) continue;
                boolean folder = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
                if (!folder && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                if (result.size() >= 10000) throw new IOException("This folder has too many entries. Open a smaller folder.");
                result.add(new Entry(path.getFileName().toString(), folder, folder ? -1 : Files.size(path)));
            }
        }
        result.sort(Comparator.comparing(Entry::folder).reversed().thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }

    public static byte[] encode(List<Entry> entries) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(entries.size());
            for (var entry : entries) { out.writeUTF(entry.name()); out.writeBoolean(entry.folder()); out.writeLong(entry.size()); }
        }
        if (bytes.size() > MAX_LIST_BYTES) throw new IOException("Folder listing is too large.");
        return bytes.toByteArray();
    }

    public static List<Entry> decode(byte[] bytes) throws IOException {
        if (bytes.length > MAX_LIST_BYTES) throw new IOException("Folder listing is too large.");
        var entries = new ArrayList<Entry>();
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int count = in.readInt();
            if (count < 0 || count > 10000) throw new IOException("Invalid folder listing.");
            Set<String> names = new HashSet<>();
            for (int i = 0; i < count; i++) {
                String name = in.readUTF(); boolean folder = in.readBoolean(); long size = in.readLong();
                validateName(name);
                if (!names.add(name.toLowerCase(Locale.ROOT))
                        || (folder ? size != -1 : size < 0)) throw new IOException("Invalid folder entry.");
                entries.add(new Entry(name, folder, size));
            }
            if (in.read() != -1) throw new IOException("Invalid folder listing.");
        }
        return List.copyOf(entries);
    }
}
