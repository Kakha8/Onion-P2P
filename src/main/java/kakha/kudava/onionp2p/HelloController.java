package kakha.kudava.onionp2p;

import javafx.fxml.FXML;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.VBox;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
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
    @FXML private ProgressIndicator pairingProgress;
    @FXML private ListView<String> savedContacts;
    @FXML private ProgressIndicator contactsProgress;
    @FXML private HBox torProgressRow;
    @FXML private ProgressIndicator torProgress;
    @FXML private Label torStatus;
    @FXML private VBox authPane;
    @FXML private Label authTitle;
    @FXML private Label authMessage;
    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private PasswordField confirmPasswordField;
    @FXML private VBox confirmPasswordRow;
    @FXML private Button authButton;
    @FXML private ProgressIndicator authProgress;
    @FXML private VBox appPane;
    private volatile ContactManager manager;
    private String selectedLink;
    private AccountStore accounts;
    private Runnable onAuthenticated;
    private boolean creatingAccount;
    private String authenticatedUsername;

    void setOnAuthenticated(Runnable callback) { onAuthenticated = callback; }

    void beginAuthentication() {
        authPane.setManaged(true); authPane.setVisible(true);
        setAuthBusy(true, "Checking local account…");
        Thread.ofPlatform().daemon().name("account-load").start(() -> {
            try {
                accounts = new AccountStore(AppData.directory());
                var profile = accounts.profile();
                Platform.runLater(() -> showAuth(profile == null));
            } catch (Exception e) {
                System.err.println("Account load failed: " + e.getMessage());
                Platform.runLater(() -> {
                    showAuth(true);
                    authButton.setDisable(true);
                    authMessage.setText("Could not open the local account database.");
                    authProgress.setVisible(false);
                    authProgress.setManaged(false);
                });
            }
        });
    }

    private void showAuth(boolean create) {
        creatingAccount = create;
        authTitle.setText(create ? "Set up your account" : "Unlock your account");
        authMessage.setText(create ? "Choose a username and a password of at least 12 characters."
                : "Enter your local username and password.");
        confirmPasswordRow.setVisible(create);
        confirmPasswordRow.setManaged(create);
        authButton.setText(create ? "Create account" : "Unlock");
        authButton.setDisable(false);
        authProgress.setVisible(false);
        authProgress.setManaged(false);
        authPane.setVisible(true); authPane.setManaged(true);
        appPane.setVisible(false); appPane.setManaged(false);
        usernameField.requestFocus();
    }

    @FXML protected void onAuthenticate() {
        if (authButton.isDisabled()) return;
        String username = usernameField.getText();
        char[] password = passwordField.getText().toCharArray();
        char[] confirmation = confirmPasswordField.getText().toCharArray();
        passwordField.clear(); confirmPasswordField.clear();
        if (creatingAccount && !constantTimeEquals(password, confirmation)) {
            java.util.Arrays.fill(password, '\0'); java.util.Arrays.fill(confirmation, '\0');
            authMessage.setText("Passwords do not match."); return;
        }
        java.util.Arrays.fill(confirmation, '\0');
        setAuthBusy(true, creatingAccount ? "Creating account…" : "Checking password…");
        Thread.ofPlatform().daemon().name("account-authentication").start(() -> {
            try {
                AccountStore.Profile profile;
                if (creatingAccount) {
                    accounts.create(username, password);
                    profile = accounts.profile();
                } else profile = accounts.authenticate(username, password);
                var authenticated = profile;
                Platform.runLater(() -> {
                    authenticatedUsername = authenticated.username();
                    authPane.setVisible(false); authPane.setManaged(false);
                    appPane.setVisible(true); appPane.setManaged(true);
                    statusText.setText("Welcome, " + authenticated.username() + ".");
                    if (onAuthenticated != null) onAuthenticated.run();
                });
            } catch (Exception e) {
                java.util.Arrays.fill(password, '\0');
                Platform.runLater(() -> {
                    authMessage.setText(e instanceof IllegalArgumentException || e instanceof SecurityException
                            ? e.getMessage() : "Could not set up or unlock the local account.");
                    setAuthBusy(false, null);
                    if (creatingAccount && e instanceof IllegalStateException) showAuth(false);
                });
            }
        });
    }

    private static boolean constantTimeEquals(char[] left, char[] right) {
        int difference = left.length ^ right.length;
        for (int i = 0; i < Math.max(left.length, right.length); i++)
            difference |= (i < left.length ? left[i] : 0) ^ (i < right.length ? right[i] : 0);
        return difference == 0;
    }

    private void setAuthBusy(boolean busy, String message) {
        authButton.setDisable(busy);
        authProgress.setManaged(busy); authProgress.setVisible(busy);
        if (busy) authMessage.setText(message);
    }

    void torStarting() {
        torStatus.setText("Starting Tor and publishing your onion service…");
        torProgressRow.setManaged(true);
        torProgressRow.setVisible(true);
    }

    void torReady(String onionAddress) {
        torProgressRow.setVisible(false);
        torProgressRow.setManaged(false);
        statusText.setText("Onion server ready: http://" + onionAddress + "/");
    }

    void torFailed(String message) {
        torProgressRow.setVisible(false);
        torProgressRow.setManaged(false);
        statusText.setText("Tor could not start. See console for details.");
    }

    void setManager(ContactManager manager) {
        this.manager = manager;
        refreshContacts();
    }

    void contactEvent(ContactManager.Event event) {
        Platform.runLater(() -> {
            if (event.link().equals(selectedLink)) {
                importStatus.setText(event.message());
                boolean waiting = !event.message().startsWith("Acknowledged")
                        && !event.message().startsWith("Contact already acknowledged")
                        && !event.message().startsWith("Contact did not respond");
                pairingProgress.setManaged(waiting);
                pairingProgress.setVisible(waiting);
            }
        });
        refreshContacts();
    }

    private void refreshContacts() {
        ContactManager current = manager;
        if (current == null) return;
        Thread.ofPlatform().daemon().name("contact-list").start(() -> {
            try {
                var contacts = current.contacts();
                Platform.runLater(() -> {
                    savedContacts.getItems().setAll(contacts.stream()
                            .map(c -> c.username() + "  —  " + c.onion() + "  (saved)").toList());
                    contactsProgress.setVisible(false);
                    contactsProgress.setManaged(false);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    contactsProgress.setVisible(false);
                    contactsProgress.setManaged(false);
                    importStatus.setText("Could not read the saved contacts database.");
                });
            }
        });
        contactsProgress.setManaged(true);
        contactsProgress.setVisible(true);
    }

    @FXML
    protected void onImportLink() {
        if (importButton.isDisabled()) return;
        String input = importLink.getText();
        selectedLink = input.strip();
        importButton.setDisable(true);
        var importSpinner = spinner(18);
        importButton.setGraphic(importSpinner);
        addressPanel.setVisible(false);
        addressPanel.setManaged(false);
        importedAddress.clear();
        importStatus.setText("Importing contact link…");
        pairingProgress.setManaged(true);
        pairingProgress.setVisible(true);
        Thread.ofPlatform().daemon().name("contact-import").start(() -> {
            try {
                var addresses = manager == null ? ContactRendezvous.importLink(AppData.directory(), input) : manager.add(input);
                System.out.println("Contact rendezvous address: " + addresses.remote());
                Platform.runLater(() -> {
                    String remoteName = ContactLink.parseLink(input).username();
                    importedAddress.setText(remoteName + " — http://" + addresses.remote() + "/");
                    addressPanel.setManaged(true);
                    addressPanel.setVisible(true);
                    if (manager == null) {
                        importStatus.setText("Contact service is not ready. Import again after startup.");
                        pairingProgress.setVisible(false);
                        pairingProgress.setManaged(false);
                    } else {
                        pairingProgress.setManaged(true);
                        pairingProgress.setVisible(true);
                    }
                    importButton.setDisable(false);
                    importButton.setGraphic(null);
                });
            } catch (Exception e) {
                System.err.println("Contact import failed: " + e.getMessage());
                Platform.runLater(() -> {
                    importStatus.setText(e instanceof IllegalArgumentException ? e.getMessage()
                            : "Could not import the link. Check the console and try again.");
                    pairingProgress.setVisible(false);
                    pairingProgress.setManaged(false);
                    importButton.setDisable(false);
                    importButton.setGraphic(null);
                });
            }
        });
    }

    @FXML
    protected void onGenerateLink() {
        generateButton.setDisable(true);
        generateButton.setGraphic(spinner(18));
        statusText.setText("Generating your link…");
        Thread.ofPlatform().daemon().name("contact-key").start(() -> {
            try {
                String link = ContactLink.loadOrCreate(AppData.directory(), authenticatedUsername);
                Platform.runLater(() -> {
                    contactLink.setText(link);
                    linkPanel.setManaged(true);
                    linkPanel.setVisible(true);
                    generateButton.setText("Show my public-key link");
                    generateButton.setDisable(false);
                    generateButton.setGraphic(null);
                    statusText.setText("Share this link with your contact.");
                });
            } catch (Exception e) {
                System.err.println("Contact link failed: " + e.getMessage());
                Platform.runLater(() -> {
                    generateButton.setDisable(false);
                    generateButton.setGraphic(null);
                    statusText.setText("Could not load or save your key. Check the console and try again.");
                });
            }
        });
    }

    private static ProgressIndicator spinner(double size) {
        var indicator = new ProgressIndicator();
        indicator.setPrefSize(size, size);
        indicator.setMaxSize(size, size);
        return indicator;
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
