package kakha.kudava.onionp2p.ui;

import kakha.kudava.onionp2p.drive.DownloadQueue;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.shape.SVGPath;
import java.util.*;

/** Floating transfer panel, matching File-Drive's compact transfer list. */
final class DownloadsPane extends VBox {
    private final DownloadQueue queue;
    private final Label title = new Label("Downloads"), summary = new Label("No downloads yet");
    private final VBox rows = new VBox();
    private final ScrollPane scroll = new ScrollPane(rows);
    private final Map<String, Row> views = new LinkedHashMap<>();
    private final Set<String> completed = new HashSet<>();
    private final Runnable onCompleted;
    private boolean minimized;

    DownloadsPane(DownloadQueue queue, Runnable onCompleted) {
        this.queue = queue; this.onCompleted = onCompleted;
        getStyleClass().add("downloads-panel");
        setMinWidth(0); setMinHeight(0); setPrefWidth(390); setMaxWidth(390); setMaxHeight(USE_PREF_SIZE);
        title.getStyleClass().add("downloads-title"); summary.getStyleClass().add("downloads-summary");
        VBox labels = new VBox(2, title, summary); HBox.setHgrow(labels, Priority.ALWAYS);
        Button minimize = icon("M6 9l6 6 6-6", "Minimize downloads");
        minimize.setOnAction(event -> {
            minimized = !minimized; scroll.setVisible(!minimized); scroll.setManaged(!minimized);
            minimize.setGraphic(glyph(minimized ? "M6 15l6-6 6 6" : "M6 9l6 6 6-6"));
        });
        Button close = icon("M6 6l12 12 M18 6L6 18", "Hide downloads — downloads continue");
        close.setOnAction(event -> { setVisible(false); setManaged(false); });
        HBox header = new HBox(6, labels, minimize, close); header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("downloads-header");
        scroll.setFitToWidth(true); scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setMinHeight(0); scroll.getStyleClass().add("downloads-list");
        rows.getStyleClass().add("downloads-rows");
        getChildren().addAll(header, scroll); setVisible(false); setManaged(false);
        queue.setListener(items -> Platform.runLater(() -> update(items)));
    }
    void reveal() { setVisible(true); setManaged(true); toFront(); }
    private void update(List<DownloadQueue.Item> items) {
        int active = 0, done = 0, waiting = 0;
        for (var item : items) {
            if (item.active()) active++;
            if (item.state() == DownloadQueue.State.WAITING) waiting++;
            if (item.state() == DownloadQueue.State.COMPLETE) {
                done++; if (completed.add(item.id())) onCompleted.run();
            }
            Row row = views.computeIfAbsent(item.id(), id -> { Row created = new Row(id); rows.getChildren().add(created); return created; });
            row.update(item);
        }
        title.setText(active > 0 ? "Downloads · " + active + " active" : "Downloads");
        summary.setText(items.isEmpty() ? "No downloads yet" : done + " of " + items.size() + " complete" + (waiting > 0 ? " · " + waiting + " waiting" : ""));
        scroll.setPrefHeight(Math.min(280, Math.max(55, items.size() * 106)));
    }
    private final class Row extends VBox {
        final Label name = new Label(), detail = new Label(), bytes = new Label();
        final ProgressBar progress = new ProgressBar(0);
        final Button action;
        DownloadQueue.Item current;
        Row(String id) {
            getStyleClass().add("download-row");
            name.getStyleClass().add("download-name"); name.setMinWidth(0); name.setMaxWidth(Double.MAX_VALUE); HBox.setHgrow(name, Priority.ALWAYS);
            action = icon("M6 6l12 12 M18 6L6 18", "Cancel download");
            action.setOnAction(event -> {
                if (current.state() == DownloadQueue.State.FAILED) queue.retry(id);
                else if (current.state() == DownloadQueue.State.COMPLETE && current.destination() != null) {
                    try { java.awt.Desktop.getDesktop().open(current.destination().toFile()); }
                    catch (Exception error) { detail.setText("Could not open download: " + error.getMessage()); }
                } else queue.cancel(id);
            });
            HBox line = new HBox(8, name, action); line.setAlignment(Pos.CENTER_LEFT);
            detail.getStyleClass().add("download-detail"); detail.setWrapText(true); detail.setMaxWidth(Double.MAX_VALUE);
            bytes.getStyleClass().add("downloads-summary");
            progress.setMaxWidth(Double.MAX_VALUE); progress.getStyleClass().add("download-progress");
            getChildren().addAll(line, detail, progress, bytes);
        }
        void update(DownloadQueue.Item item) {
            current = item; name.setText(item.name()); name.setTooltip(new Tooltip(item.name()));
            detail.setText(item.detail()); detail.setTooltip(new Tooltip("Shared by " + item.ownerName()));
            boolean complete = item.state() == DownloadQueue.State.COMPLETE;
            double fraction = item.total() > 0 ? Math.min(.99, (double) item.received() / item.total()) : 0;
            progress.setProgress(complete ? 1 : item.total() < 0 && item.state() != DownloadQueue.State.WAITING ? -1 : fraction);
            bytes.setText(item.total() < 0 ? "Folder size will appear when connected" : size(item.received()) + " / " + size(item.total())
                    + " · " + (complete ? 100 : (int) (fraction * 100)) + "%");
            String path = complete ? "M12 3v12 M7 10l5 5 5-5 M4 19h16" : item.state() == DownloadQueue.State.FAILED ? "M4 11a8 8 0 1 1 2 6 M4 5v6h6" : "M6 6l12 12 M18 6L6 18";
            action.setGraphic(glyph(path));
            action.setDisable(item.state() == DownloadQueue.State.CANCELLED);
            String label = complete ? "Open download" : item.state() == DownloadQueue.State.FAILED ? "Retry download" : "Cancel download";
            action.setAccessibleText(label); action.setTooltip(new Tooltip(label));
        }
    }
    private static Button icon(String path, String label) {
        Button button = new Button(null, glyph(path)); button.getStyleClass().add("download-icon-button");
        button.setAccessibleText(label); button.setTooltip(new Tooltip(label)); return button;
    }
    private static SVGPath glyph(String path) { SVGPath icon = new SVGPath(); icon.setContent(path); icon.getStyleClass().add("download-glyph"); return icon; }
    private static String size(long bytes) {
        return bytes >= 1000000 ? String.format(Locale.ROOT, "%.1f MB", bytes / 1000000.0)
                : bytes >= 1000 ? String.format(Locale.ROOT, "%.1f KB", bytes / 1000.0) : bytes + " B";
    }
}
