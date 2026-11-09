package kakha.kudava.onionp2p;

import javafx.fxml.FXML;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.PasswordField;
import javafx.scene.shape.SVGPath;
import javafx.scene.layout.VBox;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.Cursor;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
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
    @FXML private VBox messengerHost;
    @FXML private VBox addContactPane;
    @FXML private HBox windowBar;
    @FXML private Button maximizeButton;
    @FXML private Button minimizeButton;
    @FXML private Button closeWindowButton;
    @FXML private BorderPane windowRoot;
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
    private double windowDragOffsetX;
    private double windowDragOffsetY;
    private double resizeStartX, resizeStartY, resizeStartWidth, resizeStartHeight, resizeStartWindowX, resizeStartWindowY;
    private Cursor activeResizeCursor = Cursor.DEFAULT;

    @FXML private void initialize() {
        minimizeButton.setFocusTraversable(false);
        maximizeButton.setFocusTraversable(false);
        closeWindowButton.setFocusTraversable(false);
        minimizeButton.setGraphic(windowControlGlyph("M4 12 H20"));
        maximizeButton.setGraphic(windowControlGlyph("M5 5 H19 V19 H5 Z"));
        closeWindowButton.setGraphic(windowControlGlyph("M5 5 L19 19 M19 5 L5 19"));
        windowBar.setOnMousePressed(event -> {
            if (event.getClickCount() > 1 || event.getTarget() instanceof Button) return;
            Stage stage = windowStage();
            if (stage == null || stage.isMaximized()) return;
            windowDragOffsetX = event.getScreenX() - stage.getX();
            windowDragOffsetY = event.getScreenY() - stage.getY();
        });
        windowBar.setOnMouseDragged(event -> {
            Stage stage = windowStage();
            if (stage != null && !stage.isMaximized() && !(event.getTarget() instanceof Button)) {
                stage.setX(event.getScreenX() - windowDragOffsetX);
                stage.setY(event.getScreenY() - windowDragOffsetY);
            }
        });
        windowBar.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2 && !(event.getTarget() instanceof Button)) toggleMaximize();
        });
        windowRoot.addEventHandler(MouseEvent.MOUSE_MOVED, this::updateResizeCursor);
        windowRoot.addEventHandler(MouseEvent.MOUSE_PRESSED, this::beginResize);
        windowRoot.addEventHandler(MouseEvent.MOUSE_DRAGGED, this::resizeWindow);
        windowRoot.addEventHandler(MouseEvent.MOUSE_RELEASED, event -> activeResizeCursor = Cursor.DEFAULT);
    }

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

    @FXML private void onMinimizeWindow() {
        Stage stage = windowStage();
        if (stage != null) stage.setIconified(true);
    }

    @FXML private void onToggleMaximizeWindow() { toggleMaximize(); }

    private void toggleMaximize() {
        Stage stage = windowStage();
        if (stage == null) return;
        stage.setMaximized(!stage.isMaximized());
        maximizeButton.setGraphic(windowControlGlyph(stage.isMaximized()
                ? "M8 5 H19 V16 M16 8 H5 V19 H16 Z" : "M5 5 H19 V19 H5 Z"));
    }

    private static SVGPath windowControlGlyph(String path) {
        SVGPath icon = new SVGPath();
        icon.setContent(path);
        icon.getStyleClass().add("window-control-glyph");
        icon.setScaleX(0.82);
        icon.setScaleY(0.82);
        return icon;
    }

    @FXML private void onCloseWindow() {
        Stage stage = windowStage();
        if (stage != null) stage.close();
    }

    private Stage windowStage() {
        return windowRoot.getScene() != null && windowRoot.getScene().getWindow() instanceof Stage stage ? stage : null;
    }

    private void updateResizeCursor(MouseEvent event) {
        if (windowStage() == null || windowStage().isMaximized()) return;
        double x = event.getX(), y = event.getY(), width = windowRoot.getWidth(), height = windowRoot.getHeight();
        boolean left = x <= 6, right = x >= width - 6, top = y <= 6, bottom = y >= height - 6;
        activeResizeCursor = top && left ? Cursor.NW_RESIZE : top && right ? Cursor.NE_RESIZE
                : bottom && left ? Cursor.SW_RESIZE : bottom && right ? Cursor.SE_RESIZE
                : left ? Cursor.W_RESIZE : right ? Cursor.E_RESIZE : top ? Cursor.N_RESIZE
                : bottom ? Cursor.S_RESIZE : Cursor.DEFAULT;
        windowRoot.setCursor(activeResizeCursor);
    }

    private void beginResize(MouseEvent event) {
        Stage stage = windowStage();
        if (stage == null || stage.isMaximized() || activeResizeCursor == Cursor.DEFAULT
                || event.getTarget() instanceof Button) return;
        resizeStartX = event.getScreenX(); resizeStartY = event.getScreenY();
        resizeStartWidth = stage.getWidth(); resizeStartHeight = stage.getHeight();
        resizeStartWindowX = stage.getX(); resizeStartWindowY = stage.getY();
        event.consume();
    }

    private void resizeWindow(MouseEvent event) {
        Stage stage = windowStage();
        if (stage == null || stage.isMaximized() || activeResizeCursor == Cursor.DEFAULT
                || event.getTarget() instanceof Button || resizeStartWidth == 0) return;
        double dx = event.getScreenX() - resizeStartX, dy = event.getScreenY() - resizeStartY;
        if (activeResizeCursor == Cursor.E_RESIZE || activeResizeCursor == Cursor.NE_RESIZE || activeResizeCursor == Cursor.SE_RESIZE)
            stage.setWidth(Math.max(stage.getMinWidth(), resizeStartWidth + dx));
        if (activeResizeCursor == Cursor.W_RESIZE || activeResizeCursor == Cursor.NW_RESIZE || activeResizeCursor == Cursor.SW_RESIZE) {
            double width = Math.max(stage.getMinWidth(), resizeStartWidth - dx);
            stage.setWidth(width); stage.setX(resizeStartWindowX + resizeStartWidth - width);
        }
        if (activeResizeCursor == Cursor.S_RESIZE || activeResizeCursor == Cursor.SE_RESIZE || activeResizeCursor == Cursor.SW_RESIZE)
            stage.setHeight(Math.max(stage.getMinHeight(), resizeStartHeight + dy));
        if (activeResizeCursor == Cursor.N_RESIZE || activeResizeCursor == Cursor.NE_RESIZE || activeResizeCursor == Cursor.NW_RESIZE) {
            double height = Math.max(stage.getMinHeight(), resizeStartHeight - dy);
            stage.setHeight(height); stage.setY(resizeStartWindowY + resizeStartHeight - height);
        }
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
        MessengerPane.updateTorIndicator("Starting Tor", null);
    }

    void torReady(String onionAddress) {
        MessengerPane.updateTorIndicator("Tor ready", onionAddress);
    }

    void torFailed(String message) {
        MessengerPane.updateTorIndicator("Tor unavailable", null);
    }

    void setManager(ContactManager manager) {
        this.manager = manager;
        var pane = new MessengerPane(manager, authenticatedUsername, this::showAddContactPane);
        messengerHost.getChildren().setAll(pane);
        VBox.setVgrow(pane, javafx.scene.layout.Priority.ALWAYS);
    }

    private void showAddContactPane() {
        messengerHost.setVisible(false);
        messengerHost.setManaged(false);
        addContactPane.setVisible(true);
        addContactPane.setManaged(true);
        importLink.requestFocus();
    }

    @FXML protected void onCloseAddContact() {
        addContactPane.setVisible(false);
        addContactPane.setManaged(false);
        messengerHost.setVisible(true);
        messengerHost.setManaged(true);
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
