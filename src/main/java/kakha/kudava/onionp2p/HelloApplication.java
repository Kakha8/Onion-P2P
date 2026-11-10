package kakha.kudava.onionp2p;

import javafx.application.Application;
import javafx.beans.binding.Bindings;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.StageStyle;
import javafx.stage.Stage;

import java.io.IOException;

public class HelloApplication extends Application {
    private static final double MESSENGER_WIDTH = 860;
    private static final double MESSENGER_HEIGHT = 600;
    private final OnionServer onionServer = new OnionServer();
    private ContactManager contacts;

    @Override
    public void start(Stage stage) throws Exception {
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "onion-shutdown"));
        stage.initStyle(StageStyle.TRANSPARENT);
        FXMLLoader fxmlLoader = new FXMLLoader(HelloApplication.class.getResource("hello-view.fxml"));
        Parent root = fxmlLoader.load();
        Rectangle windowOutline = new Rectangle();
        windowOutline.setManaged(false);
        windowOutline.setMouseTransparent(true);
        windowOutline.setFill(Color.TRANSPARENT);
        windowOutline.setStroke(Color.web("#46505d"));
        windowOutline.setStrokeWidth(1.2);
        windowOutline.setLayoutX(0.6);
        windowOutline.setLayoutY(0.6);
        StackPane windowFrame = new StackPane(root, windowOutline);
        Scene scene = new Scene(windowFrame, 410, 474, Color.TRANSPARENT);
        Rectangle windowClip = new Rectangle();
        windowClip.widthProperty().bind(scene.widthProperty());
        windowClip.heightProperty().bind(scene.heightProperty());
        var cornerDiameter = Bindings.when(stage.maximizedProperty()).then(0.0).otherwise(16.0);
        windowClip.arcWidthProperty().bind(cornerDiameter);
        windowClip.arcHeightProperty().bind(cornerDiameter);
        windowFrame.setClip(windowClip);
        windowOutline.widthProperty().bind(scene.widthProperty().subtract(1.2));
        windowOutline.heightProperty().bind(scene.heightProperty().subtract(1.2));
        windowOutline.arcWidthProperty().bind(cornerDiameter);
        windowOutline.arcHeightProperty().bind(cornerDiameter);
        scene.getStylesheets().add(HelloApplication.class.getResource("app.css").toExternalForm());
        HelloController controller = fxmlLoader.getController();
        controller.setOnAuthenticated(() -> {
            stage.setResizable(true);
            stage.setMinWidth(760);
            stage.setMinHeight(MESSENGER_HEIGHT);
            stage.setWidth(MESSENGER_WIDTH);
            stage.setHeight(MESSENGER_HEIGHT);
            stage.centerOnScreen();
            startServices(controller);
        });
        stage.setTitle("Onion-P2P");
        stage.getIcons().add(new Image(HelloApplication.class.getResource("onion-owl.png").toExternalForm(),
                256, 256, true, true));
        stage.setResizable(false);
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
