package kakha.kudava.onionp2p;

import kakha.kudava.onionp2p.contacts.ContactLink;
import kakha.kudava.onionp2p.contacts.ContactRendezvous;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ContactViewTest {
    @TempDir Path directory;

    @Test void generateButtonRevealsPersistentLink() throws Exception {
        String previous = System.getProperty("onion.dataDir");
        System.setProperty("onion.dataDir", directory.toString());
        var result = new CompletableFuture<String>();
        var imported = new CompletableFuture<String>();
        String peerLink = ContactLink.loadOrCreate(directory.resolve("peer"));
        try {
            Platform.startup(() -> {
                try {
                    Parent root = FXMLLoader.load(HelloApplication.class.getResource("hello-view.fxml"));
                    var panel = root.lookup("#linkPanel");
                    assertFalse(panel.isVisible());
                    var field = (TextField) root.lookup("#contactLink");
                    assertFalse(field.isEditable());
                    panel.visibleProperty().addListener((observable, old, visible) -> {
                        if (visible) result.complete(field.getText());
                    });
                    ((Button) root.lookup("#generateButton")).fire();
                    var addressPanel = root.lookup("#addressPanel");
                    assertFalse(addressPanel.isVisible());
                    addressPanel.visibleProperty().addListener((observable, old, visible) -> {
                        if (visible) imported.complete(((TextField) root.lookup("#importedAddress")).getText());
                    });
                    ((TextField) root.lookup("#importLink")).setText(peerLink);
                    ((Button) root.lookup("#importButton")).fire();
                } catch (Throwable e) { result.completeExceptionally(e); }
            });
            assertEquals(ContactLink.loadOrCreate(directory), result.get(15, TimeUnit.SECONDS));
            assertEquals("http://" + ContactRendezvous.importLink(directory, peerLink).remote() + "/",
                    imported.get(15, TimeUnit.SECONDS));
        } finally {
            if (previous == null) System.clearProperty("onion.dataDir");
            else System.setProperty("onion.dataDir", previous);
            Platform.exit();
        }
    }
}
