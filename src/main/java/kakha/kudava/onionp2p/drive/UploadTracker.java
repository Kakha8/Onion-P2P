package kakha.kudava.onionp2p.drive;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Sender-side activity; progress advances only after an authenticated receipt. */
public final class UploadTracker implements AutoCloseable {
    public static final int MAX_RECIPIENTS = 3;
    public enum State { UPLOADING, QUEUED, WAITING, PAUSED, DISCONNECTED, COMPLETE, FAILED, CANCELLED, CANCELLED_BY_SENDER }
    public record Cancellation(String contact, String share, String relative, String request) {}
    public record Item(String key, String contact, String name, long sent, long total, double speed, State state, int queuePosition) {
        public Item(String key, String contact, String name, long sent, long total, double speed, State state) { this(key, contact, name, sent, total, speed, state, 0); }
    }
    private static final class Transfer {
        Item item; Object connection; long updated;
        TransferRate rate;
        String share, relative, request;
        boolean inFlight;
        Transfer(Item item, Object connection, long now) { this.item = item; this.connection = connection; updated = now; }
    }
    private final Map<String, Transfer> transfers = new LinkedHashMap<>();
    private final Set<String> stoppedRequests = new HashSet<>();
    private final Map<String, Long> recipients = new LinkedHashMap<>();
    private final LinkedHashMap<String, Long> recipientQueue = new LinkedHashMap<>();

