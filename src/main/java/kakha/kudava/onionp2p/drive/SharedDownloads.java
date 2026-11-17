package kakha.kudava.onionp2p.drive;

import kakha.kudava.onionp2p.contacts.ContactManager;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Durable requests, folder manifests and authenticated chunk checkpoints. */
public final class SharedDownloads implements AutoCloseable {
    private final ContactManager manager;
    private final DownloadQueue queue;
    private final Path stateRoot, defaultDestination;
    public SharedDownloads(ContactManager manager, Path directory) throws IOException {
        this.manager = manager;
        stateRoot = directory.resolve("download-state");
        defaultDestination = directory.resolve("drive/shared").toAbsolutePath().normalize();
        queue = new DownloadQueue(owner -> {
            if (!manager.isTorReady()) throw new DownloadQueue.TorNotReadyException();
            return true;
        }, stateRoot.resolve("queue"), values -> {
            try { return new FileWork(values); }
            catch (IOException error) { throw new UncheckedIOException(error); }
        });
    }
    public DownloadQueue queue() { return queue; }
    public void file(SharedDriveStore.SharedFile share, String sender) {
        add(share, sender, "", share.name(), false, share.size(), defaultDestination, true);
    }
    public void folderItem(SharedDriveStore.SharedFile share, String sender, String relative,
                           String name, boolean folder, long size, Path saveIn) {
        add(share, sender, relative, name, folder, size, saveIn, false);
    }
    private void add(SharedDriveStore.SharedFile share, String sender, String relative, String name,
                     boolean folder, long size, Path saveIn, boolean rootFile) {
        try {
            var values = new Properties();
            values.setProperty("request.task", UUID.randomUUID().toString());
            values.setProperty("request.share", share.id()); values.setProperty("request.owner", share.contactLink());
            values.setProperty("request.relative", relative); values.setProperty("request.name", name);
            values.setProperty("request.folder", Boolean.toString(folder)); values.setProperty("request.size", Long.toString(size));
            values.setProperty("request.saveIn", saveIn.toAbsolutePath().normalize().toString());
            values.setProperty("request.rootFile", Boolean.toString(rootFile));
            String key = share.contactLink() + ":" + share.id() + (rootFile ? "" : ":" + relative + ":" + saveIn);
            queue.add(key, share.contactLink(), sender, name, folder ? -1 : size, new FileWork(values));
        } catch (IOException error) { throw new UncheckedIOException("Could not save download request", error); }
    }
    private record Member(String relative, String local, boolean folder, long size) {}
    private final class FileWork implements DownloadQueue.Work {
        final Properties request = new Properties();
        final String owner, shareId, relative, name;
        final boolean folder, rootFile;
        final Path saveIn, state;
        List<Member> plan;
        Path destination;
        final Set<String> completed = new HashSet<>();
        final Map<String, ResumableFile> transfers = new HashMap<>();
        long completeBytes;
        volatile String currentMember;

