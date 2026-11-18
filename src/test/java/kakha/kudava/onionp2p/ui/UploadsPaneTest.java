package kakha.kudava.onionp2p.ui;

import kakha.kudava.onionp2p.drive.UploadTracker;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class UploadsPaneTest {
    private static void fx(Runnable work) throws Exception {
        var result = new CompletableFuture<Void>();
        Platform.runLater(() -> { try { work.run(); result.complete(null); } catch (Throwable error) { result.completeExceptionally(error); } });
        result.get(5, TimeUnit.SECONDS);
    }
    @Test @Timeout(15)
    void showsRecipientProgressAndPausedStatusWithoutOpeningAutomatically() throws Exception {
        var toolkit = new CompletableFuture<Void>();
        try { Platform.startup(() -> toolkit.complete(null)); }
        catch (IllegalStateException running) { toolkit.complete(null); }
        toolkit.get(5, TimeUnit.SECONDS);
        try (var tracker = new UploadTracker()) {
            UploadsPane[] pane = new UploadsPane[1];
            fx(() -> {
                pane[0] = new UploadsPane(tracker, contact -> "Alice", tracker::cancelBySender);
                var scene = new Scene(new StackPane(pane[0]), 440, 360);
                scene.getStylesheets().add(getClass().getResource("/kakha/kudava/onionp2p/app.css").toExternalForm());
            });
            String key = tracker.begin("alice", "share", "file", "Documents.zip", 1000, 0, new Object());
            tracker.acknowledged(key, 400);
            fx(() -> {
                assertFalse(pane[0].isVisible()); assertEquals(1, pane[0].activeCountProperty().get());
                pane[0].reveal(); pane[0].applyCss(); pane[0].layout();
                var labels = pane[0].lookupAll(".label").stream().map(node -> ((Label) node).getText()).toList();
                assertTrue(labels.contains("To Alice")); assertTrue(labels.contains("Documents.zip"));
                assertTrue(labels.stream().anyMatch(text -> text.contains("400 B / 1.0 KB")));
                assertTrue(labels.stream().anyMatch(text -> text.contains("/s") && (text.contains("left") || text.contains("Estimating"))));
            });
            tracker.paused("alice", "share", "file");
            fx(() -> {
                assertEquals(0, pane[0].activeCountProperty().get());
                assertTrue(pane[0].lookupAll(".label").stream().anyMatch(node -> "Paused by recipient".equals(((Label) node).getText())));
            });
            fx(() -> {
                var button = pane[0].lookupAll(".button").stream().filter(node -> "Cancel upload".equals(node.getAccessibleText())).findFirst().orElseThrow();
                ((javafx.scene.control.Button) button).fire();
            });
            fx(() -> assertTrue(pane[0].lookupAll(".label").stream()
                    .anyMatch(node -> "Cancelled by you".equals(((Label) node).getText()))));
            tracker.cancelled("alice", "share", "file", false);
            fx(() -> {
                assertEquals(0, pane[0].activeCountProperty().get());
                assertTrue(pane[0].lookupAll(".label").stream().anyMatch(node -> "Cancelled by recipient".equals(((Label) node).getText())));
                assertTrue(pane[0].lookupAll(".label").stream()
                        .map(node -> (Label) node).anyMatch(label -> "Cancelled by recipient".equals(label.getText()) && label.getGraphic() != null));
            });
            tracker.begin("one", "share", "file", "one.bin", 1000, 0, new Object());
            tracker.begin("two", "share", "file", "two.bin", 1000, 0, new Object());
            tracker.begin("three", "share", "file", "three.bin", 1000, 0, new Object());
            tracker.begin("four", "share", "file", "four.bin", 1000, 0, new Object());
            javafx.scene.Node[] queueIcon = new javafx.scene.Node[1];
            fx(() -> {
                assertEquals(3, pane[0].activeCountProperty().get());
                var labels = pane[0].lookupAll(".label").stream().map(node -> (Label) node).toList();
                assertTrue(labels.stream().anyMatch(label -> label.isVisible() && label.getText().contains("3-recipient limit reached")));
                assertTrue(labels.stream().anyMatch(label -> label.getText().equals("Queued · position 1") && label.getGraphic() != null));
                queueIcon[0] = labels.stream().filter(label -> label.getText().equals("Queued · position 1")).findFirst().orElseThrow().getGraphic();
            });
            tracker.refresh();
            fx(() -> {
                var status = pane[0].lookupAll(".label").stream().map(node -> (Label) node)
                        .filter(label -> label.getText().equals("Queued · position 1")).findFirst().orElseThrow();
                assertSame(queueIcon[0], status.getGraphic(), "A routine refresh must reuse the queue icon");
            });
        }
    }
}
