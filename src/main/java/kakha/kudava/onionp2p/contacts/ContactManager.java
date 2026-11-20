package kakha.kudava.onionp2p.contacts;

import kakha.kudava.onionp2p.account.AccountStore;
import kakha.kudava.onionp2p.messaging.MessageStore;
import kakha.kudava.onionp2p.tor.OnionServer;
import kakha.kudava.onionp2p.tor.TorControl;
import kakha.kudava.onionp2p.drive.SharedDriveStore;
import kakha.kudava.onionp2p.drive.FolderAccess;
import kakha.kudava.onionp2p.drive.DownloadQueue;
import kakha.kudava.onionp2p.drive.SharedDownloads;

import java.net.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Pending imports remain in memory; only cryptographically acknowledged contacts enter SQLite. */
public final class ContactManager implements AutoCloseable {
    public record Event(String link, String message) {}
    public record DriveShareResult(int sharedNow, int queued, int alreadyShared, String deliveryError) {}
    private final Path directory;
    private final OnionServer tor;
    private final ContactStore store;
    private final MessageStore messageStore;
    private final SharedDriveStore sharedDriveStore;
    private final kakha.kudava.onionp2p.drive.DriveChecksums driveChecksums;
    private final kakha.kudava.onionp2p.drive.ImportQueue imports;
    private final SharedDownloads downloads;
    private final kakha.kudava.onionp2p.drive.UploadTracker uploads = new kakha.kudava.onionp2p.drive.UploadTracker();
    private final String localUsername;
    private final Consumer<Event> events;
    private volatile Consumer<String> presenceListener = ignored -> {};
    private volatile Consumer<String> driveShareListener = ignored -> {};
    private volatile Runnable contactsChanged = () -> {};
    private final ConcurrentMap<String, Pending> pending = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, MessageSession> messageSessions = new ConcurrentHashMap<>();
    // Presence, text and share metadata reuse the same authenticated stream.
    private final ConcurrentMap<String, MessageSession> shareSessions = messageSessions;
    private final ConcurrentMap<String, MessageSession> transferSessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> shareDeliveryErrors = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> shareRetryStarted = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> shareRetryLast = new ConcurrentHashMap<>();
    private final Map<Path, Long> missingSharedSources = new HashMap<>();
    private long lastMissingSourceCheck;
    private final ConcurrentMap<String, Object> driveShareLocks = new ConcurrentHashMap<>();
    private final Set<String> driveSyncInFlight = ConcurrentHashMap.newKeySet();
    private final Set<String> driveSyncDirty = ConcurrentHashMap.newKeySet();
    private final Set<String> onlineUpdates = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<String, Long> lastDriveSync = new ConcurrentHashMap<>();
    private final ScheduledExecutorService retries = Executors.newScheduledThreadPool(2, r -> {
        Thread thread = new Thread(r, "contact-retry"); thread.setDaemon(true); return thread;
    });
    // Contact work blocks on network replies and per-contact locks. An offline
    // contact must not occupy a shared worker needed by a reachable contact.
    private final ExecutorService driveShareRetries = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("drive-share-retry-", 0).factory());
    private final ScheduledExecutorService driveShareRetryScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "drive-share-scheduler"); thread.setDaemon(true); return thread;
    });
    private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
    private final Semaphore incoming = new Semaphore(256);
    private volatile boolean closed;

    public ContactManager(Path directory, OnionServer tor, Consumer<Event> events) throws Exception {
        this.directory = directory; this.tor = tor; this.events = events;
        store = new ContactStore(directory);
        messageStore = new MessageStore(directory);
        sharedDriveStore = new SharedDriveStore(directory);
        driveChecksums = new kakha.kudava.onionp2p.drive.DriveChecksums(directory);
        imports = new kakha.kudava.onionp2p.drive.ImportQueue(driveChecksums);
        var profile = new AccountStore(directory).profile();
        localUsername = profile == null ? "local" : profile.username();
        downloads = new SharedDownloads(this, directory);
        driveShareRetryScheduler.scheduleWithFixedDelay(this::retryPendingDriveShares, 1, 1, TimeUnit.SECONDS);
    }

    public List<ContactStore.Contact> contacts() throws Exception { return store.all(); }
    public void setContactsChangedListener(Runnable listener) { contactsChanged = listener == null ? () -> {} : listener; }
    public void setPresenceListener(Consumer<String> listener) {
        presenceListener = listener == null ? ignored -> {} : listener;
    }
    public void setDriveShareListener(Consumer<String> listener) {
        driveShareListener = listener == null ? ignored -> {} : listener;
    }
    private void signalDriveShare(String link) {
        try { driveShareListener.accept(link); }
        catch (RuntimeException e) { System.err.println("Drive-share update failed: " + safeMessage(e)); }
    }
    public List<SharedDriveStore.SharedFile> sharedDriveFiles() throws Exception { return sharedDriveStore.all(); }
    public SharedDownloads downloads() { return downloads; }
    public kakha.kudava.onionp2p.drive.DriveChecksums driveChecksums() { return driveChecksums; }
    public kakha.kudava.onionp2p.drive.ImportQueue imports() { return imports; }
    public kakha.kudava.onionp2p.drive.UploadTracker uploads() { return uploads; }
    public void cancelUpload(String key) {
        var cancellation = uploads.cancelBySender(key); if (cancellation == null) return;
        driveShareRetries.execute(() -> {
            try {
                withMessageSession(cancellation.contact(), ignored -> {}, () -> false, 1, (entry, session, canonical) -> {
                    ContactMessaging.sendDownloadStop(session.socket, entry.material,
                            new ContactMessaging.DownloadStop(cancellation.share(), cancellation.relative(), "Download cancelled by sender.", cancellation.request()), session.firstMessage);
                    return null;
                });
            } catch (Exception ignored) { /* The next chunk request also returns the cancellation. */ }
        });
    }
    public void notifyDownloadPaused(String link, String id, String relative) throws Exception {
        withSession(downloadRequestSessions(link), link, ignored -> {}, () -> false, 1, (entry, session, canonical) -> {
            ContactMessaging.notifyDownloadPaused(session.socket, entry.material, id, relative, session.firstMessage); return null;
        });
    }
    public void notifyDownloadCancelled(String link, String id, String relative, boolean folder) throws Exception {
        withSession(downloadRequestSessions(link), link, ignored -> {}, () -> false, 1, (entry, session, canonical) -> {
            ContactMessaging.notifyDownloadCancelled(session.socket, entry.material, id, relative, folder, session.firstMessage); return null;
        });
    }
    public kakha.kudava.onionp2p.drive.ResumableFile.Chunk downloadChunk(String link, String id, String relative,
            long offset, String version) throws Exception {
        return downloadChunk(link, id, relative, offset, version, "");
    }
    public kakha.kudava.onionp2p.drive.ResumableFile.Chunk downloadChunk(String link, String id, String relative,
            long offset, String version, String requestId) throws Exception {
        var result = new java.util.concurrent.atomic.AtomicReference<kakha.kudava.onionp2p.drive.ResumableFile.Chunk>();
        withSession(downloadRequestSessions(link), link, ignored -> {}, () -> false, 1, (entry, session, canonical) -> {
            result.set(ContactMessaging.downloadChunk(session.socket, entry.material, id, relative, offset, version, session.firstMessage, requestId));
            return null;
        });
        return result.get();
    }
    /** Bounded chunk exchanges can share an established stream without a second Tor setup. */
    private ConcurrentMap<String, MessageSession> downloadRequestSessions(String link) {
        String canonical = canonicalLink(link);
        MessageSession transfer = transferSessions.get(canonical);
        if (established(transfer)) return transferSessions;
        if (established(messageSessions.get(canonical))) return messageSessions;
        return transferSessions;
    }
    private static boolean established(MessageSession session) {
        return session != null && session.socket != null && !session.socket.isClosed() && !session.firstMessage;
    }

    private ConcurrentMap<String, MessageSession> controlSessions(String link) {
        String canonical = canonicalLink(link);
        if (established(messageSessions.get(canonical))) return messageSessions;
        if (established(transferSessions.get(canonical))) return transferSessions;
        return shareSessions;
    }
    public void markSharedDownload(String id, Path path) throws Exception { sharedDriveStore.markDownloaded(id, path); }
    public boolean isTorReady() { return tor.socksReady().isDone() && !tor.socksReady().isCompletedExceptionally(); }

    public int stopSharingDriveFile(Path file) throws Exception {
        int removed = sharedDriveStore.unpublishForPath(file);
        sharedDriveStore.removePendingShares(file);
        for (var entry : pending.values()) if (entry.saved) requestDriveSync(entry.link, true);
        return removed;
    }

    public int stopSharingDriveFile(Path file, String contactLink) throws Exception {
        String canonical = canonicalLink(contactLink);
        int removed = sharedDriveStore.unpublishForPathAndContact(file, canonical);
        sharedDriveStore.removePendingShares(file, canonical);
        requestDriveSync(canonical, true);
        return removed;
    }

    public int driveDeletionShareCount(Path file) throws Exception {
        return sharedDriveStore.deletionShareCount(file);
    }

    /** Called after a successful owner-side delete; revocation persists even while recipients are offline. */
    public void driveEntryDeleted(Path file) throws Exception {
        for (String link : sharedDriveStore.removeDeletedShares(file)) {
            requestDriveSync(link, true);
            signalDriveShare(link);
        }
        try { driveChecksums.deleted(file); }
        catch (Exception error) { System.err.println("Could not clear deleted file checksums: " + safeMessage(error)); }
    }

    public boolean isDriveFileSharedWith(Path file, String contactLink) throws Exception {
        return sharedDriveStore.isPublishedForPathAndContact(file, canonicalLink(contactLink));
    }

    public Map<String, SharedDriveStore.ShareCount> driveShareCounts(List<Path> files) throws Exception {
        return sharedDriveStore.shareCountsForFiles(files);
    }

    public void removeSharedDriveOffer(String shareId, String contactLink) throws Exception {
        sharedDriveStore.removeReceived(shareId, canonicalLink(contactLink));
    }

    public void shareDriveFile(String link, Path file, java.util.function.LongConsumer progress,
                               java.util.function.Consumer<String> status) throws Exception {
        withMessageSession(link, status, () -> false, (entry, session, canonical) -> {
            ContactMessaging.sendDriveShare(session.socket, entry.material, sharedDriveStore, canonical, file,
                    session.firstMessage, progress, () -> false);
            return null;
        });
    }

    /** Queue every selected file durably, then make one immediate delivery attempt. */
    public DriveShareResult shareDriveFiles(String link, List<Path> files) throws Exception {
        String canonical = canonicalLink(link);
        Pending entry = pending.get(canonical);
        if (entry == null || !entry.saved)
            throw new IllegalStateException("This contact is not paired yet. Keep both apps open until pairing is acknowledged.");

        var toQueue = new ArrayList<Path>();
        int alreadyShared = 0;
        for (Path file : files) {
            Path normalized = file.toAbsolutePath().normalize();
            if (java.nio.file.Files.isSymbolicLink(normalized) || (!java.nio.file.Files.isRegularFile(normalized) && !java.nio.file.Files.isDirectory(normalized)))
                throw new java.io.IOException("File is no longer available: " + normalized.getFileName());
            long size = java.nio.file.Files.isDirectory(normalized) ? -1 : java.nio.file.Files.size(normalized);
            if (size > ContactMessaging.MAX_FILE_BYTES)
                throw new IllegalArgumentException("Files must be 1 GiB or smaller: " + normalized.getFileName());
            if (sharedDriveStore.isPublishedForPathAndContact(normalized, canonical)) alreadyShared++;
            else toQueue.add(normalized);
        }
        sharedDriveStore.queueShares(canonical, toQueue);
        shareRetryStarted.put(canonical, System.currentTimeMillis());
        int sent = deliverPendingDriveShares(canonical);
        int queued = sharedDriveStore.pendingShares(canonical).size();
        return new DriveShareResult(sent, queued, alreadyShared, queued == 0 ? null : shareDeliveryErrors.get(canonical));
    }

    private int deliverPendingDriveShares(String contactLink) {
        Object lock = driveShareLocks.computeIfAbsent(contactLink, ignored -> new Object());
        synchronized (lock) {
            if (closed) return 0;
            Pending entry = pending.get(contactLink);
            if (entry == null || !entry.saved) return 0;
            int delivered = 0;
            try {
                for (var queued : sharedDriveStore.pendingShares(contactLink)) {
                    Path file = Path.of(queued.path());
                    try {
                        if (!java.nio.file.Files.isRegularFile(file) && !java.nio.file.Files.isDirectory(file)) {
                            sharedDriveStore.completePendingShare(contactLink, file);
                            System.err.println("Discarded queued share for missing file: " + file.getFileName());
                            continue;
                        }
                        if (sharedDriveStore.isPublishedForPathAndContact(file, contactLink)) {
                            sharedDriveStore.completePendingShare(contactLink, file);
                            continue;
                        }
                        sendDriveShareOnce(entry, contactLink, queued.id(), file);
                        sharedDriveStore.completePendingShare(contactLink, file);
                        delivered++;
                        shareDeliveryErrors.remove(contactLink);
                        shareRetryStarted.remove(contactLink);
                        shareRetryLast.remove(contactLink);
                        signalDriveShare(contactLink);
                    } catch (Exception transportFailure) {
                        shareDeliveryErrors.put(contactLink, safeMessage(transportFailure));
                        // Keep this and all later selections queued; the next authenticated
                        // online signal will retry them.
                        System.err.println("Drive share remains queued for " + contactLink + ": " + safeMessage(transportFailure));
                        break;
                    }
                }
            } catch (Exception error) {
                System.err.println("Could not process queued Drive shares: " + safeMessage(error));
            }
            return delivered;
        }
    }

    private void sendDriveShareOnce(Pending entry, String canonical, String shareId, Path file) throws Exception {
        withSession(controlSessions(canonical), canonical, ignored -> {}, () -> false, 1, (contact, session, link) -> {
            ContactMessaging.sendDriveShare(session.socket, contact.material, sharedDriveStore, link, shareId, file,
                    session.firstMessage, ignored -> {}, () -> false);
            return null;
        });
    }

    public void downloadSharedFile(String link, String shareId, java.util.function.Consumer<String> status) throws Exception {
        downloadSharedFile(link, shareId, status, null);
    }

    public void downloadSharedFile(String link, String shareId, java.util.function.Consumer<String> status, DownloadQueue.Progress progress) throws Exception {
        withSession(transferSessions, link, status, () -> false, 1, (entry, session, canonical) -> {
            ContactMessaging.downloadDriveShare(session.socket, entry.material, sharedDriveStore, shareId, canonical,
                    session.firstMessage, () -> signalDriveShare(canonical), progress);
            return null;
        });
    }

    public void driveEntryMoved(Path before, Path after) throws Exception {
        sharedDriveStore.moved(before, after);
        try { driveChecksums.moved(before, after); }
        catch (Exception error) { System.err.println("Could not move cached file checksums: " + safeMessage(error)); }
    }

    public List<FolderAccess.Entry> browseSharedFolder(String link, String id, String relative,
                                                     java.util.function.Consumer<String> status) throws Exception {
        return browseSharedFolder(link, id, relative, status, 5);
    }

    public List<FolderAccess.Entry> browseSharedFolderForDownload(String link, String id, String relative,
                                                                  java.util.function.Consumer<String> status) throws Exception {
        return browseSharedFolder(link, id, relative, status, 1);
    }

    private List<FolderAccess.Entry> browseSharedFolder(String link, String id, String relative,
                                                        java.util.function.Consumer<String> status, int connectAttempts) throws Exception {
        var result = new java.util.concurrent.atomic.AtomicReference<List<FolderAccess.Entry>>();
        withSession(downloadRequestSessions(link), link, status, () -> false, connectAttempts, (entry, session, canonical) -> {
            result.set(ContactMessaging.browseFolder(session.socket, entry.material, id, relative, session.firstMessage, sharedDriveStore, canonical));
            return null;
        });
        signalDriveShare(canonicalLink(link));
        return result.get();
    }

    public void downloadSharedFolderFile(String link, String id, String relative, Path directory,
                                         java.util.function.Consumer<String> status) throws Exception {
        downloadSharedFolderFile(link, id, relative, directory, status, null);
    }

    public void downloadSharedFolderFile(String link, String id, String relative, Path directory,
                                         java.util.function.Consumer<String> status, DownloadQueue.Progress progress) throws Exception {
        withSession(transferSessions, link, status, () -> false, 1, (entry, session, canonical) -> {
            ContactMessaging.downloadFolderFile(session.socket, entry.material, id, relative, directory, canonical, session.firstMessage, progress);
            return null;
        });
    }

    public void downloadSharedFolder(String link, String id, String relative, Path destination,
                                     java.util.function.Consumer<String> status) throws Exception {
        downloadSharedFolder(link, id, relative, destination, status, 0, new int[]{0});
    }

    private void downloadSharedFolder(String link, String id, String relative, Path destination,
                                      java.util.function.Consumer<String> status, int depth, int[] count) throws Exception {
        if (depth > 64) throw new IOException("Folder nesting exceeds 64 levels.");
        var entries = browseSharedFolder(link, id, relative, status);
        java.nio.file.Files.createDirectories(destination);
        if (java.nio.file.Files.isSymbolicLink(destination)) throw new IOException("Download destination cannot be a link.");
        for (var child : entries) {
            if (++count[0] > 100000) throw new IOException("Folder download exceeds 100,000 entries.");
            String path = relative.isEmpty() ? child.name() : relative + "/" + child.name();
            status.accept("Downloading " + path + "…");
            if (child.folder()) downloadSharedFolder(link, id, path, destination.resolve(child.name()), status, depth + 1, count);
            else downloadSharedFolderFile(link, id, path, destination, status);
        }
    }

    private void signalContactOnline(String link) {
        try { presenceListener.accept(link); }
        catch (RuntimeException e) { System.err.println("Presence update failed: " + safeMessage(e)); }
        // The receive loop must be free to acknowledge the next request.
        // Database and download-queue locks belong on background workers.
        if (closed || !onlineUpdates.add(link)) return;
        try {
            driveShareRetries.execute(() -> {
                try { downloads.queue().wake(link); requestDriveSync(link, false); }
                finally { onlineUpdates.remove(link); }
            });
        } catch (RejectedExecutionException ignored) { onlineUpdates.remove(link); }
    }

    private void requestDriveSync(String link, boolean changed) {
        if (closed) return;
        if (changed) driveSyncDirty.add(link);
        boolean queued;
        try { queued = !sharedDriveStore.pendingShares(link).isEmpty(); }
        catch (Exception error) { queued = false; }
        if (!queued && !driveSyncDirty.contains(link) && System.currentTimeMillis() - lastDriveSync.getOrDefault(link, 0L) < 30000) return;
        if (!driveSyncInFlight.add(link)) return;
        try {
            driveShareRetries.execute(() -> {
                boolean completed = false;
                try {
                    deliverPendingDriveShares(link);
                    driveSyncDirty.remove(link);
                    withSession(controlSessions(link), link, ignored -> {}, () -> false, 1, (entry, session, canonical) -> {
                        ContactMessaging.syncDriveShares(session.socket, entry.material, sharedDriveStore, canonical, session.firstMessage);
                        return null;
                    });
                    lastDriveSync.put(link, System.currentTimeMillis());
                    completed = true;
                } catch (Exception error) {
                    // The stored owner inventory is the durable source of truth. Retry on reconnect.
                    driveSyncDirty.add(link);
                    if (!closed) System.err.println("Share reconciliation waiting for contact: " + safeMessage(error));
                } finally {
                    driveSyncInFlight.remove(link);
                    if (completed && driveSyncDirty.contains(link)) requestDriveSync(link, false);
                }
            });
        } catch (RejectedExecutionException ignored) { driveSyncInFlight.remove(link); }
    }

    /** Retry offline shares in the background, even while the user is viewing Drive. */
    private void retryPendingDriveShares() {
        if (closed) return;
        try {
            pruneDeletedDriveSources();
            if (!isTorReady()) return;
            Set<String> contacts = new HashSet<>(sharedDriveStore.pendingShareContacts());
            contacts.addAll(driveSyncDirty);
            for (String link : contacts) {
                if (closed) return;
                long now = System.currentTimeMillis();
                long started = shareRetryStarted.computeIfAbsent(link, ignored -> now);
                long delay = now - started < TimeUnit.MINUTES.toMillis(2) ? 1000 : 15000;
                if (now - shareRetryLast.getOrDefault(link, 0L) < delay) continue;
                // Coalesce online signals, reconciliation and timer retries for
                // each contact rather than stacking workers behind its lock.
                shareRetryLast.put(link, now);
                requestDriveSync(link, false);
            }
        } catch (Exception error) {
            if (!closed) System.err.println("Could not check queued Drive shares: " + safeMessage(error));
        }
    }

    private void pruneDeletedDriveSources() throws Exception {
        long now = System.currentTimeMillis();
        if (now - lastMissingSourceCheck < 5000) return;
        lastMissingSourceCheck = now;
        Set<Path> missing = sharedDriveStore.missingSharedPaths();
        missingSharedSources.keySet().retainAll(missing);
        for (Path path : missing) {
            Long firstMissing = missingSharedSources.putIfAbsent(path, now);
            // A rename moves the file before updating share metadata. Recheck
            // on the next sweep so a short transition cannot revoke that share.
            if (firstMissing != null && now - firstMissing >= 5000) {
                driveEntryDeleted(path);
                missingSharedSources.remove(path);
            }
        }
    }

    public boolean isOnline(String link) throws Exception {
        String canonical = canonicalLink(link);
        Pending entry = pending.get(canonical);
        // The local listener is only needed to receive connections. An outgoing presence
        // probe can be sent as soon as Tor is ready, even while that listener is publishing.
        if (entry == null || !entry.saved || entry.stopped) return false;
        if (!isTorReady()) return false;
        withSession(controlSessions(canonical), canonical, ignored -> {}, () -> false, 1, (contact, session, ignored) -> {
            ContactMessaging.ping(session.socket, contact.material, session.firstMessage);
            return null;
        });
        // Retain the authenticated socket: queued offers can use it immediately.
        signalContactOnline(canonical);
        return true;
    }
    public List<MessageStore.Message> messages(String link) throws Exception {
        return messageStore.all(canonicalLink(link));
    }

    public void sendReadReceipt(String link, String messageId) throws Exception {
        withMessageSession(link, ignored -> {}, () -> false, 1, (entry, session, canonical) -> {
            ContactMessaging.sendReadReceipt(session.socket, entry.material, messageId, session.firstMessage);
            return null;
        });
    }

    public MessageStore.Message send(String link, String body) throws Exception {
        return send(link, body, ignored -> {});
    }

    public MessageStore.Message send(String link, String body, java.util.function.Consumer<String> status) throws Exception {
        return withMessageSession(link, status, () -> false, (entry, session, canonical) ->
                ContactMessaging.send(session.socket, entry.material, messageStore, canonical, body, session.firstMessage,
                        () -> status.accept("MESSAGE_SENT")));
    }

    public MessageStore.Message sendFile(String link, Path file, java.util.function.LongConsumer progress) throws Exception {
        return sendFile(link, file, progress, ignored -> {});
    }

    public MessageStore.Message sendFile(String link, Path file, java.util.function.LongConsumer progress,
                                  java.util.function.Consumer<String> status) throws Exception {
        return sendFile(link, file, new java.util.concurrent.atomic.AtomicBoolean(), progress, status);
    }

    public MessageStore.Message sendFile(String link, Path file, java.util.concurrent.atomic.AtomicBoolean cancelled,
                                  java.util.function.LongConsumer progress,
                                  java.util.function.Consumer<String> status) throws Exception {
        return sendFile(link, file, "", cancelled, progress, status);
    }

    public MessageStore.Message sendFile(String link, Path file, String caption,
                                  java.util.concurrent.atomic.AtomicBoolean cancelled,
                                  java.util.function.LongConsumer progress,
                                  java.util.function.Consumer<String> status) throws Exception {
        if (java.nio.file.Files.size(file) > ContactMessaging.MAX_FILE_BYTES)
            throw new IllegalArgumentException("Files must be 1 GiB or smaller.");
        return withSession(transferSessions, link, status, cancelled::get, 1, (entry, session, canonical) ->
                ContactMessaging.sendFile(session.socket, entry.material, messageStore, canonical, file, caption,
                        session.firstMessage, progress, cancelled::get));
    }

    private MessageStore.Message withMessageSession(String link, java.util.function.Consumer<String> status,
                                                    java.util.function.BooleanSupplier cancelled,
                                                    SessionSend action) throws Exception {
        return withMessageSession(link, status, cancelled, 5, action);
    }

    private MessageStore.Message withMessageSession(String link, java.util.function.Consumer<String> status,
                                                    java.util.function.BooleanSupplier cancelled, int connectAttempts,
                                                    SessionSend action) throws Exception {
        return withSession(controlSessions(link), link, status, cancelled, connectAttempts, action);
    }

    private MessageStore.Message withSession(ConcurrentMap<String, MessageSession> sessions, String link,
                                            java.util.function.Consumer<String> status,
                                            java.util.function.BooleanSupplier cancelled, int connectAttempts,
                                            SessionSend action) throws Exception {
        String canonical = canonicalLink(link);
        Pending entry = pending.get(canonical);
        if (entry == null || !entry.saved) throw new IllegalStateException("This contact is not ready. Keep both apps open until pairing is acknowledged.");
        MessageSession session = sessions.computeIfAbsent(canonical, ignored -> new MessageSession());
        // Chunk loops and presence probes must not repeatedly overtake a text
        // or share update already waiting for this authenticated stream.
        session.exchange.lockInterruptibly();
        try {
            if (closed) throw new IllegalStateException("Contact service is stopped.");
            checkCancelled(cancelled);
            if (session.socket != null && !session.socket.isClosed() && !session.firstMessage
                    && System.nanoTime() - session.lastUsed > TimeUnit.SECONDS.toNanos(20)) {
                try { ContactMessaging.ping(session.socket, entry.material); }
                catch (Exception stale) {
                    connections.remove(session.socket);
                    try { session.socket.close(); } catch (Exception ignored) {}
                    session.socket = null;
                    session.firstMessage = true;
                    status.accept("The previous Tor connection closed; reconnecting…");
                }
            }
            checkCancelled(cancelled);
            if (session.socket == null || session.socket.isClosed()) {
                int socksPort = tor.socksReady().get(30, TimeUnit.SECONDS);
                session.socket = connectWithRetry(session, socksPort, entry.material.addresses().remote(), status, cancelled, connectAttempts);
                session.firstMessage = true;
                connections.add(session.socket);
            }
            try {
                checkCancelled(cancelled);
                var message = action.send(entry, session, canonical);
                session.firstMessage = false;
                session.lastUsed = System.nanoTime();
                tor.contactExchangeSucceeded();
                return message;
            } catch (DownloadQueue.QueuedException | DownloadQueue.OwnerStoppedException outcome) {
                // These are fully authenticated and acknowledged peer responses,
                // not a broken transport. Queue polling must keep the same stream,
                // which may also be carrying text and presence messages.
                session.firstMessage = false;
                session.lastUsed = System.nanoTime();
                tor.contactExchangeSucceeded();
                throw outcome;
            } catch (Exception e) {
                connections.remove(session.socket);
                try { session.socket.close(); } catch (Exception ignored) {}
                session.socket = null;
                session.firstMessage = true;
                throw e;
            }
        } finally { session.exchange.unlock(); }
    }

    private Socket connectWithRetry(MessageSession session, int socksPort, String onion, java.util.function.Consumer<String> status,
                                   java.util.function.BooleanSupplier cancelled, int attempts) throws Exception {
        IOException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (closed) throw new IllegalStateException("Contact service is stopped.");
            checkCancelled(cancelled);
            try {
                Socket socket = openSession(session, socksPort, onion);
                if (cancelled.getAsBoolean()) { socket.close(); checkCancelled(cancelled); }
                return socket;
            }
            catch (IOException e) {
                last = e;
                if (attempt == attempts) break;
                status.accept("Tor is still locating the contact service; retry " + (attempt + 1) + " of " + attempts + "…");
                try { Thread.sleep(1000); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw interrupted; }
            }
        }
        throw new IOException("Tor could not reach the contact after " + attempts + " attempt" + (attempts == 1 ? "" : "s") + ": "
                + (last == null ? "connection unavailable" : safeMessage(last)), last);
    }

    private static void checkCancelled(java.util.function.BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException("File transfer cancelled.");
    }

    public void cancelTransfer(String link) {
        MessageSession session = transferSessions.get(canonicalLink(link));
        if (session != null) {
            Socket socket = session.socket;
            if (socket != null) try { socket.close(); } catch (Exception ignored) {}
        }
    }

    @FunctionalInterface private interface SessionSend {
        MessageStore.Message send(Pending entry, MessageSession session, String canonical) throws Exception;
    }

    public synchronized ContactRendezvous.Addresses add(String input) throws Exception {
        return add(input, false);
    }

    private synchronized ContactRendezvous.Addresses add(String input, boolean previouslyAcknowledged) throws Exception {
        if (closed) throw new IllegalStateException("Contact service is stopped.");
        String link = canonicalLink(input);
        Pending existing = pending.get(link);
        if (existing != null) {
            if (previouslyAcknowledged) existing.saved = true;
            event(link, existing.saved ? "Contact already acknowledged and saved." : "Waiting for contact—both users must import each other's links.");
            return existing.material.addresses();
        }
        if (pending.size() >= 100) throw new IllegalArgumentException("Contact limit reached (100).");
        var material = ContactRendezvous.derive(directory, link, localUsername);
        Pending entry = new Pending(link, material);
        entry.saved = previouslyAcknowledged;
        pending.put(link, entry);
        event(link, "Waiting for Tor, then for your contact to import your link…");
        entry.task = retries.scheduleWithFixedDelay(() -> attempt(entry), 0, 3, TimeUnit.SECONDS);
        return material.addresses();
    }

    private static String canonicalLink(String input) {
        var parsed = ContactLink.parseLink(input);
        var key = Base64.getUrlEncoder().withoutPadding().encodeToString(parsed.publicKey());
        if (parsed.username().equals("Unknown")) return ContactLink.PREFIX_V1 + key;
        return ContactLink.PREFIX + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(parsed.username().getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "/" + key;
    }

    public void restore() throws Exception {
        for (var contact : store.all()) add(contact.link(), true);
    }

    private void attempt(Pending entry) {
        if (closed || entry.stopped) return;
        if (!entry.saved && System.nanoTime() - entry.created > TimeUnit.MINUTES.toNanos(10)) {
            event(entry.link, "Contact did not respond. Import the link again to retry.");
            remove(entry); return;
        }
        if (!tor.controlReady().isDone()) return;
        try {
            int socksPort = tor.controlReady().get();
            synchronized (entry) {
                if (closed || entry.stopped) return;
                if (entry.listener == null) {
                    event(entry.link, "Publishing your contact address on Tor…");
                    entry.listener = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
                    try (var control = new TorControl(directory)) {
                        control.publish(entry.material.localSeed(), entry.listener.getLocalPort(), entry.material.addresses().local());
                    } catch (Exception e) { entry.listener.close(); entry.listener = null; throw e; }
                    Thread.ofPlatform().daemon().name("contact-listen").start(() -> accept(entry));
                    event(entry.link, "Waiting for contact—both users must import each other's links.");
                }
            }
            if (!isTorReady()) return;
            // Announce restored contacts once bootstrap completes.
            if (entry.saved) {
                if (!entry.announcedOnline) {
                    entry.announcedOnline = true;
                    driveShareRetries.execute(() -> {
                        try { isOnline(entry.link); }
                        catch (Exception ignored) { entry.announcedOnline = false; }
                    });
                }
                return;
            }
            try (Socket socket = TorControl.connect(socksPort, entry.material.addresses().remote(), 20000)) {
                connections.add(socket);
                try { ContactHandshake.client(socket, entry.material, () -> save(entry)); }
                finally { connections.remove(socket); }
            }
        } catch (Exception e) {
            if (!closed && !entry.saved && !entry.stopped) {
                String detail = safeMessage(e);
                if (detail.contains("not reachable"))
                    event(entry.link, "Waiting for your contact to come online… retrying automatically.");
                else event(entry.link, "Connecting securely; retrying automatically. " + detail);
            }
        }
    }

    private void accept(Pending entry) {
        while (!closed && !entry.stopped) {
            ServerSocket listener = entry.listener;
            if (listener == null) return;
            try {
                Socket socket = listener.accept();
                if (!incoming.tryAcquire()) { socket.close(); continue; }
                connections.add(socket);
                Thread.ofPlatform().daemon().name("contact-handshake").start(() -> {
                    try (socket) {
                        socket.setSoTimeout(20000);
                        int protocol = new java.io.DataInputStream(socket.getInputStream()).readInt();
                        if (protocol == ContactHandshake.MAGIC) ContactHandshake.server(socket, entry.material, protocol, () -> save(entry));
                        else if (protocol == ContactMessaging.MAGIC || protocol == ContactMessaging.PREVIOUS_MAGIC
                                || protocol == ContactMessaging.LEGACY_MAGIC)
                            ContactMessaging.receive(socket, entry.material, messageStore, sharedDriveStore, entry.link,
                                    protocol != ContactMessaging.LEGACY_MAGIC, () -> signalContactOnline(entry.link), () -> signalDriveShare(entry.link), uploads,
                                    stop -> downloads.queue().stoppedByOwner(entry.link, stop.share(), stop.relative(), stop.reason(), stop.request()));
                        else throw new java.io.IOException("Unsupported contact protocol.");
                    }
                    catch (Exception e) {
                        if (!closed && !entry.saved) event(entry.link, "Contact verification incomplete; waiting for a valid acknowledgment.");
                        else if (!closed && !(e instanceof java.io.EOFException)) System.err.println("Incoming message failed: " + safeMessage(e));
                    } finally { connections.remove(socket); incoming.release(); }
                });
            } catch (Exception e) {
                if (!closed && !entry.stopped) {
                    synchronized (entry) {
                        if (entry.listener == listener) {
                            entry.listener = null;
                            try { listener.close(); } catch (Exception ignored) {}
                            try (var control = new TorControl(directory)) { control.unpublish(entry.material.addresses().local()); }
                            catch (Exception unpublishError) { System.err.println("Could not reset contact listener: " + safeMessage(unpublishError)); }
                        }
                    }
                    System.err.println("Contact listener stopped; it will be republished: " + safeMessage(e));
                }
                return;
            }
        }
    }

    private synchronized void save(Pending entry) throws Exception {
        if (closed || entry.stopped) throw new IllegalStateException("Contact service is stopped.");
        store.acknowledge(entry.link, entry.material.addresses().remote(), entry.material.remoteUsername());
        entry.saved = true;
        try { contactsChanged.run(); }
        catch (RuntimeException error) { System.err.println("Contact list update failed: " + safeMessage(error)); }
        event(entry.link, "Acknowledged—contact saved locally.");
        System.out.println("Contact acknowledged and saved: " + entry.material.addresses().remote());
    }

    private void event(String link, String message) {
        if (!closed) events.accept(new Event(link, message));
    }
    private static String safeMessage(Exception e) {
        Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
        return cause.getMessage() == null ? "Connection unavailable." : cause.getMessage();
    }
    private void remove(Pending entry) {
        pending.remove(entry.link, entry);
        synchronized (entry) {
            entry.stopped = true;
            if (entry.task != null) entry.task.cancel(false);
            if (entry.listener != null) {
                try { entry.listener.close(); } catch (Exception ignored) {}
                try (var control = new TorControl(directory)) { control.unpublish(entry.material.addresses().local()); }
                catch (Exception ignored) {}
            }
        }
        // No future connection can be opened; in-flight handshakes are bounded by socket timeout.
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        downloads.close();
        imports.close();
        uploads.close();
        retries.shutdownNow();
        driveShareRetries.shutdownNow();
        driveShareRetryScheduler.shutdownNow();
        for (var socket : connections) try { socket.close(); } catch (Exception ignored) {}
        messageSessions.clear();
        transferSessions.clear();
        for (var entry : pending.values()) remove(entry);
    }

    private static final class Pending {
        final String link;
        final ContactRendezvous.Material material;
        final long created = System.nanoTime();
        volatile boolean saved, stopped, announcedOnline;
        volatile ServerSocket listener;
        ScheduledFuture<?> task;
        Pending(String link, ContactRendezvous.Material material) { this.link = link; this.material = material; }
    }

    private static final class MessageSession {
        final java.util.concurrent.locks.ReentrantLock exchange = new java.util.concurrent.locks.ReentrantLock(true);
        volatile Socket socket;
        volatile boolean firstMessage = true;
        long lastUsed;
        final PendingConnection connecting = new PendingConnection();
    }

    private Socket openSession(MessageSession session, int socksPort, String onion) throws Exception {
        return session.connecting.await(() -> {
            Socket socket = TorControl.connect(socksPort, onion, 60000);
            connections.add(socket);
            if (closed) { connections.remove(socket); socket.close(); throw new IOException("Contact service is stopped."); }
            return socket;
        }, 5, TimeUnit.SECONDS);
    }
}
