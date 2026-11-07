package kakha.kudava.onionp2p;

import javafx.fxml.FXML;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;

public class HelloController {
    @FXML
    private Label statusText;
    @FXML private Button generateButton;
    @FXML private TextField contactLink;
    @FXML private VBox linkPanel;

    @FXML
    protected void onGenerateLink() {
        generateButton.setDisable(true);
        statusText.setText("Generating your link…");
        Thread.ofPlatform().daemon().name("contact-key").start(() -> {
            try {
                String link = ContactLink.loadOrCreate(AppData.directory());
                Platform.runLater(() -> {
                    contactLink.setText(link);
                    linkPanel.setManaged(true);
                    linkPanel.setVisible(true);
                    generateButton.setText("Show my public-key link");
                    generateButton.setDisable(false);
                    statusText.setText("Share this link with your contact.");
                });
            } catch (Exception e) {
                System.err.println("Contact link failed: " + e.getMessage());
                Platform.runLater(() -> {
                    generateButton.setDisable(false);
                    statusText.setText("Could not load or save your key. Check the console and try again.");
                });
            }
        });
    }

    @FXML
    protected void onCopyLink() {
        var content = new ClipboardContent();
        content.putString(contactLink.getText());
        try {
            statusText.setText(Clipboard.getSystemClipboard().setContent(content)
                    ? "Link copied." : "Could not copy. Select the link and press Ctrl+C.");
        } catch (IllegalStateException e) {
            statusText.setText("Clipboard unavailable. Select the link and press Ctrl+C.");
        }
    }
}
