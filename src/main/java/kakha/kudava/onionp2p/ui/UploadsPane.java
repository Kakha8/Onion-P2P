package kakha.kudava.onionp2p.ui;

import kakha.kudava.onionp2p.drive.UploadTracker;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.shape.SVGPath;
import java.util.*;
import java.util.function.Function;

/** Read-only live sender activity, styled to match Downloads. */
final class UploadsPane extends VBox {
    private final ReadOnlyIntegerWrapper activeCount = new ReadOnlyIntegerWrapper();
    private final Label title = new Label("Uploads"), summary = new Label("No uploads yet");
    private final VBox rows = new VBox();
    private final ScrollPane scroll = new ScrollPane(rows);
    private final Map<String, VBox> views = new LinkedHashMap<>();
    private final Function<String, String> contactName;
    private final java.util.function.Consumer<String> cancelUpload;
    private boolean minimized;

    UploadsPane(UploadTracker tracker, Function<String, String> contactName) {
        this(tracker, contactName, ignored -> {});
    }
    UploadsPane(UploadTracker tracker, Function<String, String> contactName, java.util.function.Consumer<String> cancelUpload) {
        this.contactName = contactName;
        this.cancelUpload = cancelUpload;
        getStyleClass().add("downloads-panel");
        setMinWidth(0); setMinHeight(0); setPrefWidth(390); setMaxWidth(390); setMaxHeight(USE_PREF_SIZE);
        title.getStyleClass().add("downloads-title"); summary.getStyleClass().add("downloads-summary");
        VBox labels = new VBox(2, title, summary); HBox.setHgrow(labels, Priority.ALWAYS);
        Button minimize = icon("M6 9l6 6 6-6", "Minimize uploads");
        minimize.setOnAction(event -> {
            minimized = !minimized; scroll.setManaged(!minimized); scroll.setVisible(!minimized);
            minimize.setGraphic(glyph(minimized ? "M6 15l6-6 6 6" : "M6 9l6 6 6-6"));
        });
        Button close = icon("M6 6l12 12 M18 6L6 18", "Hide uploads"); close.setOnAction(event -> hide());
        HBox header = new HBox(6, labels, minimize, close); header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("downloads-header");
        scroll.setFitToWidth(true); scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setMinHeight(0); scroll.getStyleClass().add("downloads-list");
        rows.getStyleClass().add("downloads-rows"); getChildren().addAll(header, scroll); hide();
        tracker.setListener(items -> Platform.runLater(() -> update(items)));
    }
    ReadOnlyIntegerProperty activeCountProperty() { return activeCount.getReadOnlyProperty(); }
    void reveal() { setVisible(true); setManaged(true); toFront(); }
    void hide() { setVisible(false); setManaged(false); }
    private void update(List<UploadTracker.Item> items) {
        int active = (int) items.stream().filter(item -> item.state() == UploadTracker.State.UPLOADING).count();
        activeCount.set(active);
        title.setText(active == 0 ? "Uploads" : "Uploads · " + active + " active");
        long complete = items.stream().filter(item -> item.state() == UploadTracker.State.COMPLETE).count();
        summary.setText(items.isEmpty() ? "No uploads yet" : complete + " of " + items.size() + " complete");
        var keys = new HashSet<String>();
        for (var item : items) {
            keys.add(item.key());
            VBox row = views.computeIfAbsent(item.key(), ignored -> {
                Label name = new Label(), recipient = new Label(), detail = new Label(), bytes = new Label(), metrics = new Label();
                name.getStyleClass().add("download-name"); recipient.getStyleClass().add("download-detail");
                detail.getStyleClass().add("download-detail"); bytes.getStyleClass().add("downloads-summary");
                metrics.getStyleClass().add("downloads-summary");
                name.setMinWidth(0); name.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(name, Priority.ALWAYS);
                Button cancel = icon("M6 6l12 12 M18 6L6 18", "Cancel upload");
                cancel.setOnAction(event -> cancelUpload.accept(item.key()));
                HBox heading = new HBox(8, name, cancel); heading.setAlignment(Pos.CENTER_LEFT);
                recipient.setMaxWidth(Double.MAX_VALUE); detail.setWrapText(true);
                ProgressBar progress = new ProgressBar(); progress.setMaxWidth(Double.MAX_VALUE);
                progress.getStyleClass().add("download-progress");
                VBox created = new VBox(5, heading, recipient, detail, progress, bytes, metrics);
                created.getStyleClass().add("download-row"); rows.getChildren().add(0, created); return created;
            });
            HBox heading = (HBox) row.getChildren().get(0);
            ((Label) heading.getChildren().get(0)).setText(item.name());
            boolean canCancel = item.state() == UploadTracker.State.UPLOADING || item.state() == UploadTracker.State.WAITING
                    || item.state() == UploadTracker.State.PAUSED || item.state() == UploadTracker.State.DISCONNECTED;
            heading.getChildren().get(1).setVisible(canCancel); heading.getChildren().get(1).setManaged(canCancel);
            ((Label) row.getChildren().get(1)).setText("To " + contactName.apply(item.contact()));
            String status = switch (item.state()) {
                case UPLOADING -> "Uploading";
                case WAITING -> "Waiting for recipient";
                case PAUSED -> "Paused by recipient";
                case DISCONNECTED -> "Recipient disconnected · progress retained";
                case COMPLETE -> "Uploaded";
                case FAILED -> "Transfer stopped · file unavailable";
                case CANCELLED -> "Cancelled by recipient";
                case CANCELLED_BY_SENDER -> "Cancelled by you";
            };
            ((Label) row.getChildren().get(2)).setText(status);
            TransferPresentation.cancelled(row, (Label) row.getChildren().get(2), item.state() == UploadTracker.State.CANCELLED || item.state() == UploadTracker.State.CANCELLED_BY_SENDER);
            ((ProgressBar) row.getChildren().get(3)).setProgress(item.state() == UploadTracker.State.COMPLETE ? 1
                    : item.total() == 0 ? 0 : Math.min(.99, (double) item.sent() / item.total()));
            ((Label) row.getChildren().get(4)).setText(size(item.sent()) + " / " + size(item.total()));
            ((Label) row.getChildren().get(5)).setText(item.state() == UploadTracker.State.UPLOADING
                    ? TransferPresentation.speedAndEta(item.sent(), item.total(), item.speed()) : "0 B/s · —");
        }
        views.entrySet().removeIf(entry -> { if (keys.contains(entry.getKey())) return false; rows.getChildren().remove(entry.getValue()); return true; });
        TransferPresentation.fitList(scroll, rows, getWidth() > 0 ? getWidth() : getPrefWidth());
    }
    private static Button icon(String path, String label) {
        Button button = new Button(null, glyph(path)); button.getStyleClass().add("download-icon-button");
        button.setAccessibleText(label); button.setTooltip(new Tooltip(label)); return button;
    }
    private static SVGPath glyph(String path) { var icon = new SVGPath(); icon.setContent(path); icon.getStyleClass().add("download-glyph"); return icon; }
    private static String size(long bytes) {
        return bytes >= 1000000 ? String.format(Locale.ROOT, "%.1f MB", bytes / 1000000.0)
                : bytes >= 1000 ? String.format(Locale.ROOT, "%.1f KB", bytes / 1000.0) : bytes + " B";
    }
}
