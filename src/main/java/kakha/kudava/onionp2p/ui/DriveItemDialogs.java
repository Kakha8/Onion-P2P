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
            grid.add(key, 0, row); grid.add(value, 1, row++);
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
