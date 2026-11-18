package kakha.kudava.onionp2p.ui;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationPaneTest {
    @Test @Timeout(15)
    void registrationToggleShowsConfirmationAndPreservesRoomForControls() throws Exception {
        var ready = new CompletableFuture<Void>();
        try { Platform.startup(() -> ready.complete(null)); } catch (IllegalStateException running) { ready.complete(null); }
        ready.get(5, TimeUnit.SECONDS);
        var checked = new CompletableFuture<Void>();
        Platform.runLater(() -> {
            try {
                var loader = new FXMLLoader(getClass().getResource("/kakha/kudava/onionp2p/hello-view.fxml"));
                Parent root = loader.load();
                var scene = new Scene(root, 410, 560);
                scene.getStylesheets().add(getClass().getResource("/kakha/kudava/onionp2p/app.css").toExternalForm());
                var controller = loader.getController();
                var show = HelloController.class.getDeclaredMethod("showAuth", boolean.class); show.setAccessible(true); show.invoke(controller, false);
                Button mode = (Button) root.lookup("#authModeButton");
                assertEquals("Create an account", mode.getText()); mode.fire();
                assertTrue(root.lookup("#confirmPasswordRow").isVisible());
                assertEquals("Register", ((Button) root.lookup("#authButton")).getText());
                root.applyCss(); root.layout();
                var bounds = mode.localToScene(mode.getBoundsInLocal());
                assertTrue(bounds.getMaxY() <= scene.getHeight()); assertTrue(bounds.getMinY() >= 0);
                ((PasswordField) root.lookup("#passwordField")).setText("a secret password"); mode.fire();
                assertFalse(root.lookup("#confirmPasswordRow").isManaged());
                assertTrue(((PasswordField) root.lookup("#passwordField")).getText().isEmpty());
                checked.complete(null);
            } catch (Throwable error) { checked.completeExceptionally(error); }
        });
        checked.get(8, TimeUnit.SECONDS);
    }
}
