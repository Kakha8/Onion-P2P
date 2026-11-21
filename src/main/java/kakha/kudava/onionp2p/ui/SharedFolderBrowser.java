package kakha.kudava.onionp2p.ui;

import kakha.kudava.onionp2p.contacts.ContactManager;
import kakha.kudava.onionp2p.drive.FolderAccess;
import kakha.kudava.onionp2p.drive.SharedDriveStore;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.control.*;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.stage.DirectoryChooser;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.Duration;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutorService;

/** Read-only live browser. Cached rows remain visible when a refresh cannot reach the owner. */
final class SharedFolderBrowser {
    private final ContactManager manager;
    private final SharedDriveStore.SharedFile share;
    private final ExecutorService workers;
    private final String sender;
    private final Runnable showDownloads;
    private final Dialog<Void> dialog = new Dialog<>();
    private final ListView<FolderAccess.Entry> files = new ListView<>();
    private final Label location = new Label();
    private final Label status = new Label();
    private final ProgressIndicator loading = new ProgressIndicator();
    private final Button back = new Button("←"), refresh = new Button("Refresh"), more = moreButton("More options for this shared folder");
    private final Timeline updates = new Timeline(new KeyFrame(Duration.seconds(10), event -> {
        if (dialog.isShowing() && !this.loadingRequest) load(this.relative, true);
    }));
    private final Map<String, List<FolderAccess.Entry>> cache = new HashMap<>();
    private String relative = "";
    private int generation;
    private boolean busy;
    private boolean loadingRequest;

    SharedFolderBrowser(Window owner, ContactManager manager, SharedDriveStore.SharedFile share,
                        String sender, ExecutorService workers, Runnable showDownloads) {
        this.manager = manager; this.share = share; this.workers = workers;
        this.sender = sender; this.showDownloads = showDownloads;
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
        HBox toolbar = new HBox(10, back, location, loading, refresh, more); toolbar.setAlignment(Pos.CENTER_LEFT);
        more.setOnAction(event -> showMenu(more, null));
        files.getStyleClass().add("drive-share-list"); files.setPrefHeight(300);
        files.setPlaceholder(new Label("This folder is empty"));
        files.setCellFactory(view -> new ListCell<>() {
            @Override protected void updateItem(FolderAccess.Entry entry, boolean empty) {
                super.updateItem(entry, empty);
                setText(null); setGraphic(null); setContextMenu(null);
                if (empty || entry == null) return;
                SVGPath glyph = new SVGPath();
                glyph.setContent(entry.folder() ? "M3 7h7l2 2h9v11H3z M3 9h18" : "M6 3h8l4 4v14H6z M14 3v5h5");
                glyph.getStyleClass().add("drive-menu-glyph");
                Label name = new Label(entry.name()); name.setMaxWidth(Double.MAX_VALUE); name.setTextOverrun(OverrunStyle.ELLIPSIS);
                HBox.setHgrow(name, Priority.ALWAYS);
                Label size = new Label(entry.folder() ? "Folder" : String.format(Locale.ROOT, "%.1f KB", entry.size() / 1000.0));
                size.getStyleClass().add("drive-page-subtitle");
                Button entryMore = moreButton("More options for " + entry.name());
                entryMore.setOnAction(event -> {
                    event.consume();
                    showMenu(entryMore, entry);
                });
                entryMore.addEventHandler(MouseEvent.MOUSE_CLICKED, MouseEvent::consume);
                HBox row = new HBox(12, glyph, name, size, entryMore); row.setAlignment(Pos.CENTER_LEFT); setGraphic(row);
                setContextMenu(entryMenu(entry));
            }
        });
        files.setOnMouseClicked(event -> {
            var selected = files.getSelectionModel().getSelectedItem();
            if (!busy && event.getClickCount() == 2 && selected != null) {
                if (selected.folder()) load(childPath(selected.name())); else downloadSelection(selected);
            }
        });
        back.setOnAction(event -> load(relative.contains("/") ? relative.substring(0, relative.lastIndexOf('/')) : ""));
        refresh.setOnAction(event -> load(relative));
        status.setWrapText(true); status.setMaxWidth(620); status.getStyleClass().add("drive-page-subtitle");
        VBox body = new VBox(12, title, person, toolbar, files, status);
        body.getStyleClass().add("drive-share-body"); pane.setContent(body); pane.setPrefWidth(650);
        updates.setCycleCount(Timeline.INDEFINITE);
        dialog.setOnShown(event -> { pane.getScene().setFill(Color.TRANSPARENT); load(""); updates.play(); });
        dialog.setOnHidden(event -> { generation++; loadingRequest = false; updates.stop(); });
    }