    private int admit(String contact, long now) {
        expireQueue(now); promote(now);
        if (recipients.containsKey(contact)) { recipients.put(contact, now); return 0; }
        recipientQueue.put(contact, now); promote(now);
        return position(contact);
    }
    private int position(String contact) {
        if (recipients.containsKey(contact)) return 0;
        int position = 1;
        for (String queued : recipientQueue.keySet()) { if (queued.equals(contact)) return position; position++; }
        return 0;
    }
    private void promote(long now) {
        var iterator = recipientQueue.entrySet().iterator();
        while (recipients.size() < MAX_RECIPIENTS && iterator.hasNext()) {
            var entry = iterator.next(); recipients.put(entry.getKey(), now); iterator.remove();
        }
    }
    private void expireQueue(long now) {
        recipientQueue.entrySet().removeIf(entry -> {
            if (now - entry.getValue() <= TimeUnit.SECONDS.toNanos(30)) return false;
            for (var transfer : transfers.values()) if (transfer.item.contact().equals(entry.getKey()) && transfer.item.state() == State.QUEUED) state(transfer, State.DISCONNECTED);
            return true;
        });
        recipients.entrySet().removeIf(entry -> {
            if (now - entry.getValue() <= TimeUnit.SECONDS.toNanos(20)
                    || transfers.values().stream().anyMatch(transfer -> transfer.item.contact().equals(entry.getKey()) && transfer.inFlight)) return false;
            for (var transfer : transfers.values()) if (transfer.item.contact().equals(entry.getKey())
                    && (transfer.item.state() == State.UPLOADING || transfer.item.state() == State.QUEUED)) state(transfer, State.WAITING);
            return true;
        });
    }
    private void releaseIfIdle(String contact) {
        boolean active = transfers.values().stream().anyMatch(transfer -> transfer.item.contact().equals(contact) && transfer.item.state() == State.UPLOADING);
        boolean queued = transfers.values().stream().anyMatch(transfer -> transfer.item.contact().equals(contact) && transfer.item.state() == State.QUEUED);
        if (!active) recipients.remove(contact);
        if (!queued) recipientQueue.remove(contact);
        promote(clock.getAsLong());
    }
    public synchronized int queuePosition(String key) {
        Transfer transfer = transfers.get(key); return transfer == null ? 0 : position(transfer.item.contact());
    }
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
        int position = admit(contact, now);
        var item = new Item(key, contact, name, offset, total,
                position == 0 && previous != null && previous.item.state() == State.UPLOADING ? previous.item.speed() : 0,
                position == 0 ? State.UPLOADING : State.QUEUED);
        var transfer = new Transfer(item, connection, previous == null || previous.item.state() != State.UPLOADING ? now : previous.updated);
        transfer.share = share; transfer.relative = relative; transfer.request = request;
        transfer.inFlight = position == 0;
        transfer.rate = previous != null && previous.item.state() == State.UPLOADING && offset >= previous.item.sent() ? previous.rate : new TransferRate(clock);
        if (transfer.rate != (previous == null ? null : previous.rate)) transfer.rate.reset(offset);
        transfers.put(key, transfer);
        if (transfers.size() > 200) {
            var it = transfers.entrySet().iterator();
            while (transfers.size() > 200 && it.hasNext()) {
                State state = it.next().getValue().item.state();
                if (state != State.UPLOADING && state != State.QUEUED) it.remove();
            }
        }
        publish(); return key;
    }
    public synchronized void acknowledged(String key, long sent) {
        Transfer transfer = transfers.get(key); if (transfer == null) return;
        if (transfer.item.state() != State.UPLOADING) return;
        Item item = transfer.item; long now = clock.getAsLong();
        transfer.inFlight = false; recipients.put(item.contact(), now);
        double speed = transfer.rate.update(sent);
        transfer.item = new Item(key, item.contact(), item.name(), sent, item.total(), speed,
                sent == item.total() ? State.COMPLETE : State.UPLOADING);
        transfer.updated = now;
        if (sent == item.total()) releaseIfIdle(item.contact());
        publish();
    }
    public synchronized void paused(String contact, String share, String relative) {
        Transfer transfer = transfers.get(key(contact, share, relative));
        if (transfer != null && transfer.item.state() != State.COMPLETE && transfer.item.state() != State.CANCELLED && transfer.item.state() != State.CANCELLED_BY_SENDER) { state(transfer, State.PAUSED); releaseIfIdle(contact); publish(); }
    }
    public synchronized Cancellation cancelBySender(String key) {
        Transfer transfer = transfers.get(key);
        if (transfer == null || transfer.item.state() == State.COMPLETE || transfer.item.state() == State.CANCELLED
                || transfer.item.state() == State.CANCELLED_BY_SENDER) return null;
        stoppedRequests.add(key + "\0" + transfer.request);
        state(transfer, State.CANCELLED_BY_SENDER); releaseIfIdle(transfer.item.contact()); publish();
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
        releaseIfIdle(contact);
        publish();
    }
    public synchronized void disconnected(Object connection) {
        var affected = new HashSet<String>();
        for (var transfer : transfers.values()) if (transfer.connection == connection
                && (transfer.item.state() == State.UPLOADING || transfer.item.state() == State.WAITING || transfer.item.state() == State.QUEUED)) {
            state(transfer, State.DISCONNECTED); affected.add(transfer.item.contact());
        }
        for (String contact : affected) releaseIfIdle(contact);
        publish();
    }
    public synchronized void failed(String key) {
        Transfer transfer = transfers.get(key);
        if (transfer != null && transfer.item.state() != State.CANCELLED_BY_SENDER) { state(transfer, State.FAILED); releaseIfIdle(transfer.item.contact()); publish(); }
    }
    public synchronized void unavailable(String contact, String share) {
        String prefix = key(contact, share, "");
        for (var entry : transfers.entrySet()) if (entry.getKey().startsWith(prefix)
                && entry.getValue().item.state() != State.COMPLETE) state(entry.getValue(), State.FAILED);
        releaseIfIdle(contact);
        publish();
    }
    public synchronized void refresh() {
        long now = clock.getAsLong();
        for (var transfer : transfers.values()) if (transfer.item.state() == State.UPLOADING
                && !transfer.inFlight && now - transfer.updated > TimeUnit.SECONDS.toNanos(20)) state(transfer, State.WAITING);
        expireQueue(now); promote(now);
        for (var transfer : transfers.values()) if (transfer.item.state() == State.UPLOADING) {
            Item item = transfer.item;
            transfer.item = new Item(item.key(), item.contact(), item.name(), item.sent(), item.total(), transfer.rate.update(item.sent()), item.state());
        }
        publish();
    }
    private void state(Transfer transfer, State state) {
        transfer.inFlight = false;
        Item item = transfer.item;
        transfer.item = new Item(item.key(), item.contact(), item.name(), item.sent(), item.total(), 0, state);
    }
    public synchronized List<Item> items() {
        return transfers.values().stream().map(transfer -> {
            Item item = transfer.item;
            return new Item(item.key(), item.contact(), item.name(), item.sent(), item.total(), item.speed(), item.state(),
                    item.state() == State.QUEUED ? position(item.contact()) : 0);
        }).toList();
    }
    public synchronized void setListener(Consumer<List<Item>> listener) { this.listener = listener; publish(); }
    private void publish() { try { listener.accept(items()); } catch (RuntimeException ignored) {} }
    @Override public void close() { timer.shutdownNow(); }
}
