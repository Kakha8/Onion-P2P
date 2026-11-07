package kakha.kudava.onionp2p;

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
                } catch (Throwable e) { result.completeExceptionally(e); }
            });
            assertEquals(ContactLink.loadOrCreate(directory), result.get(15, TimeUnit.SECONDS));
        } finally {
            if (previous == null) System.clearProperty("onion.dataDir");
            else System.setProperty("onion.dataDir", previous);
            Platform.exit();
        }
    }
}
