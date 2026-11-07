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
        Scene scene = new Scene(fxmlLoader.load(), 760, 780);
        HelloController controller = fxmlLoader.getController();
        contacts = new ContactManager(AppData.directory(), onionServer, controller::contactEvent);
        controller.setManager(contacts);
        contacts.restore();
        stage.setTitle("Onion-P2P");
        stage.setMinWidth(480);
        stage.setMinHeight(780);
        stage.setScene(scene);
        stage.show();
        Thread.ofPlatform().daemon().name("onion-startup").start(() -> {
            try {
                onionServer.start();
            } catch (Exception e) {
                System.err.println("Onion server failed: " + e.getMessage());
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
