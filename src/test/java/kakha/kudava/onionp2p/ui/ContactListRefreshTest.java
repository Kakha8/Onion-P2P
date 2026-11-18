package kakha.kudava.onionp2p.ui;

import kakha.kudava.onionp2p.contacts.*;
import kakha.kudava.onionp2p.tor.OnionServer;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.Scene;
import javafx.scene.control.ListView;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ContactListRefreshTest {
    @TempDir Path temp;
    private static void fx(Runnable work) throws Exception {
        var result = new CompletableFuture<Void>();
        Platform.runLater(() -> { try { work.run(); result.complete(null); } catch (Throwable error) { result.completeExceptionally(error); } });
        result.get(5, TimeUnit.SECONDS);
    }
    @Test @Timeout(20)
    void acknowledgedContactAppearsInTheExistingChatList() throws Exception {
        String previous = System.getProperty("onion.dataDir"); System.setProperty("onion.dataDir", temp.toString());
        var toolkit = new CompletableFuture<Void>();
        try { Platform.startup(() -> toolkit.complete(null)); } catch (IllegalStateException running) { toolkit.complete(null); }
        toolkit.get(5, TimeUnit.SECONDS);
        try (var tor = new OnionServer(temp); var manager = new ContactManager(temp, tor, event -> {})) {
            String bob = ContactLink.loadOrCreate(temp.resolve("bob"), "Bob");
            MessengerPane[] pane = new MessengerPane[1]; StackPane[] root = new StackPane[1];
            var appeared = new CountDownLatch(1);
            fx(() -> {
                pane[0] = new MessengerPane(manager, "Alice", () -> {});
                root[0] = new StackPane(pane[0]); var scene = new Scene(root[0], 860, 600);
                scene.getStylesheets().add(getClass().getResource("/kakha/kudava/onionp2p/app.css").toExternalForm());
                root[0].applyCss(); root[0].layout();
                var list = (ListView<ContactStore.Contact>) pane[0].lookup(".contact-list");
                assertTrue(list.getItems().isEmpty());
                list.getItems().addListener((ListChangeListener<ContactStore.Contact>) change -> {
                    if (list.getItems().stream().anyMatch(contact -> contact.username().equals("Bob"))) appeared.countDown();
                });
            });
            manager.add(bob);
            var field = ContactManager.class.getDeclaredField("pending"); field.setAccessible(true);
            Object pending = ((Map<?, ?>) field.get(manager)).get(bob);
            var save = ContactManager.class.getDeclaredMethod("save", pending.getClass()); save.setAccessible(true); save.invoke(manager, pending);
            assertTrue(appeared.await(5, TimeUnit.SECONDS), "Acknowledgment must update the already-open list");
            fx(() -> {
                var list = (ListView<ContactStore.Contact>) pane[0].lookup(".contact-list");
                assertEquals(1, list.getItems().size());
                assertEquals("Bob", list.getSelectionModel().getSelectedItem().username());
                root[0].getChildren().clear();
            });
        } finally {
            if (previous == null) System.clearProperty("onion.dataDir"); else System.setProperty("onion.dataDir", previous);
        }
    }
}
