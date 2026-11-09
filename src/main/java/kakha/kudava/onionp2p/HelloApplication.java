package kakha.kudava.onionp2p;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

public class HelloApplication extends Application {
    private final OnionServer onionServer = new OnionServer();
    private ContactManager contacts;

    @Override
    public void start(Stage stage) throws Exception {
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "onion-shutdown"));
        FXMLLoader fxmlLoader = new FXMLLoader(HelloApplication.class.getResource("hello-view.fxml"));
        Scene scene = new Scene(fxmlLoader.load(), 1120, 760);
        scene.getStylesheets().add(HelloApplication.class.getResource("app.css").toExternalForm());
        HelloController controller = fxmlLoader.getController();
        controller.setOnAuthenticated(() -> startServices(controller));
        stage.setTitle("Onion-P2P");
        stage.setMinWidth(760);
        stage.setMinHeight(620);
        stage.setScene(scene);
        stage.show();
        controller.beginAuthentication();
    }

    private void startServices(HelloController controller) {
        controller.torStarting();
        try {
            contacts = new ContactManager(AppData.directory(), onionServer, controller::contactEvent);
            controller.setManager(contacts);
            contacts.restore();
        } catch (Exception e) {
            System.err.println("Could not start contact storage: " + e.getMessage());
            controller.torFailed(e.getMessage());
            return;
        }
        Thread.ofPlatform().daemon().name("onion-startup").start(() -> {
            try {
                onionServer.start();
                String onionAddress = java.nio.file.Files.readString(
                        AppData.directory().resolve("service/hostname")).strip();
                javafx.application.Platform.runLater(() -> controller.torReady(onionAddress));
            } catch (Exception e) {
                System.err.println("Onion server failed: " + e.getMessage());
                javafx.application.Platform.runLater(() -> controller.torFailed(e.getMessage()));
                onionServer.close();
            }
        });
    }

    @Override
    public void stop() {
        if (contacts != null) contacts.close();
        onionServer.close();
    }

    public static void main(String[] args) {
        launch();
    }
}
