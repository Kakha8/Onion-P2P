package kakha.kudava.onionp2p.drive;

import java.io.EOFException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Download queue with optional durable requests and explicit resume after restart. */
public final class DownloadQueue implements AutoCloseable {
    public static final class TorNotReadyException extends Exception {}
    public enum State { WAITING, CONNECTING, DOWNLOADING, PAUSED, COMPLETE, FAILED, CANCELLED }
    public record Item(String id, String key, String owner, String ownerName, String name,
                       State state, String detail, long received, long total, Path destination) {
        public boolean active() { return state == State.WAITING || state == State.CONNECTING || state == State.DOWNLOADING || state == State.PAUSED; }
    }
    public interface Progress { void update(long received, long total); void stage(String detail); default void checkpoint() {} }
    @FunctionalInterface public interface Work {
        Path run(Progress progress) throws Exception;
        default void discard() {} default void paused() throws Exception {} default void cancelled() throws Exception {}
        default Properties descriptor() { return null; }
        default long savedReceived() { return -1; } default long savedTotal() { return -1; }
    }
    @FunctionalInterface public interface OnlineCheck { boolean check(String owner) throws Exception; }
    private static final class Job {
        volatile Item item;
        final Work work;
        final AtomicBoolean running = new AtomicBoolean(), started = new AtomicBoolean(), cancelled = new AtomicBoolean(), paused = new AtomicBoolean(), resuming = new AtomicBoolean();
        long lastProgress;
        Job(Item item, Work work) { this.item = item; this.work = work; }
    }
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final ScheduledExecutorService executor;
    private final OnlineCheck online;
    private volatile Consumer<List<Item>> listener = ignored -> {};
    private volatile boolean closed;
    private java.nio.file.Path journal;

    public DownloadQueue(OnlineCheck online, java.nio.file.Path journal, java.util.function.Function<Properties, Work> restore) throws java.io.IOException {
        this(online);
        this.journal = journal;
        try {
            java.nio.file.Files.createDirectories(journal);
            restoreJournal(restore);
        } catch (java.io.IOException | RuntimeException error) { executor.shutdownNow(); throw error; }
    }
    private synchronized void restoreJournal(java.util.function.Function<Properties, Work> restore) throws java.io.IOException {
        try (var files = java.nio.file.Files.newDirectoryStream(journal, "*.properties")) {
            for (var file : files) {
                Properties values = DownloadState.read(file);
                try {
                    String id = values.getProperty("id"); UUID.fromString(id);
                    State state = State.valueOf(values.getProperty("state"));
                    if (state == State.CANCELLED) { java.nio.file.Files.deleteIfExists(file); continue; }
                    // Restored unfinished downloads require an explicit Resume after login.
                    if (state != State.COMPLETE && state != State.FAILED) state = State.PAUSED;
                    Work work = state == State.COMPLETE ? progress -> java.nio.file.Path.of(values.getProperty("destination")) : restore.apply(values);
                    String target = values.getProperty("destination");
                    var item = new Item(id, values.getProperty("key"), values.getProperty("owner"), values.getProperty("ownerName"), values.getProperty("name"),
                            state, state == State.PAUSED ? "Paused · saved from previous session" : values.getProperty("detail"),
                            work.savedReceived() >= 0 ? work.savedReceived() : Long.parseLong(values.getProperty("received")),
                            work.savedTotal() >= 0 ? work.savedTotal() : Long.parseLong(values.getProperty("total")), target == null ? null : java.nio.file.Path.of(target));
                    var job = new Job(item, work); job.paused.set(state == State.PAUSED); jobs.put(id, job);
                } catch (RuntimeException error) { throw new java.io.IOException("Could not restore download " + file.getFileName(), error); }
            }
        }
    }

