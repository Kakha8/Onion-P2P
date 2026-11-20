package kakha.kudava.onionp2p.ui;

import kakha.kudava.onionp2p.drive.ImportQueue;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import java.util.*;

/** Local copy and checksum work is displayed alongside network transfers. */
final class ImportsPane extends VBox {
    private final ReadOnlyIntegerWrapper active = new ReadOnlyIntegerWrapper();
    private final ReadOnlyIntegerWrapper itemCount = new ReadOnlyIntegerWrapper();
    private final Label summary = new Label("No files being added");
    private final VBox rows = new VBox();
    private final Map<String, Row> views = new LinkedHashMap<>();
    private final Set<String> completed = new HashSet<>();
    ImportsPane(ImportQueue queue, Runnable onCompleted) {
        getStyleClass().add("activity-section"); setMinWidth(0);
        var title = new Label("Adding"); title.getStyleClass().add("downloads-title");
        summary.getStyleClass().add("downloads-summary");
        var heading = new VBox(2, title, summary); heading.getStyleClass().add("activity-section-header");
        getChildren().addAll(heading, rows);
        queue.setListener(items -> Platform.runLater(() -> {
            itemCount.set(items.size());
            active.set((int) items.stream().filter(ImportQueue.Item::active).count());
            long done = items.stream().filter(item -> item.state() == ImportQueue.State.COMPLETE).count();
            summary.setText(items.isEmpty() ? "No files being added" : done + " of " + items.size() + " added" + (active.get() > 0 ? " · " + active.get() + " pending" : ""));
            boolean changed = false;
            for (var item : items) {
                var row = views.computeIfAbsent(item.id(), key -> { var created = new Row(); rows.getChildren().add(created); return created; });
                row.update(item);
                if (item.state() == ImportQueue.State.COMPLETE && completed.add(item.id())) changed = true;
            }
            if (changed) onCompleted.run();
        }));
    }
    ReadOnlyIntegerProperty activeCountProperty() { return active.getReadOnlyProperty(); }
    ReadOnlyIntegerProperty itemCountProperty() { return itemCount.getReadOnlyProperty(); }
    private static final class Row extends VBox {
        final Label name = label("download-name"), detail = label("download-detail"),
                bytes = label("downloads-summary"), metrics = label("downloads-summary");
        final ProgressBar progress = bar();
        Row() {
            getStyleClass().add("download-row"); detail.setWrapText(true);
            getChildren().addAll(name, detail, progress, bytes, metrics);
        }
        void update(ImportQueue.Item item) {
            name.setText(item.name()); name.setTooltip(new Tooltip(item.name()));
            detail.setText(item.detail());
            boolean done = item.state() == ImportQueue.State.COMPLETE;
            long processed = item.state() == ImportQueue.State.COPYING ? item.copied() : item.hashed();
            bytes.setText(amount(processed, item.total(), done));
            double total = item.total();
            progress.setProgress(done ? 1 : item.state() == ImportQueue.State.QUEUED ? 0 : total < 0 ? -1 : total == 0 ? 0 : Math.min(.99, processed / total));
            String files = item.files() > 1 ? item.filesDone() + " / " + item.files() + " files · " : "";
            metrics.setText(files + (item.state() == ImportQueue.State.COPYING || item.state() == ImportQueue.State.HASHING
                    ? TransferPresentation.speedAndEta(processed, item.total(), item.speed())
                    : done ? "Ready to share" : item.state() == ImportQueue.State.QUEUED ? "Starts automatically" : "—"));
        }
        static String amount(long bytes, long total, boolean done) {
            return total < 0 ? "Preparing…" : size(bytes) + " / " + size(total) + " · " + (done ? 100 : total == 0 ? 0 : Math.min(99, bytes * 100 / total)) + "%";
        }
        static String size(long value) { return value >= 1_000_000 ? String.format(Locale.ROOT, "%.1f MB", value / 1_000_000.0) : value >= 1000 ? String.format(Locale.ROOT, "%.1f KB", value / 1000.0) : value + " B"; }
        static Label label(String style) { var label = new Label(); label.getStyleClass().add(style); label.setMinWidth(0); label.setMaxWidth(Double.MAX_VALUE); return label; }
        static ProgressBar bar() { var bar = new ProgressBar(); bar.getStyleClass().add("download-progress"); bar.setMaxWidth(Double.MAX_VALUE); return bar; }
    }
}
