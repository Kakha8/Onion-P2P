package kakha.kudava.onionp2p.ui;

import kakha.kudava.onionp2p.contacts.*;
import kakha.kudava.onionp2p.drive.SharedDriveStore;
import kakha.kudava.onionp2p.tor.OnionServer;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SharedDriveStatusTest {
    @TempDir Path temp;
    private static void fx(Runnable work) throws Exception {
        var done = new CompletableFuture<Void>();
        Platform.runLater(() -> { try { work.run(); done.complete(null); } catch (Throwable error) { done.completeExceptionally(error); } });
        done.get(5, TimeUnit.SECONDS);
    }

    @Test @Timeout(20)
    void statusIconsAndSharerPresenceUpdateWithoutRebuildingRows() throws Exception {
        var started = new CompletableFuture<Void>();
        try { Platform.startup(() -> started.complete(null)); } catch (IllegalStateException running) { started.complete(null); }
        started.get(5, TimeUnit.SECONDS);
        String previous = System.getProperty("onion.dataDir"); System.setProperty("onion.dataDir", temp.toString());
        String bob = ContactLink.loadOrCreate(temp.resolve("bob"), "Bob");
        Path downloaded = Files.writeString(temp.resolve("Saved.pdf"), "saved");
        var rows = new ArrayList<HBox>();
        MessengerPane[] pane = new MessengerPane[1];
        try (var tor = new OnionServer(temp); var manager = new ContactManager(temp, tor, ignored -> {})) {
            fx(() -> {
                pane[0] = new MessengerPane(manager, "Alice", () -> {});
                try {
                    var factory = MessengerPane.class.getDeclaredMethod("sharedDriveEntryRow", SharedDriveStore.SharedFile.class, String.class);
                    factory.setAccessible(true);
                    rows.add((HBox) factory.invoke(pane[0], new SharedDriveStore.SharedFile("one", bob, "Report.pdf", null, 260300, 0), "Bob"));
                    rows.add((HBox) factory.invoke(pane[0], new SharedDriveStore.SharedFile("two", bob, "Saved.pdf", downloaded.toString(), 5, 0), "Bob"));
                    var root = new VBox(rows.toArray(HBox[]::new));
                    var scene = new Scene(root, 820, 128);
                    scene.getStylesheets().add(getClass().getResource("/kakha/kudava/onionp2p/app.css").toExternalForm());
                    root.applyCss(); root.layout();
                    var available = (Label) rows.get(0).lookup(".drive-share-status");
                    var complete = (Label) rows.get(1).lookup(".drive-share-status");
                    assertNotNull(available.getGraphic()); assertNotNull(complete.getGraphic());
                    assertTrue(available.getText() == null || available.getText().isEmpty());
                    assertTrue(complete.getText() == null || complete.getText().isEmpty());
                    assertEquals("Available to download", available.getTooltip().getText());
                    assertEquals("Downloaded", complete.getTooltip().getText());
                    assertTrue(rows.get(0).lookup(".drive-owner-presence").getStyleClass().contains("checking"));
                    var online = MessengerPane.class.getDeclaredMethod("contactCameOnline", String.class);
                    online.setAccessible(true); online.invoke(pane[0], bob);
                } catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
            });
            var offline = new CompletableFuture<Void>();
            fx(() -> {
                for (HBox row : rows) {
                    var indicator = (Label) row.lookup(".drive-owner-presence");
                    assertTrue(indicator.getStyleClass().contains("online"));
                    assertEquals("Bob · Online", indicator.getTooltip().getText());
                }
                try {
                    // Force an ordinary presence recheck. No Tor or paired contact
                    // exists in this test, so the real probe reports unreachable.
                    var field = MessengerPane.class.getDeclaredField("presence"); field.setAccessible(true);
                    var type = Class.forName(MessengerPane.class.getName() + "$Presence");
                    var ctor = type.getDeclaredConstructor(boolean.class, long.class); ctor.setAccessible(true);
                    ((Map<String, Object>) field.get(pane[0])).put(bob, ctor.newInstance(true, 0L));
                    rows.getFirst().lookup(".drive-owner-presence").getStyleClass().addListener((javafx.collections.ListChangeListener<String>) change -> {
                        if (change.getList().contains("offline")) offline.complete(null);
                    });
                    var probe = MessengerPane.class.getDeclaredMethod("checkContactsPresence", List.class); probe.setAccessible(true);
                    probe.invoke(pane[0], List.of(new ContactStore.Contact(bob, "", "Bob", 0)));
                } catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
            });
            offline.get(5, TimeUnit.SECONDS);
            fx(() -> {
                for (HBox row : rows) {
                    var indicator = (Label) row.lookup(".drive-owner-presence");
                    assertTrue(indicator.getStyleClass().contains("offline"));
                    assertEquals("Bob · Offline or unreachable", indicator.getTooltip().getText());
                }
            });
        } finally {
            if (previous == null) System.clearProperty("onion.dataDir"); else System.setProperty("onion.dataDir", previous);
        }
    }
}
