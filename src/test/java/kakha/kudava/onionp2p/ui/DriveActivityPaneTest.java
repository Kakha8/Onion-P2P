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
                pane[0].select(DriveActivityPane.Filter.ADDING);
                assertEquals(1, pane[0].lookupAll(".activity-section").stream().filter(javafx.scene.Node::isManaged).count());
                assertTrue(pane[0].lookupAll(".label").stream().anyMatch(node -> "Added.txt".equals(((Label) node).getText())));
                pane[0].select(DriveActivityPane.Filter.DOWNLOADING);
                var cancel = pane[0].lookupAll(".button").stream().filter(node -> "Cancel download".equals(node.getAccessibleText())).findFirst().orElseThrow();
                ((Button) cancel).fire();
                pane[0].select(DriveActivityPane.Filter.UPLOADING);
                var stop = pane[0].lookupAll(".button").stream().filter(node -> "Cancel upload".equals(node.getAccessibleText())).findFirst().orElseThrow();
                ((Button) stop).fire();
                pane[0].select(DriveActivityPane.Filter.ALL);
                root[0].applyCss(); root[0].layout();
                assertTrue(pane[0].getHeight() <= 450);
            });
            fx(() -> {
                assertEquals(0, pane[0].activeCountProperty().get());
                assertEquals(DownloadQueue.State.CANCELLED, downloads.items().getFirst().state());
                assertEquals(UploadTracker.State.CANCELLED_BY_SENDER, uploads.items().getFirst().state());
                root[0].applyCss(); root[0].layout();
                snapshot(root[0], "");
            });
        }
    }
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
