package kakha.kudava.onionp2p.ui;

import kakha.kudava.onionp2p.account.AppData;
import kakha.kudava.onionp2p.contacts.ContactManager;
import kakha.kudava.onionp2p.drive.FolderAccess;
import kakha.kudava.onionp2p.drive.SharedDriveStore;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.stage.DirectoryChooser;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutorService;

/** Read-only live browser. Cached rows remain visible when a refresh cannot reach the owner. */
final class SharedFolderBrowser {
    private final ContactManager manager;
    private final SharedDriveStore.SharedFile share;
    private final ExecutorService workers;
    private final Dialog<Void> dialog = new Dialog<>();
    private final ListView<FolderAccess.Entry> files = new ListView<>();
    private final Label location = new Label();
    private final Label status = new Label();
    private final ProgressIndicator loading = new ProgressIndicator();
    private final Button back = new Button("←"), refresh = new Button("Refresh"), download = new Button("Download folder");
    private final Map<String, List<FolderAccess.Entry>> cache = new HashMap<>();
    private String relative = "";
    private int generation;
    private boolean busy;

    SharedFolderBrowser(Window owner, ContactManager manager, SharedDriveStore.SharedFile share,
                        String sender, ExecutorService workers) {
        this.manager = manager; this.share = share; this.workers = workers;
        dialog.initOwner(owner); dialog.initStyle(StageStyle.TRANSPARENT); dialog.setTitle(share.name());
        DialogPane pane = dialog.getDialogPane();
        pane.getStyleClass().add("drive-share-dialog");
        pane.getStylesheets().add(getClass().getResource("/kakha/kudava/onionp2p/app.css").toExternalForm());
        pane.getButtonTypes().add(ButtonType.CLOSE);
        Label title = new Label(share.name()); title.getStyleClass().add("drive-rename-title");
        Label person = new Label("Shared by " + sender + " · Read only"); person.getStyleClass().add("drive-page-subtitle");
        location.setMaxWidth(Double.MAX_VALUE); location.setTextOverrun(OverrunStyle.ELLIPSIS);
        location.getStyleClass().add("drive-page-subtitle"); HBox.setHgrow(location, Priority.ALWAYS);
        loading.setPrefSize(18, 18); loading.setMaxSize(18, 18);
        HBox toolbar = new HBox(10, back, location, loading, refresh); toolbar.setAlignment(Pos.CENTER_LEFT);
        files.getStyleClass().add("drive-share-list"); files.setPrefHeight(300);
        files.setPlaceholder(new Label("This folder is empty"));
        files.setCellFactory(view -> new ListCell<>() {
            @Override protected void updateItem(FolderAccess.Entry entry, boolean empty) {
                super.updateItem(entry, empty);
                setText(null); setGraphic(null);
                if (empty || entry == null) return;
                SVGPath glyph = new SVGPath();
                glyph.setContent(entry.folder() ? "M3 7h7l2 2h9v11H3z M3 9h18" : "M6 3h8l4 4v14H6z M14 3v5h5");
                glyph.getStyleClass().add("drive-menu-glyph");
                Label name = new Label(entry.name()); name.setMaxWidth(Double.MAX_VALUE); name.setTextOverrun(OverrunStyle.ELLIPSIS);
                HBox.setHgrow(name, Priority.ALWAYS);
                Label size = new Label(entry.folder() ? "Folder" : String.format(Locale.ROOT, "%.1f KB", entry.size() / 1000.0));
                size.getStyleClass().add("drive-page-subtitle");
                HBox row = new HBox(12, glyph, name, size); row.setAlignment(Pos.CENTER_LEFT); setGraphic(row);
            }
        });
        files.setOnMouseClicked(event -> {
            var selected = files.getSelectionModel().getSelectedItem();
            if (!busy && event.getClickCount() == 2 && selected != null) {
                if (selected.folder()) load(childPath(selected.name())); else downloadSelection();
            }
        });
        files.getSelectionModel().selectedItemProperty().addListener((o, a, b) ->
                download.setText(b == null ? "Download folder" : b.folder() ? "Download selected folder" : "Download file"));
        back.setOnAction(event -> load(relative.contains("/") ? relative.substring(0, relative.lastIndexOf('/')) : ""));
        refresh.setOnAction(event -> load(relative));
        download.setOnAction(event -> downloadSelection());
        status.setWrapText(true); status.setMaxWidth(620); status.getStyleClass().add("drive-page-subtitle");
        VBox body = new VBox(12, title, person, toolbar, files, status, download);
        body.getStyleClass().add("drive-share-body"); pane.setContent(body); pane.setPrefWidth(650);
        dialog.setOnShown(event -> { pane.getScene().setFill(Color.TRANSPARENT); load(""); });
        dialog.setOnHidden(event -> generation++);
    }

