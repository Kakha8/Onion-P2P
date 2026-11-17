package kakha.kudava.onionp2p.drive;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Sender-side activity; progress advances only after an authenticated receipt. */
public final class UploadTracker implements AutoCloseable {
    public enum State { UPLOADING, WAITING, PAUSED, DISCONNECTED, COMPLETE, FAILED, CANCELLED, CANCELLED_BY_SENDER }
    public record Cancellation(String contact, String share, String relative, String request) {}
    public record Item(String key, String contact, String name, long sent, long total, double speed, State state) {}
    private static final class Transfer {
        Item item; Object connection; long updated;
        TransferRate rate;
        String share, relative, request;
        Transfer(Item item, Object connection, long now) { this.item = item; this.connection = connection; updated = now; }
    }
    private final Map<String, Transfer> transfers = new LinkedHashMap<>();
    private final Set<String> stoppedRequests = new HashSet<>();
    private final LongSupplier clock;
    private final ScheduledExecutorService timer;
    private Consumer<List<Item>> listener = ignored -> {};
    public UploadTracker() { this(System::nanoTime); }
    public UploadTracker(LongSupplier clock) {
        this.clock = clock;
        timer = Executors.newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().daemon().name("upload-status").unstarted(r));
        timer.scheduleWithFixedDelay(this::refresh, 1, 1, TimeUnit.SECONDS);
    }
    private String key(String contact, String share, String relative) { return contact + ":" + share + ":" + relative; }
    public synchronized String begin(String contact, String share, String relative, String name, long total, long offset, Object connection) {
        return begin(contact, share, relative, name, total, offset, connection, "");
    }
    public synchronized String begin(String contact, String share, String relative, String name, long total, long offset, Object connection, String request) {
        String key = key(contact, share, relative); long now = clock.getAsLong();
        if (stoppedRequests.contains(key + "\0" + request)) return key;
        Transfer previous = transfers.get(key);
        var item = new Item(key, contact, name, offset, total,
                previous != null && previous.item.state() == State.UPLOADING ? previous.item.speed() : 0, State.UPLOADING);
        var transfer = new Transfer(item, connection, previous == null || previous.item.state() != State.UPLOADING ? now : previous.updated);
        transfer.share = share; transfer.relative = relative; transfer.request = request;
        transfer.rate = previous != null && previous.item.state() == State.UPLOADING && offset >= previous.item.sent() ? previous.rate : new TransferRate(clock);
        if (transfer.rate != (previous == null ? null : previous.rate)) transfer.rate.reset(offset);
        transfers.put(key, transfer);
        if (transfers.size() > 200) {
            var it = transfers.entrySet().iterator();
            while (transfers.size() > 200 && it.hasNext()) if (it.next().getValue().item.state() != State.UPLOADING) it.remove();
        }
        publish(); return key;
    }
    public synchronized void acknowledged(String key, long sent) {
        Transfer transfer = transfers.get(key); if (transfer == null) return;
        if (transfer.item.state() == State.CANCELLED || transfer.item.state() == State.CANCELLED_BY_SENDER) return;
        Item item = transfer.item; long now = clock.getAsLong();
        double speed = transfer.rate.update(sent);
        transfer.item = new Item(key, item.contact(), item.name(), sent, item.total(), speed,
                sent == item.total() ? State.COMPLETE : State.UPLOADING);
        transfer.updated = now; publish();
    }
    public synchronized void paused(String contact, String share, String relative) {
        Transfer transfer = transfers.get(key(contact, share, relative));
        if (transfer != null && transfer.item.state() != State.COMPLETE && transfer.item.state() != State.CANCELLED && transfer.item.state() != State.CANCELLED_BY_SENDER) { state(transfer, State.PAUSED); publish(); }
    }
    public synchronized Cancellation cancelBySender(String key) {
        Transfer transfer = transfers.get(key);
        if (transfer == null || transfer.item.state() == State.COMPLETE || transfer.item.state() == State.CANCELLED
                || transfer.item.state() == State.CANCELLED_BY_SENDER) return null;
        stoppedRequests.add(key + "\0" + transfer.request);
        state(transfer, State.CANCELLED_BY_SENDER); publish();
        return new Cancellation(transfer.item.contact(), transfer.share, transfer.relative, transfer.request);
    }
    public synchronized boolean stoppedBySender(String contact, String share, String relative, String request) {
        return stoppedRequests.contains(key(contact, share, relative) + "\0" + request);
    }
    public synchronized void cancelled(String contact, String share, String relative, boolean folder) {
        String prefix = key(contact, share, relative);
        for (var entry : transfers.entrySet()) {
            boolean matches = entry.getKey().equals(prefix)
                    || folder && entry.getKey().startsWith(prefix + (relative.isEmpty() ? "" : "/"));
            if (matches && (!folder || entry.getValue().item.state() != State.COMPLETE)) state(entry.getValue(), State.CANCELLED);
        }
        publish();
    }
    public synchronized void disconnected(Object connection) {
        for (var transfer : transfers.values()) if (transfer.connection == connection
                && (transfer.item.state() == State.UPLOADING || transfer.item.state() == State.WAITING)) state(transfer, State.DISCONNECTED);
        publish();
    }
    public synchronized void failed(String key) {
        Transfer transfer = transfers.get(key);
        if (transfer != null && transfer.item.state() != State.CANCELLED_BY_SENDER) { state(transfer, State.FAILED); publish(); }
    }
    public synchronized void unavailable(String contact, String share) {
        String prefix = key(contact, share, "");
        for (var entry : transfers.entrySet()) if (entry.getKey().startsWith(prefix)
                && entry.getValue().item.state() != State.COMPLETE) state(entry.getValue(), State.FAILED);
        publish();
    }
    public synchronized void refresh() {
        long now = clock.getAsLong();
        for (var transfer : transfers.values()) if (transfer.item.state() == State.UPLOADING
                && now - transfer.updated > TimeUnit.SECONDS.toNanos(20)) state(transfer, State.WAITING);
        for (var transfer : transfers.values()) if (transfer.item.state() == State.UPLOADING) {
            Item item = transfer.item;
            transfer.item = new Item(item.key(), item.contact(), item.name(), item.sent(), item.total(), transfer.rate.update(item.sent()), item.state());
        }
        publish();
    }
    private void state(Transfer transfer, State state) {
        Item item = transfer.item;
        transfer.item = new Item(item.key(), item.contact(), item.name(), item.sent(), item.total(), 0, state);
    }
    public synchronized List<Item> items() { return transfers.values().stream().map(transfer -> transfer.item).toList(); }
    public synchronized void setListener(Consumer<List<Item>> listener) { this.listener = listener; publish(); }
    private void publish() { try { listener.accept(items()); } catch (RuntimeException ignored) {} }
    @Override public void close() { timer.shutdownNow(); }
}
