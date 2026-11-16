package kakha.kudava.onionp2p.ui;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ShareDialogTitleBarTest {
    @Test @Timeout(15)
    void closeIconFitsInsideShareDialog() throws Exception {
        var started = new CompletableFuture<Void>();
        try { Platform.startup(() -> started.complete(null)); }
        catch (IllegalStateException alreadyRunning) { started.complete(null); }
        started.get(5, TimeUnit.SECONDS);
        var checked = new CompletableFuture<Void>();
        Platform.runLater(() -> {
            try {
                var dialog = new Dialog<Void>();
                var pane = dialog.getDialogPane();
                pane.getStyleClass().add("drive-share-dialog");
                pane.getButtonTypes().add(ButtonType.CANCEL);
                var bar = MessengerPane.driveDialogTitleBar(dialog, "Share file", "M3 12h18", false);
                pane.setContent(new VBox(bar));
                pane.setPrefWidth(460);
                var root = new StackPane(pane);
                var scene = new Scene(root, 460, 180);
                scene.getStylesheets().add(getClass().getResource("/kakha/kudava/onionp2p/app.css").toExternalForm());
                root.applyCss(); root.layout();
                var close = (Button) bar.lookup(".drive-dialog-close");
                assertNotNull(close);
                assertNotNull(close.getGraphic());
                assertTrue(close.getWidth() >= 27 && close.getWidth() <= 29);
                assertTrue(close.getBoundsInParent().getMaxX() <= bar.getWidth() + 1);
                assertTrue(close.getBoundsInParent().getMinX() >= 0);
                checked.complete(null);
            } catch (Throwable error) { checked.completeExceptionally(error); }
        });
        checked.get(5, TimeUnit.SECONDS);
    }
}
