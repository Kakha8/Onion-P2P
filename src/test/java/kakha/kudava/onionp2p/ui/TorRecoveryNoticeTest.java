package kakha.kudava.onionp2p.ui;

import kakha.kudava.onionp2p.contacts.ContactManager;
import kakha.kudava.onionp2p.tor.OnionServer;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class TorRecoveryNoticeTest {
    @TempDir Path temp;

    private static void fx(Runnable action) throws Exception {
        var result = new CompletableFuture<Void>();
        Platform.runLater(() -> {
            try { action.run(); result.complete(null); }
            catch (Throwable error) { result.completeExceptionally(error); }
        });
        result.get(5, TimeUnit.SECONDS);
    }

    @Test @Timeout(20)
    void recoveryIsGlobalStableAndClearsWithoutLeavingAnEmptyStrip() throws Exception {
        var toolkit = new CompletableFuture<Void>();
        try { Platform.startup(() -> toolkit.complete(null)); }
        catch (IllegalStateException running) { toolkit.complete(null); }
        toolkit.get(5, TimeUnit.SECONDS);
        String previous = System.getProperty("onion.dataDir");
        System.setProperty("onion.dataDir", temp.toString());
        try (var tor = new OnionServer(temp); var manager = new ContactManager(temp, tor, ignored -> {})) {
            fx(() -> {
                var pane = new MessengerPane(manager, "Alice", () -> {});
                var root = new StackPane(pane);
                var scene = new Scene(root, 860, 600);
                scene.getStylesheets().add(getClass().getResource("/kakha/kudava/onionp2p/app.css").toExternalForm());
                try {
                    root.applyCss(); root.layout();
                    var notice = (TorRecoveryNotice) pane.lookup(".tor-recovery-notice");
                    double normalTop = pane.getCenter().getLayoutY();
                    assertFalse(notice.isVisible()); assertFalse(notice.isManaged());
                    var changes = new AtomicInteger();
                    notice.visibleProperty().addListener((obs, old, value) -> changes.incrementAndGet());
                    pane.setTorRoutingRecovery(true);
                    root.applyCss(); root.layout();
                    assertTrue(notice.isVisible()); assertTrue(notice.isManaged());
                    assertTrue(notice.getHeight() > 0 && notice.getHeight() <= 44, "Notice should remain compact");
                    assertTrue(pane.getCenter().getLayoutY() > normalTop);
                    assertEquals(TorRecoveryNotice.MESSAGE, notice.getAccessibleText());
                    assertTrue(notice.lookupAll(".label").stream().map(node -> ((Label) node).getText())
                            .anyMatch("Trying another route…"::equals));
                    snapshot(root, "chats");
                    pane.setTorRoutingRecovery(true);
                    assertEquals(1, changes.get(), "Repeated events must not flicker the notice");
                    var drive = (Button) pane.lookupAll(".rail-button").stream()
                            .filter(node -> "Drive".equals(node.getAccessibleText())).findFirst().orElseThrow();
                    drive.fire(); root.applyCss(); root.layout();
                    assertTrue(notice.isVisible(), "Recovery must stay visible when switching to Drive");
                    snapshot(root, "drive");
                    pane.setTorRoutingRecovery(false); root.applyCss(); root.layout();
                    assertFalse(notice.isVisible()); assertFalse(notice.isManaged());
                    assertEquals(normalTop, pane.getCenter().getLayoutY(), .01);
                    assertEquals(2, changes.get());
                } finally { root.getChildren().clear(); }
            });
        } finally {
            if (previous == null) System.clearProperty("onion.dataDir"); else System.setProperty("onion.dataDir", previous);
        }
    }

    private static void snapshot(StackPane root, String view) {
        String prefix = System.getProperty("onion.testSnapshot");
        if (prefix == null) return;
        var image = root.snapshot(null, null);
        var bitmap = new java.awt.image.BufferedImage((int) image.getWidth(), (int) image.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < bitmap.getHeight(); y++) for (int x = 0; x < bitmap.getWidth(); x++)
            bitmap.setRGB(x, y, image.getPixelReader().getArgb(x, y));
        try { javax.imageio.ImageIO.write(bitmap, "png", new java.io.File(prefix + "-" + view + ".png")); }
        catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
    }
}
