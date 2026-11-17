package kakha.kudava.onionp2p.drive;

import kakha.kudava.onionp2p.contacts.ContactManager;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Connects the queue to Drive and retains completed folder members across retries. */
public final class SharedDownloads implements AutoCloseable {
    private final ContactManager manager;
    private final DownloadQueue queue;
    public SharedDownloads(ContactManager manager) {
        this.manager = manager;
        queue = new DownloadQueue(owner -> {
            if (!manager.isTorReady()) throw new DownloadQueue.TorNotReadyException();
            // The download itself proves reachability. Avoid an extra Tor connection and ping.
            return true;
        });
    }
    public DownloadQueue queue() { return queue; }

    public void file(SharedDriveStore.SharedFile share, String sender) {
        var transfer = new ResumableFile();
        queue.add(share.contactLink() + ":" + share.id(), share.contactLink(), sender, share.name(), share.size(), new DownloadQueue.Work() {
            public Path run(DownloadQueue.Progress progress) throws Exception {
            var saved = manager.sharedDriveFiles().stream().filter(file -> file.id().equals(share.id()) && file.contactLink().equals(share.contactLink()) && file.downloaded()).findFirst();
            if (saved.isPresent()) return Path.of(saved.get().path());
            Path path = transfer.download(kakha.kudava.onionp2p.account.AppData.directory().resolve("drive/shared"),
                    (offset, version) -> manager.downloadChunk(share.contactLink(), share.id(), "", offset, version), progress);
            manager.markSharedDownload(share.id(), path);
            return path;
            }
            public void discard() { transfer.discard(); }
            public void paused() throws Exception { manager.notifyDownloadPaused(share.contactLink(), share.id(), ""); }
        });
    }

    public void folderItem(SharedDriveStore.SharedFile share, String sender, String relative,
                           String name, boolean folder, long size, Path saveIn) {
        queue.add(share.contactLink() + ":" + share.id() + ":" + relative + ":" + saveIn,
                share.contactLink(), sender, name, folder ? -1 : size,
                new FolderWork(share, relative, name, folder, size, saveIn));
    }

    private record Member(String relative, String local, boolean folder, long size) {}
    private final class FolderWork implements DownloadQueue.Work {
        final SharedDriveStore.SharedFile share;
        final String relative, name;
        final boolean folder;
        final long size;
        final Path saveIn;
        List<Member> plan;
        Path destination;
        final Set<String> completed = new HashSet<>();
        final Map<String, ResumableFile> transfers = new HashMap<>();
        long completeBytes;
        volatile String currentMember;
        FolderWork(SharedDriveStore.SharedFile share, String relative, String name, boolean folder, long size, Path saveIn) {
            this.share = share; this.relative = relative; this.name = name; this.folder = folder; this.size = size; this.saveIn = saveIn;
        }
        public Path run(DownloadQueue.Progress progress) throws Exception {
            if (!folder) {
                Files.createDirectories(saveIn);
                return downloadMember(relative, saveIn, progress);
            }
            if (plan == null) {
                progress.stage("Reading folder contents…");
                var members = new ArrayList<Member>();
                readPlan(relative, "", members, 0, progress);
                plan = List.copyOf(members);
            }
            long total = plan.stream().filter(member -> !member.folder()).mapToLong(Member::size).sum();
            if (destination == null) {
                FolderAccess.validateName(name); Files.createDirectories(saveIn);
                for (int i = 0; ; i++) {
                    Path candidate = saveIn.resolve(name + (i == 0 ? "" : " (" + i + ")"));
                    try { Files.createDirectory(candidate); destination = candidate; break; }
                    catch (FileAlreadyExistsException exists) { if (i > 10000) throw exists; }
                }
            }
            progress.update(completeBytes, total);
            for (var member : plan) {
                if (member.folder()) { Files.createDirectories(destination.resolve(member.local())); continue; }
                if (completed.contains(member.relative())) continue;
                Path parent = destination.resolve(member.local()).getParent(); Files.createDirectories(parent);
                progress.stage("Downloading " + member.local() + "…");
                long offset = completeBytes;
                downloadMember(member.relative(), parent, scaled(progress, offset, member.size(), total));
                completed.add(member.relative()); completeBytes += member.size();
                progress.update(completeBytes, total);
            }
            return destination;
        }
        private Path downloadMember(String relative, Path directory, DownloadQueue.Progress progress) throws Exception {
            currentMember = relative;
            return transfers.computeIfAbsent(relative, ignored -> new ResumableFile()).download(directory,
                    (offset, version) -> manager.downloadChunk(share.contactLink(), share.id(), relative, offset, version), progress);
        }
        public void discard() { transfers.values().forEach(ResumableFile::discard); }
        public void paused() throws Exception {
            String member = currentMember;
            if (member != null) manager.notifyDownloadPaused(share.contactLink(), share.id(), member);
        }
        private void readPlan(String remote, String local, List<Member> members, int depth, DownloadQueue.Progress progress) throws Exception {
            if (depth > 64) throw new IOException("Folder nesting exceeds 64 levels.");
            progress.stage("Reading " + (remote.isEmpty() ? name : remote) + "…");
            for (var child : manager.browseSharedFolderForDownload(share.contactLink(), share.id(), remote, progress::stage)) {
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
            public void update(long received, long wireTotal) {
                target.update(offset + (wireTotal <= 0 ? 0 : received * size / wireTotal), total);
            }
            public void stage(String detail) { target.stage(detail); }
        };
    }
    @Override public void close() { queue.close(); }
}
