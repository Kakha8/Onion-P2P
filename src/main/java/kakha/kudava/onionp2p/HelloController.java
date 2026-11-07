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
    @FXML private TextField importLink;
    @FXML private Button importButton;
    @FXML private TextField importedAddress;
    @FXML private VBox addressPanel;
    @FXML private Label importStatus;

    @FXML
    protected void onImportLink() {
        if (importButton.isDisabled()) return;
        String input = importLink.getText();
        importButton.setDisable(true);
        addressPanel.setVisible(false);
        addressPanel.setManaged(false);
        importedAddress.clear();
        importStatus.setText("Importing contact link…");
        Thread.ofPlatform().daemon().name("contact-import").start(() -> {
            try {
                var addresses = ContactRendezvous.importLink(AppData.directory(), input);
                System.out.println("Derived contact onion address (not published): http://" + addresses.remote() + "/");
                Platform.runLater(() -> {
                    importedAddress.setText("http://" + addresses.remote() + "/");
                    addressPanel.setManaged(true);
                    addressPanel.setVisible(true);
                    importStatus.setText("Address derived. A Tor service has not been started at this address.");
                    importButton.setDisable(false);
                });
            } catch (Exception e) {
                System.err.println("Contact import failed: " + e.getMessage());
                Platform.runLater(() -> {
                    importStatus.setText(e instanceof IllegalArgumentException ? e.getMessage()
                            : "Could not import the link. Check the console and try again.");
                    importButton.setDisable(false);
                });
            }
        });
    }

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
