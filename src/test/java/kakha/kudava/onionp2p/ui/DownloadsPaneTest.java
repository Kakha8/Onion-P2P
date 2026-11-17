package kakha.kudava.onionp2p.ui;

import kakha.kudava.onionp2p.drive.DownloadQueue;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.nio.file.Path;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class DownloadsPaneTest {
    private static void fx(Runnable work) throws Exception {
        var result = new CompletableFuture<Void>();
        Platform.runLater(() -> { try { work.run(); result.complete(null); } catch (Throwable error) { result.completeExceptionally(error); } });
        result.get(5, TimeUnit.SECONDS);
    }
    @Test @Timeout(20)
    void showsWaitingAndLiveProgressWithoutRefreshing() throws Exception {
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var toolkit = new CompletableFuture<Void>();
        try { Platform.startup(() -> toolkit.complete(null)); }
        catch (IllegalStateException running) { toolkit.complete(null); }
        toolkit.get(5, TimeUnit.SECONDS);
        try (var queue = new DownloadQueue(owner -> owner.equals("online"), 10000)) {
            DownloadsPane[] pane = new DownloadsPane[1]; StackPane[] root = new StackPane[1];
            fx(() -> {
                pane[0] = new DownloadsPane(queue, () -> {});
                root[0] = new StackPane(pane[0]); StackPane.setAlignment(pane[0], Pos.BOTTOM_RIGHT);
                StackPane.setMargin(pane[0], new Insets(16));
                Scene scene = new Scene(root[0], 460, 390);
                scene.getStylesheets().add(getClass().getResource("/kakha/kudava/onionp2p/app.css").toExternalForm());
                pane[0].reveal();
            });
            queue.add("wait", "offline", "Alice", "Documents", -1, progress -> Path.of("unused"));
            queue.add("moving", "online", "Bob", "Archive.zip", 100000000, progress -> {
                progress.update(42000000, 100000000); started.countDown(); release.await(5, TimeUnit.SECONDS); return Path.of("saved.zip");
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            fx(() -> {
                root[0].applyCss(); root[0].layout();
                var labels = pane[0].lookupAll(".label").stream().map(node -> ((Label) node).getText()).toList();
                assertTrue(labels.stream().anyMatch(text -> text.contains("Waiting for Alice")));
                assertTrue(labels.stream().anyMatch(text -> text.contains("42%")));
                assertTrue(labels.stream().anyMatch(text -> text.contains("/s") && (text.contains("left") || text.contains("Estimating"))));
                assertTrue(pane[0].getLayoutX() + pane[0].getWidth() <= 460);
                assertTrue(pane[0].getLayoutY() + pane[0].getHeight() <= 390);
                String snapshot = System.getProperty("onion.downloadSnapshot");
                if (snapshot != null) {
                    var image = root[0].snapshot(null, null);
                    var pixels = new java.awt.image.BufferedImage((int) image.getWidth(), (int) image.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
                    for (int y = 0; y < pixels.getHeight(); y++) for (int x = 0; x < pixels.getWidth(); x++) pixels.setRGB(x, y, image.getPixelReader().getArgb(x, y));
                    try { javax.imageio.ImageIO.write(pixels, "png", Path.of(snapshot).toFile()); }
                    catch (java.io.IOException error) { throw new RuntimeException(error); }
                }
            });
            String moving = queue.items().stream().filter(item -> item.owner().equals("online")).findFirst().orElseThrow().id();
            queue.pause(moving);
            fx(() -> {
                assertTrue(pane[0].lookupAll(".button").stream()
                        .anyMatch(node -> "Resume download".equals(node.getAccessibleText())));
                assertTrue(pane[0].lookupAll(".label").stream()
                        .anyMatch(node -> "Paused".equals(((Label) node).getText())));
            });
            queue.cancel(moving);
            fx(() -> assertTrue(pane[0].lookupAll(".label").stream().map(node -> (Label) node)
                    .anyMatch(label -> "Cancelled".equals(label.getText()) && label.getGraphic() != null)));
            release.countDown();
        } finally { release.countDown(); }
    }
}
