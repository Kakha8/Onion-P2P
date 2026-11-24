package kakha.kudava.onionp2p.drive;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Serial local imports keep disk work off the UI and publish bounded progress updates. */
public final class ImportQueue implements AutoCloseable {
    public enum State { QUEUED, SCANNING, COPYING, HASHING, FINISHING, CANCELLING, COMPLETE, FAILED, CANCELLED }
    public record Item(String id, String name, State state, String detail, long copied, long hashed,
                       long total, int filesDone, int files, double speed, Path destination) {
        public boolean cancellable() { return state == State.QUEUED || state == State.SCANNING || state == State.COPYING || state == State.HASHING; }
        public boolean active() { return cancellable() || state == State.FINISHING || state == State.CANCELLING; }
        public boolean finished() { return !active(); }
    }
    private static final class Job {
        Item item;
        final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        boolean running;
        Job(Item item) { this.item = item; }
    }
    private final DriveChecksums checksums;
    @FunctionalInterface interface Importer { Path add(Path source, Path directory, DriveChecksums checksums, DriveImports.Progress progress) throws java.io.IOException; }
    private final Importer importer;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> Thread.ofPlatform().daemon().name("drive-import").unstarted(r));
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private Consumer<List<Item>> listener = ignored -> {};
    private boolean closed;

    public ImportQueue(DriveChecksums checksums) {
        this(checksums, DriveImports::add);
    }
    ImportQueue(DriveChecksums checksums, Importer importer) {
        this.checksums = checksums;
        this.importer = importer;
        worker.execute(() -> {
            try { ImportStaging.cleanAbandoned(checksums); }
            catch (java.io.IOException error) { System.err.println("Could not clean unfinished imports; cleanup will retry at the next startup."); }
        });
    }
    public synchronized List<Item> items() { return jobs.values().stream().map(job -> job.item).toList(); }
    public synchronized void setListener(Consumer<List<Item>> listener) { this.listener = listener; publish(); }
    private void publish() { listener.accept(items()); }
    private synchronized void update(Item item) {
        Job job = jobs.get(item.id());
        if (job == null) return;
        if (job.cancelled.get() && item.state() != State.CANCELLED) return;
        job.item = item; publish();
    }
    public synchronized void dismiss(String id) {
        Job job = jobs.get(id);
        if (job != null && job.item.finished()) { jobs.remove(id); publish(); }
    }
    public synchronized void clearFinished() {
        if (jobs.values().removeIf(job -> job.item.finished())) publish();
    }
    public synchronized void cancel(String id) {
        Job job = jobs.get(id);
        if (job == null || !job.item.cancellable()) return;
        job.cancelled.set(true);
        Item previous = job.item;
        job.item = new Item(id, previous.name(), job.running ? State.CANCELLING : State.CANCELLED,
                job.running ? "Cancelling…" : "Cancelled", previous.copied(), previous.hashed(), previous.total(),
                previous.filesDone(), previous.files(), 0, null);
        publish();
    }
    private static void checkCancelled(Job job) {
        if (job.cancelled.get()) throw new CancellationException("Import cancelled.");
    }
    public synchronized void add(List<Path> sources, Path directory) {
        if (closed) throw new IllegalStateException("Adding is stopped.");
        for (Path source : sources) {
            String id = UUID.randomUUID().toString(), name = source.getFileName().toString();
            jobs.put(id, new Job(new Item(id, name, State.QUEUED, "Queued", 0, 0, -1, 0, 0, 0, null)));
            worker.execute(() -> run(id, source, directory));
        }
        publish();
    }
    private void run(String id, Path source, Path directory) {
        Job job;
        synchronized (this) {
            job = jobs.get(id);
            if (job == null || job.cancelled.get()) return;
            job.running = true;
        }
        String name = source.getFileName().toString();
        class Progress implements DriveImports.Progress {
            long total = -1, done, copiedDone, copied, hashed, last;
            int count, finished;
            State state = State.COPYING;
            final TransferRate rate = new TransferRate();
            Progress() { rate.reset(0); }
            public void checkpoint() { checkCancelled(job); }
            public void planned(long bytes, int files) { checkpoint(); total = bytes; count = files; rate.reset(0); emit("Copying…", true); checkpoint(); }
            public void file(Path path, long copy, long hash, long size) {
                checkpoint(); boolean firstBytes = copied == 0 && copy > 0;
                copied = copiedDone + copy;
                // A source can grow between scanning and opening. Never show more than 100%.
                total = Math.max(total, copied);
                emit(detail("Copying", path), firstBytes); checkpoint();
            }
            public void copied(Path path, long bytes) { checkpoint(); copiedDone += bytes; copied = copiedDone; }
            public void hashing(Path path, long hash, long size) {
                checkpoint(); boolean firstBytes = hashed == 0 && hash > 0;
                boolean starting = state != State.HASHING;
                if (starting) { state = State.HASHING; total = copied; rate.reset(0); }
                hashed = done + hash;
                emit(detail("Hashing SHA-256", path), starting || firstBytes); checkpoint();
            }
            public void finished(Path path, long bytes) { checkpoint(); done += bytes; finished++; hashed = done; emit(detail("Hashing SHA-256", path), false); checkpoint(); }
            public void publishing() {
                // Once the final rename begins, cancellation must not report success while publishing a file.
                synchronized (ImportQueue.this) { checkpoint(); state = State.FINISHING; emit("Finishing import…", true); }
            }
            String detail(String phase, Path path) {
                Path root = source.toAbsolutePath().normalize();
                return path.equals(root) ? phase + "…" : phase + " · " + root.relativize(path);
            }
            void emit(String detail, boolean force) {
                long now = System.nanoTime();
                if (!force && now - last < TimeUnit.MILLISECONDS.toNanos(100)) return;
                last = now;
                update(new Item(id, name, state, detail, copied, hashed, total, finished, count, rate.update(state == State.COPYING ? copied : hashed), null));
            }
        }
        var progress = new Progress();
        try {
            checkCancelled(job);
            update(new Item(id, name, State.SCANNING, "Preparing files…", 0, 0, -1, 0, 0, 0, null));
            Path target = importer.add(source, directory, checksums, progress);
            update(new Item(id, name, State.COMPLETE, "Added · SHA-256 saved", progress.done, progress.done,
                    progress.done, progress.finished, progress.finished, 0, target));
        } catch (Exception error) {
            boolean cancelled = job.cancelled.get();
            String detail = cancelled ? error.getSuppressed().length == 0 ? "Cancelled" : "Cancelled · Temporary cleanup will retry when you sign in again."
                    : FileFailures.adding(error, source);
            update(new Item(id, name, cancelled ? State.CANCELLED : State.FAILED, detail,
                    progress.copied, progress.hashed, progress.total, progress.finished, progress.count, 0, null));
        } finally {
            synchronized (this) { job.running = false; }
        }
    }
    @Override public synchronized void close() { closed = true; worker.shutdownNow(); }
}