    void show() { dialog.show(); }
    private String childPath(String name) { return relative.isEmpty() ? name : relative + "/" + name; }
    private void busy(boolean value) {
        busy = value; loading.setVisible(value); loading.setManaged(value);
        back.setDisable(value || relative.isEmpty()); refresh.setDisable(value); download.setDisable(value); files.setDisable(value);
    }
    private void load(String path) {
        relative = path; int request = ++generation;
        location.setText(share.name() + (path.isEmpty() ? " /" : " / " + path));
        location.setTooltip(new Tooltip(location.getText()));
        files.getItems().setAll(cache.getOrDefault(path, List.of()));
        status.setText("Loading folder through Tor…"); busy(true);
        workers.execute(() -> {
            try {
                var entries = manager.browseSharedFolder(share.contactLink(), share.id(), path, ignored -> {});
                Platform.runLater(() -> {
                    if (request != generation) return;
                    cache.put(path, entries); files.getItems().setAll(entries); busy(false);
                    status.setText(entries.size() + " items · Refresh to see changes");
                });
            } catch (Exception error) {
                Platform.runLater(() -> {
                    if (request != generation) return;
                    busy(false);
                    status.setText("Owner offline, unreachable, or access removed. "
                            + (cache.containsKey(path) ? "Showing the previous listing. " : "") + error.getMessage());
                });
            }
        });
    }
    private void downloadSelection() {
        var selected = files.getSelectionModel().getSelectedItem();
        String path = selected == null ? relative : childPath(selected.name());
        boolean folder = selected == null || selected.folder();
        String name = selected == null ? (relative.isEmpty() ? share.name() : relative.substring(relative.lastIndexOf('/') + 1)) : selected.name();
        DirectoryChooser chooser = new DirectoryChooser(); chooser.setTitle("Save " + name + " in…");
        Path defaults = AppData.directory().resolve("drive").resolve("shared");
        try { Files.createDirectories(defaults); chooser.setInitialDirectory(defaults.toFile()); } catch (Exception ignored) {}
        var chosen = chooser.showDialog(dialog.getDialogPane().getScene().getWindow());
        if (chosen == null) return;
        busy(true); status.setText("Preparing download…");
        workers.execute(() -> {
            try {
                Path destination = chosen.toPath();
                if (folder) {
                    FolderAccess.validateName(name);
                    // Atomically reserve a new directory; never merge with a previous download.
                    for (int i = 0; ; i++) {
                        Path candidate = chosen.toPath().resolve(name + (i == 0 ? "" : " (" + i + ")"));
                        try { Files.createDirectory(candidate); destination = candidate; break; }
                        catch (FileAlreadyExistsException exists) { if (i > 10000) throw exists; }
                    }
                }
                java.util.function.Consumer<String> progress = text -> Platform.runLater(() -> status.setText(text));
                if (folder) manager.downloadSharedFolder(share.contactLink(), share.id(), path, destination, progress);
                else manager.downloadSharedFolderFile(share.contactLink(), share.id(), path, destination, progress);
                Path saved = destination;
                Platform.runLater(() -> { busy(false); status.setText("Downloaded to " + saved); });
            } catch (Exception error) {
                Platform.runLater(() -> { busy(false); status.setText("Download stopped: " + error.getMessage() + ". Completed files have been kept."); });
            }
        });
    }
}
