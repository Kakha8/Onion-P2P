package kakha.kudava.onionp2p;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

public class HelloApplication extends Application {
    private final OnionServer onionServer = new OnionServer();

    @Override
    public void start(Stage stage) throws IOException {
        Runtime.getRuntime().addShutdownHook(new Thread(onionServer::close, "onion-shutdown"));
        FXMLLoader fxmlLoader = new FXMLLoader(HelloApplication.class.getResource("hello-view.fxml"));
        Scene scene = new Scene(fxmlLoader.load(), 320, 240);
        stage.setTitle("Hello!");
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
        onionServer.close();
    }

    public static void main(String[] args) {
        launch();
    }
}
