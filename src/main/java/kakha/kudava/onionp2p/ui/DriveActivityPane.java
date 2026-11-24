package kakha.kudava.onionp2p.ui;

import javafx.beans.property.*;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.shape.SVGPath;

/** One floating activity list with type filters and the existing transfer controls. */
final class DriveActivityPane extends VBox {
    enum Filter { ALL, ADDING, UPLOADING, DOWNLOADING }
    private final ReadOnlyIntegerWrapper active = new ReadOnlyIntegerWrapper();
    private final VBox sections;
    private final ScrollPane scroll;
    private final HBox filters = new HBox(3);
    private final ToggleGroup group = new ToggleGroup();
    private final ImportsPane imports;
    private final UploadsPane uploads;
    private final DownloadsPane downloads;
    private final Button minimize;
    private final Button clearCompleted = new Button("Clear completed");
    private final Label cleanupError = new Label();
    private final Label empty = new Label("No file activity yet");
    private Filter selected = Filter.ALL;
    private boolean minimized;

    DriveActivityPane(ImportsPane imports, UploadsPane uploads, DownloadsPane downloads) {
        this.imports = imports; this.uploads = uploads.embedded(); this.downloads = downloads.embedded();
        getStyleClass().addAll("downloads-panel", "drive-activity-panel");
        setMinSize(0, 0); setPrefWidth(420); setMaxWidth(420); setMaxHeight(USE_PREF_SIZE);
        var title = new Label("Activity"); title.getStyleClass().add("downloads-title");
        var summary = new Label(); summary.getStyleClass().add("downloads-summary");
        active.bind(imports.activeCountProperty().add(uploads.activeCountProperty()).add(downloads.activeCountProperty()));
        summary.textProperty().bind(javafx.beans.binding.Bindings.when(active.greaterThan(0)).then(active.asString("%d active")).otherwise("All file activity"));
        var labels = new VBox(2, title, summary); HBox.setHgrow(labels, Priority.ALWAYS);
        minimize = icon("M6 9l6 6 6-6", "Minimize activity");
        var close = icon("M6 6l12 12 M18 6L6 18", "Hide activity — transfers continue");
        clearCompleted.getStyleClass().add("activity-clear-button");
        clearCompleted.setAccessibleText("Clear completed activity");
        clearCompleted.setTooltip(new Tooltip("Clear finished, cancelled, and failed entries in this view. Files and shares are kept."));
        clearCompleted.disableProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(() -> {
            Filter filter = group.getSelectedToggle() == null ? Filter.ALL : (Filter) group.getSelectedToggle().getUserData();
            return switch (filter) {
                case ADDING -> imports.finishedCountProperty().get() == 0;
                case UPLOADING -> uploads.finishedCountProperty().get() == 0;
                case DOWNLOADING -> downloads.finishedCountProperty().get() == 0;
                case ALL -> imports.finishedCountProperty().get() + uploads.finishedCountProperty().get() + downloads.finishedCountProperty().get() == 0;
            };
        }, group.selectedToggleProperty(), imports.finishedCountProperty(), uploads.finishedCountProperty(), downloads.finishedCountProperty()));
        cleanupError.getStyleClass().addAll("downloads-summary", "activity-cleanup-message");
        cleanupError.setWrapText(true); show(cleanupError, false);
        clearCompleted.setOnAction(event -> {
            show(cleanupError, false);
            try {
                if (selected == Filter.ALL || selected == Filter.ADDING) imports.clearFinished();
                if (selected == Filter.ALL || selected == Filter.UPLOADING) uploads.clearFinished();
                if (selected == Filter.ALL || selected == Filter.DOWNLOADING) downloads.clearFinished();
            } catch (RuntimeException error) {
                cleanupError.setText("Could not clear some entries. Please try again."); show(cleanupError, true);
            }
        });
        var heading = new HBox(6, labels, clearCompleted, minimize, close); heading.setAlignment(Pos.CENTER_LEFT); heading.getStyleClass().add("downloads-header");
        String[] names = {"All", "Adding", "Uploading", "Downloading"};
        for (Filter filter : Filter.values()) {
            var button = new ToggleButton(names[filter.ordinal()]); button.setUserData(filter); button.setToggleGroup(group);
            button.getStyleClass().add("activity-filter"); button.setMaxWidth(Double.MAX_VALUE); HBox.setHgrow(button, Priority.ALWAYS);
            if (filter != Filter.ALL) {
                ReadOnlyIntegerProperty count = switch (filter) {
                    case ADDING -> imports.activeCountProperty();
                    case UPLOADING -> uploads.activeEntryCountProperty();
                    case DOWNLOADING -> downloads.activeEntryCountProperty();
                    default -> throw new IllegalStateException();
                };
                var badge = new Label(); badge.getStyleClass().add("activity-count-badge");
                badge.textProperty().bind(count.asString());
                badge.visibleProperty().bind(button.selectedProperty().not().and(count.greaterThan(0)));
                badge.managedProperty().bind(badge.visibleProperty());
                var text = new Label(names[filter.ordinal()]); text.getStyleClass().add("activity-filter-label");
                var content = new HBox(4, text, badge); content.setAlignment(Pos.CENTER);
                button.setText(null); button.setGraphic(content); button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                button.accessibleTextProperty().bind(javafx.beans.binding.Bindings.concat(names[filter.ordinal()], ", ", count, " active"));
            }
            button.setOnAction(event -> { if (!button.isSelected()) button.setSelected(true); applyFilter(filter); });
            filters.getChildren().add(button);
        }
        filters.getStyleClass().add("activity-filters");
        empty.getStyleClass().addAll("downloads-summary", "activity-section-header");
        sections = new VBox(imports, uploads, downloads, empty);
        scroll = new ScrollPane(sections) {
            @Override protected double computePrefHeight(double width) {
                double available = DriveActivityPane.this.getWidth() > 0 ? DriveActivityPane.this.getWidth() - 10 : 410;
                return Math.min(280, Math.max(64, sections.prefHeight(available))) + snappedTopInset() + snappedBottomInset();
            }
        };
        scroll.setFitToWidth(true); scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().addAll("downloads-list", "activity-list"); scroll.setMinHeight(0);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        // Propagate row additions and wrapped text changes to the floating panel's preferred height.
        sections.needsLayoutProperty().addListener((obs, before, dirty) -> { if (dirty) scroll.requestLayout(); });
        imports.itemCountProperty().addListener((obs, before, count) -> applyFilter(selected));
        uploads.itemCountProperty().addListener((obs, before, count) -> applyFilter(selected));
        downloads.itemCountProperty().addListener((obs, before, count) -> applyFilter(selected));
        getChildren().addAll(heading, filters, scroll, cleanupError);
        minimize.setOnAction(event -> {
            minimized = !minimized; scroll.setVisible(!minimized); scroll.setManaged(!minimized); filters.setVisible(!minimized); filters.setManaged(!minimized);
            show(clearCompleted, !minimized);
            ((SVGPath) minimize.getGraphic()).setContent(minimized ? "M6 15l6-6 6 6" : "M6 9l6 6 6-6");
        });
        close.setOnAction(event -> hide()); select(Filter.ALL); hide();
    }
    ReadOnlyIntegerProperty activeCountProperty() { return active.getReadOnlyProperty(); }
    void reveal() {
        minimized = false; scroll.setVisible(true); scroll.setManaged(true); filters.setVisible(true); filters.setManaged(true);
        show(clearCompleted, true);
        ((SVGPath) minimize.getGraphic()).setContent("M6 9l6 6 6-6");
        setVisible(true); setManaged(true); toFront();
    }
    void reveal(Filter filter) { select(filter); reveal(); }
    void hide() { setVisible(false); setManaged(false); }
    void select(Filter filter) { group.selectToggle((Toggle) filters.getChildren().get(filter.ordinal())); applyFilter(filter); }
    private void applyFilter(Filter filter) {
        boolean changed = selected != filter;
        selected = filter;
        show(imports, filter == Filter.ADDING || filter == Filter.ALL && imports.itemCountProperty().get() > 0);
        show(uploads, filter == Filter.UPLOADING || filter == Filter.ALL && uploads.itemCountProperty().get() > 0);
        show(downloads, filter == Filter.DOWNLOADING || filter == Filter.ALL && downloads.itemCountProperty().get() > 0);
        show(empty, filter == Filter.ALL && imports.itemCountProperty().get() + uploads.itemCountProperty().get() + downloads.itemCountProperty().get() == 0);
        if (scroll != null && changed) scroll.setVvalue(0);
    }
    private static void show(javafx.scene.Node node, boolean show) { node.setVisible(show); node.setManaged(show); }
    private static Button icon(String path, String label) {
        var glyph = new SVGPath(); glyph.setContent(path); glyph.getStyleClass().add("download-glyph");
        var button = new Button(null, glyph); button.getStyleClass().add("download-icon-button"); button.setAccessibleText(label); button.setTooltip(new Tooltip(label)); return button;
    }
}