    void show() { dialog.show(); }
    private String childPath(String name) { return relative.isEmpty() ? name : relative + "/" + name; }
    private void busy(boolean value) {
        busy = value; loading.setVisible(value); loading.setManaged(value);
        back.setDisable(value || relative.isEmpty()); refresh.setDisable(value); files.setDisable(value); more.setDisable(value);
    }
    private void load(String path) { load(path, false); }
    private void load(String path, boolean background) {
        relative = path; int request = ++generation;
        loadingRequest = true;
        location.setText(share.name() + (path.isEmpty() ? " /" : " / " + path));
        location.setTooltip(new Tooltip(location.getText()));
        if (!background) {
            files.getItems().setAll(cache.getOrDefault(path, List.of()));
            status.setText("Loading folder through Tor…"); busy(true);
        }
        workers.execute(() -> {
            try {
                boolean stillShared = manager.sharedDriveFiles().stream()
                        .anyMatch(file -> file.id().equals(share.id()) && file.contactLink().equals(share.contactLink()));
                if (!stillShared) {
                    Platform.runLater(() -> {
                        if (request != generation) return;
                        loadingRequest = false; cache.clear(); files.getItems().clear(); dialog.close();
                    });
                    return;
                }
                var entries = background
                        ? manager.browseSharedFolderForDownload(share.contactLink(), share.id(), path, ignored -> {})
                        : manager.browseSharedFolder(share.contactLink(), share.id(), path, ignored -> {});
                Platform.runLater(() -> {
                    if (request != generation) return;
                    loadingRequest = false;
                    cache.put(path, entries); files.getItems().setAll(entries); busy(false);
                    status.setText(entries.size() + " items · Updates automatically");
                });
            } catch (Exception error) {
                Platform.runLater(() -> {
                    if (request != generation) return;
                    loadingRequest = false;
                    busy(false);
                    if ("Folder or file is unavailable. Access may have been removed.".equals(error.getMessage())) {
                        cache.remove(path); files.getItems().clear();
                        status.setText("This folder is no longer available. Go back to view the remaining files.");
                        return;
                    }
                    status.setText("Owner offline, unreachable, or access removed. "
                            + (cache.containsKey(path) ? "Showing the previous listing. " : "") + error.getMessage());
                });
            }
        });
    }
    private void downloadSelection(FolderAccess.Entry selected) {
        downloadSelection(selected, false);
    }
    private void downloadSelection(FolderAccess.Entry selected, boolean chooseDestination) {
        String path = selected == null ? relative : childPath(selected.name());
        boolean folder = selected == null || selected.folder();
        String name = selected == null ? (relative.isEmpty() ? share.name() : relative.substring(relative.lastIndexOf('/') + 1)) : selected.name();
        Path destination = manager.downloads().defaultDestination();
        if (chooseDestination) {
            DirectoryChooser chooser = new DirectoryChooser(); chooser.setTitle("Download " + name + " to…");
            try { Files.createDirectories(destination); chooser.setInitialDirectory(destination.toFile()); } catch (Exception ignored) {}
            var chosen = chooser.showDialog(dialog.getDialogPane().getScene().getWindow());
            if (chosen == null) return;
            destination = chosen.toPath();
        }
        manager.downloads().folderItem(share, sender, path, name, folder, selected == null ? -1 : selected.size(), destination);
        dialog.close();
        showDownloads.run();
    }

    private void showMenu(Button anchor, FolderAccess.Entry entry) {
        entryMenu(entry).show(anchor, Side.BOTTOM, 0, 0);
    }

    private ContextMenu entryMenu(FolderAccess.Entry entry) {
        ContextMenu menu = new ContextMenu();
        menu.getStyleClass().add("drive-entry-menu");
        if (entry != null && entry.folder()) menu.getItems().add(menuItem("Open",
                "M3 7h7l2 2h9v11H3z M3 9h18", () -> load(childPath(entry.name()))));
        menu.getItems().addAll(menuItem("Download", "M12 3v12 M7 10l5 5 5-5 M4 19h16", () -> downloadSelection(entry)),
                menuItem("Download to…", "M3 7V4h6l2 3h10v14H3z M12 10v8 M8 14l4 4 4-4", () -> downloadSelection(entry, true)),
                new SeparatorMenuItem(), menuItem("Properties", "M12 8h.01 M11 12h1v4h1 M21 12a9 9 0 1 1-18 0a9 9 0 0 1 18 0Z",
                        () -> showProperties(entry)));
        menu.setOnShown(event -> {
            menu.getScene().setFill(Color.TRANSPARENT);
            menu.getScene().getRoot().setStyle("-fx-background-color: transparent; -fx-background-insets: 0; -fx-border-color: transparent; -fx-effect: none;");
        });
        return menu;
    }

    private void showProperties(FolderAccess.Entry entry) {
        String name = entry == null ? currentFolderName() : entry.name();
        var fields = new LinkedHashMap<String, String>();
        fields.put("Type", entry == null || entry.folder() ? "Folder" : fileType(entry.name()));
        if (entry != null && !entry.folder()) fields.put("Size", formatSize(entry.size()));
        fields.put("Shared by", sender);
        fields.put("Location", share.name() + (relative.isEmpty() ? " /" : " / " + relative));
        DriveItemDialogs.properties(dialog.getDialogPane().getScene().getWindow(), name, fields).showAndWait();
    }

    private String currentFolderName() {
        return relative.isEmpty() ? share.name() : relative.substring(relative.lastIndexOf('/') + 1);
    }

    private static String fileType(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot + 1 < name.length() ? name.substring(dot + 1).toUpperCase(Locale.ROOT) : "File";
    }

    private static String formatSize(long size) {
        if (size >= 1024 * 1024) return String.format(Locale.ROOT, "%.1f MiB", size / (1024.0 * 1024));
        if (size >= 1024) return String.format(Locale.ROOT, "%.1f KiB", size / 1024.0);
        return size + " B";
    }

    private static MenuItem menuItem(String title, String path, Runnable action) {
        SVGPath glyph = new SVGPath(); glyph.setContent(path); glyph.getStyleClass().add("drive-menu-glyph");
        MenuItem item = new MenuItem(title, glyph); item.setOnAction(event -> action.run()); return item;
    }

    private static Button moreButton(String label) {
        SVGPath glyph = new SVGPath(); glyph.setContent("M12 5h.01 M12 12h.01 M12 19h.01"); glyph.getStyleClass().add("drive-menu-glyph");
        Button button = new Button(null, glyph); button.getStyleClass().add("drive-entry-more");
        button.setAccessibleText(label); button.setTooltip(new Tooltip("More options")); return button;
    }
}
