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
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ImportsPaneTest {
    @TempDir Path temp;
    private static void fx(Runnable work) throws Exception {
        var started = new CompletableFuture<Void>();
        try { Platform.startup(() -> started.complete(null)); } catch (IllegalStateException running) { started.complete(null); }
        started.get(5, TimeUnit.SECONDS);
        var done = new CompletableFuture<Void>();
        Platform.runLater(() -> { try { work.run(); done.complete(null); } catch (Throwable error) { done.completeExceptionally(error); } });
        done.get(5, TimeUnit.SECONDS);
    }
    @Test @Timeout(20)
    void cancelButtonShowsCancelledAndUpdatesTheCountWithoutInterruptingOtherImports() throws Exception {
        var release = new CountDownLatch(1); var blocked = new CountDownLatch(1);
        try (var queue = new ImportQueue(new DriveChecksums(temp.resolve("profile")))) {
            // Hold the worker ahead of the imports so the UI test can reliably click a queued item.
            var field = ImportQueue.class.getDeclaredField("worker"); field.setAccessible(true);
            ((ExecutorService) field.get(queue)).execute(() -> {
                blocked.countDown(); try { release.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            });
            assertTrue(blocked.await(5, TimeUnit.SECONDS));
            var completed = new AtomicInteger(); ImportsPane[] pane = new ImportsPane[1];
            fx(() -> {
                pane[0] = new ImportsPane(queue, completed::incrementAndGet);
                var scene = new Scene(new StackPane(pane[0]), 420, 360);
                scene.getStylesheets().add(getClass().getResource("/kakha/kudava/onionp2p/app.css").toExternalForm());
            });
            Path cancelled = Files.writeString(temp.resolve("cancel.txt"), "original"), next = Files.writeString(temp.resolve("next.txt"), "next");
            queue.add(List.of(cancelled, next), temp.resolve("drive"));
            fx(() -> {
                pane[0].applyCss(); pane[0].layout(); assertEquals(2, pane[0].activeCountProperty().get());
                var row = row(pane[0], "cancel.txt"); var button = (Button) row.lookup(".download-icon-button");
                assertEquals("Cancel import", button.getAccessibleText()); assertNotNull(button.getGraphic()); button.fire();
            });
            fx(() -> {
                assertEquals(1, pane[0].activeCountProperty().get()); assertEquals(0, completed.get());
                var row = row(pane[0], "cancel.txt");
                assertTrue(row.lookupAll(".label").stream().map(node -> (Label) node).anyMatch(label -> "Cancelled".equals(label.getText()) && label.getGraphic() != null));
                var dismiss = (Button) row.lookup(".download-icon-button");
                assertTrue(dismiss.isVisible()); assertEquals("Dismiss import", dismiss.getAccessibleText()); dismiss.fire();
            });
            fx(() -> assertTrue(pane[0].lookupAll(".download-name").stream().noneMatch(node -> "cancel.txt".equals(((Label) node).getText()))));
            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (queue.items().stream().anyMatch(ImportQueue.Item::active) && System.nanoTime() < deadline) Thread.sleep(10);
            fx(() -> { assertEquals(0, pane[0].activeCountProperty().get()); assertEquals(1, completed.get()); });
            assertEquals(1, queue.items().size());
            assertEquals(ImportQueue.State.COMPLETE, queue.items().getLast().state());
            assertFalse(Files.exists(temp.resolve("drive/cancel.txt"))); assertEquals("original", Files.readString(cancelled));
        } finally { release.countDown(); }
    }
    private static VBox row(ImportsPane pane, String name) {
        return pane.lookupAll(".download-row").stream().map(node -> (VBox) node).filter(node -> node.lookupAll(".download-name").stream()
                .anyMatch(label -> name.equals(((Label) label).getText()))).findFirst().orElseThrow();
    }
}
