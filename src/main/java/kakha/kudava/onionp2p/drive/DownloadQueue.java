package kakha.kudava.onionp2p.drive;

import java.io.EOFException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Session download queue. Only authenticated reachability starts a transfer. */
public final class DownloadQueue implements AutoCloseable {
    public enum State { WAITING, CONNECTING, DOWNLOADING, COMPLETE, FAILED, CANCELLED }
    public record Item(String id, String key, String owner, String ownerName, String name,
                       State state, String detail, long received, long total, Path destination) {
        public boolean active() { return state == State.WAITING || state == State.CONNECTING || state == State.DOWNLOADING; }
    }
    public interface Progress { void update(long received, long total); void stage(String detail); }
    @FunctionalInterface public interface Work { Path run(Progress progress) throws Exception; }
    @FunctionalInterface public interface OnlineCheck { boolean check(String owner) throws Exception; }
    private static final class Job {
        volatile Item item;
        final Work work;
        final AtomicBoolean running = new AtomicBoolean(), cancelled = new AtomicBoolean();
        long lastProgress;
        Job(Item item, Work work) { this.item = item; this.work = work; }
    }
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final ScheduledExecutorService executor;
    private final OnlineCheck online;
    private volatile Consumer<List<Item>> listener = ignored -> {};
    private volatile boolean closed;

    public DownloadQueue(OnlineCheck online) { this(online, 5000); }
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
        jobs.put(id, job); publish(); submit(job); return id;
    }
    public void wake(String owner) {
        List<Job> copy;
        synchronized (this) { copy = List.copyOf(jobs.values()); }
        for (var job : copy) if (job.item.state() == State.WAITING && (owner == null || job.item.owner().equals(owner))) submit(job);
    }
    public synchronized void cancel(String id) {
        var job = jobs.get(id); if (job == null || !job.item.active()) return;
        job.cancelled.set(true); change(job, State.CANCELLED, "Cancelled", job.item.received(), job.item.total(), null);
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
        try {
            if (job.cancelled.get() || closed || job.item.state() != State.WAITING) return;
            boolean reachable;
            try { reachable = online.check(job.item.owner()); }
            catch (Exception offline) { reachable = false; }
            if (job.cancelled.get() || closed) return;
            if (!reachable) { waiting(job); return; }
            change(job, State.CONNECTING, "Connecting through Tor…", job.item.received(), job.item.total(), null);
            Path result = job.work.run(new Progress() {
                private void check() { if (job.cancelled.get() || closed) throw new CancellationException(); }
                public void stage(String detail) { check(); change(job, State.CONNECTING, detail, job.item.received(), job.item.total(), null); }
                public void update(long received, long total) {
                    check(); long now = System.nanoTime();
                    if (received != total && now - job.lastProgress < TimeUnit.MILLISECONDS.toNanos(100)) return;
                    job.lastProgress = now;
                    change(job, State.DOWNLOADING, "Downloading", received, total, null);
                }
            });
            if (!job.cancelled.get() && !closed)
                change(job, State.COMPLETE, "Downloaded", job.item.total() < 0 ? job.item.received() : job.item.total(), job.item.total(), result);
        } catch (CancellationException ignored) {
            change(job, State.CANCELLED, "Cancelled", job.item.received(), job.item.total(), null);
        } catch (Exception error) {
            if (!job.cancelled.get() && !closed) {
                if (networkFailure(error)) waiting(job);
                else change(job, State.FAILED, error.getMessage() == null ? "Download failed" : error.getMessage(), job.item.received(), job.item.total(), null);
            }
        } finally { job.running.set(false); }
    }
    private void waiting(Job job) { change(job, State.WAITING, "Waiting for " + job.item.ownerName() + " to come online · retrying automatically", job.item.received(), job.item.total(), null); }
    private static boolean networkFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause())
            if (cause instanceof SocketException || cause instanceof SocketTimeoutException || cause instanceof EOFException) return true;
        return error instanceof java.io.IOException && error.getMessage() != null && error.getMessage().startsWith("Tor could not reach");
    }
    private void change(Job job, State state, String detail, long received, long total, Path destination) {
        synchronized (job) {
            if (job.cancelled.get() && state != State.CANCELLED) return;
            Item before = job.item;
            job.item = new Item(before.id(), before.key(), before.owner(), before.ownerName(), before.name(), state, detail, received, total, destination);
        }
        publish();
    }
    private void publish() { try { listener.accept(items()); } catch (RuntimeException ignored) {} }
    @Override public void close() { closed = true; executor.shutdownNow(); }
}
