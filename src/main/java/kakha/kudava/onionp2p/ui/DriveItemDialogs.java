package kakha.kudava.onionp2p.ui;

import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;

/** Shared dark dialogs for local items and read-only shared items. */
final class DriveItemDialogs {
    static final ButtonType DELETE = new ButtonType("Delete", ButtonBar.ButtonData.OK_DONE);
    private static final String INFO = "M12 8h.01 M11 12h1v4h1 M21 12a9 9 0 1 1-18 0a9 9 0 0 1 18 0Z";

    static Dialog<ButtonType> properties(Window owner, String name, Map<String, String> fields) {
        Dialog<ButtonType> dialog = dialog(owner, "Properties");
        var grid = new GridPane();
        grid.getStyleClass().add("drive-properties-grid");
        var keyColumn = new ColumnConstraints(); keyColumn.setMinWidth(90);
        var valueColumn = new ColumnConstraints(); valueColumn.setHgrow(Priority.ALWAYS); valueColumn.setMinWidth(0);
        grid.getColumnConstraints().addAll(keyColumn, valueColumn);
        int row = 0;
        for (var field : fields.entrySet()) {
            var key = text(field.getKey(), "drive-property-key");
            var value = text(field.getValue(), "drive-property-value");
            dialog.getDialogPane().getProperties().put("drive-property:" + field.getKey(), value);
            grid.add(key, 0, row);
            if (field.getKey().equals("SHA-256")) {
                value.getStyleClass().add("drive-checksum-value");
                value.setMinWidth(0); value.setMinHeight(Region.USE_PREF_SIZE);
                value.setTextOverrun(OverrunStyle.CLIP);
                value.setText(formatChecksum(field.getValue()));
                String copyPath = "M9 9h12v12H9z M5 15H3V3h12v2";
                var icon = new SVGPath();
                icon.setContent(copyPath);
                icon.getStyleClass().add("drive-checksum-copy-glyph");
                var copy = new Button(null, icon);
                copy.getStyleClass().add("drive-checksum-copy");
                copy.setAccessibleText("Copy SHA-256 checksum");
                copy.setTooltip(new Tooltip("Copy checksum"));
                copy.disableProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(
                        () -> !value.getText().replace("\n", "").matches("[0-9a-fA-F]{64}"), value.textProperty()));
                var feedback = text("Copied", "drive-checksum-feedback");
                feedback.setVisible(false);
                var reset = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(2));
                Runnable clearFeedback = () -> {
                    feedback.setVisible(false);
                    icon.setContent(copyPath);
                    copy.getTooltip().setText("Copy checksum");
                };
                reset.setOnFinished(event -> clearFeedback.run());
                value.textProperty().addListener((observable, previous, current) -> {
                    reset.stop(); clearFeedback.run();
                });
                copy.setOnAction(event -> {
                    var content = new javafx.scene.input.ClipboardContent();
                    content.putString(value.getText().replace("\n", ""));
                    boolean copied = javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
                    feedback.setText(copied ? "Copied" : "Couldn’t copy. Try again.");
                    feedback.setVisible(true);
                    icon.setContent(copied ? "M5 12l4 4L19 6" : copyPath);
                    copy.getTooltip().setText(copied ? "Checksum copied" : "Try copying again");
                    reset.playFromStart();
                });
                HBox.setHgrow(value, Priority.ALWAYS);
                var checksum = new HBox(8, value, copy); checksum.setAlignment(Pos.CENTER_LEFT);
                checksum.setMinWidth(0); checksum.setMaxWidth(Double.MAX_VALUE);
                grid.add(new VBox(3, checksum, feedback), 1, row++);
            } else grid.add(value, 1, row++);
            GridPane.setValignment(key, javafx.geometry.VPos.TOP);
            GridPane.setValignment(value, javafx.geometry.VPos.TOP);
        }
        var body = new VBox(14, MessengerPane.driveDialogTitleBar(dialog, "Properties", INFO, false),
                text(name, "drive-item-dialog-name"), grid);
        body.getStyleClass().add("drive-item-dialog-body");
        dialog.getDialogPane().setContent(body);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        return dialog;
    }

    static void updateProperty(Dialog<?> dialog, String key, String value) {
        if (dialog.getDialogPane().getProperties().get("drive-property:" + key) instanceof Label label) {
            label.setText(key.equals("SHA-256") ? formatChecksum(value) : value);
            if (dialog.isShowing() && dialog.getDialogPane().getScene().getWindow() instanceof javafx.stage.Stage stage)
                stage.sizeToScene();
        }
    }

    private static String formatChecksum(String value) {
        if (value == null) return "—";
        return value.matches("[0-9a-fA-F]{64}") ? value.substring(0, 32) + "\n" + value.substring(32) : value;
    }

    static Dialog<ButtonType> delete(Window owner, Path entry, int recipients) {
        boolean folder = Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS);
        Dialog<ButtonType> dialog = dialog(owner, folder ? "Delete folder" : "Delete file");
        dialog.getDialogPane().getStyleClass().add("drive-delete-dialog");
        var body = new VBox(12, MessengerPane.driveDialogTitleBar(dialog, dialog.getTitle(),
                "M3 6h18 M8 6V4h8v2 M6 6l1 15h10l1-15", true),
                text(entry.getFileName().toString(), "drive-item-dialog-name"),
                text(folder ? "This folder and its contents will be permanently deleted from this device."
                        : "This file will be permanently deleted from this device.", "drive-item-dialog-message"));
        body.getStyleClass().add("drive-item-dialog-body");
        if (recipients > 0) {
            var icon = new SVGPath();
            icon.setContent("M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2 M9 11a4 4 0 1 0 0-8a4 4 0 0 0 0 8 M22 21v-2a4 4 0 0 0-3-3.9 M16 3.1a4 4 0 0 1 0 7.8");
            icon.getStyleClass().add("drive-shared-warning-glyph");
            var warning = new VBox(5,
                    text("Shared with " + recipients + (recipients == 1 ? " person" : " people"), "drive-shared-warning-title"),
                    text("People with access will no longer be able to download this item. Offline contacts will update when they reconnect.", "drive-item-dialog-message"),
                    text("Copies already downloaded on other devices are kept.", "drive-property-key"));
            var warningRow = new HBox(12, new StackPane(icon), warning);
            warningRow.setAlignment(Pos.TOP_LEFT); warningRow.getStyleClass().add("drive-shared-delete-warning");
            HBox.setHgrow(warning, Priority.ALWAYS);
            body.getChildren().add(warningRow);
        }
        dialog.getDialogPane().setContent(body);
        dialog.getDialogPane().getButtonTypes().addAll(DELETE, ButtonType.CANCEL);
        var delete = (Button) dialog.getDialogPane().lookupButton(DELETE);
        delete.getStyleClass().add("drive-dialog-danger"); delete.setDefaultButton(false);
        ((Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL)).setDefaultButton(true);
        return dialog;
    }

    private static Label text(String value, String style) {
        var label = new Label(value == null ? "—" : value);
        label.getStyleClass().add(style); label.setWrapText(true); label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    private static Dialog<ButtonType> dialog(Window owner, String title) {
        var dialog = new Dialog<ButtonType>();
        dialog.setTitle(title); if (owner != null) dialog.initOwner(owner);
        dialog.initStyle(StageStyle.TRANSPARENT);
        var pane = dialog.getDialogPane();
        pane.getStyleClass().addAll("drive-notice-dialog", "drive-item-dialog");
        pane.getStylesheets().add(DriveItemDialogs.class.getResource("/kakha/kudava/onionp2p/app.css").toExternalForm());
        pane.setPrefWidth(460);
        dialog.setOnShown(event -> pane.getScene().setFill(Color.TRANSPARENT));
        return dialog;
    }
}