        FileWork(Properties values) throws IOException {
            for (String key : values.stringPropertyNames()) if (key.startsWith("request.")) request.setProperty(key, values.getProperty(key));
            String task = request.getProperty("request.task"); UUID.fromString(task);
            owner = request.getProperty("request.owner"); shareId = request.getProperty("request.share"); UUID.fromString(shareId);
            relative = request.getProperty("request.relative"); name = request.getProperty("request.name"); FolderAccess.validateName(name);
            folder = Boolean.parseBoolean(request.getProperty("request.folder")); rootFile = Boolean.parseBoolean(request.getProperty("request.rootFile"));
            saveIn = Path.of(request.getProperty("request.saveIn")).toAbsolutePath().normalize();
            state = stateRoot.resolve("work").resolve(task);
            Properties saved = DownloadState.read(state.resolve("folder.properties"));
            if (!saved.isEmpty()) {
                if (saved.containsKey("destination")) {
                    destination = Path.of(saved.getProperty("destination")).toAbsolutePath().normalize();
                    if (!saveIn.equals(destination.getParent()) || Files.isSymbolicLink(destination)) throw new IOException("Invalid saved folder destination.");
                }
                int count = Integer.parseInt(saved.getProperty("count"));
                if (count < 0 || count > 100000) throw new IOException("Invalid saved folder manifest.");
                var members = new ArrayList<Member>();
                for (int i = 0; i < count; i++) {
                    String local = saved.getProperty(i + ".local");
                    Path tail = Path.of(local);
                    if (tail.isAbsolute()) throw new IOException("Invalid saved folder path.");
                    for (Path part : tail) FolderAccess.validateName(part.toString());
                    var member = new Member(saved.getProperty(i + ".remote"), local,
                            Boolean.parseBoolean(saved.getProperty(i + ".folder")), Long.parseLong(saved.getProperty(i + ".size")));
                    members.add(member);
                    if (destination != null && !member.folder()) {
                        var transfer = transfer(member.relative(), destination.resolve(member.local()).getParent());
                        if (transfer.completedPath() != null) { completed.add(member.relative()); completeBytes += member.size(); }
                    }
                }
                plan = List.copyOf(members);
            }
            if (!folder) transfer(relative, saveIn);
        }
        public Properties descriptor() { var copy = new Properties(); copy.putAll(request); return copy; }
        public long savedReceived() { return transfers.values().stream().mapToLong(ResumableFile::received).sum(); }
        public long savedTotal() {
            return folder ? plan == null ? -1 : plan.stream().filter(member -> !member.folder()).mapToLong(Member::size).sum()
                    : Long.parseLong(request.getProperty("request.size"));
        }
        public Path run(DownloadQueue.Progress progress) throws Exception {
            if (!folder) {
                if (rootFile) {
                    var saved = manager.sharedDriveFiles().stream().filter(file -> file.id().equals(shareId)
                            && file.contactLink().equals(owner) && file.downloaded()).findFirst();
                    if (saved.isPresent()) return Path.of(saved.get().path());
                }
                Path path = downloadMember(relative, saveIn, progress);
                if (rootFile) manager.markSharedDownload(shareId, path);
                return path;
            }
            if (plan == null) {
                var members = new ArrayList<Member>(); readPlan(relative, "", members, 0, progress);
                plan = List.copyOf(members); saveFolder();
            }
            long total = plan.stream().filter(member -> !member.folder()).mapToLong(Member::size).sum();
            if (destination == null) {
                Files.createDirectories(saveIn);
                for (int i = 0; ; i++) {
                    Path candidate = saveIn.resolve(name + (i == 0 ? "" : " (" + i + ")"));
                    try { Files.createDirectory(candidate); destination = candidate; saveFolder(); break; }
                    catch (FileAlreadyExistsException exists) { if (i > 10000) throw exists; }
                }
            }
            progress.update(completeBytes, total);
            for (var member : plan) {
                progress.checkpoint();
                if (member.folder()) { Files.createDirectories(destination.resolve(member.local())); continue; }
                if (completed.contains(member.relative())) continue;
                Path parent = destination.resolve(member.local()).getParent(); Files.createDirectories(parent);
                downloadMember(member.relative(), parent, scaled(progress, completeBytes, member.size(), total));
                completed.add(member.relative()); completeBytes += member.size(); progress.update(completeBytes, total);
            }
            return destination;
        }
        private void saveFolder() throws IOException {
            var values = new Properties(); values.setProperty("count", Integer.toString(plan.size()));
            if (destination != null) values.setProperty("destination", destination.toString());
            for (int i = 0; i < plan.size(); i++) {
                var member = plan.get(i);
                values.setProperty(i + ".remote", member.relative()); values.setProperty(i + ".local", member.local());
                values.setProperty(i + ".folder", Boolean.toString(member.folder())); values.setProperty(i + ".size", Long.toString(member.size()));
            }
            DownloadState.write(state.resolve("folder.properties"), values);
        }
        private ResumableFile transfer(String relative, Path directory) throws IOException {
            var existing = transfers.get(relative); if (existing != null) return existing;
            String hash;
            try { hash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(relative.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
            catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
            var transfer = new ResumableFile(state.resolve(hash + ".properties"), directory);
            transfers.put(relative, transfer); return transfer;
        }
        private Path downloadMember(String relative, Path directory, DownloadQueue.Progress progress) throws Exception {
            currentMember = relative;
            return transfer(relative, directory).download(directory,
                    (offset, version) -> manager.downloadChunk(owner, shareId, relative, offset, version), progress);
        }
        public void discard() {
            transfers.values().forEach(ResumableFile::discard);
            try { Files.deleteIfExists(state.resolve("folder.properties")); } catch (IOException ignored) {}
            try { Files.deleteIfExists(state); } catch (IOException ignored) {}
        }
        public void paused() throws Exception {
            String member = currentMember;
            if (member != null) manager.notifyDownloadPaused(owner, shareId, member);
        }
        private void readPlan(String remote, String local, List<Member> members, int depth, DownloadQueue.Progress progress) throws Exception {
            if (depth > 64) throw new IOException("Folder nesting exceeds 64 levels.");
            progress.stage("Reading " + (remote.isEmpty() ? name : remote) + "…");
            for (var child : manager.browseSharedFolderForDownload(owner, shareId, remote, progress::stage)) {
                if (members.size() >= 100000) throw new IOException("Folder download exceeds 100,000 entries.");
                if (!child.folder() && child.size() > 1024L * 1024 * 1024) throw new IOException(child.name() + " exceeds the 1 GiB file limit.");
                String childRemote = remote.isEmpty() ? child.name() : remote + "/" + child.name();
                String childLocal = local.isEmpty() ? child.name() : local + "/" + child.name();
                members.add(new Member(childRemote, childLocal, child.folder(), child.size()));
                if (child.folder()) readPlan(childRemote, childLocal, members, depth + 1, progress);
            }
        }
    }
    private static DownloadQueue.Progress scaled(DownloadQueue.Progress target, long offset, long size, long total) {
        return new DownloadQueue.Progress() {
            public void checkpoint() { target.checkpoint(); }
            public void update(long received, long wireTotal) { target.update(offset + (wireTotal <= 0 ? 0 : received * size / wireTotal), total); }
            public void stage(String detail) { target.stage(detail); }
        };
    }
    @Override public void close() { queue.close(); }
}
