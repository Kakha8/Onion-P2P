package kakha.kudava.onionp2p.ui;

import kakha.kudava.onionp2p.contacts.ContactManager;
import kakha.kudava.onionp2p.drive.SharedDriveStore;
import kakha.kudava.onionp2p.tor.OnionServer;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SharedDriveActionsTest {
    @TempDir Path temp;

    private static void fx(Runnable action) throws Exception {
        var started = new CompletableFuture<Void>();
        try { Platform.startup(() -> started.complete(null)); }
        catch (IllegalStateException running) { started.complete(null); }
        started.get(5, TimeUnit.SECONDS);
        var done = new CompletableFuture<Void>();
        Platform.runLater(() -> {
            try { action.run(); done.complete(null); }
            catch (Throwable error) { done.completeExceptionally(error); }
        });
        done.get(5, TimeUnit.SECONDS);
    }

    @Test @Timeout(20)
    void sharedRowsHaveOnlyAMoreButtonAndDownloadAndPropertiesAreInTheMenu() throws Exception {
        String previous = System.getProperty("onion.dataDir");
        System.setProperty("onion.dataDir", temp.toString());
        try (var tor = new OnionServer(temp); var manager = new ContactManager(temp, tor, ignored -> {})) {
            fx(() -> {
                var pane = new MessengerPane(manager, "Alice", () -> {});
                var file = new SharedDriveStore.SharedFile(UUID.randomUUID().toString(), "test-contact", "Report.pdf", null, 260300, 0);
                var menu = pane.sharedDriveEntryMenu(file, "Bob");
                assertEquals(List.of("Download", "Download to…", "Properties"), menu.getItems().stream()
                        .filter(item -> !(item instanceof SeparatorMenuItem)).map(MenuItem::getText).toList());
                assertTrue(menu.getItems().stream().filter(item -> !(item instanceof SeparatorMenuItem)).allMatch(item -> item.getGraphic() != null));
                var paneRoot = new StackPane(pane);
                var paneScene = new Scene(paneRoot, 860, 600);
                paneScene.getStylesheets().add(getClass().getResource("/kakha/kudava/onionp2p/app.css").toExternalForm());
                paneRoot.applyCss(); paneRoot.layout();
                try {
                    var refresh = MessengerPane.class.getDeclaredField("refreshSharedDrive");
                    refresh.setAccessible(true);
                    ((Runnable) refresh.get(pane)).run();
                    assertEquals("My files", ((Label) pane.lookup(".drive-page-title")).getText(),
                            "Share updates must not switch the owner out of My files");
                    var rowFactory = MessengerPane.class.getDeclaredMethod("sharedDriveEntryRow", SharedDriveStore.SharedFile.class, String.class);
                    rowFactory.setAccessible(true);
                    var row = (HBox) rowFactory.invoke(pane, file, "Bob");
                    var root = new StackPane(row); var scene = new Scene(root, 760, 70);
                    scene.getStylesheets().add(getClass().getResource("/kakha/kudava/onionp2p/app.css").toExternalForm());
                    root.applyCss(); root.layout();
                    assertEquals(1, row.lookupAll(".button").size());
                    assertNotNull(row.lookup(".drive-entry-more"));
                    assertNull(row.lookup(".drive-row-action"));
                    menu.getItems().getFirst().fire();
                    assertEquals(1, manager.downloads().queue().items().size(), "Download menu must enqueue the transfer");
                    assertEquals("Report.pdf", manager.downloads().queue().items().getFirst().name());
                    var folder = new SharedDriveStore.SharedFile(UUID.randomUUID().toString(), "test-contact", "Documents", null, -1, 0);
                    assertEquals(List.of("Open folder", "Download", "Download to…", "Properties"), pane.sharedDriveEntryMenu(folder, "Bob").getItems().stream()
                            .filter(item -> !(item instanceof SeparatorMenuItem)).map(MenuItem::getText).toList());
                    Path chosen = temp.resolve("Chosen downloads");
                    var downloadTo = MessengerPane.class.getDeclaredMethod("downloadSharedDriveFile", SharedDriveStore.SharedFile.class, String.class, Path.class);
                    downloadTo.setAccessible(true); downloadTo.invoke(pane, file, "Bob", chosen);
                    assertEquals(2, manager.downloads().queue().items().size(), "A custom destination must not reuse a request to a different folder");
                    manager.downloads().file(file, "Bob", chosen.resolve("sub/.."));
                    assertEquals(2, manager.downloads().queue().items().size(), "The same normalized destination should reuse its active request");
                    try (var journals = Files.list(temp.resolve("download-state/queue"))) {
                        assertTrue(journals.filter(path -> path.getFileName().toString().endsWith(".properties")).anyMatch(path -> {
                            try {
                                var saved = new Properties();
                                try (var input = Files.newInputStream(path)) { saved.load(input); }
                                return chosen.toAbsolutePath().toString().equals(saved.getProperty("request.saveIn"))
                                        && "true".equals(saved.getProperty("request.rootFile"));
                            } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
                        }), "The chosen folder must be saved for resume after restarting");
                    }
                } catch (ReflectiveOperationException | java.io.IOException error) { throw new RuntimeException(error); }
                finally { paneRoot.getChildren().clear(); }
            });
        } finally {
            if (previous == null) System.clearProperty("onion.dataDir"); else System.setProperty("onion.dataDir", previous);
        }
    }

    @Test @Timeout(15)
    void sharedDeleteWarningAndPropertiesUseTransparentStyledDialogs() throws Exception {
        Path file = Files.writeString(temp.resolve("Quarterly report.pdf"), "test");
        fx(() -> {
            var deletion = DriveItemDialogs.delete(null, file, 2);
            assertTrue(deletion.getDialogPane().getStyleClass().contains("drive-item-dialog"));
            assertNull(deletion.getDialogPane().getHeaderText());
            assertTrue(labels(deletion).contains("Shared with 2 people"));
            assertTrue(labels(deletion).stream().anyMatch(text -> text.contains("Offline contacts will update")));
            assertNotNull(deletion.getDialogPane().lookup(".drive-dialog-close"));
            assertFalse(((Button) deletion.getDialogPane().lookupButton(DriveItemDialogs.DELETE)).isDefaultButton());
            render(deletion, "shared-delete");
            var unshared = DriveItemDialogs.delete(null, file, 0);
            assertTrue(labels(unshared).stream().noneMatch(text -> text.startsWith("Shared with")));
            var fields = new LinkedHashMap<String, String>();
            fields.put("Type", "PDF"); fields.put("Size", "254.2 KiB"); fields.put("Shared by", "bobgun");
            fields.put("Access", "Read only"); fields.put("Status", "Available to download");
            fields.put("SHA-256", "Calculating…");
            var properties = DriveItemDialogs.properties(null, file.getFileName().toString(), fields);
            String checksum = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
            DriveItemDialogs.updateProperty(properties, "SHA-256", checksum);
            assertTrue(labels(properties).stream().map(text -> text.replace("\n", "")).anyMatch(checksum::equals));
            assertNull(properties.getDialogPane().getHeaderText());
            assertTrue(labels(properties).containsAll(List.of("Shared by", "bobgun", "Read only")));
            render(properties, "properties");
        });
    }

    private static List<String> labels(Dialog<?> dialog) {
        return dialog.getDialogPane().getContent().lookupAll(".label").stream().map(node -> ((Label) node).getText()).toList();
    }

    @Test @Timeout(15)
    void checksumIsFullyVisibleAndCopyIsEnabledOnlyForACompleteHash() throws Exception {
        fx(() -> {
            String hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
            var properties = DriveItemDialogs.properties(null, "File.exe", Map.of("SHA-256", "Calculating…"));
            var copy = (Button) properties.getDialogPane().lookup(".drive-checksum-copy");
            assertNotNull(copy); assertNotNull(copy.getGraphic()); assertTrue(copy.isDisabled());
            DriveItemDialogs.updateProperty(properties, "SHA-256", hash);
            assertFalse(copy.isDisabled());
            render(properties, "checksum-copy");
            var value = (Label) properties.getDialogPane().lookup(".drive-checksum-value");
            assertEquals(hash.substring(0, 32) + "\n" + hash.substring(32), value.getText());
            assertTrue(value.getWidth() >= value.prefWidth(-1) - 1, "Both complete checksum lines must fit");
            assertTrue(value.getHeight() >= value.prefHeight(value.getWidth()) - 1, "Checksum must not be cut off vertically");
            DriveItemDialogs.updateProperty(properties, "SHA-256", "Unavailable");
            assertTrue(copy.isDisabled());
        });
    }

    private void render(Dialog<?> dialog, String name) {
        var root = new StackPane(dialog.getDialogPane());
        root.setStyle("-fx-background-color: transparent;");
        var scene = new Scene(root, 460, 420, javafx.scene.paint.Color.TRANSPARENT);
        scene.getStylesheets().add(getClass().getResource("/kakha/kudava/onionp2p/app.css").toExternalForm());
        root.applyCss(); root.resize(460, dialog.getDialogPane().prefHeight(460)); root.layout();
        var close = dialog.getDialogPane().lookup(".drive-dialog-close");
        assertNotNull(close);
        String prefix = System.getProperty("onion.testSnapshot");
        if (prefix == null) return;
        var image = root.snapshot(null, null);
        var bitmap = new java.awt.image.BufferedImage((int) image.getWidth(), (int) image.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < bitmap.getHeight(); y++) for (int x = 0; x < bitmap.getWidth(); x++)
            bitmap.setRGB(x, y, image.getPixelReader().getArgb(x, y));
        try { javax.imageio.ImageIO.write(bitmap, "png", new java.io.File(prefix + "-" + name + ".png")); }
        catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
    }
}
