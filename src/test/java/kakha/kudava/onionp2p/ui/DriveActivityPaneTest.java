package kakha.kudava.onionp2p.ui;

import kakha.kudava.onionp2p.drive.*;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class DriveActivityPaneTest {
    @TempDir Path temp;
    private static void fx(Runnable action) throws Exception {
        var started = new CompletableFuture<Void>();
        try { Platform.startup(() -> started.complete(null)); } catch (IllegalStateException running) { started.complete(null); }
        started.get(5, TimeUnit.SECONDS);
        var done = new CompletableFuture<Void>();
        Platform.runLater(() -> { try { action.run(); done.complete(null); } catch (Throwable e) { done.completeExceptionally(e); } });
        done.get(5, TimeUnit.SECONDS);
    }
    @Test @Timeout(25)
    void filtersReuseLiveRowsAndKeepTransferControlsWorking() throws Exception {
        try (var imports = new ImportQueue(new DriveChecksums(temp.resolve("profile")));
             var downloads = new DownloadQueue(owner -> false, 10_000); var uploads = new UploadTracker()) {
            DriveActivityPane[] pane = new DriveActivityPane[1]; StackPane[] root = new StackPane[1];
            fx(() -> {
                pane[0] = new DriveActivityPane(new ImportsPane(imports, () -> {}), new UploadsPane(uploads, link -> "Alice", uploads::cancelBySender), new DownloadsPane(downloads, () -> {}));
                root[0] = new StackPane(pane[0]); var scene = new Scene(root[0], 460, 450);
                StackPane.setAlignment(pane[0], javafx.geometry.Pos.BOTTOM_LEFT);
                scene.getStylesheets().add(getClass().getResource("/kakha/kudava/onionp2p/app.css").toExternalForm());
                pane[0].reveal();
            });
            Path source = Files.writeString(temp.resolve("Added.txt"), "abc");
            imports.add(java.util.List.of(source), temp.resolve("drive"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (imports.items().stream().anyMatch(ImportQueue.Item::active) && System.nanoTime() < deadline) Thread.sleep(10);
            fx(() -> {
                root[0].applyCss(); root[0].layout();
                root[0].requestLayout(); root[0].layout();
                assertEquals(1, pane[0].lookupAll(".progress-bar").size(), "One import uses one progress bar");
                var list = (ScrollPane) pane[0].lookup(".activity-list");
                var content = (javafx.scene.layout.VBox) list.getContent();
                assertTrue(list.getViewportBounds().getHeight() >= content.prefHeight(list.getViewportBounds().getWidth()) - 2,
                        "A single complete entry must fit without scrolling");
                assertTrue(pane[0].getHeight() < 280, "One entry should keep the pane compact");
                snapshot(root[0], "-single");
            });
            downloads.add("one", "offline", "Bob", "Waiting.zip", 1000, progress -> Path.of("unused"));
            uploads.begin("alice", "share", "", "Sending.zip", 1000, 0, new Object());
            fx(() -> {
                root[0].applyCss(); root[0].layout();
                assertEquals(2, pane[0].activeCountProperty().get());
                assertEquals(4, pane[0].lookupAll(".activity-filter").size());
                assertNull(tab(pane[0], DriveActivityPane.Filter.ALL).getGraphic());
                assertFalse(badge(pane[0], DriveActivityPane.Filter.ADDING).isVisible(), "Completed imports have no active badge");
                assertEquals("1", badge(pane[0], DriveActivityPane.Filter.UPLOADING).getText());
                assertTrue(badge(pane[0], DriveActivityPane.Filter.UPLOADING).isVisible());
                assertEquals("1", badge(pane[0], DriveActivityPane.Filter.DOWNLOADING).getText());
                assertTrue(badge(pane[0], DriveActivityPane.Filter.DOWNLOADING).isVisible());
                snapshot(root[0], "-badges");
            });
            String anotherUpload = uploads.begin("alice", "another-share", "", "Other.zip", 1000, 0, new Object());
            fx(() -> assertEquals("2", badge(pane[0], DriveActivityPane.Filter.UPLOADING).getText(), "Count upload entries, even for the same recipient"));
            uploads.acknowledged(anotherUpload, 1000);
            fx(() -> {
                assertEquals("1", badge(pane[0], DriveActivityPane.Filter.UPLOADING).getText());
                pane[0].select(DriveActivityPane.Filter.ADDING);
                assertEquals(1, pane[0].lookupAll(".activity-section").stream().filter(javafx.scene.Node::isManaged).count());
                assertTrue(pane[0].lookupAll(".label").stream().anyMatch(node -> "Added.txt".equals(((Label) node).getText())));
                pane[0].select(DriveActivityPane.Filter.DOWNLOADING);
                assertTrue(pane[0].lookup(".activity-clear-button").isDisabled(), "A view with only an active download must not be cleared");
                assertFalse(badge(pane[0], DriveActivityPane.Filter.DOWNLOADING).isVisible(), "Selected tabs have no badge");
                assertFalse(badge(pane[0], DriveActivityPane.Filter.DOWNLOADING).isManaged());
                assertTrue(badge(pane[0], DriveActivityPane.Filter.UPLOADING).isVisible());
                var cancel = pane[0].lookupAll(".button").stream().filter(node -> "Cancel download".equals(node.getAccessibleText())).findFirst().orElseThrow();
                ((Button) cancel).fire();
                pane[0].select(DriveActivityPane.Filter.UPLOADING);
                assertFalse(badge(pane[0], DriveActivityPane.Filter.UPLOADING).isVisible());
                var stop = pane[0].lookupAll(".button").stream().filter(node -> node.isVisible() && node.isManaged()
                        && "Cancel upload".equals(node.getAccessibleText())).findFirst().orElseThrow();
                ((Button) stop).fire();
                pane[0].select(DriveActivityPane.Filter.ALL);
                root[0].applyCss(); root[0].layout();
                assertTrue(pane[0].getHeight() <= 450);
            });
            fx(() -> {
                assertEquals(0, pane[0].activeCountProperty().get());
                assertFalse(badge(pane[0], DriveActivityPane.Filter.UPLOADING).isVisible());
                assertFalse(badge(pane[0], DriveActivityPane.Filter.DOWNLOADING).isVisible());
                assertEquals(DownloadQueue.State.CANCELLED, downloads.items().getFirst().state());
                assertEquals(UploadTracker.State.CANCELLED_BY_SENDER, uploads.items().getFirst().state());
                root[0].applyCss(); root[0].layout();
                snapshot(root[0], "");
                var dismiss = pane[0].lookupAll(".button").stream().filter(node -> "Dismiss import".equals(node.getAccessibleText())).findFirst().orElseThrow();
                ((Button) dismiss).fire();
            });
            fx(() -> {
                assertTrue(imports.items().isEmpty()); assertEquals("abc", read(temp.resolve("drive/Added.txt")));
                assertEquals("abc", read(source));
                pane[0].select(DriveActivityPane.Filter.UPLOADING);
                var clear = (Button) pane[0].lookup(".activity-clear-button"); assertFalse(clear.isDisabled()); clear.fire();
            });
            fx(() -> {
                assertTrue(uploads.items().isEmpty()); assertEquals(1, downloads.items().size());
                var clear = (Button) pane[0].lookup(".activity-clear-button"); assertTrue(clear.isDisabled());
                pane[0].select(DriveActivityPane.Filter.ALL); assertFalse(clear.isDisabled()); clear.fire();
            });
            fx(() -> {
                assertTrue(downloads.items().isEmpty());
                assertTrue(pane[0].lookup(".activity-clear-button").isDisabled());
                assertTrue(pane[0].lookupAll(".download-row").isEmpty());
                assertEquals("abc", read(temp.resolve("drive/Added.txt")));
                root[0].applyCss(); root[0].layout(); snapshot(root[0], "-cleared");
            });
        }
    }
    private static String read(Path path) { try { return Files.readString(path); } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); } }
    private static ToggleButton tab(DriveActivityPane pane, DriveActivityPane.Filter filter) {
        return pane.lookupAll(".activity-filter").stream().map(node -> (ToggleButton) node)
                .filter(button -> button.getUserData() == filter).findFirst().orElseThrow();
    }
    private static Label badge(DriveActivityPane pane, DriveActivityPane.Filter filter) { return (Label) tab(pane, filter).lookup(".activity-count-badge"); }
    private static void snapshot(StackPane root, String suffix) {
        String snapshot = System.getProperty("onion.activitySnapshot");
        if (snapshot != null) {
            var image = root.snapshot(null, null);
            var bitmap = new java.awt.image.BufferedImage((int) image.getWidth(), (int) image.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < bitmap.getHeight(); y++) for (int x = 0; x < bitmap.getWidth(); x++) bitmap.setRGB(x, y, image.getPixelReader().getArgb(x, y));
            try { javax.imageio.ImageIO.write(bitmap, "png", Path.of(snapshot + suffix + ".png").toFile()); } catch (java.io.IOException e) { throw new RuntimeException(e); }
        }
    }
}