    public DownloadQueue(OnlineCheck online) { this(online, 2500); }
    public DownloadQueue(OnlineCheck online, long retryMillis) {
        this.online = online;
        executor = Executors.newScheduledThreadPool(2, r -> Thread.ofPlatform().daemon().name("drive-download").unstarted(r));
        executor.scheduleWithFixedDelay(() -> wake(null), retryMillis, retryMillis, TimeUnit.MILLISECONDS);
    }
    public void setListener(Consumer<List<Item>> listener) { this.listener = listener; publish(); }
    public synchronized List<Item> items() { return jobs.values().stream().map(job -> job.item).toList(); }
    public synchronized String add(String key, String owner, String ownerName, String name, long size, Work work) {
        if (closed) throw new IllegalStateException("Downloads are stopped.");
        for (var job : jobs.values()) if (job.item.key().equals(key) && job.item.active()) return job.item.id();
        String id = UUID.randomUUID().toString();
        var job = new Job(new Item(id, key, owner, ownerName, name, State.WAITING,
                "Waiting for " + ownerName + " to come online", 0, size, null), work);
        jobs.put(id, job); persist(job); publish(); submit(job); return id;
    }
    public void wake(String owner) {
        List<Job> copy;
        synchronized (this) { copy = List.copyOf(jobs.values()); }
        for (var job : copy) if (job.item.state() == State.WAITING && (owner == null || job.item.owner().equals(owner))) submit(job);
    }
    public synchronized void cancel(String id) {
        var job = jobs.get(id); if (job == null || !job.item.active()) return;
        job.cancelled.set(true); change(job, State.CANCELLED, "Cancelled", job.item.received(), job.item.total(), null);
        Thread.ofPlatform().daemon().name("download-cancel-notice").start(() -> {
            try { job.work.cancelled(); } catch (Exception ignored) { /* Local cancellation does not depend on the peer being reachable. */ }
        });
        if (!job.started.get()) job.work.discard();
    }
    public synchronized void pause(String id) {
        var job = jobs.get(id); if (job == null || !job.item.active()) return;
        job.paused.set(true);
        change(job, State.PAUSED, "Paused", job.item.received(), job.item.total(), null);
        Thread.ofPlatform().daemon().name("download-pause-notice").start(() -> {
            try { job.work.paused(); } catch (Exception ignored) { /* Pausing locally does not require a reachable owner. */ }
        });
    }
    public synchronized void resume(String id) {
        var job = jobs.get(id); if (job == null || job.item.state() != State.PAUSED) return;
        job.paused.set(false);
        job.resuming.set(true);
        change(job, State.WAITING, "Resuming…", job.item.received(), job.item.total(), null);
        submit(job);
    }
    public synchronized void retry(String id) {
        var job = jobs.get(id); if (job == null || job.item.state() != State.FAILED || job.running.get()) return;
        change(job, State.WAITING, "Waiting for " + job.item.ownerName() + " to come online", job.item.received(), job.item.total(), null);
        submit(job);
    }
    private void submit(Job job) {
        if (closed || job.item.state() != State.WAITING || job.cancelled.get() || !job.running.compareAndSet(false, true)) return;
        try { executor.execute(() -> run(job)); }
        catch (RejectedExecutionException ignored) { job.running.set(false); }
    }
    private void run(Job job) {
        job.started.set(true);
        try {
            if (job.cancelled.get() || closed || job.item.state() != State.WAITING) return;
            boolean reachable;
            try { reachable = online.check(job.item.owner()); }
            catch (TorNotReadyException starting) { waitingForTor(job); return; }
            catch (Exception offline) { reachable = false; }
            if (job.cancelled.get() || closed) return;
            if (!reachable) { waiting(job); return; }
            change(job, State.CONNECTING, "Connecting through Tor…", job.item.received(), job.item.total(), null);
            Path result = job.work.run(new Progress() {
                private void check() { if (job.cancelled.get() || closed || job.paused.get()) throw new CancellationException(); }
                public void checkpoint() { check(); }
                public void stage(String detail) { check(); change(job, State.CONNECTING, detail, job.item.received(), job.item.total(), null); }
                public void update(long received, long total) {
                    check(); long now = System.nanoTime();
                    job.resuming.set(false);
                    if (received != total && now - job.lastProgress < TimeUnit.MILLISECONDS.toNanos(100)) return;
                    job.lastProgress = now;
                    change(job, State.DOWNLOADING, "Downloading", received, total, null);
                }
            });
            if (!job.cancelled.get() && !job.paused.get() && !closed)
                change(job, State.COMPLETE, "Downloaded", job.item.total() < 0 ? job.item.received() : job.item.total(), job.item.total(), result);
        } catch (CancellationException ignored) {
            if (closed) return;
            if (job.paused.get()) change(job, State.PAUSED, "Paused", job.item.received(), job.item.total(), null);
            else if (job.cancelled.get() || closed) change(job, State.CANCELLED, "Cancelled", job.item.received(), job.item.total(), null);
        } catch (Exception error) {
            if (!job.cancelled.get() && !closed) {
                if (networkFailure(error)) waiting(job);
                else change(job, State.FAILED, error.getMessage() == null ? "Download failed" : error.getMessage(), job.item.received(), job.item.total(), null);
            }
        } finally {
            job.running.set(false);
            job.started.set(false);
            if (job.cancelled.get()) job.work.discard();
        }
    }
    private void waiting(Job job) {
        String detail = job.resuming.get()
                ? job.item.ownerName() + " is offline or unreachable. Try again later. Retrying automatically."
                : "Waiting for " + job.item.ownerName() + " to come online · retrying automatically";
        change(job, State.WAITING, detail, job.item.received(), job.item.total(), null);
    }
    private void waitingForTor(Job job) { change(job, State.WAITING, "Waiting for Tor to connect · retrying automatically", job.item.received(), job.item.total(), null); }
    private static boolean networkFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause())
            if (cause instanceof SocketException || cause instanceof SocketTimeoutException || cause instanceof EOFException) return true;
        return error instanceof java.io.IOException && error.getMessage() != null
                && (error.getMessage().startsWith("Tor could not reach")
                    || error.getMessage().equals("Contact is not reachable yet.")
                    || error.getMessage().equals("SOCKS negotiation failed."));
    }
    private void change(Job job, State state, String detail, long received, long total, Path destination) {
        synchronized (job) {
            if (closed) return;
            if (job.cancelled.get() && state != State.CANCELLED) return;
            if (job.paused.get() && state != State.PAUSED && state != State.CANCELLED) return;
            Item before = job.item;
            job.item = new Item(before.id(), before.key(), before.owner(), before.ownerName(), before.name(), state, detail, received, total, destination);
        }
        persist(job);
        publish();
    }
    private void persist(Job job) {
        if (journal == null) return;
        synchronized (job) {
            try {
                var file = journal.resolve(job.item.id() + ".properties");
                if (job.item.state() == State.CANCELLED) { java.nio.file.Files.deleteIfExists(file); return; }
                Properties values = job.work.descriptor();
                if (values == null) return;
                Item item = job.item;
                values.setProperty("id", item.id()); values.setProperty("key", item.key()); values.setProperty("owner", item.owner());
                values.setProperty("ownerName", item.ownerName()); values.setProperty("name", item.name());
                values.setProperty("state", item.state().name()); values.setProperty("detail", item.detail());
                values.setProperty("received", Long.toString(item.received())); values.setProperty("total", Long.toString(item.total()));
                if (item.destination() != null) values.setProperty("destination", item.destination().toString());
                DownloadState.write(file, values);
            } catch (java.io.IOException error) { throw new java.io.UncheckedIOException("Could not save download progress", error); }
        }
    }
    private void publish() { try { listener.accept(items()); } catch (RuntimeException ignored) {} }
    @Override public synchronized void close() {
        closed = true; executor.shutdownNow();
        for (var job : jobs.values()) {
            persist(job);
            if (job.cancelled.get() && !job.started.get()) job.work.discard();
        }
    }
}
