package kakha.kudava.onionp2p.ui;

import kakha.kudava.onionp2p.drive.*;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class FileFailurePresentationTest {
    @TempDir Path temp;
    private static void fx(Runnable work) throws Exception {
        var started = new CompletableFuture<Void>();
        try { Platform.startup(() -> started.complete(null)); } catch (IllegalStateException running) { started.complete(null); }
        started.get(5, TimeUnit.SECONDS);
        var done = new CompletableFuture<Void>();
        Platform.runLater(() -> { try { work.run(); done.complete(null); } catch (Throwable error) { done.completeExceptionally(error); } });
        done.get(5, TimeUnit.SECONDS);
    }

    @Test @Timeout(20) void actionableFailuresWrapInsideTheExistingCompactActivityRows() throws Exception {
        try (var imports = new ImportQueue(new DriveChecksums(temp.resolve("profile")));
             var downloads = new DownloadQueue(owner -> true, 10000)) {
            VBox[] root = new VBox[1];
            fx(() -> {
                root[0] = new VBox(new ImportsPane(imports, () -> {}), new DownloadsPane(downloads, () -> {}).embedded());
                root[0].setStyle("-fx-background-color: #1b232e;");
                var scene = new Scene(root[0], 420, 530);
                scene.getStylesheets().add(getClass().getResource("/kakha/kudava/onionp2p/app.css").toExternalForm());
            });
            imports.add(List.of(temp.resolve("Original.txt")), temp.resolve("drive"));
            downloads.add("denied", "owner", "Owner", "Report.pdf", 100, progress -> {
                throw new AccessDeniedException(temp.resolve("downloads/Report.pdf").toString());
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((imports.items().getFirst().active() || downloads.items().getFirst().active()) && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(ImportQueue.State.FAILED, imports.items().getFirst().state());
            assertEquals(DownloadQueue.State.FAILED, downloads.items().getFirst().state());
            fx(() -> {
                root[0].applyCss(); root[0].layout();
                var details = root[0].lookupAll(".download-detail").stream().map(node -> (Label) node).toList();
                assertEquals(2, details.size());
                assertTrue(details.stream().anyMatch(label -> label.getText().contains("moved or deleted")));
                assertTrue(details.stream().anyMatch(label -> label.getText().contains("Download to…")));
                for (var label : details) {
                    assertTrue(label.isWrapText());
                    assertTrue(label.getWidth() < 420);
                    assertTrue(label.getHeight() + 1 >= label.prefHeight(label.getWidth()), "The full recovery instruction must fit");
                    assertFalse(label.getText().contains(temp.toString()));
                }
                assertTrue(root[0].lookupAll(".button").stream().anyMatch(node -> "Retry download".equals(node.getAccessibleText())));
                assertTrue(root[0].lookupAll(".label").stream().anyMatch(node -> "Not added".equals(((Label) node).getText())));
                assertTrue(root[0].lookupAll(".progress-bar").stream().noneMatch(node -> ((ProgressBar) node).getProgress() < 0), "Failed work must stop its progress animation");
                String output = System.getProperty("onion.failureSnapshot");
                if (output != null) {
                    var snapshot = root[0].snapshot(null, null);
                    var pixels = new java.awt.image.BufferedImage((int) snapshot.getWidth(), (int) snapshot.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
                    for (int y = 0; y < pixels.getHeight(); y++) for (int x = 0; x < pixels.getWidth(); x++) pixels.setRGB(x, y, snapshot.getPixelReader().getArgb(x, y));
                    try { javax.imageio.ImageIO.write(pixels, "png", Path.of(output).toFile()); }
                    catch (java.io.IOException error) { throw new RuntimeException(error); }
                }
            });
        }
    }
}
