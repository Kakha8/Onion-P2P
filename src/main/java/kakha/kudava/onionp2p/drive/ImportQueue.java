package kakha.kudava.onionp2p.drive;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Serial local imports keep disk work off the UI and publish bounded progress updates. */
public final class ImportQueue implements AutoCloseable {
    public enum State { QUEUED, SCANNING, ADDING, COMPLETE, FAILED }
    public record Item(String id, String name, State state, String detail, long copied, long hashed,
                       long total, int filesDone, int files, double speed, Path destination) {
        public boolean active() { return state == State.QUEUED || state == State.SCANNING || state == State.ADDING; }
    }
    private final DriveChecksums checksums;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> Thread.ofPlatform().daemon().name("drive-import").unstarted(r));
    private final Map<String, Item> jobs = new LinkedHashMap<>();
    private Consumer<List<Item>> listener = ignored -> {};
    private boolean closed;

    public ImportQueue(DriveChecksums checksums) { this.checksums = checksums; }
    public synchronized List<Item> items() { return List.copyOf(jobs.values()); }
    public synchronized void setListener(Consumer<List<Item>> listener) { this.listener = listener; publish(); }
    private void publish() { listener.accept(List.copyOf(jobs.values())); }
    private synchronized void update(Item item) { jobs.put(item.id(), item); publish(); }
    public synchronized void add(List<Path> sources, Path directory) {
        if (closed) throw new IllegalStateException("Adding is stopped.");
        for (Path source : sources) {
            String id = UUID.randomUUID().toString(), name = source.getFileName().toString();
            jobs.put(id, new Item(id, name, State.QUEUED, "Queued", 0, 0, -1, 0, 0, 0, null));
            worker.execute(() -> run(id, source, directory));
        }
        publish();
    }
    private void run(String id, Path source, Path directory) {
        String name = source.getFileName().toString();
        update(new Item(id, name, State.SCANNING, "Preparing files…", 0, 0, -1, 0, 0, 0, null));
        class Progress implements DriveImports.Progress {
            long total = -1, done, copied, hashed, last;
            int count, finished;
            final TransferRate rate = new TransferRate();
            Progress() { rate.reset(0); }
            public void planned(long bytes, int files) { total = bytes; count = files; rate.reset(0); emit("Copying and calculating SHA-256…", true); }
            public void file(Path path, long copy, long hash, long size) {
                copied = done + copy; hashed = done + hash;
                // A source can grow between scanning and opening. Never show more than 100%.
                total = Math.max(total, copied);
                emit(path.equals(source.toAbsolutePath().normalize()) ? "Copying and calculating SHA-256…" : source.toAbsolutePath().normalize().relativize(path).toString(), false);
            }
            public void finished(Path path, long bytes) { done += bytes; finished++; copied = hashed = done; emit("Adding files…", false); }
            void emit(String detail, boolean force) {
                long now = System.nanoTime();
                if (!force && now - last < TimeUnit.MILLISECONDS.toNanos(100)) return;
                last = now;
                update(new Item(id, name, State.ADDING, detail, copied, hashed, total, finished, count, rate.update(hashed), null));
            }
        }
        var progress = new Progress();
        try {
            Path target = DriveImports.add(source, directory, checksums, progress);
            update(new Item(id, name, State.COMPLETE, "Added · SHA-256 saved", progress.done, progress.done,
                    progress.done, progress.finished, progress.finished, 0, target));
        } catch (Exception error) {
            update(new Item(id, name, State.FAILED, "Could not add: " + (error.getMessage() == null ? "Try again." : error.getMessage()),
                    progress.copied, progress.hashed, progress.total, progress.finished, progress.count, 0, null));
        }
    }
    @Override public synchronized void close() { closed = true; worker.shutdownNow(); }
}
